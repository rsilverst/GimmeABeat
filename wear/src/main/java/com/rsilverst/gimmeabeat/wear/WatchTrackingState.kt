package com.rsilverst.gimmeabeat.wear

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton state for the watch's exercise/HR tracking. Written by
 * [ExerciseService] and read by the UI.
 */
object WatchTrackingState {

    private val _tracking = MutableStateFlow(false)
    val tracking: StateFlow<Boolean> = _tracking.asStateFlow()

    private val _heartRate = MutableStateFlow<Int?>(null)
    val heartRate: StateFlow<Int?> = _heartRate.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    fun setTracking(value: Boolean) { _tracking.value = value }
    fun setHeartRate(bpm: Int?) { _heartRate.value = bpm }
    fun setStatus(text: String?) { _status.value = text }

    fun reset() {
        _tracking.value = false
        _heartRate.value = null
        _status.value = null
    }
}
