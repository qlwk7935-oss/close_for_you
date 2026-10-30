package kr.closeforyou.ui.voice.overlay

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.R
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.SpeechHandlingListener
import org.junit.Assert.assertEquals
import org.junit.Test

/** [내용 다시 읽기] and the auto title: same words, made readable ([kr.closeforyou.ui.voice.SpeakableText]). */
class ContentSpeechTest {
    private val bounds = ScreenBounds(0, 0, 10, 10)
    private fun text(value: String) = ExtractedText(value, bounds, TextSource.ACCESSIBILITY_TREE, null)

    private val format: (Int, List<Any>) -> String = { res, args ->
        when (res) {
            R.string.ui_content_none -> "NONE"
            R.string.ui_choice_role_close -> "닫기"
            R.string.ui_content_button_real_name -> "REAL(${args.joinToString("|")})"
            else -> "?"
        }
    }

    @Test fun contentIsMadeReadable() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            texts = persistentListOf(text("서버 점검 안내"), text("02:00~04:00")),
            closeCandidates = persistentListOf(),
        )
        assertEquals("서버 점검 안내.\n오전 2시부터 오전 4시까지", PromptSpeech.content(analysis, format))
    }

    @Test fun onlyDecorationsReadAsNothing() {
        val analysis = MockPopupFixtures.notice().analysis.copy(texts = persistentListOf(text("★ ♥")), closeCandidates = persistentListOf())
        assertEquals("NONE", PromptSpeech.content(analysis, format))
    }

    @Test fun keptButtonNamesAddNoLine() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            texts = persistentListOf(text("가을 할인")),
            closeCandidates = persistentListOf(CloseCandidate("c", "1", null, "닫기", bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)),
        )
        assertEquals("가을 할인", PromptSpeech.content(analysis, format))
    }

    @Test fun titleIsMadeReadableBeforeShortening() =
        assertEquals("오늘만 특가 이벤트", SpeechHandlingListener.spokenTitle("★오늘만 특가★ 이벤트"))

    @Test fun titleWithLongNumberStaysSilent() =
        assertEquals(null, SpeechHandlingListener.spokenTitle("문의 010-1234-5678"))
}
