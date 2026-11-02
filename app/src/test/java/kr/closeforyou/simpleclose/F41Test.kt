package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.overlay.PromptSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F41: [방금 닫은 내용 듣기] keeps the closed popup's words in memory only, and only while its card is up. */
class F41Test {
    @Test fun keptUntilForgottenAndNeverPrinted() {
        val last = LastClosedContent()
        assertNull(last.current())
        last.keep("가을 할인 쿠폰")
        assertEquals("가을 할인 쿠폰", last.current())
        assertEquals("가을 할인 쿠폰", last.current()) // a second press says it again
        assertFalse(last.toString().contains("쿠폰"))
        last.forget()
        assertNull(last.current())
    }

    @Test fun aBlankSentenceKeepsNothing() {
        val last = LastClosedContent()
        last.keep("오래된 글")
        last.keep("  ")
        assertNull(last.current())
    }

    @Test fun aPopupWithoutWordsOffersNoButton() {
        // The service offers the button only when the popup had words (PromptSpeech.contentOrNull).
        val analysis = MockPopupFixtures.advertisement().analysis
        assertNotNull(PromptSpeech.contentOrNull(analysis))
        assertNull(PromptSpeech.contentOrNull(analysis.copy(texts = persistentListOf())))
        val blank = analysis.copy(texts = persistentListOf(ExtractedText(" ", ScreenBounds(0, 0, 1, 1), TextSource.ACCESSIBILITY_TREE, null)))
        assertNull(PromptSpeech.contentOrNull(blank))
        assertTrue(PromptSpeech.content(analysis) { res, _ -> "r$res" }.isNotBlank())
    }
}
