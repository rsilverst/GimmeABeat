package com.rsilverst.gimmeabeat.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
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
        // Surface the battery-opt prompt early so users don't have to start
        // tracking to discover that Samsung will throttle the publisher.
        if (permissionsGranted.value) promptBatteryOptOnceIfNeeded()
        setContent {
            GimmeABeatWearTheme {
                val tracking by WatchTrackingState.tracking.collectAsState()
                val hr by WatchTrackingState.heartRate.collectAsState()
                val cadence by WatchTrackingState.cadence.collectAsState()
                val sourceKey by WatchTrackingState.signalSourceKey.collectAsState()
                val status by WatchTrackingState.status.collectAsState()
                val nowPlaying by WatchTrackingState.nowPlaying.collectAsState()
                val granted by remember { permissionsGranted }
                val cadenceMode = sourceKey == "cadence"
                // Samsung One UI Watch pauses Health Services sensor delivery the
                // moment the display drops to ambient/dim. Keep the screen
                // interactive while a workout is active so HR/cadence keep
                // flowing — this is the same tradeoff every other Wear OS
                // fitness app makes during a session.
                LaunchedEffect(tracking) {
                    if (tracking) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                HeartRateScreen(
                    tracking = tracking,
                    signalValue = if (cadenceMode) cadence else hr,
                    signalLabel = if (cadenceMode) "Cadence" else "Heart rate",
                    signalUnit = if (cadenceMode) "spm" else "bpm",
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
        // Service starts regardless — the prompt is informational and the user
        // may decline. Without exemption, Samsung will throttle the publisher
        // when the screen turns off; with it, sensors keep flowing.
        promptBatteryOptOnceIfNeeded()
        val intent = Intent(this, ExerciseService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun promptBatteryOptOnceIfNeeded() {
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Log.d(TAG, "battery opt: already ignored")
            return
        }
        val prefs = getSharedPreferences(PREFS_FILE, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_BATT_OPT_PROMPTED, false)) {
            Log.d(TAG, "battery opt: already prompted previously, skipping")
            return
        }
        // Samsung's One UI Watch routes battery optimization through its own
        // Settings rather than honoring the standard intents, so we try the
        // direct request first, fall back to the system "Battery optimization"
        // list, and finally to the app info page so the user can find it.
        val attempts = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        for (intent in attempts) {
            if (intent.resolveActivity(packageManager) == null) {
                Log.d(TAG, "battery opt: ${intent.action} has no handler")
                continue
            }
            try {
                startActivity(intent)
                prefs.edit().putBoolean(KEY_BATT_OPT_PROMPTED, true).apply()
                Log.d(TAG, "battery opt: launched ${intent.action}")
                return
            } catch (t: Throwable) {
                Log.w(TAG, "battery opt: ${intent.action} threw", t)
            }
        }
        Log.w(TAG, "battery opt: no fallback worked; user must enable manually")
    }

    private fun stopTracking() {
        val intent = Intent(this, ExerciseService::class.java).apply {
            action = ExerciseService.ACTION_STOP
        }
        startService(intent)
    }

    companion object {
        // Wear OS 4+ uses health.READ_HEART_RATE as the canonical sensor-access
        // permission; BODY_SENSORS is kept in the manifest only for backward compat
        // with older Wear OS. Don't runtime-request it on modern versions — its
        // system dialog is broken/deprecated and silently denies.
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.ACTIVITY_RECOGNITION,
            "android.permission.health.READ_HEART_RATE",
        )

        private const val TAG = "WearMainActivity"
        private const val PREFS_FILE = "watch_prefs"
        // v2 of the key so any stale "prompted" flag from an earlier build that
        // set it before startActivity succeeded gets ignored on first launch.
        private const val KEY_BATT_OPT_PROMPTED = "battery_opt_prompted_v2"
    }
}
