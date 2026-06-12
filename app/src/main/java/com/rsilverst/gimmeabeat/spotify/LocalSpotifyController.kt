package com.rsilverst.gimmeabeat.spotify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rsilverst.gimmeabeat.BuildConfig
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "LocalSpotify"
private const val SPOTIFY_PACKAGE = "com.spotify.music"

/**
 * Wraps the Spotify App Remote SDK so we can wake Spotify and play tracks on
 * the local device without ever bringing Spotify's UI to the foreground.
 * App Remote talks to Spotify via local IPC, which sidesteps both the Activity
 * launch and Spotify Connect device-routing entirely — playback always lands
 * here, even if other Connect devices (emulators, desktops) are active.
 */
class LocalSpotifyController(private val context: Context) {

    @Volatile private var appRemote: SpotifyAppRemote? = null

    // App Remote internals construct a Handler() without an explicit Looper,
    // so the SDK must be invoked from a thread with a Looper attached. The
    // Service callbacks run on main, but our auto-mode loop runs on
    // Dispatchers.IO, so we hop to the main looper for any SDK entry point.
    private val mainHandler = Handler(Looper.getMainLooper())

    val isConnected: Boolean get() = appRemote?.isConnected == true

    /**
     * Connects to Spotify's App Remote service. Returns true once connected.
     * Returns false if Spotify isn't installed, the user hasn't authorized
     * our client ID in the Spotify app yet, or the bind otherwise fails.
     */
    suspend fun connect(): Boolean {
        if (context.packageManager.getLaunchIntentForPackage(SPOTIFY_PACKAGE) == null) {
            Log.d(TAG, "Spotify not installed; skipping App Remote connect")
            return false
        }
        appRemote?.let { if (it.isConnected) return true }

        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(SpotifyAuthRepository.REDIRECT_URI)
            .showAuthView(false)
            .build()
        return suspendCancellableCoroutine { cont ->
            mainHandler.post {
                SpotifyAppRemote.connect(
                    context.applicationContext, params,
                    object : Connector.ConnectionListener {
                        override fun onConnected(remote: SpotifyAppRemote) {
                            appRemote = remote
                            Log.d(TAG, "App Remote connected")
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onFailure(t: Throwable) {
                            Log.w(TAG, "App Remote connect failed", t)
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
            }
        }
    }

    fun disconnect() {
        val remote = appRemote ?: return
        appRemote = null
        mainHandler.post { runCatching { SpotifyAppRemote.disconnect(remote) } }
    }

    /** Plays the given Spotify track URI on this device via local IPC. */
    suspend fun play(uri: String): PlayResult {
        val remote = appRemote
        if (remote == null || !remote.isConnected) return PlayResult.NoActiveDevice
        return suspendCancellableCoroutine { cont ->
            mainHandler.post {
                remote.playerApi.play(uri)
                    .setResultCallback {
                        if (cont.isActive) cont.resume(PlayResult.Playing)
                    }
                    .setErrorCallback { error ->
                        Log.w(TAG, "App Remote play failed", error)
                        if (cont.isActive) cont.resume(
                            PlayResult.Error(error.message ?: "App Remote play failed"),
                        )
                    }
            }
        }
    }
}
