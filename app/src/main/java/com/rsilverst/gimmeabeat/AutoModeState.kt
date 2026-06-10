package com.rsilverst.gimmeabeat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton state for auto mode, shared between [AutoModeService] (writer) and
 * the UI (reader). Survives the service lifecycle so the UI can still observe
 * paused/stopped state for one composition cycle.
 */
object AutoModeState {

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying.asStateFlow()

    fun setActive(value: Boolean) { _active.value = value }
    fun setStatus(text: String?) { _status.value = text }
    fun setNowPlaying(value: NowPlaying?) { _nowPlaying.value = value }

    fun reset() {
        _active.value = false
        _status.value = null
    }
}
