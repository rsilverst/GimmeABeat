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

    private val _cadence = MutableStateFlow<Int?>(null)
    val cadence: StateFlow<Int?> = _cadence.asStateFlow()

    /** Wall-clock time of the last non-null reading, for staleness detection. */
    private val _lastReadingAtMs = MutableStateFlow<Long?>(null)
    val lastReadingAtMs: StateFlow<Long?> = _lastReadingAtMs.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _nowPlaying = MutableStateFlow<String?>(null)
    val nowPlaying: StateFlow<String?> = _nowPlaying.asStateFlow()

    /** Mirrors the phone's selected signal source. Defaults to heart rate. */
    private val _signalSourceKey = MutableStateFlow("hr")
    val signalSourceKey: StateFlow<String> = _signalSourceKey.asStateFlow()

    fun setTracking(value: Boolean) { _tracking.value = value }
    fun setHeartRate(bpm: Int?) {
        _heartRate.value = bpm
        if (bpm != null) _lastReadingAtMs.value = System.currentTimeMillis()
    }
    fun setCadence(spm: Int?) {
        _cadence.value = spm
        if (spm != null) _lastReadingAtMs.value = System.currentTimeMillis()
    }
    fun setStatus(text: String?) { _status.value = text }
    fun setNowPlaying(text: String?) { _nowPlaying.value = text }
    fun setSignalSourceKey(key: String) { _signalSourceKey.value = key }

    fun reset() {
        _tracking.value = false
        _heartRate.value = null
        _cadence.value = null
        _lastReadingAtMs.value = null
        _status.value = null
        _nowPlaying.value = null
        // keep _signalSourceKey — it's a UI preference, not session data
    }
}
