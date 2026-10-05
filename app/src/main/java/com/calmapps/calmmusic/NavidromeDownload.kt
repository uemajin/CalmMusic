package com.calmapps.calmmusic

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.calmapps.calmmusic.data.CalmMusicDatabase
import com.calmapps.calmmusic.data.SOURCE_NAVIDROME
import com.calmapps.calmmusic.data.SongEntity
import com.calmapps.calmmusic.ui.SongUiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Subfolder of the app Music dir; kept separate so the YouTube download ingest never picks these files up. */
const val NAVIDROME_DOWNLOAD_DIR = "Navidrome"

/**
 * Downloads the original audio file of a Navidrome song and points the
 * existing library row at it (the song keeps its id, so playlists keep working).
 */
internal suspend fun performNavidromeDownloadInternal(
    app: CalmMusic,
    song: SongUiModel,
    musicDir: File,
    client: OkHttpClient,
    onProgress: (Float) -> Unit,
): Boolean {
    val serverId = song.id.removePrefix(NAVIDROME_ID_PREFIX)
    val targetDir = File(musicDir, NAVIDROME_DOWNLOAD_DIR)
    val tmpFile = withContext(Dispatchers.IO) { File.createTempFile("nd-", ".part", app.cacheDir) }

    try {
        val extension = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(app.navidromeClient.buildDownloadUrl(serverId)).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Download failed: HTTP " + response.code)
                val body = response.body ?: throw IOException("Empty response")

                val contentType = response.header("Content-Type").orEmpty()
                if (contentType.startsWith("application/json") || contentType.startsWith("text/")) {
                    throw IOException("Server did not return audio")
                }

                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                FileOutputStream(tmpFile).use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(16 * 1024)
                        var read: Int
                        var soFar = 0L
                        while (input.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            soFar += read
                            if (total > 0) onProgress((soFar.toFloat() / total.toFloat()).coerceIn(0f, 1f))
                        }
                    }
                }
                extensionFor(response.header("Content-Disposition"), contentType)
            }
        }

        fun String.safe() = replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        val baseName = (song.artist.safe() + " - " + song.title.safe()).take(120)
        withContext(Dispatchers.IO) {
            val saved = saveDownloadedFile(app, tmpFile, baseName, extension, serverId, targetDir)

            val songDao = CalmMusicDatabase.getDatabase(app).songDao()
            val fileUri = saved.uri
            val existing = songDao.getSongsBySourceType(SOURCE_NAVIDROME).firstOrNull { it.id == song.id }
            val updated = (existing ?: SongEntity(
                id = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumId = null,
                discNumber = song.discNumber,
                trackNumber = song.trackNumber,
                durationMillis = song.durationMillis,
                sourceType = SOURCE_NAVIDROME,
                audioUri = fileUri,
            )).copy(
                audioUri = fileUri,
                localLastModifiedMillis = saved.lastModified,
                localFileSizeBytes = saved.size,
            )
            songDao.upsertAll(listOf(updated))
        }
        onProgress(1f)
        return true
    } finally {
        tmpFile.delete()
    }
}

internal data class SavedFile(val uri: String, val size: Long, val lastModified: Long)

/**
 * Moves the finished download to its final place: the folder picked in Settings (written
 * through the document provider) or, by default, the app's own Music directory.
 */
internal fun saveDownloadedFile(
    app: CalmMusic,
    tmp: File,
    baseName: String,
    extension: String,
    serverId: String,
    defaultDir: File,
): SavedFile {
    val treeUri = app.settingsManager.downloadTreeUri.value
    if (treeUri != null) {
        val tree = DocumentFile.fromTreeUri(app, Uri.parse(treeUri))
            ?.takeIf { it.exists() && it.canWrite() }
            ?: throw IOException("The chosen download folder is unavailable. Pick a folder again in Settings.")

        var name = "$baseName.$extension"
        if (tree.findFile(name) != null) name = "$baseName [$serverId].$extension"
        tree.findFile(name)?.delete()

        val doc = tree.createFile(mimeFor(extension), name)
            ?: throw IOException("Couldn't create a file in the chosen folder")
        try {
            val out = app.contentResolver.openOutputStream(doc.uri, "w")
                ?: throw IOException("Couldn't write to the chosen folder")
            out.use { stream -> tmp.inputStream().use { it.copyTo(stream) } }
        } catch (e: Exception) {
            doc.delete()
            throw e
        }
        requestMediaScan(app, doc.uri, mimeFor(extension))
        return SavedFile(doc.uri.toString(), doc.length(), doc.lastModified())
    }

    defaultDir.mkdirs()
    var target = File(defaultDir, "$baseName.$extension")
    if (target.exists()) target = File(defaultDir, "$baseName [$serverId].$extension")
    if (target.exists()) target.delete()
    if (!tmp.renameTo(target)) {
        tmp.copyTo(target, overwrite = true)
    }
    return SavedFile(Uri.fromFile(target).toString(), target.length(), target.lastModified())
}

/**
 * Asks Android's media index to pick up a file written through the document provider, so other
 * music apps (such as the Mudita player) list it straight away.
 */
private fun requestMediaScan(app: CalmMusic, documentUri: Uri, mime: String) {
    try {
        val documentId = android.provider.DocumentsContract.getDocumentId(documentUri)
        val volume = documentId.substringBefore(':')
        val relativePath = documentId.substringAfter(':', "")
        if (relativePath.isEmpty()) return
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        android.media.MediaScannerConnection.scanFile(app, arrayOf("$root/$relativePath"), arrayOf(mime), null)
    } catch (_: Exception) {
    }
}

private fun mimeFor(extension: String): String = when (extension) {
    "mp3" -> "audio/mpeg"
    "flac" -> "audio/flac"
    "m4a" -> "audio/mp4"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    else -> "audio/*"
}

private fun extensionFor(contentDisposition: String?, contentType: String): String {
    val fromName = contentDisposition
        ?.let { Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
        ?.substringAfterLast('.', "")
        ?.lowercase()
        ?.takeIf { it.length in 2..5 }
    if (fromName != null) return fromName

    return when (contentType.substringBefore(';').trim().lowercase()) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/x-m4a", "audio/aac" -> "m4a"
        "audio/ogg", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav" -> "wav"
        else -> "audio"
    }
}
