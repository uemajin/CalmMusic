package com.calmapps.calmmusic.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class CalmMusicSettingsManager(context: Context) {

    private val appContext: Context = context.applicationContext

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _includeLocalMusic = MutableStateFlow(getIncludeLocalMusicSync())
    val includeLocalMusic: StateFlow<Boolean> = _includeLocalMusic.asStateFlow()

    private val _localMusicFolders = MutableStateFlow(getLocalMusicFoldersSync())
    val localMusicFolders: StateFlow<Set<String>> = _localMusicFolders.asStateFlow()

    private val _streamingProvider = MutableStateFlow(getStreamingProviderSync())
    val streamingProvider: StateFlow<StreamingProvider> = _streamingProvider.asStateFlow()

    private val _completeAlbumsWithYouTube = MutableStateFlow(getCompleteAlbumsWithYouTubeSync())
    val completeAlbumsWithYouTube: StateFlow<Boolean> = _completeAlbumsWithYouTube.asStateFlow()

    // Navidrome credentials live in a separate, encrypted preferences file.
    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                appContext,
                SECURE_PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            // Keystore unavailable on some devices: fall back to private prefs.
            appContext.getSharedPreferences(SECURE_PREFS_NAME + "_fallback", Context.MODE_PRIVATE)
        }
    }

    private val _navidromeConfig = MutableStateFlow(readNavidromeConfig())
    val navidromeConfig: StateFlow<NavidromeConfig?> = _navidromeConfig.asStateFlow()

    private val _lockScreenControls = MutableStateFlow(prefs.getBoolean(KEY_LOCK_SCREEN_CONTROLS, false))
    /** Show playback buttons over the lock screen while music is loaded. */
    val lockScreenControls: StateFlow<Boolean> = _lockScreenControls.asStateFlow()

    fun setLockScreenControls(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LOCK_SCREEN_CONTROLS, enabled) }
        _lockScreenControls.value = enabled
    }

    private val _navidromeStreamKbps = MutableStateFlow(prefs.getInt(KEY_NAVIDROME_STREAM_KBPS, 0))
    /** Max streaming bitrate in kbps for Navidrome; 0 = original file. */
    val navidromeStreamKbps: StateFlow<Int> = _navidromeStreamKbps.asStateFlow()

    fun setNavidromeStreamKbps(kbps: Int) {
        prefs.edit { putInt(KEY_NAVIDROME_STREAM_KBPS, kbps) }
        _navidromeStreamKbps.value = kbps
    }

    private val _navidromeDownloadKbps = MutableStateFlow(prefs.getInt(KEY_NAVIDROME_DOWNLOAD_KBPS, 0))
    /** Max bitrate in kbps for Navidrome downloads; 0 = original file. */
    val navidromeDownloadKbps: StateFlow<Int> = _navidromeDownloadKbps.asStateFlow()

    fun setNavidromeDownloadKbps(kbps: Int) {
        prefs.edit { putInt(KEY_NAVIDROME_DOWNLOAD_KBPS, kbps) }
        _navidromeDownloadKbps.value = kbps
    }

    private val _downloadDirPath = MutableStateFlow(prefs.getString(KEY_DOWNLOAD_DIR, null))
    /** App-specific Music directory (on any storage volume) where downloads are saved; null = default. */
    val downloadDirPath: StateFlow<String?> = _downloadDirPath.asStateFlow()

    fun setDownloadDirPath(path: String?) {
        prefs.edit { if (path == null) remove(KEY_DOWNLOAD_DIR) else putString(KEY_DOWNLOAD_DIR, path) }
        _downloadDirPath.value = path
    }

    private val _downloadTreeUri = MutableStateFlow(prefs.getString(KEY_DOWNLOAD_TREE_URI, null))
    /** A folder picked with the system folder picker (any storage). Takes priority over [downloadDirPath]. */
    val downloadTreeUri: StateFlow<String?> = _downloadTreeUri.asStateFlow()

    fun setDownloadTreeUri(uri: String?) {
        prefs.edit { if (uri == null) remove(KEY_DOWNLOAD_TREE_URI) else putString(KEY_DOWNLOAD_TREE_URI, uri) }
        _downloadTreeUri.value = uri
    }

    /** The chosen download directory, falling back to primary storage if it is no longer available. */
    fun resolveDownloadDir(): File? {
        val chosen = _downloadDirPath.value
        val volumes = appContext.getExternalFilesDirs(Environment.DIRECTORY_MUSIC).filterNotNull()
        return volumes.firstOrNull { it.absolutePath == chosen }
            ?: appContext.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
    }

    fun getNavidromeConfigSync(): NavidromeConfig? = _navidromeConfig.value

    fun setNavidromeConfig(config: NavidromeConfig?) {
        securePrefs.edit {
            if (config == null) {
                remove(KEY_NAVIDROME_URL)
                remove(KEY_NAVIDROME_USER)
                remove(KEY_NAVIDROME_PASSWORD)
            } else {
                putString(KEY_NAVIDROME_URL, config.baseUrl)
                putString(KEY_NAVIDROME_USER, config.username)
                putString(KEY_NAVIDROME_PASSWORD, config.password)
            }
        }
        _navidromeConfig.value = config
    }

    private fun readNavidromeConfig(): NavidromeConfig? {
        val url = securePrefs.getString(KEY_NAVIDROME_URL, null)
        val user = securePrefs.getString(KEY_NAVIDROME_USER, null)
        val password = securePrefs.getString(KEY_NAVIDROME_PASSWORD, null)
        if (url.isNullOrBlank() || user.isNullOrBlank() || password == null) return null
        return NavidromeConfig(url, user, password)
    }

    fun getLastAppleMusicSyncMillis(): Long {
        return prefs.getLong(KEY_LAST_APPLE_MUSIC_SYNC_MILLIS, 0L)
    }

    fun updateLastAppleMusicSyncMillis(value: Long) {
        prefs.edit { putLong(KEY_LAST_APPLE_MUSIC_SYNC_MILLIS, value) }
    }

    fun getLastLocalLibraryScanMillis(): Long {
        return prefs.getLong(KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS, 0L)
    }

    fun updateLastLocalLibraryScanMillis(value: Long) {
        prefs.edit { putLong(KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS, value) }
    }

    fun setIncludeLocalMusic(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_INCLUDE_LOCAL_MUSIC, enabled) }
        _includeLocalMusic.value = enabled
    }

    private fun getIncludeLocalMusicSync(): Boolean {
        return prefs.getBoolean(KEY_INCLUDE_LOCAL_MUSIC, false)
    }

    fun addLocalMusicFolder(uri: String) {
        val current = getLocalMusicFoldersSync().toMutableSet()
        if (current.add(uri)) {
            prefs.edit { putStringSet(KEY_LOCAL_MUSIC_FOLDERS, current) }
            _localMusicFolders.value = current
        }
    }

    fun removeLocalMusicFolder(uri: String) {
        val current = getLocalMusicFoldersSync().toMutableSet()
        if (current.remove(uri)) {
            prefs.edit { putStringSet(KEY_LOCAL_MUSIC_FOLDERS, current) }
            _localMusicFolders.value = current
        }
    }

    fun getLocalMusicFoldersSync(): Set<String> {
        return prefs.getStringSet(KEY_LOCAL_MUSIC_FOLDERS, emptySet()) ?: emptySet()
    }

    private fun getStreamingProviderSync(): StreamingProvider {
        val raw = prefs.getString(KEY_STREAMING_PROVIDER, null)
        return StreamingProvider.fromStored(raw)
    }

    fun setStreamingProvider(provider: StreamingProvider) {
        prefs.edit { putString(KEY_STREAMING_PROVIDER, StreamingProvider.toStored(provider)) }
        _streamingProvider.value = provider
    }

    fun getCompleteAlbumsWithYouTubeSync(): Boolean {
        return prefs.getBoolean(KEY_COMPLETE_ALBUMS_WITH_YOUTUBE, false)
    }

    fun setCompleteAlbumsWithYouTube(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_COMPLETE_ALBUMS_WITH_YOUTUBE, enabled) }
        _completeAlbumsWithYouTube.value = enabled
    }

    // Permissions onboarding
    fun hasCompletedPermissionsOnboarding(): Boolean {
        return prefs.getBoolean(KEY_HAS_COMPLETED_PERMISSIONS_ONBOARDING, false)
    }

    fun setHasCompletedPermissionsOnboarding(completed: Boolean) {
        prefs.edit { putBoolean(KEY_HAS_COMPLETED_PERMISSIONS_ONBOARDING, completed) }
    }

    companion object {
        private const val PREFS_NAME = "calmmusic_settings"
        private const val KEY_INCLUDE_LOCAL_MUSIC = "include_local_music"
        private const val KEY_LOCAL_MUSIC_FOLDERS = "local_music_folders"
        private const val KEY_LAST_APPLE_MUSIC_SYNC_MILLIS = "last_apple_music_sync_millis"
        private const val KEY_LAST_LOCAL_LIBRARY_SCAN_MILLIS = "last_local_library_scan_millis"
        private const val KEY_HAS_COMPLETED_PERMISSIONS_ONBOARDING = "has_completed_permissions_onboarding"
        private const val SECURE_PREFS_NAME = "calmmusic_secure"
        private const val KEY_LOCK_SCREEN_CONTROLS = "lock_screen_controls"
        private const val KEY_NAVIDROME_STREAM_KBPS = "navidrome_stream_kbps"
        private const val KEY_NAVIDROME_DOWNLOAD_KBPS = "navidrome_download_kbps"
        private const val KEY_DOWNLOAD_DIR = "download_dir"
        private const val KEY_DOWNLOAD_TREE_URI = "download_tree_uri"
        private const val KEY_NAVIDROME_URL = "navidrome_url"
        private const val KEY_NAVIDROME_USER = "navidrome_user"
        private const val KEY_NAVIDROME_PASSWORD = "navidrome_password"
        private const val KEY_STREAMING_PROVIDER = "streaming_provider"
        private const val KEY_COMPLETE_ALBUMS_WITH_YOUTUBE = "complete_albums_with_youtube"
    }
}