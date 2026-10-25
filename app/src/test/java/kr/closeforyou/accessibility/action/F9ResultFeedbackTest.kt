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

    @Test fun observedRemovalWithoutMechanicalSuccessNeverCountsAsSuccess() {
        for (returned in listOf(true, false, null)) {
            val result = F9Result(token, true, returned, ClickOutcome.CLOSED_CONFIRMED).toActionResult()
            assertTrue(result.attempted)
            assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
            assertFalse(result.semanticSafetyConfirmedByUser)
            assertEquals(returned == true, PressResultSpeech.failureOf(result) == null)
            assertEquals(if (returned == true) HandledOutcome.CLOSED else HandledOutcome.NOT_CONFIRMED,
                AnnouncementBatcher.outcomeOf(result, ChoiceKind.CLOSE_ONCE))
            assertEquals(returned == true, F9Result(token, true, returned, ClickOutcome.CLOSED_CONFIRMED).closedConfirmed)
            assertEquals(returned == true, "닫았어요" in F9ResultFeedback.request(result).text)
        }
    }

    @Test fun allResultCombinationsUseTheSameThreeConditionsAsHandlingReport() {
        val decision = ActionDecision(token, ActionAuthorization.ALLOW_ONCE,
            kotlinx.collections.immutable.persistentSetOf(), "close", source = DecisionSource.PRESET)
        for (attempted in listOf(false, true)) for (status in ActionStatus.entries) {
            val outcomes = if (attempted) listOf(null) + ClickOutcome.entries else listOf(null)
            for (outcome in outcomes) {
                val result = ActionResult(token, status, null, attempted, outcome = outcome)
                val success = attempted && status == ActionStatus.MECHANICAL_SUCCESS && outcome == ClickOutcome.CLOSED_CONFIRMED
                assertEquals(success, HandlingReport(token, PopupClassification.ADVERTISEMENT, decision, result).closedConfirmed)
                assertEquals(success, PressResultSpeech.failureOf(result) == null)
                assertEquals(success, AnnouncementBatcher.outcomeOf(result, ChoiceKind.CLOSE_ONCE) == HandledOutcome.CLOSED)
                assertEquals(success, "닫았어요" in F9ResultFeedback.request(result).text)
            }
        }
    }
}
