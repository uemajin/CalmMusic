package com.calmapps.calmmusic.data

const val SOURCE_NAVIDROME = "NAVIDROME"

/**
 * True for sources that play through the shared Media3 queue segment
 * (local files, finished YouTube downloads and Navidrome streams).
 */
fun isLocalPlayback(sourceType: String?): Boolean =
    sourceType == "LOCAL_FILE" || sourceType == "YOUTUBE_DOWNLOAD" || sourceType == SOURCE_NAVIDROME

/** A Navidrome song whose audio has been downloaded: its audioUri points at a file instead of navidrome://. */
fun isDownloadedNavidrome(sourceType: String?, audioUri: String?): Boolean =
    sourceType == SOURCE_NAVIDROME && !audioUri.isNullOrBlank() && !audioUri.startsWith("navidrome:")

/** Whether the file behind a file:// or content:// (document) URI still exists. */
fun mediaUriExists(context: android.content.Context, uriString: String?): Boolean {
    if (uriString.isNullOrBlank()) return false
    val uri = android.net.Uri.parse(uriString)
    return try {
        if (uri.scheme == "content") {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
        } else {
            uri.path?.let { java.io.File(it).exists() } ?: false
        }
    } catch (_: Exception) {
        false
    }
}

/** Deletes the file behind a file:// or content:// (document) URI. Best effort. */
fun deleteMediaUri(context: android.content.Context, uriString: String?) {
    if (uriString.isNullOrBlank()) return
    val uri = android.net.Uri.parse(uriString)
    try {
        if (uri.scheme == "content") {
            androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.delete()
        } else {
            uri.path?.let { java.io.File(it).delete() }
        }
    } catch (_: Exception) {
    }
}
