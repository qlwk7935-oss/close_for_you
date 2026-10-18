package kr.closeforyou.ui.voice.overlay

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PromptSpeechTest {
    private val bounds = ScreenBounds(0, 0, 10, 10)
    private fun text(value: String) = ExtractedText(value, bounds, TextSource.ACCESSIBILITY_TREE, null)

    @Test fun contentKeepsReadingOrderAndDropsBlankLines() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            texts = persistentListOf(text(" 첫 줄 "), text("  "), text("둘째 줄")),
        )
        assertEquals("첫 줄\n둘째 줄", PromptSpeech.contentOrNull(analysis))
    }

    @Test fun noReadableTextGivesNull() {
        val analysis = MockPopupFixtures.notice().analysis.copy(texts = persistentListOf())
        assertNull(PromptSpeech.contentOrNull(analysis))
    }
}
