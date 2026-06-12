package com.rsilverst.gimmeabeat

import com.rsilverst.gimmeabeat.telemetry.SignalArrivalMeter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HeartRateReading(val bpm: Int, val receivedAtMs: Long)

/**
 * Holds the latest HR reading from the watch plus a rolling average over the
 * past [WINDOW_MS]. Auto-mode picks tracks against the smoothed value so a
 * single noisy reading can't whip-saw song choice.
 */
object HeartRateRelay {

    private const val WINDOW_MS = 10_000L

    private val _heartRate = MutableStateFlow<HeartRateReading?>(null)
    val heartRate: StateFlow<HeartRateReading?> = _heartRate.asStateFlow()

    private val _smoothedBpm = MutableStateFlow<Int?>(null)
    val smoothedBpm: StateFlow<Int?> = _smoothedBpm.asStateFlow()

    private val recent = ArrayDeque<Pair<Long, Int>>()
    private val arrivalMeter = SignalArrivalMeter("hr")

    @Synchronized
    fun update(bpm: Int) {
        arrivalMeter.onArrival()
        val now = System.currentTimeMillis()
        _heartRate.value = HeartRateReading(bpm = bpm, receivedAtMs = now)
        recent.addLast(now to bpm)
        while (recent.isNotEmpty() && now - recent.first().first > WINDOW_MS) {
            recent.removeFirst()
        }
        _smoothedBpm.value = recent.map { it.second }.average().toInt()
    }
}
