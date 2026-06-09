package com.rsilverst.gimmeabeat

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

class HeartRateRelayService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH) return
        if (event.data.size < 4) {
            Log.w(TAG, "heart rate payload too short: ${event.data.size}")
            return
        }
        val bpm = ByteBuffer.wrap(event.data).int
        HeartRateRelay.update(bpm)
    }

    companion object {
        const val PATH = "/heart_rate"
        private const val TAG = "HeartRateRelaySvc"
    }
}
