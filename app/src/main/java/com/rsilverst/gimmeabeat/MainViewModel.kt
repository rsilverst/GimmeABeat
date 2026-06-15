package com.rsilverst.gimmeabeat

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rsilverst.gimmeabeat.bpm.GetSongBpmClient
import com.rsilverst.gimmeabeat.spotify.PlayResult
import com.rsilverst.gimmeabeat.spotify.SpotifyAuthRepository
import com.rsilverst.gimmeabeat.spotify.SpotifyClient
import com.rsilverst.gimmeabeat.spotify.SpotifyUser
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService

private const val TEST_TRACK_URI = "spotify:track:11dFghVXANMlKmJXsNCbNl" // Cut To The Feeling

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext

    val authRepo = SpotifyAuthRepository(app)
    val authService = AuthorizationService(app)
    private val spotifyClient = SpotifyClient(appContext, authRepo, authService)
    private val bpmClient = GetSongBpmClient()
    private val songFinder = SongFinder(bpmClient, spotifyClient)

    val isAuthorized: StateFlow<Boolean> = authRepo.isAuthorized

    private val _user = MutableStateFlow<SpotifyUser?>(null)
    val user: StateFlow<SpotifyUser?> = _user.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()


    private val _targetBpm = MutableStateFlow(120)
    val targetBpm: StateFlow<Int> = _targetBpm.asStateFlow()

    val multiplier: StateFlow<Float> =
        Preferences.multiplierFlow(app).stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            Preferences.DEFAULT_MULTIPLIER,
        )

    val selectedGenre: StateFlow<String?> =
        Preferences.genreFlow(app).stateIn(viewModelScope, SharingStarted.Eagerly, "pop")

    val signalSource: StateFlow<SignalSource> =
        Preferences.signalSourceFlow(app).stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SignalSource.HeartRate,
        )

    /**
     * Coarse health of the active watch signal for the Home indicator. Driven by
     * a ticker as well as the source so it flips to delayed/absent even when
     * readings simply stop arriving (a frozen relay flow never re-emits).
     */
    val signalHealth: StateFlow<SignalHealth> =
        combine(signalSource, tickerFlow(FRESHNESS_TICK_MS)) { source, _ ->
            val receivedAtMs = when (source) {
                SignalSource.HeartRate -> HeartRateRelay.heartRate.value?.receivedAtMs
                SignalSource.Cadence -> CadenceRelay.cadence.value?.receivedAtMs
            }
            val ageMs = receivedAtMs?.let { System.currentTimeMillis() - it }
            SignalFreshness.health(ageMs, SIGNAL_FRESH_MS, SIGNAL_ABSENT_MS)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SignalHealth.ABSENT)

    /** Auto state lives in [AutoModeState] singleton so the service is the writer. */
    val autoActive: StateFlow<Boolean> = AutoModeState.active
    val autoStatus: StateFlow<String?> = AutoModeState.status
    val nowPlaying: StateFlow<NowPlaying?> = AutoModeState.nowPlaying

    init {
        viewModelScope.launch {
            isAuthorized.collect { authed ->
                if (authed) refreshUser() else _user.value = null
            }
        }
    }

    fun getAuthorizationIntent(): Intent =
        authService.getAuthorizationRequestIntent(authRepo.buildAuthorizationRequest())

    fun handleAuthResult(data: Intent?) {
        if (data == null) return
        val response = AuthorizationResponse.fromIntent(data)
        val ex = AuthorizationException.fromIntent(data)
        if (response == null) {
            _status.value = "Sign-in cancelled: ${ex?.errorDescription ?: "unknown"}"
            return
        }
        viewModelScope.launch {
            authRepo.handleAuthorizationResponse(authService, response)
                .onFailure { _status.value = "Token exchange failed: ${it.message}" }
        }
    }

    fun setTargetBpm(bpm: Int) {
        _targetBpm.value = bpm.coerceIn(40, 220)
    }

    fun setGenre(genre: String?) {
        viewModelScope.launch { Preferences.setGenre(appContext, genre) }
    }

    fun setMultiplier(value: Float) {
        viewModelScope.launch { Preferences.setMultiplier(appContext, value) }
    }

    fun setSignalSource(value: SignalSource) {
        viewModelScope.launch {
            Preferences.setSignalSource(appContext, value)
            WatchSync.sendSignalSource(appContext, value)
        }
    }

    fun playTestTrack() {
        _status.value = "Requesting playback…"
        viewModelScope.launch {
            _status.value = playResultMessage(spotifyClient.playTrack(TEST_TRACK_URI))
        }
    }

    fun findAndPlayMatchingSong() {
        _status.value = "Looking for a song near ${_targetBpm.value} BPM…"
        AutoModeState.setNowPlaying(null)
        viewModelScope.launch {
            when (val r = songFinder.findCandidate(_targetBpm.value, selectedGenre.value)) {
                FindResult.NoBpmCandidates ->
                    _status.value = "No songs near ${_targetBpm.value} BPM in that genre."
                FindResult.NoSpotifyMatch ->
                    _status.value = "Found BPM matches but none were on Spotify."
                is FindResult.Found -> {
                    AutoModeState.setNowPlaying(
                        NowPlaying(
                            title = r.candidate.title,
                            artist = r.candidate.artist,
                            bpm = r.candidate.bpm,
                            imageUrl = r.track.album?.images?.firstOrNull()?.url,
                        )
                    )
                    _status.value = playResultMessage(spotifyClient.playTrack(r.track.uri))
                }
            }
        }
    }

    fun startAuto() {
        val intent = Intent(appContext, AutoModeService::class.java)
        ContextCompat.startForegroundService(appContext, intent)
    }

    fun stopAuto() {
        val intent = Intent(appContext, AutoModeService::class.java).apply {
            action = AutoModeService.ACTION_STOP
        }
        appContext.startService(intent)
    }

    fun signOut() {
        stopAuto()
        authRepo.signOut()
        _status.value = null
        AutoModeState.setNowPlaying(null)
    }

    private fun playResultMessage(r: PlayResult): String = when (r) {
        PlayResult.Playing -> "Playing!"
        PlayResult.NoActiveDevice ->
            "No active Spotify device. Open Spotify on your phone or computer and hit play once, then try again."
        PlayResult.PremiumRequired -> "Spotify Premium is required for remote playback."
        PlayResult.NotAuthorized -> "Not signed in."
        is PlayResult.Error -> "Playback error: ${r.message}"
    }

    private fun refreshUser() {
        viewModelScope.launch {
            _user.value = spotifyClient.me()
        }
    }

    /** Emits [Unit] every [periodMs] so time-based UI state (signal freshness)
     *  can re-evaluate even when no new reading arrives. */
    private fun tickerFlow(periodMs: Long): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(periodMs)
        }
    }

    override fun onCleared() {
        authService.dispose()
        super.onCleared()
    }

    companion object {
        private const val SIGNAL_FRESH_MS = 12_000L
        private const val SIGNAL_ABSENT_MS = 30_000L
        private const val FRESHNESS_TICK_MS = 1_000L

        val Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application,
                )
            }
        }
    }
}
