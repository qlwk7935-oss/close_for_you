package kr.closeforyou.ui.voice

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

/**
 * Minimal theme for windows C owns (the choice overlay, C debug/preview screens).
 * Follows the system dark setting with Material's default schemes only: no C-specific colors, so
 * it can be swapped for the app-wide theme A introduces at integration (A, 10/19).
 * C screens inside A's activity (first run, settings via UiVoiceAppRoot) also use it, for dark mode.
 * REPLACE-WITH-APP-THEME
 */
@Composable
fun UiVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = uiVoiceColorScheme(isSystemInDarkTheme()), content = content)
}

/** Separate so the contrast of both schemes can be checked in a JVM test. */
fun uiVoiceColorScheme(dark: Boolean): ColorScheme = if (dark) darkColorScheme() else lightColorScheme()
