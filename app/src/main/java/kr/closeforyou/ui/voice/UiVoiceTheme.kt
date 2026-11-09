package kr.closeforyou.ui.voice

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import kr.closeforyou.ui.voice.theme.UiVoicePalette

/**
 * Theme for windows C owns (the choice overlay, first run, settings via UiVoiceAppRoot).
 * F63: Material's scheme is filled from C's design tokens ([UiVoicePalette]), so dialogs, text fields and checkboxes
 * take the same colors as C's own drawn parts. Follows the system dark setting (both palettes exist).
 * REPLACE-WITH-APP-THEME
 */
@Composable
fun UiVoiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = uiVoiceColorScheme(isSystemInDarkTheme()), content = content)
}

/** Separate so the contrast of both schemes can be checked in a JVM test. */
fun uiVoiceColorScheme(dark: Boolean): ColorScheme {
    val p = UiVoicePalette.of(dark)
    // surfaceTint = the card color: an elevated surface (dialogs, the prompt) is not tinted orange.
    // Material's own text in primary (dialog buttons, checkboxes, text fields) needs 4.5:1, which #E8683A is not (3.24:1 on white):
    // light uses the darker selected orange, dark a lighter orange with dark text on it. C's main buttons draw p.main themselves.
    return if (dark) {
        darkColorScheme(
            primary = DARK_PRIMARY, onPrimary = p.screen, primaryContainer = p.sub, onPrimaryContainer = p.onSub,
            secondary = p.onSub, onSecondary = p.screen, secondaryContainer = p.sub, onSecondaryContainer = p.onSub,
            background = p.screen, onBackground = p.ink, surface = p.card, onSurface = p.ink,
            surfaceVariant = p.sub, onSurfaceVariant = p.muted, surfaceTint = p.card,
            outline = p.ink, outlineVariant = p.disabledLine,
            surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.card,
            surfaceContainerLow = p.card, surfaceContainerLowest = p.card,
        )
    } else {
        lightColorScheme(
            primary = p.selected, onPrimary = p.onSelected, primaryContainer = p.sub, onPrimaryContainer = p.onSub,
            secondary = p.onSub, onSecondary = p.card, secondaryContainer = p.sub, onSecondaryContainer = p.onSub,
            background = p.screen, onBackground = p.ink, surface = p.card, onSurface = p.ink,
            surfaceVariant = p.sub, onSurfaceVariant = p.muted, surfaceTint = p.card,
            outline = p.ink, outlineVariant = p.disabledLine,
            surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.card,
            surfaceContainerLow = p.card, surfaceContainerLowest = p.card,
        )
    }
}

/** Dark scheme primary: 6.18:1 on the dark card, 6.98:1 under the dark screen color as its text. */
private val DARK_PRIMARY = androidx.compose.ui.graphics.Color(0xFFF08A5D)
