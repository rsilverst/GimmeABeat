package com.rsilverst.gimmeabeat.spotify

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.delay
import net.openid.appauth.AuthorizationService
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.io.IOException

private const val TAG = "SpotifyClient"

class SpotifyClient(
    context: Context,
    private val auth: SpotifyAuthRepository,
    private val authService: AuthorizationService,
) {

    /**
     * Lowercased name fragments we'll use to identify the Spotify device
     * entry that corresponds to *this* phone. Spotify Connect typically names
     * a device after its system device name or Build.MODEL, so containment
     * either way is usually enough.
     */
    private val localDeviceHints: List<String> = buildLocalDeviceHints(context)

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

    suspend fun me(): SpotifyUser? = withToken { token -> retrying { api.me(token) } }

    suspend fun listDevices(): List<SpotifyDevice>? =
        withToken { token -> retrying { api.devices(token).devices } }

    /** Returns the best-matching Spotify track for [title]+[artist], or null. */
    suspend fun searchTrack(title: String, artist: String): SpotifyTrack? = withToken { token ->
        val q = "track:\"${title.escapeForSearch()}\" artist:\"${artist.escapeForSearch()}\""
        retrying { api.search(token, q) }.tracks?.items?.firstOrNull()
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
     * Plays [spotifyUri], always routing to the user's phone when one is
     * available. We look up devices up-front and prefer type=Smartphone
     * (active first, then any non-restricted) so playback doesn't slip onto a
     * desktop or other Connect target that happens to be active. Falls back to
     * the active/any device if no smartphone is registered.
     */
    suspend fun playTrack(spotifyUri: String): PlayResult {
        val token = auth.getFreshAccessToken(authService) ?: return PlayResult.NotAuthorized
        val body = PlayRequest(uris = listOf(spotifyUri))

        val devices = try {
            api.devices("Bearer $token").devices
        } catch (t: Throwable) {
            Log.w(TAG, "devices lookup failed", t)
            emptyList()
        }
        val target = pickTargetDevice(devices)

        if (target != null) {
            val resp = api.play("Bearer $token", body, target)
            if (resp.code() in 200..204) return PlayResult.Playing
            return classify(resp)
        }

        // No devices visible at all — try an un-targeted call so Spotify can
        // route to its last-active device, if any.
        val fallback = api.play("Bearer $token", body)
        if (fallback.code() in 200..204) return PlayResult.Playing
        if (fallback.code() == 404) return PlayResult.NoActiveDevice
        return classify(fallback)
    }

    private fun pickTargetDevice(devices: List<SpotifyDevice>): String? {
        val phones = devices.filter { it.type.equals("Smartphone", ignoreCase = true) }
        val selfPhones = phones.filter { looksLikeThisPhone(it.name) }
        return selfPhones.firstOrNull { it.is_active && it.id != null }?.id
            ?: selfPhones.firstOrNull { it.id != null && !it.is_restricted }?.id
            ?: phones.firstOrNull { it.is_active && it.id != null }?.id
            ?: phones.firstOrNull { it.id != null && !it.is_restricted }?.id
            ?: devices.firstOrNull { it.is_active && it.id != null }?.id
            ?: devices.firstOrNull { it.id != null && !it.is_restricted }?.id
    }

    private fun looksLikeThisPhone(deviceName: String): Boolean {
        if (localDeviceHints.isEmpty()) return false
        val n = deviceName.trim().lowercase()
        if (n.isEmpty()) return false
        return localDeviceHints.any { hint -> n.contains(hint) || hint.contains(n) }
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

    /**
     * Retries [block] on transient failures: network IO errors, HTTP 5xx, or
     * 429 (rate-limited). 4xx errors other than 429 propagate immediately.
     */
    private suspend fun <T> retrying(
        attempts: Int = 3,
        initialDelayMs: Long = 800,
        maxDelayMs: Long = 6_000,
        block: suspend () -> T,
    ): T {
        var delayMs = initialDelayMs
        var last: Throwable? = null
        repeat(attempts) { i ->
            try {
                return block()
            } catch (t: IOException) {
                last = t
            } catch (t: retrofit2.HttpException) {
                if (t.code() != 429 && t.code() !in 500..599) throw t
                last = t
            }
            if (i < attempts - 1) {
                Log.d(TAG, "transient error, retrying in ${delayMs}ms (${last?.javaClass?.simpleName})")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
            }
        }
        throw last ?: IllegalStateException("retry: unknown failure")
    }
}

/**
 * Returns short, lowercased fragments useful for matching the "self" device in
 * Spotify's `/me/player/devices` response — typically the user-set device name
 * and the hardware model. Fragments shorter than 3 chars are dropped to avoid
 * matching unrelated devices on a single common letter.
 */
private fun buildLocalDeviceHints(context: Context): List<String> {
    val hints = mutableListOf<String>()
    runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.let { hints += it }
    Build.MODEL?.let { hints += it }
    Build.MANUFACTURER?.let { hints += it }
    return hints
        .map { it.trim().lowercase() }
        .filter { it.length >= 3 }
        .distinct()
}

sealed interface PlayResult {
    data object Playing : PlayResult
    data object NoActiveDevice : PlayResult
    data object PremiumRequired : PlayResult
    data object NotAuthorized : PlayResult
    data class Error(val message: String) : PlayResult
}
