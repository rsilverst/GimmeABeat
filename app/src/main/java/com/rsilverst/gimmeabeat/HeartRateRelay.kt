package com.rsilverst.gimmeabeat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HeartRateReading(val bpm: Int, val receivedAtMs: Long)

object HeartRateRelay {

    private val _heartRate = MutableStateFlow<HeartRateReading?>(null)
    val heartRate: StateFlow<HeartRateReading?> = _heartRate.asStateFlow()

    fun update(bpm: Int) {
        _heartRate.value = HeartRateReading(bpm = bpm, receivedAtMs = System.currentTimeMillis())
    }
}
