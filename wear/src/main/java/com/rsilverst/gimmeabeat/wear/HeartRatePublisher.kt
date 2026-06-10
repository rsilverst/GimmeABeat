package com.rsilverst.gimmeabeat.wear

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import java.nio.ByteBuffer

class HeartRatePublisher(context: Context) {

    private val appContext = context.applicationContext
    private val messageClient = Wearable.getMessageClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)

    suspend fun publishHeartRate(bpm: Int) = publish(PATH_HR, bpm, "bpm")

    suspend fun publishCadence(spm: Int) = publish(PATH_CADENCE, spm, "spm")

    private suspend fun publish(path: String, value: Int, label: String) {
        val payload = ByteBuffer.allocate(4).putInt(value).array()
        val nodes = try {
            nodeClient.connectedNodes.await()
        } catch (t: Throwable) {
            Log.w(TAG, "getConnectedNodes failed", t)
            return
        }
        if (nodes.isEmpty()) {
            Log.d(TAG, "No connected phone nodes — pairing not set up?")
            return
        }
        nodes.forEach { node ->
            try {
                messageClient.sendMessage(node.id, path, payload).await()
                Log.d(TAG, "Sent $label=$value to ${node.displayName}")
            } catch (t: Throwable) {
                Log.w(TAG, "sendMessage($path) to ${node.displayName} failed", t)
            }
        }
    }

    companion object {
        const val PATH_HR = "/heart_rate"
        const val PATH_CADENCE = "/cadence"
        private const val TAG = "HeartRatePublisher"
    }
}
