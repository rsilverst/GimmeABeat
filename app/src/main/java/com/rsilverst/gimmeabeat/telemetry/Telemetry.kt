package com.rsilverst.gimmeabeat.telemetry

import android.util.Log

/**
 * Single, lightweight telemetry surface for the app. Every signal/loop event
 * funnels through here so there is one place to read the core loop's behaviour
 * (see the P0 "instrument before optimizing" items in ASSESSMENT.md).
 *
 * Sink is intentionally local-only for now: structured logcat lines plus a
 * bounded in-memory ring buffer. The ring buffer is what a future debug card
 * or opt-in export would read — no analytics SDK, no new dependency, no
 * network, no privacy surface yet. Swapping in a real backend later means
 * adding a second sink in [log]; producers don't change.
 *
 * Read it during development with:
 *   adb logcat | grep -i "GABeatTelemetry"
 */
object Telemetry {

    private const val TAG = "GABeatTelemetry"
    private const val RING_CAPACITY = 500

    /** A single structured event. [atMs] is wall-clock time for display/export. */
    data class Event(
        val atMs: Long,
        val category: String,
        val fields: Map<String, Any?>,
    )

    private val ring = ArrayDeque<Event>()

    /**
     * Record one event. [fields] is rendered as `key=value` pairs in logcat and
     * kept verbatim in the ring buffer. Cheap enough to call on the hot path.
     */
    @Synchronized
    fun log(category: String, fields: Map<String, Any?>) {
        val event = Event(
            atMs = System.currentTimeMillis(),
            category = category,
            fields = fields,
        )
        ring.addLast(event)
        while (ring.size > RING_CAPACITY) ring.removeFirst()
        Log.i(TAG, render(category, fields))
    }

    /** Snapshot of recent events, oldest first — for a debug card or export. */
    @Synchronized
    fun recentEvents(): List<Event> = ring.toList()

    @Synchronized
    fun clear() = ring.clear()

    private fun render(category: String, fields: Map<String, Any?>): String =
        buildString {
            append(category)
            for ((k, v) in fields) {
                append(' ')
                append(k)
                append('=')
                append(v)
            }
        }
}
