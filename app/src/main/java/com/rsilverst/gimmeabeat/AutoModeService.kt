package com.rsilverst.gimmeabeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import com.rsilverst.gimmeabeat.spotify.PlayResult
import com.rsilverst.gimmeabeat.spotify.SpotifyAuthRepository
import com.rsilverst.gimmeabeat.spotify.SpotifyClient
import com.rsilverst.gimmeabeat.spotify.SpotifyPlaybackState
import com.rsilverst.gimmeabeat.bpm.GetSongBpmClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import net.openid.appauth.AuthorizationService
import kotlin.coroutines.coroutineContext

class AutoModeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private lateinit var authRepo: SpotifyAuthRepository
    private lateinit var authService: AuthorizationService
    private lateinit var spotifyClient: SpotifyClient
    private val bpmClient = GetSongBpmClient()
    private lateinit var songFinder: SongFinder

    private var loopJob: Job? = null
    private val recentlyPlayedIds = ArrayDeque<String>()

    override fun onCreate() {
        super.onCreate()
        authRepo = SpotifyAuthRepository(this)
        authService = AuthorizationService(this)
        spotifyClient = SpotifyClient(authRepo, authService)
        songFinder = SongFinder(bpmClient, spotifyClient)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAuto()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground("Starting auto mode…")
                startAuto()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground(text: String) {
        ensureChannel()
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startAuto() {
        if (loopJob?.isActive == true) return
        AutoModeState.setActive(true)
        recentlyPlayedIds.clear()
        sendWatchCommand(PATH_START_TRACKING)
        loopJob = scope.launch { runLoop() }
    }

    private fun stopAuto() {
        loopJob?.cancel()
        loopJob = null
        AutoModeState.reset()
        sendWatchCommand(PATH_NOW_PLAYING, ByteArray(0)) // clear before tearing down
        sendWatchCommand(PATH_STOP_TRACKING)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun sendWatchCommand(path: String, payload: ByteArray = ByteArray(0)) {
        scope.launch {
            val ctx = applicationContext
            val nodes = try {
                Wearable.getNodeClient(ctx).connectedNodes.await()
            } catch (t: Throwable) {
                Log.w(TAG, "connectedNodes failed for $path", t)
                return@launch
            }
            nodes.forEach { node ->
                try {
                    Wearable.getMessageClient(ctx)
                        .sendMessage(node.id, path, payload)
                        .await()
                } catch (t: Throwable) {
                    Log.w(TAG, "sendMessage($path) to ${node.displayName} failed", t)
                }
            }
        }
    }

    private suspend fun runLoop() {
        var lastFailed = !pickAndPlay("Starting auto mode")

        while (coroutineContext.isActive) {
            delay(if (lastFailed) IDLE_BACKOFF_MS else POLL_INTERVAL_MS)
            val state = spotifyClient.playbackState()
            if (state == null || state.item == null) {
                lastFailed = !pickAndPlay("Player idle")
                continue
            }
            val duration = state.item.duration_ms ?: continue
            val remaining = duration - state.progress_ms
            updateUiStatus(autoStatusLine(state, remaining))
            lastFailed = false
            if (remaining < END_OF_TRACK_THRESHOLD_MS) {
                lastFailed = !pickAndPlay("Track ending")
            }
        }
    }

    private suspend fun pickAndPlay(reason: String): Boolean {
        val multiplier = Preferences.currentMultiplier(applicationContext)
        val genre = Preferences.currentGenre(applicationContext)
        val rawHr = HeartRateRelay.smoothedBpm.value
            ?: HeartRateRelay.heartRate.value?.bpm
            ?: DEFAULT_HR
        val targetBpm = (rawHr * multiplier).toInt().coerceIn(40, 220)

        updateUiStatus("$reason — finding song at $targetBpm BPM (${"%.1f".format(multiplier)}× HR)")

        val result = songFinder.findAndPlay(
            targetBpm = targetBpm,
            genre = genre,
            excludeTrackIds = recentlyPlayedIds.toSet(),
        )
        return when (result) {
            FindAndPlayResult.NoBpmCandidates -> {
                updateUiStatus("No songs near $targetBpm BPM — will retry")
                false
            }
            FindAndPlayResult.NoSpotifyMatch -> {
                updateUiStatus("BPM matches but none on Spotify — will retry")
                false
            }
            is FindAndPlayResult.Resolved -> {
                rememberPlayed(result.track.id)
                val nowPlayingText =
                    "${result.candidate.title} — ${result.candidate.artist} (${result.candidate.bpm} BPM)"
                AutoModeState.setNowPlaying(nowPlayingText)
                sendWatchCommand(PATH_NOW_PLAYING, nowPlayingText.toByteArray())
                when (val pr = result.playResult) {
                    PlayResult.Playing -> {
                        updateUiStatus(
                            "Auto: playing ${result.candidate.title} • target $targetBpm BPM"
                        )
                        true
                    }
                    PlayResult.NoActiveDevice -> {
                        updateUiStatus(
                            "No Spotify device available. Open Spotify anywhere; will auto-pick."
                        )
                        false
                    }
                    PlayResult.PremiumRequired -> {
                        updateUiStatus("Spotify Premium required.")
                        false
                    }
                    PlayResult.NotAuthorized -> {
                        updateUiStatus("Not signed in.")
                        false
                    }
                    is PlayResult.Error -> {
                        updateUiStatus("Playback error: ${pr.message}")
                        false
                    }
                }
            }
        }
    }

    private fun rememberPlayed(trackId: String) {
        recentlyPlayedIds.remove(trackId)
        recentlyPlayedIds.addLast(trackId)
        while (recentlyPlayedIds.size > RECENT_LIMIT) recentlyPlayedIds.removeFirst()
    }

    private fun updateUiStatus(text: String) {
        AutoModeState.setStatus(text)
        notify(text)
    }

    private fun autoStatusLine(state: SpotifyPlaybackState, remainingMs: Long): String {
        val mmss = formatMmSs(remainingMs.coerceAtLeast(0))
        val name = state.item?.name ?: "—"
        return "Auto: $mmss left of \"$name\""
    }

    private fun formatMmSs(ms: Long): String {
        val totalSec = ms / 1000
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    private fun buildNotification(text: String): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, AutoModeService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GimmeABeat — auto mode")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Auto mode",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "Ongoing notification while auto-DJ is running" }
            )
        }
    }

    private fun notify(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        loopJob?.cancel()
        authService.dispose()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.rsilverst.gimmeabeat.action.STOP_AUTO"
        private const val CHANNEL_ID = "auto_mode"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 5_000L
        private const val IDLE_BACKOFF_MS = 20_000L
        private const val END_OF_TRACK_THRESHOLD_MS = 8_000L
        private const val DEFAULT_HR = 80
        private const val RECENT_LIMIT = 50
        private const val PATH_START_TRACKING = "/start_tracking"
        private const val PATH_STOP_TRACKING = "/stop_tracking"
        private const val PATH_NOW_PLAYING = "/now_playing"
        private const val TAG = "AutoModeService"
    }
}
