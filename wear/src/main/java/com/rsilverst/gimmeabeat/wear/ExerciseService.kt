package com.rsilverst.gimmeabeat.wear

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

class ExerciseService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var exerciseClient: ExerciseClient
    private lateinit var publisher: HeartRatePublisher

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val points = update.latestMetrics.getData(DataType.HEART_RATE_BPM)
            val latest = points.lastOrNull() ?: return
            val bpm = latest.value.toInt()
            WatchTrackingState.setHeartRate(bpm)
            scope.launch { publisher.publish(bpm) }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}

        override fun onAvailabilityChanged(
            dataType: DataType<*, *>,
            availability: Availability,
        ) {
            if (dataType == DataType.HEART_RATE_BPM && availability is DataTypeAvailability) {
                if (availability != DataTypeAvailability.AVAILABLE) {
                    WatchTrackingState.setStatus("Sensor: ${availability.name}")
                } else {
                    WatchTrackingState.setStatus(null)
                }
            }
        }

        override fun onRegistered() {
            Log.d(TAG, "exercise update callback registered")
        }

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.w(TAG, "exercise update callback registration failed", throwable)
            WatchTrackingState.setStatus("Registration failed: ${throwable.message}")
        }
    }

    override fun onCreate() {
        super.onCreate()
        exerciseClient = HealthServices.getClient(this).exerciseClient
        publisher = HeartRatePublisher(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopTracking()
                return START_NOT_STICKY
            }
            else -> {
                startAsForeground()
                startTracking()
            }
        }
        return START_STICKY
    }

    private fun startAsForeground() {
        ensureChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun startTracking() {
        if (WatchTrackingState.tracking.value) return
        WatchTrackingState.setTracking(true)
        scope.launch {
            try {
                exerciseClient.setUpdateCallback(callback)
                val config = ExerciseConfig.builder(ExerciseType.WORKOUT)
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                    .setIsAutoPauseAndResumeEnabled(false)
                    .setIsGpsEnabled(false)
                    .build()
                exerciseClient.startExerciseAsync(config).await()
            } catch (t: Throwable) {
                Log.w(TAG, "startExerciseAsync failed", t)
                WatchTrackingState.setStatus("Start error: ${t.message}")
                WatchTrackingState.setTracking(false)
            }
        }
    }

    private fun stopTracking() {
        scope.launch {
            runCatching { exerciseClient.endExerciseAsync().await() }
                .onFailure { Log.w(TAG, "endExerciseAsync failed", it) }
            runCatching { exerciseClient.clearUpdateCallbackAsync(callback).await() }
                .onFailure { Log.w(TAG, "clearUpdateCallback failed", it) }
            WatchTrackingState.reset()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ExerciseService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("GimmeABeat")
            .setContentText("Tracking heart rate")
            .setOngoing(true)
            .setContentIntent(openAppIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "HR tracking",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.rsilverst.gimmeabeat.wear.action.STOP_TRACKING"
        private const val CHANNEL_ID = "hr_tracking"
        private const val NOTIF_ID = 2001
        private const val TAG = "ExerciseService"
    }
}
