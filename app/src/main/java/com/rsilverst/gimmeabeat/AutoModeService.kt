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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    // Elapsed-time of the last live watch signal (or auto-mode start). The loop
    // checks freshness every poll — even while a track keeps playing — so a lost
    // signal escalates to a "waiting" state and eventually stops auto mode.
    // [awaitingSignal] makes the loop poll quickly while a signal is gone.
    private var lastLiveSignalAtMs = 0L
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
            ACTION_RESYNC -> {
                resyncWatch()
                return START_STICKY
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
        lastLiveSignalAtMs = SystemClock.elapsedRealtime()
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
        // Fire-and-forget, but the send itself retries with backoff and records
        // dropouts (see WearMessenger). Runs on the process-lifetime
        // [watchSendScope] so the teardown stop-tracking command still lands
        // even though onDestroy cancels the session [scope].
        watchSendScope.launch { WearMessenger.send(applicationContext, path, payload) }
    }

    /**
     * Re-poke the watch when the user taps "Retry sync" — re-sends the
     * start-tracking command and the current signal source, in case the watch
     * missed them or its exercise service stopped. Same sends [startAuto] makes.
     */
    private fun resyncWatch() {
        updateUiStatus("Re-syncing with watch…")
        sendWatchCommand(PATH_START_TRACKING)
        scope.launch {
            WatchSync.sendSignalSource(
                applicationContext,
                Preferences.currentSignalSource(applicationContext),
            )
        }
    }

    private suspend fun runLoop() {
        var firstTick = true
        var lastFailed = false
        while (coroutineContext.isActive) {
            if (!firstTick) {
                delay(
                    when {
                        awaitingSignal -> SIGNAL_WAIT_INTERVAL_MS
                        lastFailed -> IDLE_BACKOFF_MS
                        else -> POLL_INTERVAL_MS
                    }
                )
            }
            maybeFlushCounters()
            lastFailed = !tick(firstTick)
            firstTick = false
        }
    }

    /**
     * One loop iteration. Evaluates the watch signal *every* time — not just when
     * we pick — so loss is caught even while a track keeps playing. Returns true
     * for a "good" tick (live signal, and either playing or a successful pick),
     * false when it backs off (waiting / idle / failed pick / give-up).
     */
    private suspend fun tick(firstTick: Boolean): Boolean {
        val signal = evaluateSignal()

        // Sustained loss → give up regardless of what Spotify is doing. Checked
        // before the playback fetch so a dead watch stops us without waiting on
        // the network.
        if (signal is SignalStatus.Lost && SignalFreshness.shouldGiveUp(signal.lostMs, SIGNAL_GIVE_UP_MS)) {
            logSignalLoss("stop", signal.lostMs)
            giveUpNoSignal()
            return false
        }

        val state = spotifyClient.playbackState()
        val item = state?.item

        if (signal is SignalStatus.Lost) {
            if (item != null) {
                // Behavior (a): don't interrupt the current track. Keep it
                // playing, follow Spotify if it changed tracks, but tell the
                // truth in the status — and keep counting toward give-up.
                if (item.id != syncedTrackId) syncCardFromState(item)
                logSignalLoss("keep_playing", signal.lostMs)
                updateUiStatus("Signal lost — keeping current track")
            } else {
                // Idle + lost → wait; the loop polls quickly to resume or stop.
                logSignalLoss("wait", signal.lostMs)
                updateUiStatus("Waiting for signal from watch…")
            }
            return false
        }

        // Signal is live.
        val bpm = (signal as SignalStatus.Live).bpm
        if (state == null || item == null) {
            return pickAndPlay(if (firstTick) "Starting auto mode" else "Player idle", bpm)
        }
        // If what's actually playing isn't what our card claims (Spotify
        // auto-queued, a skip in Spotify, etc.), sync the card to reality.
        if (item.id != syncedTrackId) syncCardFromState(item)
        val duration = item.duration_ms ?: return true
        val remaining = duration - state.progress_ms
        updateUiStatus(autoStatusLine(state, remaining))
        if (remaining < END_OF_TRACK_THRESHOLD_MS) {
            return pickAndPlay("Track ending", bpm)
        }
        return true
    }

    /**
     * Classifies the active signal source as live or lost. A live reading
     * refreshes [lastLiveSignalAtMs] and clears [awaitingSignal]; a lost one sets
     * [awaitingSignal] (so the loop polls quickly) and bumps the signal_absent
     * counter. "Lost" means no reading, a reading older than [SIGNAL_FRESH_MS],
     * or no usable value yet.
     */
    private suspend fun evaluateSignal(): SignalStatus {
        val source = Preferences.currentSignalSource(applicationContext)
        val receivedAtMs = when (source) {
            SignalSource.HeartRate -> HeartRateRelay.heartRate.value?.receivedAtMs
            SignalSource.Cadence -> CadenceRelay.cadence.value?.receivedAtMs
        }
        val lastKnownBpm = when (source) {
            SignalSource.HeartRate ->
                HeartRateRelay.smoothedBpm.value ?: HeartRateRelay.heartRate.value?.bpm
            SignalSource.Cadence ->
                CadenceRelay.smoothedSpm.value ?: CadenceRelay.cadence.value?.stepsPerMinute
        }
        val ageMs = receivedAtMs?.let { System.currentTimeMillis() - it }
        val now = SystemClock.elapsedRealtime()
        return when (val status = SignalFreshness.classify(
            lastKnownBpm = lastKnownBpm,
            signalAgeMs = ageMs,
            lostMs = now - lastLiveSignalAtMs,
            freshThresholdMs = SIGNAL_FRESH_MS,
        )) {
            is SignalStatus.Live -> {
                lastLiveSignalAtMs = now
                awaitingSignal = false
                status
            }
            is SignalStatus.Lost -> {
                awaitingSignal = true
                Counters.increment(Counters.SIGNAL_ABSENT)
                status
            }
        }
    }

    private fun logSignalLoss(action: String, lostMs: Long) {
        Telemetry.log("signal_loss", mapOf("action" to action, "lostMs" to lostMs))
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

    private suspend fun pickAndPlay(reason: String, signalBpm: Int): Boolean {
        // Spotify precondition. Without a usable token every track search returns
        // null, which used to surface as a misleading "none on Spotify". A
        // revoked refresh token still reports isAuthorized=true (the token is
        // present, just rejected on use), so getFreshAccessToken() is the only
        // reliable "can we actually call Spotify" check.
        if (authRepo.getFreshAccessToken(authService) == null) {
            val msg = if (authRepo.isAuthorized.value) {
                "Spotify session expired — sign in again"
            } else {
                "Not signed in to Spotify"
            }
            updateUiStatus(msg)
            Telemetry.log(
                "auto_pick",
                mapOf("reason" to reason, "outcome" to "not_authorized"),
            )
            return false
        }

        val multiplier = Preferences.currentMultiplier(applicationContext)
        val genre = Preferences.currentGenre(applicationContext)
        val source = Preferences.currentSignalSource(applicationContext)
        val targetBpm = (signalBpm * multiplier).toInt().coerceIn(40, 220)

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
                "signalBpm" to signalBpm,
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
        // Cancels the loop and any session-scoped children; watchSendScope is
        // process-lifetime and intentionally survives so in-flight watch sends
        // (e.g. stop-tracking) complete.
        scope.cancel()
        localSpotify.disconnect()
        authService.dispose()
        super.onDestroy()
    }

    companion object {
        // Process-lifetime scope for best-effort watch sends that must outlive a
        // single service instance — notably the stop-tracking command fired
        // during teardown, which would otherwise be cut off when onDestroy
        // cancels the session scope. Holds no long-running work, only transient
        // sends, so it isn't a leak.
        private val watchSendScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val ACTION_STOP = "com.rsilverst.gimmeabeat.action.STOP_AUTO"
        const val ACTION_RESYNC = "com.rsilverst.gimmeabeat.action.RESYNC_WATCH"
        private const val CHANNEL_ID = "auto_mode"
        private const val NOTIF_ID = 1001
        private const val POLL_INTERVAL_MS = 5_000L
        private const val IDLE_BACKOFF_MS = 20_000L
        private const val END_OF_TRACK_THRESHOLD_MS = 8_000L
        private const val COUNTERS_FLUSH_MS = 60_000L
        // A reading older than this is treated as no live signal.
        private const val SIGNAL_FRESH_MS = 12_000L
        // How long the signal can stay gone before we give up and stop auto mode.
        private const val SIGNAL_GIVE_UP_MS = 120_000L
        // Poll cadence while waiting for the signal to return.
        private const val SIGNAL_WAIT_INTERVAL_MS = 5_000L
        private const val RECENT_LIMIT = 50
        private const val PATH_START_TRACKING = "/start_tracking"
        private const val PATH_STOP_TRACKING = "/stop_tracking"
        private const val PATH_NOW_PLAYING = "/now_playing"
        private const val TAG = "AutoModeService"
    }
}

/** Result of classifying the current watch signal each loop iteration. */
internal sealed interface SignalStatus {
    /** A fresh reading is available; [bpm] is the value to match against. */
    data class Live(val bpm: Int) : SignalStatus

    /** No usable signal; [lostMs] is how long it has been gone. */
    data class Lost(val lostMs: Long) : SignalStatus
}

/**
 * Pure decision logic for watch-signal freshness, split out from
 * [AutoModeService] so it can be unit tested without Android dependencies. The
 * service supplies the live readings and the elapsed clock; this only decides
 * live-vs-lost and when to give up.
 */
internal object SignalFreshness {

    /**
     * Classifies a reading as live or lost.
     *
     * @param lastKnownBpm smoothed/last reading value, or null if none received
     * @param signalAgeMs age of that reading (now − receivedAt), or null if none
     * @param lostMs how long the signal has been gone (carried on the Lost result)
     * @param freshThresholdMs the maximum age that still counts as live
     */
    fun classify(
        lastKnownBpm: Int?,
        signalAgeMs: Long?,
        lostMs: Long,
        freshThresholdMs: Long,
    ): SignalStatus =
        if (lastKnownBpm != null && signalAgeMs != null && signalAgeMs <= freshThresholdMs) {
            SignalStatus.Live(lastKnownBpm)
        } else {
            SignalStatus.Lost(lostMs)
        }

    /** True once the signal has been gone at least [giveUpAfterMs]. */
    fun shouldGiveUp(lostMs: Long, giveUpAfterMs: Long): Boolean =
        lostMs >= giveUpAfterMs

    /**
     * Coarse health bucket for the Home indicator: [SignalHealth.LIVE] within
     * [freshThresholdMs], [SignalHealth.DELAYED] up to [absentThresholdMs], and
     * [SignalHealth.ABSENT] beyond that or with no reading ([ageMs] null).
     */
    fun health(ageMs: Long?, freshThresholdMs: Long, absentThresholdMs: Long): SignalHealth =
        when {
            ageMs == null || ageMs > absentThresholdMs -> SignalHealth.ABSENT
            ageMs <= freshThresholdMs -> SignalHealth.LIVE
            else -> SignalHealth.DELAYED
        }
}

/** Coarse watch-signal health shown on the Home screen. */
enum class SignalHealth { LIVE, DELAYED, ABSENT }
