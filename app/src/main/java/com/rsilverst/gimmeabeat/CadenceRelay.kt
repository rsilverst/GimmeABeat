package com.rsilverst.gimmeabeat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CadenceReading(val stepsPerMinute: Int, val receivedAtMs: Long)

/** Same shape as [HeartRateRelay] but for steps-per-minute. */
object CadenceRelay {

    private const val WINDOW_MS = 10_000L

    private val _cadence = MutableStateFlow<CadenceReading?>(null)
    val cadence: StateFlow<CadenceReading?> = _cadence.asStateFlow()

    private val _smoothedSpm = MutableStateFlow<Int?>(null)
    val smoothedSpm: StateFlow<Int?> = _smoothedSpm.asStateFlow()

    private val recent = ArrayDeque<Pair<Long, Int>>()

    @Synchronized
    fun update(spm: Int) {
        val now = System.currentTimeMillis()
        _cadence.value = CadenceReading(stepsPerMinute = spm, receivedAtMs = now)
        recent.addLast(now to spm)
        while (recent.isNotEmpty() && now - recent.first().first > WINDOW_MS) {
            recent.removeFirst()
        }
        _smoothedSpm.value = recent.map { it.second }.average().toInt()
    }
}
