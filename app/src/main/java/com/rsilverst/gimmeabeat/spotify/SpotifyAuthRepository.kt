package com.rsilverst.gimmeabeat.spotify

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.rsilverst.gimmeabeat.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthState
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import org.json.JSONException
import kotlin.coroutines.resume

private const val TAG = "SpotifyAuth"
private val Context.spotifyDataStore by preferencesDataStore(name = "spotify_auth")
private val AUTH_STATE_KEY = stringPreferencesKey("auth_state_json")

class SpotifyAuthRepository(context: Context) {

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _authState = MutableStateFlow<AuthState>(AuthState())
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val isAuthorized: StateFlow<Boolean> = _authState
        .let { src ->
            val s = MutableStateFlow(src.value.isAuthorized)
            scope.launch { src.map { it.isAuthorized }.collect { s.value = it } }
            s.asStateFlow()
        }

    init {
        scope.launch { loadFromDisk() }
    }

    val authServiceConfig: AuthorizationServiceConfiguration =
        AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.spotify.com/authorize"),
            Uri.parse("https://accounts.spotify.com/api/token"),
        )

    fun buildAuthorizationRequest(): AuthorizationRequest =
        AuthorizationRequest.Builder(
            authServiceConfig,
            BuildConfig.SPOTIFY_CLIENT_ID,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI),
        )
            .setScopes(*SCOPES)
            .build()

    suspend fun handleAuthorizationResponse(
        authService: AuthorizationService,
        response: AuthorizationResponse,
    ): Result<Unit> = suspendCancellableCoroutine { cont ->
        authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, ex ->
            val newState = AuthState(response, ex).apply { update(tokenResponse, ex) }
            if (tokenResponse != null) {
                _authState.value = newState
                scope.launch { saveToDisk(newState) }
                cont.resume(Result.success(Unit))
            } else {
                Log.w(TAG, "token exchange failed", ex)
                cont.resume(Result.failure(ex ?: IllegalStateException("unknown token error")))
            }
        }
    }

    suspend fun getFreshAccessToken(authService: AuthorizationService): String? {
        val state = _authState.value
        if (!state.isAuthorized) return null
        return suspendCancellableCoroutine { cont ->
            state.performActionWithFreshTokens(authService) { accessToken, _, ex ->
                if (ex != null) {
                    Log.w(TAG, "refresh failed", ex)
                    cont.resume(null)
                } else {
                    scope.launch { saveToDisk(state) }
                    cont.resume(accessToken)
                }
            }
        }
    }

    fun signOut() {
        _authState.value = AuthState()
        scope.launch {
            appContext.spotifyDataStore.edit { it.remove(AUTH_STATE_KEY) }
        }
    }

    private suspend fun loadFromDisk() {
        val json = appContext.spotifyDataStore.data.map { it[AUTH_STATE_KEY] }.firstOrNull()
        if (json.isNullOrBlank()) return
        try {
            _authState.value = AuthState.jsonDeserialize(json)
        } catch (e: JSONException) {
            Log.w(TAG, "failed to load AuthState", e)
        }
    }

    private suspend fun saveToDisk(state: AuthState) {
        appContext.spotifyDataStore.edit { it[AUTH_STATE_KEY] = state.jsonSerializeString() }
    }

    companion object {
        const val REDIRECT_URI = "gimmeabeat://spotify-callback"
        val SCOPES = arrayOf(
            "user-read-private",
            "user-read-email",
            "user-modify-playback-state",
            "user-read-playback-state",
            "user-read-currently-playing",
        )
    }
}
