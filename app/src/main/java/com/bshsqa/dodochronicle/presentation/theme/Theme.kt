package com.bshsqa.dodochronicle.presentation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Peach40,
    onPrimary = Color.White,
    primaryContainer = Peach90,
    onPrimaryContainer = Coral30,
    secondary = Sage40,
    onSecondary = Color.White,
    secondaryContainer = Sage90,
    onSecondaryContainer = Color(0xFF0A2112),
    surface = Neutral99,
    onSurface = Neutral10,
    surfaceVariant = Color(0xFFF4EDE8),
    onSurfaceVariant = Color(0xFF52443F),
    background = Neutral99,
    onBackground = Neutral10,
    error = ErrorRed,
    onError = Color.White,
    errorContainer = ErrorRedContainer,
    onErrorContainer = Color(0xFF410002),
    outline = Color(0xFF857370)
)

private val DarkColorScheme = darkColorScheme(
    primary = Peach80,
    onPrimary = Coral30,
    primaryContainer = Coral50,
    onPrimaryContainer = Peach90,
    secondary = Sage80,
    onSecondary = Color(0xFF1A3A1A),
    secondaryContainer = Sage40,
    onSecondaryContainer = Sage90,
    surface = Color(0xFF1A1C18),
    onSurface = Neutral90,
    background = Color(0xFF1A1C18),
    onBackground = Neutral90,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun DodoTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (darkTheme) DarkColorScheme else LightColorScheme
    MaterialTheme(colorScheme = colors, typography = DodoTypography, content = content)
}
