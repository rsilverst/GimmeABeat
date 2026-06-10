package com.rsilverst.gimmeabeat.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.rsilverst.gimmeabeat.wear.ui.HeartRateScreen
import com.rsilverst.gimmeabeat.wear.ui.theme.GimmeABeatWearTheme

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted.value = results.values.all { it }
        if (permissionsGranted.value) startTracking()
    }

    private val permissionsGranted = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionsGranted.value = REQUIRED_PERMISSIONS.all { p ->
            ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED
        }
        setContent {
            GimmeABeatWearTheme {
                val tracking by WatchTrackingState.tracking.collectAsState()
                val hr by WatchTrackingState.heartRate.collectAsState()
                val status by WatchTrackingState.status.collectAsState()
                val nowPlaying by WatchTrackingState.nowPlaying.collectAsState()
                val granted by remember { permissionsGranted }
                HeartRateScreen(
                    tracking = tracking,
                    heartRate = hr,
                    status = status,
                    nowPlaying = nowPlaying,
                    permissionsGranted = granted,
                    onRequestPermissions = {
                        requestPermissions.launch(REQUIRED_PERMISSIONS)
                    },
                    onOpenSettings = { openAppSettings() },
                    onStartTracking = { startTracking() },
                    onStopTracking = { stopTracking() },
                )
            }
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun startTracking() {
        val intent = Intent(this, ExerciseService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopTracking() {
        val intent = Intent(this, ExerciseService::class.java).apply {
            action = ExerciseService.ACTION_STOP
        }
        startService(intent)
    }

    companion object {
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BODY_SENSORS,
            "android.permission.health.READ_HEART_RATE",
        )
    }
}
