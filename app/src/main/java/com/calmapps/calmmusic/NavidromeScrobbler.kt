package com.calmapps.calmmusic

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Plays that could not be sent yet (for example because the phone was offline). */
private class PendingScrobbles(context: Context) {
    private val prefs = context.getSharedPreferences("calmmusic_scrobbles", Context.MODE_PRIVATE)

    data class Entry(val songId: String, val playedAtMillis: Long)

    @Synchronized
    fun all(): List<Entry> {
        val array = try {
            JSONArray(prefs.getString(KEY, "[]"))
        } catch (_: Exception) {
            JSONArray()
        }
        return (0 until array.length()).map {
            val o = array.getJSONObject(it)
            Entry(o.getString("id"), o.getLong("t"))
        }
    }

    @Synchronized
    fun add(entry: Entry) = write((all() + entry).takeLast(MAX_PENDING))

    @Synchronized
    fun remove(entry: Entry) = write(all().filterNot { it == entry })

    private fun write(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.songId).put("t", it.playedAtMillis)) }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private companion object {
        const val KEY = "pending"
        const val MAX_PENDING = 2000
    }
}

/**
 * Reports Navidrome plays to the server so they reach ListenBrainz / Last.fm.
 *
 * Navidrome does not count a song just because it was streamed; the player has to say so. A play
 * is reported once half of the song (or 4 minutes) has really been listened to, the usual
 * ListenBrainz rule, and songs under 30 seconds are ignored. Plays that fail to send are kept
 * and sent when the connection is back, with the time they actually happened.
 */
class NavidromeScrobbler(
    private val app: CalmMusic,
    private val player: Player,
    private val scope: CoroutineScope,
) : Player.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private val pending = PendingScrobbles(app)
    private val flushMutex = Mutex()

    private var currentSongId: String? = null
    private var playStartMillis = 0L
    private var playedMillis = 0L
    private var lastTickRealtime = 0L
    private var scrobbled = false

    private val tick = object : Runnable {
        override fun run() {
            onTick()
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scope.launch { flushPending() }
        }
    }

    fun start() {
        player.addListener(this)
        beginPlay(player.currentMediaItem)
        handler.postDelayed(tick, TICK_MS)
        try {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
        scope.launch { flushPending() }
    }

    fun release() {
        player.removeListener(this)
        handler.removeCallbacks(tick)
        try {
            app.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {
        }
    }

    private fun enabled() = app.settingsManager.navidromeScrobble.value &&
        app.settingsManager.getNavidromeConfigSync() != null

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        beginPlay(mediaItem)
    }

    private fun beginPlay(item: MediaItem?) {
        val songId = item?.mediaId?.takeIf { it.startsWith(NAVIDROME_ID_PREFIX) }?.removePrefix(NAVIDROME_ID_PREFIX)

        // The same song re-loaded part-way through (for example swapped to its downloaded copy)
        // is the same listen, not a new one.
        if (songId != null && songId == currentSongId && player.currentPosition > CONTINUATION_MIN_POSITION_MS) {
            lastTickRealtime = SystemClock.elapsedRealtime()
            return
        }

        currentSongId = songId
        playStartMillis = System.currentTimeMillis()
        playedMillis = 0L
        scrobbled = false
        lastTickRealtime = SystemClock.elapsedRealtime()

        if (songId != null && enabled()) {
            scope.launch {
                try {
                    app.navidromeClient.scrobble(songId, null, submission = false)
                } catch (_: Exception) {
                    // "Now playing" is best effort only.
                }
            }
        }
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastTickRealtime
        lastTickRealtime = now

        val songId = currentSongId ?: return
        if (scrobbled || !player.isPlaying) return
        playedMillis += elapsed

        val duration = player.duration
        if (duration == C.TIME_UNSET || duration < MIN_TRACK_MS) return
        if (playedMillis >= minOf(duration / 2, MAX_THRESHOLD_MS)) {
            scrobbled = true
            submit(songId, playStartMillis)
        }
    }

    private fun submit(songId: String, playedAtMillis: Long) {
        if (!enabled()) return
        scope.launch {
            try {
                app.navidromeClient.scrobble(songId, playedAtMillis, submission = true)
                flushPending()
            } catch (e: NavidromeApiException) {
                // The server refused it (for example the song no longer exists); retrying won't help.
            } catch (e: IOException) {
                pending.add(PendingScrobbles.Entry(songId, playedAtMillis))
            }
        }
    }

    private suspend fun flushPending() {
        if (!enabled()) return
        flushMutex.withLock {
            for (entry in pending.all()) {
                try {
                    app.navidromeClient.scrobble(entry.songId, entry.playedAtMillis, submission = true)
                    pending.remove(entry)
                } catch (e: NavidromeApiException) {
                    pending.remove(entry)
                } catch (e: IOException) {
                    return // still offline; try again later
                }
            }
        }
    }

    private companion object {
        const val TICK_MS = 1_000L
        const val MIN_TRACK_MS = 30_000L
        const val MAX_THRESHOLD_MS = 4 * 60_000L
        const val CONTINUATION_MIN_POSITION_MS = 2_000L
    }
}
