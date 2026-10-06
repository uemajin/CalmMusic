package com.calmapps.calmmusic

import com.calmapps.calmmusic.data.NavidromeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** The server answered, but refused the request (as opposed to a network failure). */
class NavidromeApiException(message: String) : IOException(message)

data class NavidromeSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val albumId: String?,
    val artistId: String?,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val durationMillis: Long?,
)

data class NavidromeAlbum(
    val id: String,
    val title: String,
    val artist: String?,
    val artistId: String?,
    val year: Int?,
)

data class NavidromePlaylist(
    val id: String,
    val name: String,
    val comment: String?,
    val songIds: List<String>,
)

data class NavidromeSearchResult(
    val songs: List<NavidromeSong>,
    val albums: List<NavidromeAlbum>,
)

/**
 * Minimal Subsonic/OpenSubsonic client for talking to a Navidrome server.
 * Auth uses the salted-token scheme (t = md5(password + salt)), so the
 * password itself never appears in a URL.
 */
class NavidromeApiClient(
    private val http: OkHttpClient,
    private val configProvider: () -> NavidromeConfig?,
    private val streamKbps: () -> Int = { 0 },
    private val downloadKbps: () -> Int = { 0 },
) {
    private val random = SecureRandom()

    private fun requireConfig(): NavidromeConfig =
        configProvider() ?: throw IOException("Navidrome server is not configured")

    private fun buildUrl(
        config: NavidromeConfig,
        endpoint: String,
        params: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val saltBytes = ByteArray(8).also { random.nextBytes(it) }
        val salt = saltBytes.joinToString("") { "%02x".format(it) }
        val token = md5Hex(config.password + salt)

        val builder = (config.baseUrl.trimEnd('/') + "/rest/$endpoint").toHttpUrl().newBuilder()
            .addQueryParameter("u", config.username)
            .addQueryParameter("t", token)
            .addQueryParameter("s", salt)
            .addQueryParameter("v", API_VERSION)
            .addQueryParameter("c", CLIENT_NAME)
            .addQueryParameter("f", "json")
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        return builder.build()
    }

    /** URL for streaming the original audio of a song. A fresh token is generated on every call. */
    fun buildStreamUrl(songId: String): String =
        buildUrl(requireConfig(), "stream", streamParams(songId, streamKbps(), estimateLength = true)).toString()

    private fun streamParams(songId: String, kbps: Int, estimateLength: Boolean = false): Map<String, String> =
        if (kbps <= 0) {
            mapOf("id" to songId)
        } else {
            mapOf(
                "id" to songId,
                "maxBitRate" to kbps.toString(),
                "format" to "mp3",
            ) + if (estimateLength) {
                // Gives the player a duration/length so seeking works; not used for downloads
                // because the estimate is not byte-exact and HTTP/2 resets the stream at the end.
                mapOf("estimateContentLength" to "true")
            } else {
                emptyMap()
            }
        }

    /** URL for downloading the original file of a song. */
    fun buildDownloadUrl(songId: String): String {
        val kbps = downloadKbps()
        return if (kbps <= 0) {
            buildUrl(requireConfig(), "download", mapOf("id" to songId)).toString()
        } else {
            buildUrl(requireConfig(), "stream", streamParams(songId, kbps)).toString()
        }
    }

    private suspend fun call(
        config: NavidromeConfig,
        endpoint: String,
        params: Map<String, String> = emptyMap(),
    ): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(buildUrl(config, endpoint, params)).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Server returned HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw IOException("Empty response from server")
            val root = try {
                JSONObject(body).getJSONObject("subsonic-response")
            } catch (e: Exception) {
                throw IOException("Not a Subsonic/Navidrome server response")
            }
            if (root.optString("status") != "ok") {
                val error = root.optJSONObject("error")
                throw NavidromeApiException(
                    error?.optString("message").takeUnless { it.isNullOrBlank() } ?: "Request failed",
                )
            }
            root
        }
    }

    /**
     * Reports a play to the server (which forwards it to ListenBrainz / Last.fm if linked there).
     * With [submission] false it only announces "now playing"; with true it records the play,
     * stamped with [playedAtMillis] so plays made offline keep their real time.
     */
    suspend fun scrobble(songId: String, playedAtMillis: Long?, submission: Boolean) {
        val params = mutableMapOf("id" to songId, "submission" to submission.toString())
        if (playedAtMillis != null) params["time"] = playedAtMillis.toString()
        call(requireConfig(), "scrobble", params)
    }

    /** Verifies the server URL and credentials. Returns a short server description. */
    suspend fun ping(config: NavidromeConfig): String {
        val root = call(config, "ping")
        val type = root.optString("type").ifBlank { "Subsonic" }
        val version = root.optString("serverVersion").ifBlank { root.optString("version") }
        return "$type $version".trim()
    }

    suspend fun search(query: String, songCount: Int = 50, albumCount: Int = 25): NavidromeSearchResult {
        val root = call(
            requireConfig(),
            "search3",
            mapOf(
                "query" to query,
                "songCount" to songCount.toString(),
                "albumCount" to albumCount.toString(),
                "artistCount" to "0",
            ),
        )
        val result = root.optJSONObject("searchResult3")
        val albumArray = result?.optJSONArray("album")
        val albums = (0 until (albumArray?.length() ?: 0)).map { albumArray!!.getJSONObject(it).toAlbum() }
        return NavidromeSearchResult(songs = result.songs("song"), albums = albums)
    }

    /** Downloads the whole catalogue (songs and albums) using paged empty-query search3 calls. */
    suspend fun fetchLibrary(onProgress: (Int) -> Unit = {}): Pair<List<NavidromeSong>, List<NavidromeAlbum>> {
        val config = requireConfig()
        val pageSize = 500
        val songs = mutableListOf<NavidromeSong>()
        val albums = mutableListOf<NavidromeAlbum>()

        var offset = 0
        while (true) {
            val result = call(
                config,
                "search3",
                mapOf(
                    "query" to "",
                    "songCount" to pageSize.toString(),
                    "songOffset" to offset.toString(),
                    "albumCount" to "0",
                    "artistCount" to "0",
                ),
            ).optJSONObject("searchResult3")
            val page = result.songs("song")
            songs += page
            onProgress(songs.size)
            if (page.size < pageSize) break
            offset += pageSize
        }

        offset = 0
        while (true) {
            val result = call(
                config,
                "search3",
                mapOf(
                    "query" to "",
                    "songCount" to "0",
                    "albumCount" to pageSize.toString(),
                    "albumOffset" to offset.toString(),
                    "artistCount" to "0",
                ),
            ).optJSONObject("searchResult3")
            val arr = result?.optJSONArray("album")
            val page = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it).toAlbum() }
            albums += page
            if (page.size < pageSize) break
            offset += pageSize
        }
        return songs to albums
    }

    /** All playlists visible to the user, each with its ordered song ids. */
    suspend fun fetchPlaylists(): List<NavidromePlaylist> {
        val config = requireConfig()
        val list = call(config, "getPlaylists").optJSONObject("playlists")?.optJSONArray("playlist")
            ?: return emptyList()

        return (0 until list.length()).map { index ->
            val item = list.getJSONObject(index)
            val id = item.getString("id")
            val entries = call(config, "getPlaylist", mapOf("id" to id))
                .optJSONObject("playlist")?.optJSONArray("entry")
            NavidromePlaylist(
                id = id,
                name = item.optString("name").ifBlank { "Playlist" },
                comment = item.optString("comment").takeIf { it.isNotBlank() },
                songIds = (0 until (entries?.length() ?: 0)).map { entries!!.getJSONObject(it).getString("id") },
            )
        }
    }

    suspend fun getAlbumSongs(albumId: String): List<NavidromeSong> {
        val root = call(requireConfig(), "getAlbum", mapOf("id" to albumId))
        return root.optJSONObject("album").songs("song")
    }

    private fun JSONObject?.songs(key: String): List<NavidromeSong> {
        val arr = this?.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).toSong() }
    }

    private fun JSONObject.toSong() = NavidromeSong(
        id = getString("id"),
        title = optString("title"),
        artist = optString("artist").ifBlank { "Unknown Artist" },
        album = optString("album").takeIf { it.isNotBlank() },
        albumId = optString("albumId").takeIf { it.isNotBlank() },
        artistId = optString("artistId").takeIf { it.isNotBlank() },
        year = if (has("year")) optInt("year") else null,
        trackNumber = if (has("track")) optInt("track") else null,
        discNumber = if (has("discNumber")) optInt("discNumber") else null,
        durationMillis = if (has("duration")) optLong("duration") * 1000L else null,
    )

    private fun JSONObject.toAlbum() = NavidromeAlbum(
        id = getString("id"),
        title = optString("name").ifBlank { optString("title") },
        artist = optString("artist").takeIf { it.isNotBlank() },
        artistId = optString("artistId").takeIf { it.isNotBlank() },
        year = if (has("year")) optInt("year") else null,
    )

    private fun md5Hex(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val API_VERSION = "1.16.1"
        private const val CLIENT_NAME = "CalmMusic"
    }
}

const val NAVIDROME_ID_PREFIX = "NAVIDROME:"

fun NavidromeSong.toUiModel() = com.calmapps.calmmusic.ui.SongUiModel(
    id = NAVIDROME_ID_PREFIX + id,
    title = title,
    artist = artist,
    durationText = formatDurationMillis(durationMillis),
    durationMillis = durationMillis,
    discNumber = discNumber,
    trackNumber = trackNumber,
    sourceType = com.calmapps.calmmusic.data.SOURCE_NAVIDROME,
    // Resolved to a signed stream URL by PlaybackService at play time.
    audioUri = "${PlaybackService.NAVIDROME_SCHEME}://$id",
    album = album,
)

fun NavidromeAlbum.toUiModel() = com.calmapps.calmmusic.ui.AlbumUiModel(
    id = NAVIDROME_ID_PREFIX + id,
    title = title,
    artist = artist,
    sourceType = com.calmapps.calmmusic.data.SOURCE_NAVIDROME,
    releaseYear = year,
)
