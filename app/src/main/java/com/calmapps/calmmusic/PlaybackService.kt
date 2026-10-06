package com.calmapps.calmmusic

import android.app.NotificationChannel
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.KeyEvent
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Media3-based playback service.
 */
class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    private val scrobbleScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )
    private var scrobbler: NavidromeScrobbler? = null

    // When the radios are switched off (airplane mode / the Mudita "Offline+" switch) the
    // headphone-jack detector can report a play/pause button press that never releases, and
    // Android then repeats it ~20 times a second. Remember when airplane mode last changed so
    // the first spurious press can be ignored too.
    private var lastAirplaneModeChangeMs = 0L
    private val airplaneModeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            lastAirplaneModeChangeMs = SystemClock.elapsedRealtime()
        }
    }

    // Opt-in lock screen controls: when the screen turns on while locked and music is loaded,
    // show CalmMusic's own playback bar (the Mudita lock screen widget only supports the Mudita
    // player). A full-screen-intent notification is used so no extra permission is needed.
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            showLockScreenControlsIfNeeded()
        }
    }

    private fun showLockScreenControlsIfNeeded() {
        val app = application as CalmMusic
        if (!app.settingsManager.lockScreenControls.value) return
        if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != true) return

        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_IDLE ||
            player.playbackState == Player.STATE_ENDED
        ) return

        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(LOCK_SCREEN_CHANNEL_ID, "Lock screen controls", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows playback buttons over the lock screen"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )

        val launch = PendingIntent.getActivity(
            this,
            LOCK_SCREEN_NOTIFICATION_ID,
            Intent(this, LockScreenControlsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, LOCK_SCREEN_CHANNEL_ID)
            .setSmallIcon(androidx.media3.session.R.drawable.media3_notification_small_icon)
            .setContentTitle("CalmMusic")
            .setCategory(androidx.core.app.NotificationCompat.CATEGORY_ALARM)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_SECRET)
            // The channel has no sound or vibration. Do not use setSilent(): it makes the system
            // refuse to launch the full-screen intent.
            .setTimeoutAfter(10_000)
            .setFullScreenIntent(launch, true)
            .build()
        manager.notify(LOCK_SCREEN_NOTIFICATION_ID, notification)
    }

    private val mediaSessionCallback = object : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onMediaButtonEvent(
            session: MediaSession,
            controllerInfo: MediaSession.ControllerInfo,
            intent: Intent,
        ): Boolean {
            val event = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                ?: return false

            // Held-down keys auto-repeat; a repeat is never a new button press.
            if (event.repeatCount > 0) return true

            val isPlayPauseKey = event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK
            val justAfterAirplaneModeChange =
                SystemClock.elapsedRealtime() - lastAirplaneModeChangeMs < AIRPLANE_MODE_KEY_GRACE_MS
            if (isPlayPauseKey && justAfterAirplaneModeChange) return true

            return false
        }
    }

    companion object {
        const val NAVIDROME_SCHEME = "navidrome"

        private const val AIRPLANE_MODE_KEY_GRACE_MS = 3_000L
        const val LOCK_SCREEN_NOTIFICATION_ID = 1002
        private const val LOCK_SCREEN_CHANNEL_ID = "calmmusic_lock_screen_channel"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "calmmusic_playback_channel"
        private var errorCallback: ((PlaybackException) -> Unit)? = null

        private const val NEWPIPE_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        private const val BYPASS_COOKIES = "SOCS=CAI; VISITOR_INFO1_LIVE=i7Sm6Qgj0lE; CONSENT=YES+cb.20210328-17-p0.en+FX+475"

        fun registerErrorCallback(callback: ((PlaybackException) -> Unit)?) {
            errorCallback = callback
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,
                120_000,
                500,
                1000
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val mediaSourceFactory = DefaultMediaSourceFactory(createDataSourceFactory())

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .build()

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player.setAudioAttributes(audioAttributes, true)

        player.setHandleAudioBecomingNoisy(true)

        scrobbler = NavidromeScrobbler(application as CalmMusic, player, scrobbleScope).also { it.start() }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                errorCallback?.invoke(error)
                super.onPlayerError(error)
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)
                (application as? CalmMusic)?.playbackStateManager?.updatePlaybackStatus(isPlaying)
            }

            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                super.onMediaItemTransition(mediaItem, reason)
                val meta = mediaItem?.mediaMetadata
                if (meta != null) {
                    val uri = mediaItem.localConfiguration?.uri
                    val inferredSourceType = when (uri?.scheme) {
                        "content", "file" -> "LOCAL_FILE"
                        NAVIDROME_SCHEME -> "NAVIDROME"
                        else -> "YOUTUBE"
                    }

                    (application as? CalmMusic)?.playbackStateManager?.updateState(
                        songId = mediaItem.mediaId,
                        title = meta.title?.toString() ?: "Unknown Title",
                        artist = meta.artist?.toString() ?: "Unknown Artist",
                        isPlaying = player.isPlaying,
                        sourceType = inferredSourceType,
                    )
                }
            }
        })

        val sessionActivityIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivityPendingIntent)
            .setCallback(mediaSessionCallback)
            .build()

        ContextCompat.registerReceiver(
            this,
            airplaneModeReceiver,
            IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this,
            screenOnReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setNotificationId(NOTIFICATION_ID)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }

    @OptIn(UnstableApi::class)
    private fun createDataSourceFactory(): DataSource.Factory {
        val app = application as CalmMusic

        val okHttpClient = OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        val upstreamFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent(NEWPIPE_USER_AGENT)
            .setDefaultRequestProperties(mapOf(
                "Cookie" to BYPASS_COOKIES,
                "Referer" to "https://www.youtube.com/"
            ))

        val resolvingFactory = ResolvingDataSource.Factory(upstreamFactory) { dataSpec ->
            val uri = dataSpec.uri
            val scheme = uri.scheme
            if (scheme == "content" || scheme == "file") {
                return@Factory dataSpec
            }

            if (scheme == NAVIDROME_SCHEME) {
                // navidrome://<songId> -> signed stream URL (fresh token on every open).
                val songId = uri.schemeSpecificPart.removePrefix("//")
                return@Factory dataSpec.withUri(app.navidromeClient.buildStreamUrl(songId).toUri())
            }

            val videoId = dataSpec.key
                ?: uri.getQueryParameter("v")
                ?: uri.lastPathSegment
                ?: return@Factory dataSpec

            val precache = app.youTubePrecacheManager
            val now = System.currentTimeMillis()
            val cached = precache.getCachedWithLabel(videoId, now)

            if (cached != null) {
                val (cachedUrl, cachedLabel) = cached
                app.playbackStateManager.updateStreamResolverLabel(cachedLabel)
                return@Factory dataSpec.withUri(cachedUrl.toUri())
            }

            val (resolvedUrl, resolverLabel) = runBlocking(Dispatchers.IO) {
                try {
                    app.youTubeInnertubeClient.getBestAudioUrl(videoId) to "Innertube/Piped"
                } catch (_: Exception) {
                    app.youTubeStreamResolver.getBestAudioUrl(videoId) to "NewPipe"
                }
            }
            precache.putUrl(videoId, resolvedUrl, resolverLabel, now)
            app.playbackStateManager.updateStreamResolverLabel(resolverLabel)

            dataSpec.withUri(resolvedUrl.toUri())
        }

        val networkAndCacheStack = CacheDataSource.Factory()
            .setCache(app.mediaCache)
            .setCacheKeyFactory { dataSpec ->
                if (dataSpec.uri.scheme == NAVIDROME_SCHEME) {
                    // Different bitrates are different byte streams; never mix them in the cache.
                    dataSpec.uri.toString() + "#kbps=" + app.settingsManager.navidromeStreamKbps.value
                } else {
                    dataSpec.key ?: dataSpec.uri.toString()
                }
            }
            .setUpstreamDataSourceFactory(resolvingFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        val defaultSources = DefaultDataSource.Factory(this, networkAndCacheStack)

        // Outermost layer: whenever the player opens a Navidrome song that has been downloaded,
        // read the local file instead of the stream. This also covers songs that were queued
        // before they finished downloading. The result is a file/content URI, which
        // DefaultDataSource reads directly (no network, no stream cache).
        return ResolvingDataSource.Factory(defaultSources) { dataSpec ->
            if (dataSpec.uri.scheme != NAVIDROME_SCHEME) return@Factory dataSpec

            val songId = com.calmapps.calmmusic.NAVIDROME_ID_PREFIX +
                dataSpec.uri.schemeSpecificPart.removePrefix("//")
            val localUri = runBlocking(Dispatchers.IO) {
                val entity = com.calmapps.calmmusic.data.CalmMusicDatabase.getDatabase(app)
                    .songDao().getSongById(songId)
                if (entity != null &&
                    com.calmapps.calmmusic.data.isDownloadedNavidrome(entity.sourceType, entity.audioUri) &&
                    com.calmapps.calmmusic.data.mediaUriExists(app, entity.audioUri)
                ) {
                    entity.audioUri.toUri()
                } else {
                    null
                }
            }
            if (localUri != null) dataSpec.withUri(localUri) else dataSpec
        }
    }

    private fun createNotificationChannel() {
        val name = "CalmMusic playback"
        val descriptionText = "Music playback controls"
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
            description = descriptionText
        }
        val notificationManager: NotificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        mediaSession?.player?.stop()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onDestroy() {
        scrobbler?.release()
        scrobbler = null
        scrobbleScope.cancel()
        try {
            unregisterReceiver(airplaneModeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        try {
            unregisterReceiver(screenOnReceiver)
        } catch (_: IllegalArgumentException) {
        }
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }
}