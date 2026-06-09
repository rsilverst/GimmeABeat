package com.rsilverst.gimmeabeat.wear

import android.content.Intent
import androidx.core.content.ContextCompat
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/**
 * Receives commands from the phone companion. On `/start_tracking` we kick off
 * the watch's [ExerciseService]; on `/stop_tracking` we ask it to stop.
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
        }
    }

    companion object {
        const val PATH_START = "/start_tracking"
        const val PATH_STOP = "/stop_tracking"
    }
}
