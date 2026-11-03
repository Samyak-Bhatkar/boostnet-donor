package com.example.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = GoogleBluePrimary,
    onPrimary = GoogleBlueOnPrimary,
    primaryContainer = GoogleBlueContainer,
    onPrimaryContainer = GoogleBlueOnContainer,
    secondary = GoogleCyan,
    onSecondary = Color.Black,
    tertiary = GoogleGreen,
    background = AmoledBackground,
    onBackground = Color(0xFFE8EAED),
    surface = DarkSurface,
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = Color(0xFFC4C7C5),
    outline = DarkOutline,
    error = GoogleRed,
    onError = Color.Black
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    // Prefer AMOLED Dark theme
    val colorScheme = DarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = AmoledBackground.toArgb()
                window.navigationBarColor = AmoledBackground.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
