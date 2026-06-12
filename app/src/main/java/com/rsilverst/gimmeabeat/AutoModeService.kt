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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.wearable.Wearable
import com.rsilverst.gimmeabeat.telemetry.Counters
import com.rsilverst.gimmeabeat.telemetry.Telemetry
import com.rsilverst.gimmeabeat.spotify.LocalSpotifyController
import com.rsilverst.gimmeabeat.spotify.PlayResult
import com.rsilverst.gimmeabeat.spotify.SpotifyAuthRepository
import com.rsilverst.gimmeabeat.spotify.SpotifyClient
import com.rsilverst.gimmeabeat.spotify.SpotifyPlaybackState
import com.rsilverst.gimmeabeat.spotify.SpotifyTrack
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
    private lateinit var localSpotify: LocalSpotifyController

    private var loopJob: Job? = null
    private var lastCountersFlushMs = 0L
    // Consecutive picks with no live watch signal; drives the wait → give-up
    // escalation in [handleSignalLoss]. [awaitingSignal] makes the loop poll
    // quickly while we wait for the signal to come back.
    private var consecutiveSignalMisses = 0
    private var awaitingSignal = false
    private val recentlyPlayedIds = ArrayDeque<String>()
    // ID of the track our nowPlaying card currently reflects. Updated both when
    // pickAndPlay starts a track and when the polling loop notices an external
    // change (Spotify autoplay, a user skip in Spotify, etc.).
    private var syncedTrackId: String? = null

    override fun onCreate() {
        super.onCreate()
        authRepo = SpotifyAuthRepository(this)
        authService = AuthorizationService(this)
        spotifyClient = SpotifyClient(applicationContext, authRepo, authService)
        songFinder = SongFinder(bpmClient, spotifyClient)
        localSpotify = LocalSpotifyController(applicationContext)
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
        Counters.reset()
        lastCountersFlushMs = SystemClock.elapsedRealtime()
        consecutiveSignalMisses = 0
        awaitingSignal = false
        recentlyPlayedIds.clear()
        sendWatchCommand(PATH_START_TRACKING)
        // Sync the current signal source to the watch so its hero matches.
        scope.launch {
            WatchSync.sendSignalSource(
                applicationContext,
                Preferences.currentSignalSource(applicationContext),
            )
        }
        loopJob = scope.launch {
            // Wake Spotify silently via local App Remote IPC. No UI flash; the
            // connection also pins subsequent playback to this device.
            updateUiStatus("Waking Spotify…")
            localSpotify.connect()
            runLoop()
        }
    }

    private fun stopAuto() {
        Counters.logSnapshot("session")
        loopJob?.cancel()
        loopJob = null
        localSpotify.disconnect()
        syncedTrackId = null
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
                Counters.increment(Counters.WATCH_UNREACHABLE)
                return@launch
            }
            if (nodes.isEmpty()) {
                Log.w(TAG, "no connected watch node for $path")
                Counters.increment(Counters.WATCH_UNREACHABLE)
                return@launch
            }
            nodes.forEach { node ->
                try {
                    Wearable.getMessageClient(ctx)
                        .sendMessage(node.id, path, payload)
                        .await()
                } catch (t: Throwable) {
                    Log.w(TAG, "sendMessage($path) to ${node.displayName} failed", t)
                    Counters.increment(Counters.WATCH_SEND_FAILED)
                }
            }
        }
    }

    private suspend fun runLoop() {
        var lastFailed = !pickAndPlay("Starting auto mode")

        while (coroutineContext.isActive) {
            delay(
                when {
                    awaitingSignal -> SIGNAL_WAIT_INTERVAL_MS
                    lastFailed -> IDLE_BACKOFF_MS
                    else -> POLL_INTERVAL_MS
                }
            )
            maybeFlushCounters()
            val state = spotifyClient.playbackState()
            if (state == null || state.item == null) {
                lastFailed = !pickAndPlay("Player idle")
                continue
            }
            val item = state.item
            // If what's actually playing isn't what our card claims (because
            // Spotify auto-queued, the user pressed skip in Spotify, etc.),
            // sync the card to reality. BPM is unknown for non-our tracks.
            if (item.id != syncedTrackId) {
                syncCardFromState(item)
            }
            val duration = item.duration_ms ?: continue
            val remaining = duration - state.progress_ms
            updateUiStatus(autoStatusLine(state, remaining))
            lastFailed = false
            if (remaining < END_OF_TRACK_THRESHOLD_MS) {
                lastFailed = !pickAndPlay("Track ending")
            }
        }
    }

    /**
     * Called when a pick has no live watch signal. Below the give-up threshold
     * we surface a "waiting" state and let the loop re-check quickly; past it we
     * stop auto mode entirely so the app isn't left pretending to track a watch
     * that has gone away. Always returns false (this pick produced no playback).
     */
    private fun handleSignalLoss(signalAgeMs: Long?): Boolean {
        val giveUp = consecutiveSignalMisses >= SIGNAL_GIVE_UP_MISSES
        Telemetry.log(
            "signal_loss",
            mapOf(
                "misses" to consecutiveSignalMisses,
                "signalAgeMs" to signalAgeMs,
                "action" to if (giveUp) "stop" else "wait",
            ),
        )
        if (giveUp) {
            giveUpNoSignal()
        } else {
            awaitingSignal = true
            updateUiStatus("Waiting for signal from watch…")
        }
        return false
    }

    private fun giveUpNoSignal() {
        // stopAuto() tears down and resets status; restore a final explanation
        // afterwards so the UI shows why auto mode ended (AutoModeState outlives
        // the service).
        stopAuto()
        AutoModeState.setStatus("No signal from watch — auto mode stopped")
    }

    /** Emit a rolling counter snapshot at most once per [COUNTERS_FLUSH_MS]. */
    private fun maybeFlushCounters() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCountersFlushMs >= COUNTERS_FLUSH_MS) {
            Counters.logSnapshot("rolling")
            lastCountersFlushMs = now
        }
    }

    private fun syncCardFromState(item: SpotifyTrack) {
        val title = item.name
        val artist = item.artists.joinToString(", ") { it.name }.ifBlank { "—" }
        val imageUrl = item.album?.images?.firstOrNull()?.url
        AutoModeState.setNowPlaying(
            NowPlaying(title = title, artist = artist, bpm = null, imageUrl = imageUrl),
        )
        syncedTrackId = item.id
        sendWatchCommand(PATH_NOW_PLAYING, "$title — $artist".toByteArray())
    }

    private suspend fun pickAndPlay(reason: String): Boolean {
        val multiplier = Preferences.currentMultiplier(applicationContext)
        val genre = Preferences.currentGenre(applicationContext)
        val source = Preferences.currentSignalSource(applicationContext)
        // Freshest reading behind the signal, plus the last known value. We do
        // not fabricate a default when the watch goes quiet: a stale or absent
        // signal is handled explicitly below rather than matching songs to a
        // made-up heart rate.
        val signalReceivedAtMs = when (source) {
            SignalSource.HeartRate -> HeartRateRelay.heartRate.value?.receivedAtMs
            SignalSource.Cadence -> CadenceRelay.cadence.value?.receivedAtMs
        }
        val lastKnownBpm = when (source) {
            SignalSource.HeartRate ->
                HeartRateRelay.smoothedBpm.value ?: HeartRateRelay.heartRate.value?.bpm
            SignalSource.Cadence ->
                CadenceRelay.smoothedSpm.value ?: CadenceRelay.cadence.value?.stepsPerMinute
        }
        val signalAgeMs = signalReceivedAtMs?.let { System.currentTimeMillis() - it }
        val signalLive = signalAgeMs != null && signalAgeMs <= SIGNAL_FRESH_MS
        if (signalLive) {
            consecutiveSignalMisses = 0
        } else {
            consecutiveSignalMisses++
            Counters.increment(Counters.SIGNAL_ABSENT)
        }

        // Sustained loss (or never having had a signal) → wait, then eventually
        // give up, instead of picking against a stale value. Brief gaps coast on
        // the last known reading so a one-off dropout doesn't interrupt music.
        if (!signalLive && consecutiveSignalMisses >= SIGNAL_MISS_THRESHOLD) {
            return handleSignalLoss(signalAgeMs)
        }
        val effectiveBpm = lastKnownBpm ?: return handleSignalLoss(signalAgeMs)
        awaitingSignal = false

        val targetBpm = (effectiveBpm * multiplier).toInt().coerceIn(40, 220)

        updateUiStatus("$reason — finding song at $targetBpm BPM (${"%.1f".format(multiplier)}× HR)")

        val result = songFinder.findCandidate(
            targetBpm = targetBpm,
            genre = genre,
            excludeTrackIds = recentlyPlayedIds.toSet(),
        )

        var trackId: String? = null
        var playLatencyMs: Long? = null
        val outcome: String
        val success: Boolean
        when (result) {
            FindResult.NoBpmCandidates -> {
                updateUiStatus("No songs near $targetBpm BPM — will retry")
                outcome = "no_bpm_candidates"
                success = false
            }
            FindResult.NoSpotifyMatch -> {
                updateUiStatus("BPM matches but none on Spotify — will retry")
                outcome = "no_spotify_match"
                success = false
            }
            is FindResult.Found -> {
                rememberPlayed(result.track.id)
                trackId = result.track.id
                val imageUrl = result.track.album?.images?.firstOrNull()?.url
                AutoModeState.setNowPlaying(
                    NowPlaying(
                        title = result.candidate.title,
                        artist = result.candidate.artist,
                        bpm = result.candidate.bpm,
                        imageUrl = imageUrl,
                    )
                )
                syncedTrackId = result.track.id
                val watchText =
                    "${result.candidate.title} — ${result.candidate.artist} (${result.candidate.bpm} BPM)"
                sendWatchCommand(PATH_NOW_PLAYING, watchText.toByteArray())
                val playStartedMs = SystemClock.elapsedRealtime()
                val pr = playOnLocalOrFallback(result.track.uri)
                playLatencyMs = SystemClock.elapsedRealtime() - playStartedMs
                when (pr) {
                    PlayResult.Playing -> {
                        updateUiStatus(
                            "Auto: playing ${result.candidate.title} • target $targetBpm BPM"
                        )
                        outcome = "playing"
                        success = true
                    }
                    PlayResult.NoActiveDevice -> {
                        updateUiStatus(
                            "No Spotify device available. Open Spotify; will auto-pick."
                        )
                        outcome = "no_active_device"
                        success = false
                    }
                    PlayResult.PremiumRequired -> {
                        updateUiStatus("Spotify Premium required.")
                        outcome = "premium_required"
                        success = false
                    }
                    PlayResult.NotAuthorized -> {
                        updateUiStatus("Not signed in.")
                        outcome = "not_authorized"
                        success = false
                    }
                    is PlayResult.Error -> {
                        updateUiStatus("Playback error: ${pr.message}")
                        outcome = "play_error"
                        success = false
                    }
                }
                Counters.increment(Counters.PLAY_PREFIX + outcome)
            }
        }

        Telemetry.log(
            "auto_pick",
            mapOf(
                "reason" to reason,
                "source" to source.key,
                "effectiveBpm" to effectiveBpm,
                "signalPresent" to (signalReceivedAtMs != null),
                "signalAgeMs" to signalAgeMs,
                "multiplier" to "%.2f".format(multiplier),
                "targetBpm" to targetBpm,
                "outcome" to outcome,
                "trackId" to trackId,
                "playLatencyMs" to playLatencyMs,
            ),
        )
        return success
    }

    /**
     * Prefers App Remote (local IPC) so playback lands on this phone with no
     * UI swap and no risk of Spotify Connect routing to another device. Falls
     * back to the Web API path when App Remote isn't connected — typically
     * when Spotify isn't installed locally or the user hasn't authorized our
     * client in the Spotify app yet.
     */
    private suspend fun playOnLocalOrFallback(uri: String): PlayResult {
        if (localSpotify.isConnected) {
            val local = localSpotify.play(uri)
            if (local !is PlayResult.NoActiveDevice) return local
        }
        return spotifyClient.playTrack(uri)
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
        localSpotify.disconnect()
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
        private const val COUNTERS_FLUSH_MS = 60_000L
        // A reading older than this is treated as no live signal.
        private const val SIGNAL_FRESH_MS = 12_000L
        // Consecutive misses before we stop coasting and show "waiting".
        private const val SIGNAL_MISS_THRESHOLD = 3
        // Consecutive misses before we give up and stop auto mode (~2 min at the
        // wait-poll cadence below).
        private const val SIGNAL_GIVE_UP_MISSES = 24
        // Poll cadence while waiting for the signal to return.
        private const val SIGNAL_WAIT_INTERVAL_MS = 5_000L
        private const val RECENT_LIMIT = 50
        private const val PATH_START_TRACKING = "/start_tracking"
        private const val PATH_STOP_TRACKING = "/stop_tracking"
        private const val PATH_NOW_PLAYING = "/now_playing"
        private const val TAG = "AutoModeService"
    }
}
