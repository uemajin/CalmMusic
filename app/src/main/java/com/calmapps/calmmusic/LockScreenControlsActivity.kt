package com.calmapps.calmmusic

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.mudita.mmd.ThemeMMD
import com.mudita.mmd.components.buttons.OutlinedButtonMMD
import com.mudita.mmd.components.text.TextMMD

/**
 * CalmMusic's own lock screen page: the time and date with a playback bar (previous / play-pause /
 * next / hide) under them.
 *
 * The Mudita lock screen widget is hard-wired to the Mudita player. Android also hides the real
 * lock screen while an app screen is shown over it, so instead of leaving a black background this
 * page draws the clock, date and unlock hint itself. It is started by [PlaybackService] when the
 * screen turns on while the device is locked and music is loaded, and closes itself when the
 * screen turns off or the phone unlocks. "Hide" closes it and reveals the real lock screen.
 */
class LockScreenControlsActivity : ComponentActivity() {

    private var controller: MediaController? = null
    private var title by mutableStateOf("")
    private var artist by mutableStateOf("")
    private var playing by mutableStateOf(false)

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            finish()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            refresh(player)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)

        NotificationManagerCompat.from(this).cancel(PlaybackService.LOCK_SCREEN_NOTIFICATION_ID)

        ContextCompat.registerReceiver(
            this,
            closeReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        setContent {
            ThemeMMD {
                LockScreenPage(
                    title = title,
                    artist = artist,
                    playing = playing,
                    onPrevious = { controller?.seekToPreviousMediaItem() },
                    onPlayPause = { togglePlayback() },
                    onNext = { controller?.seekToNextMediaItem() },
                    onHide = { finish() },
                    onUnlock = { unlock() },
                )
            }
        }

        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener(
            {
                try {
                    val connected = future.get()
                    controller = connected
                    connected.addListener(playerListener)
                    refresh(connected)
                } catch (_: Exception) {
                    finish()
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    private fun refresh(player: Player) {
        title = player.mediaMetadata.title?.toString().orEmpty()
        artist = player.mediaMetadata.artist?.toString().orEmpty()
        playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
    }

    /**
     * The real lock screen is hidden while this page is shown, so the fingerprint sensor is not
     * listening. Ask Android to show its unlock prompt (fingerprint or PIN) on top of this page.
     */
    private fun unlock() {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null || !keyguard.isKeyguardLocked) {
            finish()
            return
        }
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    finish()
                }
            },
        )
    }

    private fun togglePlayback() {
        val c = controller ?: return
        if (c.playWhenReady && c.playbackState != Player.STATE_ENDED) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    override fun onStop() {
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(closeReceiver)
        } catch (_: IllegalArgumentException) {
        }
        controller?.removeListener(playerListener)
        controller?.release()
        controller = null
        super.onDestroy()
    }
}

@Composable
private fun LockScreenPage(
    title: String,
    artist: String,
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onHide: () -> Unit,
    onUnlock: () -> Unit,
) {
    val context = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(10_000)
        }
    }

    val is24Hour = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val time = remember(now / 60_000) {
        SimpleDateFormat(if (is24Hour) "HH:mm" else "h:mm", Locale.getDefault()).format(Date(now))
    }
    val amPm = remember(now / 60_000) {
        if (is24Hour) "" else SimpleDateFormat("a", Locale.getDefault()).format(Date(now)).uppercase()
    }
    val date = remember(now / 3_600_000) {
        SimpleDateFormat("EEEE, dd MMMM", Locale.getDefault()).format(Date(now))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(36.dp))

        Row(verticalAlignment = Alignment.Top) {
            TextMMD(text = time, fontSize = 92.sp, color = Color.Black)
            if (amPm.isNotEmpty()) {
                TextMMD(
                    text = amPm,
                    fontSize = 20.sp,
                    color = Color.Black,
                    modifier = Modifier.padding(start = 2.dp, top = 14.dp),
                )
            }
        }

        TextMMD(text = date, fontSize = 20.sp, color = Color.Black)

        Spacer(modifier = Modifier.height(16.dp))

        LockControls(
            title = title,
            artist = artist,
            playing = playing,
            onPrevious = onPrevious,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onHide = onHide,
        )

        Spacer(modifier = Modifier.weight(1f))

        Icon(
            imageVector = Icons.Filled.Lock,
            contentDescription = null,
            tint = Color.Black,
            modifier = Modifier.size(28.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButtonMMD(
            onClick = onUnlock,
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .fillMaxWidth(),
        ) {
            TextMMD(text = "Unlock", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.height(20.dp))
    }
}

@Composable
private fun LockControls(
    title: String,
    artist: String,
    playing: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onHide: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .background(Color.White, shape)
            .border(2.dp, Color.Black, shape)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (title.isNotBlank()) {
            TextMMD(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (artist.isNotBlank()) {
                TextMMD(
                    text = artist,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous song", tint = Color.Black)
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = Color.Black,
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next song", tint = Color.Black)
            }
            IconButton(onClick = onHide) {
                Icon(Icons.Outlined.Close, contentDescription = "Hide", tint = Color.Black)
            }
        }
    }
}
