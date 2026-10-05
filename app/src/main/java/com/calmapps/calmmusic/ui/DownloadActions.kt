package com.calmapps.calmmusic.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Download entry points used by the long-press menus on songs, albums and playlists. Provided
 * once by the main screen so list items do not need the download plumbing passed through
 * every screen.
 */
interface DownloadActions {
    fun canDownload(song: SongUiModel): Boolean
    fun downloadSong(song: SongUiModel)

    fun canDownloadAlbum(album: AlbumUiModel): Boolean
    fun downloadAlbum(album: AlbumUiModel)

    fun downloadPlaylist(playlist: PlaylistUiModel)
}

val LocalDownloadActions = staticCompositionLocalOf<DownloadActions?> { null }
