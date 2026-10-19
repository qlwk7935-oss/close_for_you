package kr.closeforyou.ui.voice.overlay

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.R
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.PressFailure
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.settings.AnnouncementDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun candidate(id: String, label: String, role: ButtonRole) =
        CloseCandidate(id, "1", null, label, bounds, EvidenceLevel.E2_LABELED_NODE, role)

    private val closeAndSkip = ChoicePromptPlanner.plan(
        MockPopupFixtures.advertisement().analysis.copy(
            closeCandidates = persistentListOf(candidate("c", "닫기", ButtonRole.CLOSE), candidate("s", "건너뛰기", ButtonRole.SKIP)),
        ),
        PopupClassification.ADVERTISEMENT,
    )

    @Test fun shortIsSummaryThenWhere() {
        val parts = PromptSpeech.parts(closeAndSkip, AnnouncementDetail.SHORT)
        assertEquals(listOf(R.string.ui_choice_summary_ad, R.string.ui_choice_where), parts.map { it.resId })
    }

    @Test fun detailedAddsButtonNamesAndOptionCountOnly() {
        val parts = PromptSpeech.parts(closeAndSkip, AnnouncementDetail.DETAILED)
        assertEquals(
            listOf(
                R.string.ui_choice_summary_ad,
                R.string.ui_choice_detail_press_buttons,
                R.string.ui_choice_detail_option_count,
                R.string.ui_choice_where,
            ),
            parts.map { it.resId },
        )
        assertEquals(listOf("'닫기', '건너뛰기'"), parts[1].args)
        assertEquals(listOf(4), parts[2].args)
    }

    @Test fun detailedWithNoButtonsSaysCannotPressAndCount() {
        val prompt = ChoicePromptPlanner.plan(MockPopupFixtures.unlabeledButton().analysis, PopupClassification.UNKNOWN)
        val ids = PromptSpeech.parts(prompt, AnnouncementDetail.DETAILED).map { it.resId }
        assertTrue(R.string.ui_choice_cannot_press in ids)
        assertFalse(R.string.ui_choice_detail_press_buttons in ids)
        assertTrue(R.string.ui_choice_detail_option_count in ids)
    }

    @Test fun followUpSpeaksFailureNotSummary() {
        val followUp = ChoicePromptPlanner.afterFailedPress(closeAndSkip, PressFailure.PRESS_FAILED)!!
        val ids = PromptSpeech.parts(followUp, AnnouncementDetail.SHORT).map { it.resId }
        assertEquals(listOf(R.string.ui_result_press_failed, R.string.ui_result_next, R.string.ui_choice_where), ids)
    }
}
