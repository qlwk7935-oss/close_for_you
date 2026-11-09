package kr.closeforyou.ui.voice.choice

import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.theme.InkGrade
import org.junit.Assert.assertEquals
import org.junit.Test

/** F63: the new look groups buttons into lines, but never changes their order (TalkBack and focus follow layout order). */
class ChoicePromptLayoutTest {
    private fun opt(kind: PromptOptionKind) = PromptOption(kind)

    @Test fun readThenKeepShareALineInOrder() {
        val options = listOf(
            opt(PromptOptionKind.PRESS_CANDIDATE), opt(PromptOptionKind.READ_CONTENT), opt(PromptOptionKind.KEEP_OPEN), opt(PromptOptionKind.STOP_ASKING),
        )
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), ChoicePromptLayout.rows(options))
    }

    @Test fun onlyThatExactPairIsJoined() {
        val keepFirst = listOf(opt(PromptOptionKind.KEEP_OPEN), opt(PromptOptionKind.READ_CONTENT))
        assertEquals(listOf(listOf(0), listOf(1)), ChoicePromptLayout.rows(keepFirst))
        val apart = listOf(opt(PromptOptionKind.READ_CONTENT), opt(PromptOptionKind.MOVE_TO_BUTTON), opt(PromptOptionKind.KEEP_OPEN))
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), ChoicePromptLayout.rows(apart))
        assertEquals(emptyList<List<Int>>(), ChoicePromptLayout.rows(emptyList()))
    }

    // Every planned prompt of the fixtures: the lines flattened are exactly the option order.
    @Test fun flattenedRowsAreAlwaysTheOptionOrder() {
        val fixtures = listOf(
            MockPopupFixtures.advertisement(), MockPopupFixtures.notice(), MockPopupFixtures.decision(), MockPopupFixtures.unlabeledButton(),
        )
        for (f in fixtures) for (c in PopupClassification.entries) {
            val prompt = ChoicePromptPlanner.plan(f.analysis, c)
            assertEquals(prompt.options.indices.toList(), ChoicePromptLayout.rows(prompt.options).flatten())
        }
    }

    @Test fun gradesFollowTheDesign() {
        assertEquals(InkGrade.MAIN, ChoicePromptLayout.gradeOf(PromptOptionKind.PRESS_CANDIDATE))
        assertEquals(InkGrade.SUB, ChoicePromptLayout.gradeOf(PromptOptionKind.REMEMBER_ALWAYS))
        assertEquals(InkGrade.SUB, ChoicePromptLayout.gradeOf(PromptOptionKind.MOVE_TO_BUTTON))
        assertEquals(InkGrade.LINE, ChoicePromptLayout.gradeOf(PromptOptionKind.READ_CONTENT))
        assertEquals(InkGrade.LINE, ChoicePromptLayout.gradeOf(PromptOptionKind.KEEP_OPEN))
        assertEquals(InkGrade.LINE, ChoicePromptLayout.gradeOf(PromptOptionKind.STOP_ASKING))
    }
}
