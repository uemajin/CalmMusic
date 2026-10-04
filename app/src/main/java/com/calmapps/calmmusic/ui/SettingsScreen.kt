package com.calmapps.calmmusic.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmapps.calmmusic.data.NavidromeConfig
import com.calmapps.calmmusic.data.StreamingProvider
import com.mudita.mmd.components.buttons.ButtonMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.chips.SuggestionChipMMD
import com.mudita.mmd.components.divider.HorizontalDividerMMD
import com.mudita.mmd.components.lazy.LazyColumnMMD
import com.mudita.mmd.components.radio_button.RadioButtonMMD
import com.mudita.mmd.components.slider.SliderMMD
import com.mudita.mmd.components.switcher.SwitchMMD
import com.mudita.mmd.components.tabs.PrimaryTabRowMMD
import com.mudita.mmd.components.tabs.TabMMD
import com.mudita.mmd.components.text.TextMMD

data class DownloadVolume(
    val path: String,
    val label: String,
    val freeBytes: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    selectedTab: Int,
    onSelectedTabChange: (Int) -> Unit,
    streamingProvider: StreamingProvider,
    onStreamingProviderChange: (StreamingProvider) -> Unit,
    navidromeConfig: NavidromeConfig?,
    onSaveNavidromeConfig: suspend (NavidromeConfig) -> Result<String>,
    onClearNavidromeConfig: () -> Unit,
    navidromeSyncStatus: String?,
    onSyncNavidromeLibrary: () -> Unit,
    navidromeStreamKbps: Int,
    onNavidromeStreamKbpsChange: (Int) -> Unit,
    navidromeDownloadKbps: Int,
    onNavidromeDownloadKbpsChange: (Int) -> Unit,
    downloadVolumes: List<DownloadVolume>,
    selectedDownloadPath: String?,
    onDownloadVolumeSelected: (String) -> Unit,
    customDownloadFolderLabel: String?,
    onChooseDownloadFolder: () -> Unit,
    completeAlbumsWithYouTube: Boolean,
    onCompleteAlbumsWithYouTubeChange: (Boolean) -> Unit,
    includeLocalMusic: Boolean,
    localFolders: List<String>,
    isAppleMusicAuthenticated: Boolean,
    hasBatteryOptimizationExemption: Boolean,
    onConnectAppleMusicClick: () -> Unit,
    onRequestBatteryOptimizationExemption: () -> Unit,
    onIncludeLocalMusicChange: (Boolean) -> Unit,
    onAddFolderClick: () -> Unit,
    onRemoveFolderClick: (String) -> Unit,
    onRescanLocalMusicClick: () -> Unit,
    isRescanningLocal: Boolean,
    localScanProgress: Float,
    isIngestingLocal: Boolean,
    localIngestProgress: Float,
    localScanTotalDiscovered: Int?,
    localScanSkippedUnchanged: Int?,
    localScanIndexedNewOrUpdated: Int?,
    localScanDeletedMissing: Int?,
) {
    val navidromeForm = remember { NavidromeFormState(navidromeConfig) }

    // 0 = General, 1 = Streaming, 2 = Local
    val tabOptions = listOf("General", "Streaming", "Local")

    Column(
        modifier = Modifier
            .fillMaxSize()
    ) {
        PrimaryTabRowMMD(selectedTabIndex = selectedTab) {
            tabOptions.forEachIndexed { index, title ->
                TabMMD(
                    selected = selectedTab == index,
                    onClick = { onSelectedTabChange(index) },
                    text = {
                        TextMMD(
                            text = title,
                            fontSize = 16.sp,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                        )
                    },
                )
            }
        }

        if (selectedTab == 0) {
            // General tab - app-wide settings
            LazyColumnMMD(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 16.dp),
                    ) {
                        TextMMD(
                            text = "Background playback",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        TextMMD(
                            text = if (hasBatteryOptimizationExemption) {
                                "Battery optimizations are currently ignoring CalmMusic. Background playback is less likely to be stopped, but the system may still close the app in extreme cases."
                            } else {
                                "On some devices, battery optimizations can stop CalmMusic while playing in the background. You can request an exemption so the system is less likely to pause playback."
                            },
                            fontSize = 14.sp,
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButtonMMD(
                            onClick = onRequestBatteryOptimizationExemption,
                            enabled = !hasBatteryOptimizationExemption,
                        ) {
                            TextMMD(
                                text = if (hasBatteryOptimizationExemption) {
                                    "Background optimization already allowed"
                                } else {
                                    "Allow CalmMusic to run in background"
                                },
                                fontSize = 16.sp,
                            )
                        }
                    }
                }
            }
        } else if (selectedTab == 1) {
            LazyColumnMMD(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 16.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.Top,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                    ) {
                        TextMMD(
                            text = "Streaming source",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )

                        HorizontalDividerMMD(
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextMMD(
                                    text = "Apple Music",
                                    fontSize = 16.sp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                SuggestionChipMMD(
                                    label = { TextMMD(text = "Coming Soon", fontSize = 12.sp) },
                                    onClick = {}
                                )
                            }
                            RadioButtonMMD(
                                selected = streamingProvider == StreamingProvider.APPLE_MUSIC,
                                onClick = { },
                                enabled = false
                            )
                        }

                        DashedDivider(thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onStreamingProviderChange(StreamingProvider.YOUTUBE) }
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextMMD(
                                text = "YouTube Music",
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f),
                            )
                            RadioButtonMMD(
                                selected = streamingProvider == StreamingProvider.YOUTUBE,
                                onClick = { onStreamingProviderChange(StreamingProvider.YOUTUBE) },
                            )
                        }

                        DashedDivider(thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onStreamingProviderChange(StreamingProvider.NAVIDROME) }
                                .padding(end = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextMMD(
                                text = "Navidrome",
                                fontSize = 16.sp,
                                modifier = Modifier.weight(1f),
                            )
                            RadioButtonMMD(
                                selected = streamingProvider == StreamingProvider.NAVIDROME,
                                onClick = { onStreamingProviderChange(StreamingProvider.NAVIDROME) },
                            )
                        }
                    }
                }

                if (streamingProvider == StreamingProvider.NAVIDROME) {
                    navidromeItems(
                        form = navidromeForm,
                        savedConfig = navidromeConfig,
                        onSave = onSaveNavidromeConfig,
                        onClear = onClearNavidromeConfig,
                        syncStatus = navidromeSyncStatus,
                        onSyncNow = onSyncNavidromeLibrary,
                        streamKbps = navidromeStreamKbps,
                        onStreamKbpsChange = onNavidromeStreamKbpsChange,
                        downloadKbps = navidromeDownloadKbps,
                        onDownloadKbpsChange = onNavidromeDownloadKbpsChange,
                    )
                }

                if (streamingProvider == StreamingProvider.YOUTUBE) {
                    item {
                        Spacer(modifier = Modifier.height(24.dp))

                        TextMMD(
                            text = "Library features",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )

                        HorizontalDividerMMD(
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 16.dp)
                        ) {

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onCompleteAlbumsWithYouTubeChange(!completeAlbumsWithYouTube) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 16.dp)
                                ) {
                                    TextMMD(
                                        text = "Complete albums with YouTube",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    TextMMD(
                                        text = "When viewing a local album, search YouTube for missing songs and display them in the list.",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                SwitchMMD(
                                    checked = completeAlbumsWithYouTube,
                                    onCheckedChange = onCompleteAlbumsWithYouTubeChange,
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }

                if (streamingProvider == StreamingProvider.APPLE_MUSIC) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))

                        HorizontalDividerMMD(
                            thickness = 1.dp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(end = 16.dp)
                        ) {
                            TextMMD(
                                text = "Apple Music",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            TextMMD(
                                text = if (isAppleMusicAuthenticated) "Apple Music is connected" else "Apple Music is not connected",
                                fontSize = 16.sp,
                            )

                            if (!isAppleMusicAuthenticated) {
                                Spacer(modifier = Modifier.height(4.dp))
                                TextMMD(
                                    text = "Connect to access your Apple Music library.",
                                    fontSize = 14.sp,
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            if (!isAppleMusicAuthenticated) {
                                ButtonMMD(
                                    onClick = onConnectAppleMusicClick
                                ) {
                                    TextMMD(text = "Connect")
                                }
                            }
                        }
                    }
                }
            }
        } else if (selectedTab == 2) {
            LazyColumnMMD(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Top,
            ) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    ) {
                        TextMMD(
                            text = "Download location",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        TextMMD(
                            text = "Where downloads are saved. Pick a folder to keep your music where file managers can see it. Existing downloads stay where they are.",
                            fontSize = 14.sp,
                        )
                        HorizontalDividerMMD(
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )

                        downloadVolumes.forEach { volume ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onDownloadVolumeSelected(volume.path) }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    TextMMD(text = volume.label, fontSize = 16.sp)
                                    TextMMD(
                                        text = formatFreeSpace(volume.freeBytes) + " free",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                RadioButtonMMD(
                                    selected = volume.path == selectedDownloadPath,
                                    onClick = { onDownloadVolumeSelected(volume.path) },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onChooseDownloadFolder() }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                TextMMD(text = "Choose a folder...", fontSize = 16.sp)
                                TextMMD(
                                    text = customDownloadFolderLabel ?: "Any folder, including on your SD card",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            RadioButtonMMD(
                                selected = customDownloadFolderLabel != null,
                                onClick = onChooseDownloadFolder,
                            )
                        }

                        if (downloadVolumes.size < 2) {
                            Spacer(modifier = Modifier.height(4.dp))
                            TextMMD(
                                text = "No SD card detected.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        HorizontalDividerMMD(
                            thickness = 1.dp,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }

                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextMMD(
                            text = "Include local music",
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f),
                        )
                        SwitchMMD(
                            checked = includeLocalMusic,
                            onCheckedChange = onIncludeLocalMusicChange,
                        )
                    }
                }

                if (includeLocalMusic) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        ) {
                            TextMMD(
                                text = "Local music folders",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            TextMMD(
                                text = "Choose one or more folders to scan for audio files.",
                                fontSize = 14.sp,
                            )

                            HorizontalDividerMMD(
                                thickness = 1.dp,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    }

                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ButtonMMD(
                                onClick = onAddFolderClick,
                                modifier = Modifier.weight(1f),
                            ) {
                                TextMMD(
                                    text = "Add folder",
                                    fontSize = 16.sp,
                                )
                            }

                            if (localFolders.isNotEmpty()) {
                                OutlinedButtonMMD(
                                    onClick = onRescanLocalMusicClick,
                                    modifier = Modifier.weight(1f),
                                    enabled = localFolders.isNotEmpty() && !isRescanningLocal,
                                ) {
                                    TextMMD(
                                        text = "Rescan",
                                        fontSize = 16.sp,
                                    )
                                }
                            }
                        }
                    }

                    if (isRescanningLocal && !isIngestingLocal) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                                ) {
                                    SliderMMD(
                                        modifier = Modifier.fillMaxWidth(),
                                        value = localScanProgress.coerceIn(0f, 1f),
                                        onValueChange = { },
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    val percent =
                                        (localScanProgress * 100f).toInt().coerceIn(0, 100)
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        TextMMD(
                                            text = "Step 1 of 2 – Scanning folders for audio files… $percent%",
                                            fontSize = 14.sp,
                                        )
                                        if (localScanTotalDiscovered != null && localScanSkippedUnchanged != null && localScanIndexedNewOrUpdated != null) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            TextMMD(
                                                text = "Found $localScanTotalDiscovered files · Skipped $localScanSkippedUnchanged unchanged · Indexed $localScanIndexedNewOrUpdated new/updated",
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isIngestingLocal) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                                ) {
                                    SliderMMD(
                                        modifier = Modifier.fillMaxWidth(),
                                        value = localIngestProgress.coerceIn(0f, 1f),
                                        onValueChange = { },
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                ) {
                                    val ingestPercent =
                                        (localIngestProgress * 100f).toInt().coerceIn(0, 100)
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        TextMMD(
                                            text = "Step 2 of 2 – Adding music to library… $ingestPercent%",
                                            fontSize = 14.sp,
                                        )
                                        if (localScanDeletedMissing != null && localScanDeletedMissing > 0) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            TextMMD(
                                                text = "Removed ${localScanDeletedMissing} files that are no longer present",
                                                fontSize = 13.sp,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Show the last scan summary after work is complete.
                    if (!isRescanningLocal && !isIngestingLocal &&
                        localScanTotalDiscovered != null &&
                        localScanSkippedUnchanged != null &&
                        localScanIndexedNewOrUpdated != null
                    ) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                            ) {
                                TextMMD(
                                    text = "Last scan",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )

                                Spacer(modifier = Modifier.height(4.dp))

                                TextMMD(
                                    text = "Found $localScanTotalDiscovered files · Skipped $localScanSkippedUnchanged unchanged · Indexed $localScanIndexedNewOrUpdated new/updated",
                                    fontSize = 13.sp,
                                )

                                if (localScanDeletedMissing != null && localScanDeletedMissing > 0) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    TextMMD(
                                        text = "Removed ${localScanDeletedMissing} files that are no longer present",
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }

                    if (localFolders.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                            ) {
                                TextMMD(
                                    text = "No folders selected yet.",
                                    fontSize = 14.sp,
                                )
                            }
                        }
                    } else {
                        items(localFolders) { folder ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextMMD(
                                    text = formatDirectoryPath(folder),
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { onRemoveFolderClick(folder) }) {
                                    Icon(
                                        imageVector = Icons.Outlined.Delete,
                                        contentDescription = "Remove folder",
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatDirectoryPath(uriString: String): String {
    try {
        val decoded = Uri.decode(uriString)
        val marker = "primary:"
        val index = decoded.indexOf(marker)
        if (index != -1) {
            val afterMarker = decoded.substring(index + marker.length)
            val path = afterMarker.replace("/", " > ")
            return if (path.isEmpty()) "Primary" else "Primary > $path"
        }
    } catch (e: Exception) {
        // Fallback
    }
    return uriString
}


private class NavidromeFormState(initial: NavidromeConfig?) {
    var url by mutableStateOf(initial?.baseUrl ?: "")
    var username by mutableStateOf(initial?.username ?: "")
    var password by mutableStateOf(initial?.password ?: "")
    var isTesting by mutableStateOf(false)
    var statusMessage by mutableStateOf<String?>(null)
}

/**
 * The Navidrome settings are split into several list items because the Mudita
 * list scrolls one item at a time; a single tall item would be cut off.
 */
private fun LazyListScope.navidromeItems(
    form: NavidromeFormState,
    savedConfig: NavidromeConfig?,
    onSave: suspend (NavidromeConfig) -> Result<String>,
    onClear: () -> Unit,
    syncStatus: String?,
    onSyncNow: () -> Unit,
    streamKbps: Int,
    onStreamKbpsChange: (Int) -> Unit,
    downloadKbps: Int,
    onDownloadKbpsChange: (Int) -> Unit,
) {
    item {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, end = 16.dp),
        ) {
            TextMMD(
                text = "Navidrome server",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )

            HorizontalDividerMMD(
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            OutlinedTextField(
                value = form.url,
                onValueChange = { form.url = it },
                label = { TextMMD(text = "Server URL", fontSize = 12.sp) },
                placeholder = { TextMMD(text = "https://music.example.com", fontSize = 14.sp) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (form.url.isNotBlank() && normalizeServerUrl(form.url).startsWith("http://")) {
                Spacer(modifier = Modifier.height(4.dp))
                TextMMD(
                    text = "This connection is not encrypted (http). Only use it on a network you trust.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = form.username,
                onValueChange = { form.username = it },
                label = { TextMMD(text = "Username", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = form.password,
                onValueChange = { form.password = it },
                label = { TextMMD(text = "Password", fontSize = 12.sp) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    item {
        val scope = rememberCoroutineScope()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp, end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ButtonMMD(
                onClick = {
                    val trimmedUrl = form.url.trim()
                    if (trimmedUrl.isBlank() || form.username.isBlank()) {
                        form.statusMessage = "Enter a server URL and username"
                        return@ButtonMMD
                    }
                    val normalizedUrl = normalizeServerUrl(trimmedUrl)
                    form.isTesting = true
                    form.statusMessage = "Connecting..."
                    scope.launch {
                        val result = onSave(
                            NavidromeConfig(normalizedUrl, form.username.trim(), form.password),
                        )
                        form.isTesting = false
                        form.statusMessage = result.fold(
                            onSuccess = { "Connected to " + it },
                            onFailure = { "Connection failed: " + (it.message ?: it.javaClass.simpleName) },
                        )
                    }
                },
                enabled = !form.isTesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                TextMMD(text = "Save & test")
            }

            if (savedConfig != null) {
                OutlinedButtonMMD(
                    onClick = onSyncNow,
                    enabled = !form.isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextMMD(text = "Sync library")
                }
                OutlinedButtonMMD(
                    onClick = {
                        onClear()
                        form.url = ""
                        form.username = ""
                        form.password = ""
                        form.statusMessage = "Signed out"
                    },
                    enabled = !form.isTesting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    TextMMD(text = "Sign out")
                }
            }

            val messages = listOfNotNull(syncStatus, form.statusMessage)
            messages.forEach {
                TextMMD(
                    text = it,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    item {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, end = 16.dp),
        ) {
            TextMMD(
                text = "Quality",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )

            HorizontalDividerMMD(
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 8.dp),
            )

            QualityRow(
                title = "Streaming quality",
                description = "Lower values save data. Seeking can be slower when a stream is converted.",
                kbps = streamKbps,
                onChange = onStreamKbpsChange,
            )

            DashedDivider(thickness = 1.dp, modifier = Modifier.padding(vertical = 8.dp))

            QualityRow(
                title = "Download quality",
                description = "Original keeps the file exactly as stored on the server.",
                kbps = downloadKbps,
                onChange = onDownloadKbpsChange,
            )
        }
    }
}

@Composable
private fun QualityRow(
    title: String,
    description: String,
    kbps: Int,
    onChange: (Int) -> Unit,
) {
    val options = listOf(0, 320, 192, 128)
    fun label(value: Int) = if (value <= 0) "Original" else "$value kbps"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val next = options[(options.indexOf(kbps).coerceAtLeast(0) + 1) % options.size]
                onChange(next)
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            TextMMD(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            TextMMD(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButtonMMD(onClick = {
            val next = options[(options.indexOf(kbps).coerceAtLeast(0) + 1) % options.size]
            onChange(next)
        }) {
            TextMMD(text = label(kbps), fontSize = 14.sp)
        }
    }
}

private fun formatFreeSpace(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.1f GB", gb) else String.format("%d MB", bytes / (1024 * 1024))
}

/**
 * Adds a scheme when none was typed: plain http for addresses that are clearly on a local
 * network (private IPs, .local names, single-word hostnames), https for everything else.
 */
private fun normalizeServerUrl(input: String): String {
    val trimmed = input.trim().trimEnd('/')
    if (trimmed.contains("://")) return trimmed
    val host = trimmed.substringBefore('/').substringBefore(':')
    val isLocal = Regex("^(10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[01])\\.|127\\.)").containsMatchIn(host) ||
        host.endsWith(".local") || host == "localhost" || !host.contains('.')
    return (if (isLocal) "http://" else "https://") + trimmed
}
