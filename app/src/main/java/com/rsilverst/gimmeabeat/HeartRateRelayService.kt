package com.rsilverst.gimmeabeat

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import java.nio.ByteBuffer

class HeartRateRelayService : WearableListenerService() {

    override fun onMessageReceived(event: MessageEvent) {
        if (event.data.size < 4) {
            Log.w(TAG, "payload too short on ${event.path}: ${event.data.size}")
            return
        }
        val value = ByteBuffer.wrap(event.data).int
        when (event.path) {
            PATH_HEART_RATE -> HeartRateRelay.update(value)
            PATH_CADENCE -> CadenceRelay.update(value)
            else -> Log.d(TAG, "ignoring unknown path ${event.path}")
        }
    }

    companion object {
        const val PATH_HEART_RATE = "/heart_rate"
        const val PATH_CADENCE = "/cadence"
        private const val TAG = "HeartRateRelaySvc"
    }
}
