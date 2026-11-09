package kr.closeforyou.ui.voice.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kr.closeforyou.ui.voice.theme.UiVoicePalette.PairKind
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F63: WCAG 2.x contrast ((L1 + 0.05) / (L2 + 0.05), relative luminance) for every text/background pair of C's tokens,
 * light and dark. Body ≥ 4.5:1, large text (≥ 18sp bold) and non-text ≥ 3:1; decorative and disabled pairs are printed only.
 * The table is printed to the test output (build/test-results) for the report.
 */
class UiVoiceDesignContrastTest {
    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private fun check(dark: Boolean) {
        val mode = if (dark) "다크" else "라이트"
        val failures = mutableListOf<String>()
        UiVoicePalette.of(dark).textPairs().forEach { pair ->
            val ratio = contrast(pair.fg, pair.bg)
            val need = when (pair.kind) {
                PairKind.BODY -> 4.5
                PairKind.LARGE, PairKind.NON_TEXT -> 3.0
                PairKind.DECORATIVE, PairKind.DISABLED -> null
            }
            val verdict = when {
                need == null -> "보고만"
                ratio >= need -> "통과(≥$need)"
                else -> "미달(<$need)"
            }
            println("CONTRAST | $mode | ${pair.name} | ${"%.2f".format(ratio)} | ${pair.kind} | $verdict")
            if (need != null && ratio < need) failures += "$mode ${pair.name} = ${"%.2f".format(ratio)} < $need"
        }
        assertTrue(failures.joinToString("; "), failures.isEmpty())
    }

    @Test fun lightTokensMeetContrast() = check(dark = false)

    @Test fun darkTokensMeetContrast() = check(dark = true)

    /** The asked colors, checked once so the report can say why two were adjusted. */
    @Test fun adjustedColorsAreDarkerThanAsked() {
        val askedMuted = Color(0xFF9A6B55)
        val askedMain = Color(0xFFE8683A)
        val light = UiVoicePalette.Light
        println("CONTRAST | 요청값 | 흐린 글자 #9A6B55/바탕 | ${"%.2f".format(contrast(askedMuted, light.screen))}")
        println("CONTRAST | 요청값 | 흐린 글자 #9A6B55/보조 상자 | ${"%.2f".format(contrast(askedMuted, light.sub))}")
        println("CONTRAST | 요청값 | 흰 글자/주황 #E8683A | ${"%.2f".format(contrast(Color.White, askedMain))}")
        assertTrue(contrast(askedMuted, light.screen) < 4.5)
        assertTrue(contrast(light.muted, light.screen) >= 4.5)
        assertTrue(contrast(Color.White, askedMain) < 4.5)
        assertTrue(contrast(light.onSelected, light.selected) >= 4.5)
    }
}
