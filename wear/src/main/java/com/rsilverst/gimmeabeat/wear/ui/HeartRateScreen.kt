package com.rsilverst.gimmeabeat.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.rsilverst.gimmeabeat.wear.HeartRateUiState
import com.rsilverst.gimmeabeat.wear.HeartRateViewModel

@Composable
fun HeartRateScreen(
    viewModel: HeartRateViewModel,
    onRequestPermission: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colors.background)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (val s = state) {
            HeartRateUiState.CheckingPermission -> Text("…")

            HeartRateUiState.PermissionRequired -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
            ) {
                Text(
                    text = "Heart rate access needed",
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRequestPermission) {
                    Text("Allow")
                }
            }

            HeartRateUiState.Connecting -> Text("Connecting…")

            is HeartRateUiState.Active -> Column(
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${s.bpm}",
                    fontSize = 56.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colors.primary,
                )
                Text(
                    text = "bpm",
                    fontSize = 14.sp,
                    color = MaterialTheme.colors.onBackground,
                )
            }

            is HeartRateUiState.Unavailable -> Text(
                text = s.message,
                textAlign = TextAlign.Center,
            )

            HeartRateUiState.NotSupported -> Text(
                text = "No HR sensor on this device",
                textAlign = TextAlign.Center,
            )
        }
    }
}
