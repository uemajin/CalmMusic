package com.calmapps.calmmusic.data

import android.net.Uri
import android.os.Environment
import com.calmapps.calmmusic.CalmMusic
import com.calmapps.calmmusic.NAVIDROME_ID_PREFIX
import com.calmapps.calmmusic.PlaybackService
import com.calmapps.calmmusic.ui.AlbumUiModel
import com.calmapps.calmmusic.ui.ArtistUiModel
import com.calmapps.calmmusic.ui.SongUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Repository responsible for performing library-related data work against the
 * local Room database and filesystem scanners.
 */
class LibraryRepository(
    private val app: CalmMusic,
) {

    private val database: CalmMusicDatabase by lazy { CalmMusicDatabase.getDatabase(app) }
    private val songDao by lazy { database.songDao() }
    private val albumDao by lazy { database.albumDao() }
    private val artistDao by lazy { database.artistDao() }
    private val playlistDao by lazy { database.playlistDao() }

    data class LocalResyncStats(
        val totalDiscovered: Int,
        val skippedUnchanged: Int,
        val indexedNewOrUpdated: Int,
        val deletedMissing: Int,
    )

    data class LocalResyncResult(
        val songs: List<SongUiModel>,
        val albums: List<AlbumUiModel>,
        val artists: List<ArtistUiModel>,
        val errorMessage: String?,
        val stats: LocalResyncStats? = null,
    )

    suspend fun resyncLocalLibrary(
        includeLocal: Boolean,
        folders: Set<String>,
        onScanProgress: (Float) -> Unit,
        onIngestProgress: (Float) -> Unit,
    ): LocalResyncResult {
        var error: String? = null
        var stats: LocalResyncStats? = null

        try {
            if (!includeLocal) {
                onScanProgress(1f)
                onIngestProgress(0f)
                withContext(Dispatchers.IO) {
                    songDao.deleteBySourceType("LOCAL_FILE")
                    albumDao.deleteBySourceType("LOCAL_FILE")
                    artistDao.deleteBySourceType("LOCAL_FILE")
                }
                onIngestProgress(1f)
            } else {
                if (folders.isNotEmpty()) {
                    try {
                        val lastScanMillis = app.settingsManager.getLastLocalLibraryScanMillis()

                        val existingAlbumsMap = withContext(Dispatchers.IO) {
                            albumDao.getAllAlbums()
                                .filter { it.sourceType == "LOCAL_FILE" }
                                .associateBy { it.id }
                        }

                        val (scannedAudio, existingLocalSongs) = withContext(Dispatchers.IO) {
                            val existingLocalSongs = songDao.getSongsBySourceType("LOCAL_FILE")
                            val existingByUri = existingLocalSongs.associateBy { it.audioUri }
                            val scanned = LocalMusicScanner.scanFolders(
                                context = app,
                                folderUris = folders,
                                existingSongsByUri = existingByUri,
                                lastScanMillis = lastScanMillis,
                            ) { processed, total ->
                                val progress = if (total > 0) {
                                    (processed.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                                } else {
                                    1f
                                }
                                onScanProgress(progress)
                            }
                            scanned to existingLocalSongs
                        }

                        onIngestProgress(0f)

                        val normalizedLocalEntities = scannedAudio.map { it.song }

                        withContext(Dispatchers.IO) {
                            val artistEntities = mutableListOf<ArtistEntity>()

                            fun String.normalize() = trim().replace(Regex("\\s+"), " ").lowercase()

                            normalizedLocalEntities.forEach { entity ->
                                val id = entity.artistId ?: return@forEach
                                val name = entity.artist.takeIf { it.isNotBlank() } ?: id.removePrefix("LOCAL_FILE:")
                                artistEntities.add(ArtistEntity(id, name, entity.sourceType))
                            }

                            scannedAudio.forEach { wrapper ->
                                val entity = wrapper.song
                                val explicit = wrapper.albumArtist

                                // If explicit is missing (unchanged file), check our preserved map
                                val effectiveAlbumArtist = explicit?.takeIf { it.isNotBlank() }
                                    ?: entity.albumId?.let { existingAlbumsMap[it]?.artist }

                                if (!effectiveAlbumArtist.isNullOrBlank()) {
                                    val id = "LOCAL_FILE:" + effectiveAlbumArtist.normalize()
                                    artistEntities.add(ArtistEntity(id, effectiveAlbumArtist, wrapper.song.sourceType))
                                }
                            }

                            val uniqueArtists = artistEntities.distinctBy { it.id }

                            onIngestProgress(0.1f)

                            val albumEntities: List<AlbumEntity> = scannedAudio
                                .mapNotNull { wrapper ->
                                    val entity = wrapper.song
                                    val id = entity.albumId ?: return@mapNotNull null
                                    val name = entity.album ?: return@mapNotNull null

                                    val artistName = wrapper.albumArtist?.takeIf { it.isNotBlank() }
                                        ?: existingAlbumsMap[id]?.artist
                                        ?: entity.artist

                                    val albumArtistId = "LOCAL_FILE:" + artistName.normalize()

                                    id to AlbumEntity(
                                        id = id,
                                        name = name,
                                        artist = artistName,
                                        sourceType = entity.sourceType,
                                        artistId = albumArtistId,
                                    )
                                }
                                .distinctBy { it.first }
                                .map { it.second }

                            onIngestProgress(0.2f)

                            if (normalizedLocalEntities.isEmpty() && albumEntities.isEmpty() && uniqueArtists.isEmpty()) {
                                onIngestProgress(1f)
                                songDao.deleteBySourceType("LOCAL_FILE")
                                albumDao.deleteBySourceType("LOCAL_FILE")
                                artistDao.deleteBySourceType("LOCAL_FILE")
                                return@withContext
                            }

                            val existingById = existingLocalSongs.associateBy { it.id }
                            val scannedById = normalizedLocalEntities.associateBy { it.id }

                            val songsToDelete = existingById.keys - scannedById.keys
                            val songsToUpsert = scannedById.values.filter { newEntity ->
                                val existing = existingById[newEntity.id]
                                existing == null || existing != newEntity
                            }

                            val totalDiscovered = normalizedLocalEntities.size
                            val indexedNewOrUpdated = songsToUpsert.size
                            val skippedUnchanged = (totalDiscovered - indexedNewOrUpdated).coerceAtLeast(0)
                            val deletedMissing = songsToDelete.size
                            stats = LocalResyncStats(
                                totalDiscovered = totalDiscovered,
                                skippedUnchanged = skippedUnchanged,
                                indexedNewOrUpdated = indexedNewOrUpdated,
                                deletedMissing = deletedMissing,
                            )

                            val totalWriteItems = songsToDelete.size + songsToUpsert.size + albumEntities.size + uniqueArtists.size
                            var writtenItems = 0

                            fun reportWriteProgress() {
                                if (totalWriteItems <= 0) return
                                val writeFraction = (writtenItems.toFloat() / totalWriteItems.toFloat()).coerceIn(0f, 1f)
                                val progress = 0.2f + 0.8f * writeFraction
                                onIngestProgress(progress.coerceIn(0f, 1f))
                            }

                            if (songsToDelete.isNotEmpty()) {
                                songDao.deleteByIds(songsToDelete.toList())
                                writtenItems += songsToDelete.size
                                reportWriteProgress()
                            }

                            if (songsToUpsert.isNotEmpty()) {
                                val chunkSize = 100.coerceAtMost(songsToUpsert.size)
                                songsToUpsert.chunked(chunkSize).forEach { chunk ->
                                    songDao.upsertAll(chunk)
                                    writtenItems += chunk.size
                                    reportWriteProgress()
                                }
                            }

                            albumDao.deleteBySourceType("LOCAL_FILE")
                            artistDao.deleteBySourceType("LOCAL_FILE")

                            if (albumEntities.isNotEmpty()) {
                                albumDao.upsertAll(albumEntities)
                                writtenItems += albumEntities.size
                                reportWriteProgress()
                            }
                            if (uniqueArtists.isNotEmpty()) {
                                artistDao.upsertAll(uniqueArtists)
                                writtenItems += uniqueArtists.size
                                reportWriteProgress()
                            }

                            onIngestProgress(1f)
                        }
                    } catch (e: Exception) {
                        error = e.message ?: "Failed to scan local music"
                    }
                } else {
                    withContext(Dispatchers.IO) {
                        songDao.deleteBySourceType("LOCAL_FILE")
                        albumDao.deleteBySourceType("LOCAL_FILE")
                        artistDao.deleteBySourceType("LOCAL_FILE")
                    }
                }
            }

            val (allSongs, allAlbums) = withContext(Dispatchers.IO) {
                val songsFromDb = songDao.getAllSongs()
                val albumsFromDb = albumDao.getAllAlbums()
                songsFromDb to albumsFromDb
            }
            val allArtistsWithCounts = withContext(Dispatchers.IO) {
                artistDao.getAllArtistsWithCounts()
            }

            val songModels = allSongs.map { entity ->
                SongUiModel(
                    id = entity.id,
                    title = entity.title,
                    artist = entity.artist,
                    durationText = com.calmapps.calmmusic.formatDurationMillis(entity.durationMillis),
                    durationMillis = entity.durationMillis,
                    trackNumber = entity.trackNumber,
                    sourceType = entity.sourceType,
                    audioUri = entity.audioUri,
                    album = entity.album,
                )
            }
            val albumIdToYear: Map<String, Int?> = allSongs
                .mapNotNull { entity ->
                    val albumId = entity.albumId ?: return@mapNotNull null
                    albumId to entity.releaseYear
                }
                .groupBy(
                    keySelector = { it.first },
                    valueTransform = { it.second },
                )
                .mapValues { (_, years) ->
                    years.filterNotNull().maxOrNull()
                }

            val albumModels = allAlbums.map { album ->
                AlbumUiModel(
                    id = album.id,
                    title = album.name,
                    artist = album.artist,
                    sourceType = album.sourceType,
                    releaseYear = albumIdToYear[album.id],
                )
            }
            val artistModels = allArtistsWithCounts.map { artist ->
                ArtistUiModel(
                    id = artist.id,
                    name = artist.name,
                    songCount = artist.songCount,
                    albumCount = artist.albumCount,
                )
            }

            app.settingsManager.updateLastLocalLibraryScanMillis(System.currentTimeMillis())

            return LocalResyncResult(
                songs = songModels,
                albums = albumModels,
                artists = artistModels,
                errorMessage = error,
                stats = stats,
            )
        } catch (e: Exception) {
            val message = e.message ?: "Failed to scan local music"
            return LocalResyncResult(
                songs = emptyList(),
                albums = emptyList(),
                artists = emptyList(),
                errorMessage = message,
                stats = null,
            )
        }
    }

    /**
     * Mirrors the Navidrome catalogue into Room (songs, albums, artists with
     * sourceType NAVIDROME) and the server's playlists. Returns (songs, playlists) synced.
     */
    suspend fun syncNavidromeLibrary(onProgress: (Int) -> Unit = {}): Pair<Int, Int> {
        val (songs, albums) = app.navidromeClient.fetchLibrary(onProgress)

        val songCount = withContext(Dispatchers.IO) {
            fun pfx(id: String) = NAVIDROME_ID_PREFIX + id

            // Songs downloaded earlier keep pointing at their local file across syncs.
            val downloaded = songDao.getSongsBySourceType(SOURCE_NAVIDROME)
                .filter { isDownloadedNavidrome(it.sourceType, it.audioUri) }
                .filter { entity -> mediaUriExists(app, entity.audioUri) }
                .associateBy { it.id }

            val albumEntities = albums.map { a ->
                AlbumEntity(
                    id = pfx(a.id),
                    name = a.title,
                    artist = a.artist,
                    sourceType = SOURCE_NAVIDROME,
                    artistId = a.artistId?.let(::pfx),
                )
            }

            val artistEntities = (
                albums.mapNotNull { a ->
                    val id = a.artistId ?: return@mapNotNull null
                    ArtistEntity(pfx(id), a.artist ?: "Unknown Artist", SOURCE_NAVIDROME)
                } + songs.mapNotNull { s ->
                    val id = s.artistId ?: return@mapNotNull null
                    ArtistEntity(pfx(id), s.artist, SOURCE_NAVIDROME)
                }
                ).distinctBy { it.id }

            val songEntities = songs.map { s ->
                SongEntity(
                    id = pfx(s.id),
                    title = s.title,
                    artist = s.artist,
                    album = s.album,
                    albumId = s.albumId?.let(::pfx),
                    discNumber = s.discNumber,
                    trackNumber = s.trackNumber,
                    durationMillis = s.durationMillis,
                    sourceType = SOURCE_NAVIDROME,
                    audioUri = downloaded[pfx(s.id)]?.audioUri
                        ?: (PlaybackService.NAVIDROME_SCHEME + "://" + s.id),
                    artistId = s.artistId?.let(::pfx),
                    releaseYear = s.year,
                    localLastModifiedMillis = downloaded[pfx(s.id)]?.localLastModifiedMillis,
                    localFileSizeBytes = downloaded[pfx(s.id)]?.localFileSizeBytes,
                )
            }

            // Replace the previous snapshot so items removed on the server disappear.
            songDao.deleteBySourceType(SOURCE_NAVIDROME)
            albumDao.deleteBySourceType(SOURCE_NAVIDROME)
            artistDao.deleteBySourceType(SOURCE_NAVIDROME)

            songEntities.chunked(200).forEach { songDao.upsertAll(it) }
            albumDao.upsertAll(albumEntities)
            artistDao.upsertAll(artistEntities)
            songEntities.size
        }

        // A playlist problem should not fail the whole library sync.
        val playlistCount = try {
            importNavidromePlaylists()
        } catch (_: Exception) {
            0
        }
        return songCount to playlistCount
    }

    /**
     * Mirrors the server's playlists into local playlists with ids "NAVIDROME:<id>".
     * Imported playlists follow the server: changes made to them in the app are
     * replaced on the next sync, and playlists removed on the server are removed here.
     */
    private suspend fun importNavidromePlaylists(): Int {
        val remote = app.navidromeClient.fetchPlaylists()

        withContext(Dispatchers.IO) {
            val knownSongIds = songDao.getSongsBySourceType(SOURCE_NAVIDROME).map { it.id }.toSet()
            val existing = playlistDao.getAllPlaylists()
                .filter { it.id.startsWith(NAVIDROME_ID_PREFIX) }
                .associateBy { it.id }

            val remoteIds = remote.map { NAVIDROME_ID_PREFIX + it.id }.toSet()
            existing.filterKeys { it !in remoteIds }.values.forEach { playlistDao.deletePlaylist(it) }

            remote.forEach { playlist ->
                val id = NAVIDROME_ID_PREFIX + playlist.id
                if (existing.containsKey(id)) {
                    playlistDao.updatePlaylistMetadata(id, playlist.name, playlist.comment)
                } else {
                    playlistDao.upsertPlaylist(PlaylistEntity(id = id, name = playlist.name, description = playlist.comment))
                }

                playlistDao.deleteTracksForPlaylist(id)
                val tracks = playlist.songIds
                    .map { NAVIDROME_ID_PREFIX + it }
                    .filter { it in knownSongIds }
                    .distinct()
                    .mapIndexed { index, songId -> PlaylistTrackEntity(id, songId, index) }
                if (tracks.isNotEmpty()) playlistDao.upsertTracks(tracks)
            }
        }
        return remote.size
    }

    suspend fun clearNavidromeLibrary() {
        withContext(Dispatchers.IO) {
            playlistDao.getAllPlaylists()
                .filter { it.id.startsWith(NAVIDROME_ID_PREFIX) }
                .forEach { playlistDao.deletePlaylist(it) }
            songDao.deleteBySourceType(SOURCE_NAVIDROME)
            albumDao.deleteBySourceType(SOURCE_NAVIDROME)
            artistDao.deleteBySourceType(SOURCE_NAVIDROME)
        }
    }

    suspend fun ingestAppDownloadsIfMissing(): Int {
        return withContext(Dispatchers.IO) {
            // Downloads may live on any storage volume (internal or SD card).
            val files = app.getExternalFilesDirs(Environment.DIRECTORY_MUSIC)
                .filterNotNull()
                .flatMap { dir -> dir.listFiles()?.filter { it.isFile } ?: emptyList() }
            if (files.isEmpty()) return@withContext 0

            val existingDownloads = songDao.getSongsBySourceType("YOUTUBE_DOWNLOAD")
            val existingByUri = existingDownloads.associateBy { it.audioUri }

            val toInsert = mutableListOf<SongEntity>()

            for (file in files) {
                val uri = Uri.fromFile(file)
                val uriString = uri.toString()
                if (existingByUri.containsKey(uriString)) continue

                val scanned = LocalMusicScanner.buildSongEntityFromFile(
                    context = app,
                    uri = uri,
                    name = file.name,
                    lastModified = file.lastModified(),
                    fileSize = file.length(),
                    existing = null,
                )
                toInsert += scanned.song.copy(sourceType = "YOUTUBE_DOWNLOAD")
            }

            if (toInsert.isNotEmpty()) {
                songDao.upsertAll(toInsert)
            }

            toInsert.size
        }
    }
}