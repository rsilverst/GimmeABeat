package com.rsilverst.gimmeabeat.telemetry

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Running tallies of core-loop outcomes and failures, complementing the
 * per-event telemetry in [Telemetry]. Individual events answer "what happened
 * just now"; these counters answer "how often" — so BPM/genre dead zones and
 * watch dropouts show up as rates over a session, not just scattered log lines.
 *
 * Snapshots are emitted through the same [Telemetry] surface (category
 * `counters`), so there is still one place to read everything. Reset per
 * auto-mode session so the tallies describe the current run.
 */
object Counters {

    // Stable, low-cardinality counter names.
    const val FIND_PREFIX = "find_"        // find_found / find_no_bpm_candidates / find_no_spotify_match
    const val PLAY_PREFIX = "play_"        // play_playing / play_no_active_device / play_premium_required / …
    const val SIGNAL_ABSENT = "signal_absent"     // pick fell back to DEFAULT_HR (no live signal)
    const val WATCH_UNREACHABLE = "watch_unreachable" // no connected watch node (or lookup failed)
    const val WATCH_SEND_FAILED = "watch_send_failed" // a node existed but the message send threw

    private val counts = ConcurrentHashMap<String, AtomicLong>()

    fun increment(name: String, by: Long = 1) {
        counts.computeIfAbsent(name) { AtomicLong(0) }.addAndGet(by)
    }

    /** Current tallies, sorted by name for stable log output. */
    fun snapshot(): Map<String, Long> =
        counts.entries.sortedBy { it.key }.associate { it.key to it.value.get() }

    fun reset() = counts.clear()

    /** Emit the current tallies through the shared telemetry surface. */
    fun logSnapshot(label: String) {
        val snap = snapshot()
        if (snap.isEmpty()) return
        Telemetry.log(
            "counters",
            buildMap {
                put("label", label)
                putAll(snap)
            },
        )
    }
}
