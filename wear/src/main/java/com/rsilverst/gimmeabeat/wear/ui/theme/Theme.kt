package com.rsilverst.gimmeabeat.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

private val WearColors = Colors(
    primary = Color(0xFFFF5252),
    primaryVariant = Color(0xFFD32F2F),
    secondary = Color(0xFF80DEEA),
    secondaryVariant = Color(0xFF00ACC1),
    background = Color.Black,
    surface = Color(0xFF1A1A1A),
    error = Color(0xFFCF6679),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    onSurfaceVariant = Color(0xFFB0B0B0),
    onError = Color.Black,
)

@Composable
fun GimmeABeatWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WearColors, content = content)
}
