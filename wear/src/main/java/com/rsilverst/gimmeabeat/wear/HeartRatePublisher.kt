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

    suspend fun publish(bpm: Int) {
        val payload = ByteBuffer.allocate(4).putInt(bpm).array()
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
                messageClient.sendMessage(node.id, PATH, payload).await()
                Log.d(TAG, "Sent bpm=$bpm to ${node.displayName}")
            } catch (t: Throwable) {
                Log.w(TAG, "sendMessage to ${node.displayName} failed", t)
            }
        }
    }

    companion object {
        const val PATH = "/heart_rate"
        private const val TAG = "HeartRatePublisher"
    }
}
