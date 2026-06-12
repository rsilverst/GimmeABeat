package com.rsilverst.gimmeabeat

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import com.rsilverst.gimmeabeat.telemetry.Counters
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

/**
 * Sends control/config messages to the paired watch over the Wearable Data
 * Layer, retrying the whole lookup+send on transient failures with exponential
 * backoff (mirrors `SpotifyClient`'s retry policy).
 *
 * Used for one-shot messages whose loss has lasting effect — start/stop
 * tracking, now-playing, signal source. The high-frequency HR/cadence stream
 * deliberately does NOT route through here: a dropped sample self-heals on the
 * next (~1s) reading, and a backed-off retry would deliver a stale value out of
 * order, which is worse than the drop.
 */
object WearMessenger {

    private const val TAG = "WearMessenger"
    private const val ATTEMPTS = 3
    private const val INITIAL_DELAY_MS = 400L
    private const val MAX_DELAY_MS = 3_000L

    /**
     * Sends [payload] to [path] on every connected node. Returns true as soon as
     * at least one node accepts it; false once [ATTEMPTS] are exhausted. On final
     * failure it bumps a telemetry counter exactly once: [Counters.WATCH_UNREACHABLE]
     * if no node was ever found, or [Counters.WATCH_SEND_FAILED] if a node existed
     * but every send threw.
     */
    suspend fun send(context: Context, path: String, payload: ByteArray = ByteArray(0)): Boolean {
        val appCtx = context.applicationContext
        var sawNode = false
        var delayMs = INITIAL_DELAY_MS
        repeat(ATTEMPTS) { attempt ->
            val nodes = try {
                Wearable.getNodeClient(appCtx).connectedNodes.await()
            } catch (t: Throwable) {
                Log.w(TAG, "connectedNodes failed for $path (attempt ${attempt + 1})", t)
                null
            }
            if (!nodes.isNullOrEmpty()) {
                sawNode = true
                var anyOk = false
                nodes.forEach { node ->
                    try {
                        Wearable.getMessageClient(appCtx)
                            .sendMessage(node.id, path, payload)
                            .await()
                        anyOk = true
                    } catch (t: Throwable) {
                        Log.w(
                            TAG,
                            "sendMessage($path) to ${node.displayName} failed (attempt ${attempt + 1})",
                            t,
                        )
                    }
                }
                if (anyOk) return true
            }
            if (attempt < ATTEMPTS - 1) {
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
            }
        }
        Counters.increment(if (sawNode) Counters.WATCH_SEND_FAILED else Counters.WATCH_UNREACHABLE)
        Log.w(TAG, "giving up sending $path after $ATTEMPTS attempts (sawNode=$sawNode)")
        return false
    }
}
