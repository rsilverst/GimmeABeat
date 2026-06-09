package com.rsilverst.gimmeabeat.wear

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface HeartRateUiState {
    data object CheckingPermission : HeartRateUiState
    data object PermissionRequired : HeartRateUiState
    data object Connecting : HeartRateUiState
    data class Active(val bpm: Int) : HeartRateUiState
    data class Unavailable(val message: String) : HeartRateUiState
    data object NotSupported : HeartRateUiState
}

class HeartRateViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = HeartRateRepository(app)
    private val publisher = HeartRatePublisher(app)

    private val _uiState = MutableStateFlow<HeartRateUiState>(HeartRateUiState.CheckingPermission)
    val uiState: StateFlow<HeartRateUiState> = _uiState.asStateFlow()

    private var collectionJob: Job? = null

    fun checkPermission(context: Context) {
        val granted = REQUIRED_PERMISSIONS.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) startCollection() else _uiState.value = HeartRateUiState.PermissionRequired
    }

    fun onPermissionResult(granted: Boolean) {
        if (granted) startCollection() else _uiState.value = HeartRateUiState.PermissionRequired
    }

    private fun startCollection() {
        collectionJob?.cancel()
        _uiState.value = HeartRateUiState.Connecting
        collectionJob = viewModelScope.launch {
            try {
                repository.heartRateUpdates().collect { update ->
                    _uiState.value = when (update) {
                        is HeartRateUpdate.Measured -> HeartRateUiState.Active(update.bpm)
                        is HeartRateUpdate.Unavailable -> HeartRateUiState.Unavailable(
                            "Sensor: ${update.reason.name}",
                        )
                        HeartRateUpdate.NotSupported -> HeartRateUiState.NotSupported
                    }
                    if (update is HeartRateUpdate.Measured) {
                        publisher.publish(update.bpm)
                    }
                }
            } catch (t: Throwable) {
                _uiState.value = HeartRateUiState.Unavailable(t.message ?: "Sensor error")
            }
        }
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BODY_SENSORS,
            "android.permission.health.READ_HEART_RATE",
        )

        val Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as Application
                HeartRateViewModel(app)
            }
        }
    }
}
