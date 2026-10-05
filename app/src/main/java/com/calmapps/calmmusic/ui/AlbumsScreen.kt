package com.calmapps.calmmusic.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.menus.DropdownMenuItemMMD
import com.mudita.mmd.components.menus.DropdownMenuMMD
import com.mudita.mmd.components.text.TextMMD

/** UI model for displaying albums in the library. */
data class AlbumUiModel(
    val id: String,
    val title: String,
    val artist: String?,
    val sourceType: String,
    /** Optional release year for display when available. */
    val releaseYear: Int? = null,
)

@Composable
fun AlbumsScreen(
    isAuthenticated: Boolean,
    albums: List<AlbumUiModel>,
    isLoading: Boolean,
    errorMessage: String?,
    isSyncInProgress: Boolean,
    hasAnySongs: Boolean,
    onOpenStreamingSettingsClick: () -> Unit,
    onOpenLocalSettingsClick: () -> Unit,
    onAlbumClick: (AlbumUiModel) -> Unit = {},
) {
    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = "Loading albums...")
                }
            }

            errorMessage != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        TextMMD(text = "Error loading albums")
                        TextMMD(text = errorMessage)
                    }
                }
            }

            albums.isEmpty() && isSyncInProgress -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    TextMMD(text = "Music sync is in progress…")
                }
            }

            albums.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!hasAnySongs) {
                        LibraryOnboardingEmptyState(
                            title = "No albums yet",
                            body = "Connect Apple Music or choose local folders in Settings to start building your library.",
                            onOpenStreamingSettingsClick = onOpenStreamingSettingsClick,
                            onOpenLocalSettingsClick = onOpenLocalSettingsClick,
                        )
                    } else {
                        TextMMD(text = "No albums to show yet. Once your songs have album info, they'll appear here.")
                    }
                }
            }

            else -> {
                val lastAlbumId = albums.lastOrNull()?.id
                LazyColumnMMD(contentPadding = PaddingValues(16.dp)) {
                    items(
                        items = albums,
                        key = { it.id },
                    ) { album ->
                        val isLast = album.id == lastAlbumId
                        AlbumItem(
                            album = album,
                            onClick = { onAlbumClick(album) },
                            showDivider = !isLast,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AlbumItem(
    album: AlbumUiModel,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    val downloadActions = LocalDownloadActions.current
    var showMenu by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    if (downloadActions?.canDownloadAlbum(album) == true) showMenu = true
                },
            )
            .padding(bottom = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                TextMMD(
                    text = album.title,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!album.artist.isNullOrBlank() || album.releaseYear != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    val subtitle = when {
                        !album.artist.isNullOrBlank() && album.releaseYear != null ->
                            "${album.artist} • ${album.releaseYear}"
                        !album.artist.isNullOrBlank() -> album.artist
                        album.releaseYear != null -> album.releaseYear.toString()
                        else -> ""
                    }
                    if (subtitle.isNotBlank()) {
                        TextMMD(
                            text = subtitle,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            if (showMenu && downloadActions != null) {
                Box(modifier = Modifier.wrapContentSize()) {
                    Icon(
                        imageVector = Icons.Outlined.Clear,
                        contentDescription = "Close menu",
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { showMenu = false }
                    )

                    DropdownMenuMMD(
                        expanded = true,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItemMMD(
                            text = { TextMMD(text = "Download album") },
                            onClick = {
                                showMenu = false
                                downloadActions.downloadAlbum(album)
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (showDivider) {
            DashedDivider(thickness = 1.dp)
        }
    }
}
