package com.rsilverst.gimmeabeat.spotify

import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import net.openid.appauth.AuthorizationService
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

private const val TAG = "SpotifyClient"

class SpotifyClient(
    private val auth: SpotifyAuthRepository,
    private val authService: AuthorizationService,
) {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val okHttp: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val api: SpotifyApi = Retrofit.Builder()
        .baseUrl("https://api.spotify.com/")
        .client(okHttp)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(SpotifyApi::class.java)

    suspend fun me(): SpotifyUser? = withToken { token -> api.me(token) }

    suspend fun listDevices(): List<SpotifyDevice>? =
        withToken { token -> api.devices(token).devices }

    /** Returns the best-matching Spotify track for [title]+[artist], or null. */
    suspend fun searchTrack(title: String, artist: String): SpotifyTrack? = withToken { token ->
        val q = "track:\"${title.escapeForSearch()}\" artist:\"${artist.escapeForSearch()}\""
        api.search(token, q).tracks?.items?.firstOrNull()
    }

    /**
     * Returns the current playback state, or null if Spotify reports 204 (nothing
     * playing) / errors. A null result is normal when the user has no active device.
     */
    suspend fun playbackState(): SpotifyPlaybackState? {
        val token = auth.getFreshAccessToken(authService) ?: return null
        return try {
            val resp = api.playbackState("Bearer $token")
            if (resp.code() == 204) null else resp.body()
        } catch (t: retrofit2.HttpException) {
            val body = runCatching { t.response()?.errorBody()?.string() }.getOrNull()
            Log.w(TAG, "HTTP ${t.code()} from /me/player: $body")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "playbackState failed", t)
            null
        }
    }

    private fun String.escapeForSearch(): String =
        replace("\"", "").replace("\\", "").trim()

    /**
     * Plays [spotifyUri]. If no device is currently *active*, looks at the user's
     * available devices and routes playback to the first one. Returns a [PlayResult]
     * describing the outcome.
     */
    suspend fun playTrack(spotifyUri: String): PlayResult {
        val token = auth.getFreshAccessToken(authService) ?: return PlayResult.NotAuthorized
        val body = PlayRequest(uris = listOf(spotifyUri))
        val first = api.play("Bearer $token", body)
        if (first.code() in 200..204) return PlayResult.Playing
        if (first.code() != 404) return classify(first)

        // 404 = no active device. Try to pick one from the available devices.
        val devices = try {
            api.devices("Bearer $token").devices
        } catch (t: Throwable) {
            Log.w(TAG, "devices lookup failed after 404", t)
            return PlayResult.NoActiveDevice
        }
        val target = devices.firstOrNull { it.is_active }?.id
            ?: devices.firstOrNull { !it.is_restricted }?.id
            ?: return PlayResult.NoActiveDevice
        val retry = api.play("Bearer $token", body, target)
        if (retry.code() in 200..204) return PlayResult.Playing
        return classify(retry)
    }

    private fun classify(resp: retrofit2.Response<Unit>): PlayResult {
        val body = resp.errorBody()?.string().orEmpty()
        Log.w(TAG, "play failed: HTTP ${resp.code()} body=$body")
        return when (resp.code()) {
            404 -> PlayResult.NoActiveDevice
            403 -> PlayResult.Error("403: $body")
            else -> PlayResult.Error("HTTP ${resp.code()}: $body")
        }
    }

    private suspend inline fun <T> withToken(block: (String) -> T): T? {
        val token = auth.getFreshAccessToken(authService) ?: return null
        return try {
            block("Bearer $token")
        } catch (t: retrofit2.HttpException) {
            val body = runCatching { t.response()?.errorBody()?.string() }.getOrNull()
            Log.w(TAG, "HTTP ${t.code()} from Spotify: $body")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "API call failed", t)
            null
        }
    }
}

sealed interface PlayResult {
    data object Playing : PlayResult
    data object NoActiveDevice : PlayResult
    data object PremiumRequired : PlayResult
    data object NotAuthorized : PlayResult
    data class Error(val message: String) : PlayResult
}
