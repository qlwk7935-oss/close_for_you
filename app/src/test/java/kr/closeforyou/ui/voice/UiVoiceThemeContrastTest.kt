package kr.closeforyou.ui.voice

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Test

/** KWCAG/WCAG text contrast ≥ 4.5:1 for the colors C's prompt and settings actually use. */
class UiVoiceThemeContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    /** The prompt is a Surface with tonalElevation 6dp, so its background is tinted. */
    private fun ColorScheme.promptBackground() = surfaceColorAtElevation(6.dp).compositeOver(background)

    private fun check(dark: Boolean) {
        val s = uiVoiceColorScheme(dark)
        val pairs = mapOf(
            "본문/화면" to (s.onSurface to s.surface),
            "본문/선택 창" to (s.onSurface to s.promptBackground()),
            "누르기 버튼 글자/버튼" to (s.onPrimary to s.primary),
            "외곽선 버튼 글자/선택 창" to (s.primary to s.promptBackground()),
            "외곽선 버튼 글자/화면" to (s.primary to s.surface),
            "보조 글자/화면" to (s.onSurfaceVariant to s.surface),
        )
        pairs.forEach { (name, pair) ->
            val ratio = contrast(pair.first, pair.second)
            assertTrue("${if (dark) "다크" else "라이트"} $name = ${"%.2f".format(ratio)}", ratio >= 4.5)
        }
    }

    @Test fun lightSchemeMeetsTextContrast() = check(dark = false)

    @Test fun darkSchemeMeetsTextContrast() = check(dark = true)
}
