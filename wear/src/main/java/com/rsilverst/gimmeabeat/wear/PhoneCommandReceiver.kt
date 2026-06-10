package com.rsilverst.gimmeabeat.wear

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives commands from the phone companion:
 *  - /start_tracking → start [ExerciseService]
 *  - /stop_tracking  → stop  [ExerciseService]
 *  - /now_playing    → display the current track on the watch UI
 */
class PhoneCommandReceiver : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_START -> {
                val intent = Intent(this, ExerciseService::class.java)
                ContextCompat.startForegroundService(this, intent)
            }
            PATH_STOP -> {
                val intent = Intent(this, ExerciseService::class.java).apply {
                    action = ExerciseService.ACTION_STOP
                }
                startService(intent)
            }
            PATH_NOW_PLAYING -> {
                val text = String(event.data).takeIf { it.isNotBlank() }
                WatchTrackingState.setNowPlaying(text)
            }
        }
    }

    companion object {
        const val PATH_START = "/start_tracking"
        const val PATH_STOP = "/stop_tracking"
        const val PATH_NOW_PLAYING = "/now_playing"
    }
}
