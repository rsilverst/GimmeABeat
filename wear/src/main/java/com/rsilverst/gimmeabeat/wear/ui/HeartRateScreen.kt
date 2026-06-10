package com.rsilverst.gimmeabeat.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text

@Composable
fun HeartRateScreen(
    tracking: Boolean,
    heartRate: Int?,
    status: String?,
    nowPlaying: String?,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onStartTracking: () -> Unit,
    onStopTracking: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            !permissionsGranted -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = "Heart rate access needed",
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRequestPermissions) { Text("Allow") }
                Text(
                    text = "Tap didn't work?",
                    fontSize = 10.sp,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            }

            !tracking -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = "Not tracking",
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onStartTracking) { Text("Start") }
            }

            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = heartRate?.toString() ?: "—",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary,
                )
                Text(
                    text = "bpm",
                    fontSize = 12.sp,
                    color = MaterialTheme.colors.onBackground,
                )
                nowPlaying?.let {
                    Text(
                        text = it,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                status?.let {
                    Text(text = it, fontSize = 10.sp, textAlign = TextAlign.Center)
                }
                Button(onClick = onStopTracking) { Text("Stop") }
            }
        }
    }
}
