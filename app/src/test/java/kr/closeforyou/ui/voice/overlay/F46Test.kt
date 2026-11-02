package kr.closeforyou.ui.voice.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F46: why the prompt's first TalkBack focus did or did not come (우진 SM-F966N, 11번가 결정 팝업). */
class F46Test {
    @Test fun windowMatches() {
        assertEquals(PromptFocusOutcome.NO_OWN_WINDOW, PromptFocusRules.windowOutcome(0))
        assertNull(PromptFocusRules.windowOutcome(1))
        assertEquals(PromptFocusOutcome.MANY_OWN_WINDOWS, PromptFocusRules.windowOutcome(2))
    }

    @Test fun refusedIsAskedOnceMoreAfterClearing() {
        // The Galaxy: the node's stale flag refuses the first ask; cleared, the second is accepted.
        val answers = ArrayDeque(listOf(false, true))
        var cleared = 0
        assertEquals(PromptFocusOutcome.AFTER_CLEAR,
            PromptFocusRules.nodeOutcome(true, { false }, { answers.removeFirst() }, { cleared += 1 }))
        assertEquals(1, cleared)
        // Refused twice.
        assertEquals(PromptFocusOutcome.REFUSED, PromptFocusRules.nodeOutcome(true, { false }, { false }, {}))
    }

    @Test fun acceptedOrAlreadyThereIsNotCleared() {
        var cleared = 0
        assertEquals(PromptFocusOutcome.OK, PromptFocusRules.nodeOutcome(true, { false }, { true }, { cleared += 1 }))
        // The system's focus is on the node already: not cleared (TalkBack would read it again).
        assertEquals(PromptFocusOutcome.ALREADY_FOCUSED, PromptFocusRules.nodeOutcome(true, { true }, { false }, { cleared += 1 }))
        assertEquals(0, cleared)
        var asked = 0
        assertEquals(PromptFocusOutcome.NOT_VISIBLE, PromptFocusRules.nodeOutcome(false, { false }, { asked += 1; true }, {}))
        assertEquals(0, asked)
    }

    @Test fun movedOnlyForTheThreeGoodOutcomes() {
        val moved = PromptFocusOutcome.entries.filter { it.moved }.toSet()
        assertEquals(setOf(PromptFocusOutcome.OK, PromptFocusOutcome.AFTER_CLEAR, PromptFocusOutcome.ALREADY_FOCUSED), moved)
        assertFalse(PromptFocusOutcome.REFUSED.moved)
        assertTrue(PromptFocusOutcome.AFTER_CLEAR.moved)
    }

    @Test fun oneLineOfCodesAndCounts() {
        val tally = PromptFocusTally()
        repeat(3) { tally.add(PromptFocusOutcome.NO_OWN_WINDOW, OwnWindowCounts(1, 1, 0)) }
        tally.add(PromptFocusOutcome.AFTER_CLEAR, null)
        assertEquals("PROMPT_FOCUS target=FIRST_ITEM result=AFTER_CLEAR attempts=4 tries=[AFTER_CLEAR:1,NO_OWN_WINDOW:3] own=1 titleNull=1 titleOther=0",
            tally.line(TakeoverFocusTarget.FIRST_ITEM, 4))
        tally.clear()
        assertEquals("PROMPT_FOCUS target=CHOICE_BUTTON result=NONE attempts=0 tries=[]", tally.line(TakeoverFocusTarget.CHOICE_BUTTON, 0))
    }
}
