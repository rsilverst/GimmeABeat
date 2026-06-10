package com.rsilverst.gimmeabeat

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object WatchSync {

    private const val TAG = "WatchSync"
    private const val PATH_SIGNAL_SOURCE = "/signal_source"

    /** Tells the watch which signal the phone is currently using for matching. */
    suspend fun sendSignalSource(context: Context, source: SignalSource) {
        val nodes = try {
            Wearable.getNodeClient(context).connectedNodes.await()
        } catch (t: Throwable) {
            Log.w(TAG, "connectedNodes failed", t)
            return
        }
        if (nodes.isEmpty()) return
        nodes.forEach { node ->
            try {
                Wearable.getMessageClient(context)
                    .sendMessage(node.id, PATH_SIGNAL_SOURCE, source.key.toByteArray())
                    .await()
            } catch (t: Throwable) {
                Log.w(TAG, "sendSignalSource(${source.key}) to ${node.displayName} failed", t)
            }
        }
    }
}
