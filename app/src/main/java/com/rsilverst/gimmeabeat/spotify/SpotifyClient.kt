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

    private fun String.escapeForSearch(): String =
        replace("\"", "").replace("\\", "").trim()

    /** Returns true on success. False if no active device or error. */
    suspend fun playTrack(spotifyUri: String): PlayResult {
        val token = auth.getFreshAccessToken(authService) ?: return PlayResult.NotAuthorized
        val resp = api.play("Bearer $token", PlayRequest(uris = listOf(spotifyUri)))
        if (resp.code() in 200..204) return PlayResult.Playing
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
