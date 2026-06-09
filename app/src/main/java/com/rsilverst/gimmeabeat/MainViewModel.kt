package com.rsilverst.gimmeabeat

import android.app.Application
import android.content.Intent
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService

private const val TEST_TRACK_URI = "spotify:track:11dFghVXANMlKmJXsNCbNl" // Cut To The Feeling

class MainViewModel(app: Application) : AndroidViewModel(app) {

    val authRepo = SpotifyAuthRepository(app)
    val authService = AuthorizationService(app)
    private val spotifyClient = SpotifyClient(authRepo, authService)
    private val bpmClient = GetSongBpmClient()
    private val songFinder = SongFinder(bpmClient, spotifyClient)

    val isAuthorized: StateFlow<Boolean> = authRepo.isAuthorized

    private val _user = MutableStateFlow<SpotifyUser?>(null)
    val user: StateFlow<SpotifyUser?> = _user.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    private val _targetBpm = MutableStateFlow(120)
    val targetBpm: StateFlow<Int> = _targetBpm.asStateFlow()

    private val _selectedGenre = MutableStateFlow<String?>("pop")
    val selectedGenre: StateFlow<String?> = _selectedGenre.asStateFlow()

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
        _selectedGenre.value = genre
    }

    fun playTestTrack() {
        _status.value = "Requesting playback…"
        viewModelScope.launch {
            _status.value = playResultMessage(spotifyClient.playTrack(TEST_TRACK_URI))
        }
    }

    fun findAndPlayMatchingSong() {
        _status.value = "Looking for a song near ${_targetBpm.value} BPM…"
        _nowPlaying.value = null
        viewModelScope.launch {
            when (val r = songFinder.findAndPlay(_targetBpm.value, _selectedGenre.value)) {
                FindAndPlayResult.NoBpmCandidates ->
                    _status.value = "No songs near ${_targetBpm.value} BPM in that genre."
                FindAndPlayResult.NoSpotifyMatch ->
                    _status.value = "Found BPM matches but none were on Spotify."
                is FindAndPlayResult.Resolved -> {
                    _nowPlaying.value =
                        "${r.candidate.title} — ${r.candidate.artist} (${r.candidate.bpm} BPM)"
                    _status.value = playResultMessage(r.playResult)
                }
            }
        }
    }

    fun signOut() {
        authRepo.signOut()
        _status.value = null
        _nowPlaying.value = null
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

    override fun onCleared() {
        authService.dispose()
        super.onCleared()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                MainViewModel(
                    this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application,
                )
            }
        }
    }
}
