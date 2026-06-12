package com.rsilverst.gimmeabeat.telemetry

import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * Measures how regularly a watch signal (heart rate or cadence) actually lands
 * on the phone, and emits a percentile summary to [Telemetry] every
 * [flushIntervalMs]. One instance per signal source.
 *
 * We deliberately measure phone-side *inter-arrival gaps* — the time between
 * successive readings reaching us — rather than true watch→phone latency.
 * The watch and phone clocks aren't synchronized, so subtracting a watch
 * send-timestamp from a phone receive-timestamp would yield meaningless (often
 * negative) numbers. Inter-arrival gaps need only one clock and are exactly
 * what diagnose the "signal lag → stale song picks" failure mode: when gaps
 * balloon, the value auto-mode matches against is stale.
 *
 * Durations use [SystemClock.elapsedRealtime] (monotonic) so a wall-clock
 * adjustment mid-workout can't corrupt a measurement.
 */
class SignalArrivalMeter(
    private val source: String,
    private val flushIntervalMs: Long = 30_000L,
) {

    private val gapsMs = ArrayList<Long>()
    private var lastArrivalMs = NONE
    private var windowStartMs = NONE

    /** Call once per reading received from the watch. */
    @Synchronized
    fun onArrival() {
        val now = SystemClock.elapsedRealtime()
        if (lastArrivalMs != NONE) gapsMs.add(now - lastArrivalMs)
        lastArrivalMs = now
        if (windowStartMs == NONE) windowStartMs = now
        if (now - windowStartMs >= flushIntervalMs) {
            flush()
            windowStartMs = now
        }
    }

    private fun flush() {
        if (gapsMs.isEmpty()) return
        val sorted = gapsMs.sorted()
        Telemetry.log(
            "signal_latency",
            mapOf(
                "source" to source,
                "readings" to sorted.size,
                "gapP50Ms" to percentile(sorted, 50),
                "gapP95Ms" to percentile(sorted, 95),
                "gapP99Ms" to percentile(sorted, 99),
                "gapMaxMs" to sorted.last(),
            ),
        )
        gapsMs.clear()
    }

    private fun percentile(sortedAsc: List<Long>, p: Int): Long {
        if (sortedAsc.isEmpty()) return 0
        val idx = ((p / 100.0) * (sortedAsc.size - 1)).roundToInt()
        return sortedAsc[idx.coerceIn(0, sortedAsc.size - 1)]
    }

    private companion object {
        const val NONE = -1L
    }
}
