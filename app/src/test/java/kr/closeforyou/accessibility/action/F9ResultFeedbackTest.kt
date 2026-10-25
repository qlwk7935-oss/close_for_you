package kr.closeforyou.accessibility.action

import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.*
import org.junit.Assert.*
import org.junit.Test

class F9ResultFeedbackTest {
    private val token = SnapshotToken(1, 1, "test", "p", "s", "c", 1)

    @Test fun mechanicalSuccessWithoutV2OutcomeNeverClaimsClosure() {
        val result = ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true)
        assertEquals(PressFailure.NOT_CONFIRMED, PressResultSpeech.failureOf(result))
        assertEquals(HandledOutcome.NOT_CONFIRMED, AnnouncementBatcher.outcomeOf(result, ChoiceKind.CLOSE_ONCE))
        val confirmed = result.copy(outcome = ClickOutcome.CLOSED_CONFIRMED)
        assertNull(PressResultSpeech.failureOf(confirmed))
        assertEquals(HandledOutcome.CLOSED, AnnouncementBatcher.outcomeOf(confirmed, ChoiceKind.CLOSE_ONCE))
        assertEquals(PressFailure.NOT_CONFIRMED, PressResultSpeech.failureOf(result.copy(attempted = false)))
    }

    @Test fun onlyConfirmedRemovalUsesClosedWording() {
        for (outcome in ClickOutcome.entries) {
            val result = F9Result(token, true, true, outcome)
            assertEquals(outcome == ClickOutcome.CLOSED_CONFIRMED, "닫았어요" in F9ResultFeedback.request(result.toActionResult()).text)
        }
        val blocked = F9Result(token, false, block = F9Block.POLICY_UNAVAILABLE)
        assertFalse("닫았어요" in F9ResultFeedback.request(blocked.toActionResult()).text)
    }

    @Test fun sharedOutcomeDoesNotDependOnMechanicalReturnValue() {
        for (returned in listOf(true, false, null)) {
            val result = F9Result(token, true, returned, ClickOutcome.CLOSED_CONFIRMED).toActionResult()
            assertTrue(result.attempted)
            assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
            assertFalse(result.semanticSafetyConfirmedByUser)
            assertNull(PressResultSpeech.failureOf(result))
            assertEquals(HandledOutcome.CLOSED, AnnouncementBatcher.outcomeOf(result, ChoiceKind.CLOSE_ONCE))
        }
    }
}
