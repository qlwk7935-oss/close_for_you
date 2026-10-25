package kr.closeforyou.core.model

import kr.closeforyou.core.contract.ActionPolicy
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract v3: the A → C report of a popup handled without asking, and the popup title. */
class HandlingReportContractTest {
    private val token: SnapshotToken = MockPopupFixtures.notice().snapshot.token

    private fun decision(next: NextStep, source: DecisionSource? = DecisionSource.PRESET) = when (next) {
        NextStep.EXECUTE -> ActionDecision(token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), "c", source, next)
        NextStep.WAIT -> ActionDecision(token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.WAITING_FOR_TARGET), null, source, next)
        NextStep.DENY -> ActionDecision(token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.SUSPICIOUS_CONTENT), null, source, next)
        else -> ActionDecision(token, ActionAuthorization.NEEDS_CONFIRMATION, persistentSetOf(SafetyReason.FIRST_SEEN), "c", source, next)
    }

    private fun pressed(outcome: ClickOutcome?) =
        ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = outcome)

    @Test fun onlyAConfirmedCloseCountsAsClosed() {
        assertTrue(HandlingReport(token, PopupClassification.COUPON, decision(NextStep.EXECUTE), pressed(ClickOutcome.CLOSED_CONFIRMED)).closedConfirmed)
        for (outcome in listOf(ClickOutcome.LEFT_APP, ClickOutcome.STILL_PRESENT, ClickOutcome.UNKNOWN, null)) {
            assertFalse(outcome.toString(), HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(NextStep.EXECUTE), pressed(outcome)).closedConfirmed)
        }
        // The popup vanished but the press failed or its outcome is unknown: not closed (team rule 10/25).
        for (status in listOf(ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN)) {
            val vanished = ActionResult(token, status, null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED)
            assertFalse(status.name, HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(NextStep.EXECUTE), vanished).closedConfirmed)
        }
        // A's gate did not press (non-executing today): not closed either.
        val notPressed = ActionResult(token, ActionStatus.NOT_EXECUTED, SafetyReason.NOT_IMPLEMENTED)
        assertFalse(HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(NextStep.EXECUTE), notPressed).closedConfirmed)
        assertFalse(HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(NextStep.EXECUTE)).closedConfirmed)
    }

    @Test fun everyNextStepCanBeReportedButOnlyAnExecutedOneHasAResult() {
        for (next in NextStep.entries) {
            assertEquals(next, HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(next)).decision.next)
        }
        for (next in NextStep.entries - NextStep.EXECUTE) {
            assertThrows(next.name, IllegalArgumentException::class.java) {
                HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(next), pressed(ClickOutcome.CLOSED_CONFIRMED))
            }
        }
    }

    @Test fun onlyAutomaticHandlingIsReported() {
        assertEquals(DecisionSource.REMEMBERED_RULE,
            HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(NextStep.ASK, DecisionSource.REMEMBERED_RULE)).decision.source)
        // The person's own choice goes through the choice prompt, and a decision without a source is not automatic.
        for (source in listOf(DecisionSource.USER_CHOICE, null)) {
            assertThrows(source.toString(), IllegalArgumentException::class.java) {
                HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(NextStep.ASK, source))
            }
        }
    }

    @Test fun oneScreenOnlyAndNoBlankTitle() {
        val other = token.copy(screenVersion = token.screenVersion + 1)
        assertThrows(IllegalArgumentException::class.java) {
            HandlingReport(other, PopupClassification.ADVERTISEMENT, decision(NextStep.ASK))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(NextStep.EXECUTE), pressed(null).copy(token = other))
        }
        assertThrows(IllegalArgumentException::class.java) {
            HandlingReport(token, PopupClassification.ADVERTISEMENT, decision(NextStep.ASK), title = "  ")
        }
    }

    @Test fun titleAndReportNeverShowScreenText() {
        val secret = "가을 할인 쿠폰 비밀"
        val analysis = PopupAnalysis(token, title = secret)
        assertFalse(analysis.toString().contains("비밀"))
        val report = HandlingReport(token, PopupClassification.COUPON, decision(NextStep.EXECUTE), pressed(ClickOutcome.CLOSED_CONFIRMED), secret)
        assertFalse(report.toString().contains("비밀"))
        assertFalse(report.toString().contains(token.packageName))
        assertNull(PopupAnalysis(token).title)
    }

    @Test fun policiesWithoutASessionRecordIgnoreResults() {
        // The default onActionResult does nothing, so ports written before v3 keep compiling and working.
        val policy = object : ActionPolicy {
            override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext) =
                ActionDecision(analysis.token, ActionAuthorization.DENY, persistentSetOf(SafetyReason.NOT_IMPLEMENTED))
        }
        policy.onActionResult(pressed(ClickOutcome.LEFT_APP))
    }
}
