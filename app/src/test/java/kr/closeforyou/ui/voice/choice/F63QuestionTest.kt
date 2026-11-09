package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.R
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** F63-3: only a prompt asking about a nameless close button (the X) gets the new question; every other prompt keeps its words. */
class F63QuestionTest {
    private val x = "X 모양 닫기"
    private val base = ChoicePromptPlanner.plan(MockPopupFixtures.notice().analysis, PopupClassification.NOTICE)

    private fun with(vararg options: PromptOption, brief: Boolean = false) = base.copy(options = persistentListOf(*options), briefQuestion = brief)

    private val keep = PromptOption(PromptOptionKind.KEEP_OPEN)

    @Test fun theUnnamedXGetsTheNewQuestion() {
        val press = PromptOption(PromptOptionKind.PRESS_CANDIDATE, candidateId = "x", candidateRole = ButtonRole.CLOSE, buttonLabel = x)
        assertEquals(R.string.ui_choice_takeover_question_unnamed_x, ChoicePromptText.questionRes(with(press, keep), x))
        // A label of icon glyphs only is nameless too (shown as "X 모양 닫기").
        val glyph = press.copy(buttonLabel = "\uEA02")
        assertEquals(R.string.ui_choice_takeover_question_unnamed_x, ChoicePromptText.questionRes(with(glyph, keep), x))
    }

    @Test fun namedClosesAndOtherPromptsKeepTheOldQuestion() {
        val named = PromptOption(PromptOptionKind.PRESS_CANDIDATE, candidateId = "c", candidateRole = ButtonRole.CLOSE, buttonLabel = "닫기")
        assertEquals(R.string.ui_choice_takeover_question, ChoicePromptText.questionRes(with(named, keep), x))
        // No press (decisions, scam warnings, cannot press): unchanged.
        assertEquals(R.string.ui_choice_takeover_question, ChoicePromptText.questionRes(with(keep), x))
        // The X offered as a move only (not a press): unchanged.
        val move = PromptOption(PromptOptionKind.MOVE_TO_BUTTON, candidateId = "x", buttonLabel = x)
        assertEquals(R.string.ui_choice_takeover_question, ChoicePromptText.questionRes(with(move, keep), x))
        for (f in listOf(MockPopupFixtures.decision(), MockPopupFixtures.advertisement())) {
            for (c in PopupClassification.entries) {
                assertEquals(R.string.ui_choice_takeover_question, ChoicePromptText.questionRes(ChoicePromptPlanner.plan(f.analysis, c), x))
            }
        }
    }

    @Test fun theBriefQuestionStays() {
        val press = PromptOption(PromptOptionKind.PRESS_CANDIDATE, candidateId = "x", candidateRole = ButtonRole.CLOSE, buttonLabel = x)
        assertEquals(R.string.ui_choice_takeover_question_short, ChoicePromptText.questionRes(with(press, brief = true), x))
    }
}
