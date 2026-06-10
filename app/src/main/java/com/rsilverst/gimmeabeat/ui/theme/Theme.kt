package com.rsilverst.gimmeabeat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = BrandRed,
    onPrimary = Color.Black,
    primaryContainer = BrandRedDeep,
    onPrimaryContainer = Color(0xFFFFE5E5),
    secondary = Color(0xFFFFB4AB),
    background = DarkBackground,
    onBackground = Color(0xFFF1F1F1),
    surface = DarkSurface,
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFB7B7B7),
    outline = Color(0xFF555555),
)

private val LightColors = lightColorScheme(
    primary = BrandRedDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9D6),
    onPrimaryContainer = Color(0xFF410001),
    secondary = BrandRed,
    background = LightBackground,
    onBackground = Color(0xFF181717),
    surface = LightSurface,
    onSurface = Color(0xFF181717),
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = Color(0xFF5C5854),
    outline = Color(0xFFBDB8B4),
)

@Composable
fun GimmeABeatTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography,
        content = content,
    )
}
