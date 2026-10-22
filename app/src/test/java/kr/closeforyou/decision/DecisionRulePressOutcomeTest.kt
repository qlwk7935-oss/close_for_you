package kr.closeforyou.decision

import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionRulePressOutcomeTest {
    private val fixture = MockPopupFixtures.notice()
    private val token = fixture.snapshot.token
    private val rule = PopupRule(
        id = "rule-1",
        key = RuleKey("kr.closeforyou.mock.host", 1, "structure", "content", "target"),
        choice = ChoiceKind.REMEMBER_EXACT_POPUP,
        buttonMeaningConfirmed = true,
        repeatAutomationExplicitlyAllowed = true,
        createdAtEpochMillis = 0,
        expiresAtEpochMillis = 30L * 24 * 60 * 60 * 1000,
    )

    private fun result(status: ActionStatus, attempted: Boolean, at: SnapshotToken = token) = ActionResult(at, status, null, attempted)

    @Test fun everyStatusHasTheAgreedMeaning() {
        val expected = mapOf(
            ActionStatus.MECHANICAL_SUCCESS to PressOutcome.SUCCEEDED,
            ActionStatus.ATTEMPT_FAILED to PressOutcome.FAILED,
            ActionStatus.OUTCOME_UNKNOWN to PressOutcome.FAILED,
            ActionStatus.NOT_EXECUTED to PressOutcome.NOT_ATTEMPTED,
            ActionStatus.BLOCKED to PressOutcome.NOT_ATTEMPTED,
            ActionStatus.STALE to PressOutcome.NOT_ATTEMPTED,
        )
        assertEquals("every status is decided", ActionStatus.entries.toSet(), expected.keys)
        for ((status, outcome) in expected) assertEquals(status.name, outcome, DecisionRulePressOutcome.outcomeOf(result(status, true)))
    }

    /** A different screen (a separate attempt) for each status. */
    private fun screen(n: Int): SnapshotToken = token.copy(screenVersion = 100L + n)

    @Test fun onlyFailuresAreRecorded() = runBlocking {
        val recorded = mutableListOf<String>()
        val outcome = DecisionRulePressOutcome { recorded += it }
        ActionStatus.entries.forEachIndexed { i, status ->
            outcome.afterRulePress(rule, result(status, attempted = status != ActionStatus.NOT_EXECUTED, at = screen(i)))
        }
        assertEquals("ATTEMPT_FAILED and OUTCOME_UNKNOWN, once each", listOf("rule-1", "rule-1"), recorded)
    }

    @Test fun theSameFailedAttemptDeliveredTwiceIsRecordedOnce() = runBlocking {
        // Review on #23 (A): D's failureCount must not grow twice for one failed attempt.
        val recorded = mutableListOf<String>()
        val outcome = DecisionRulePressOutcome { recorded += it }
        val failed = result(ActionStatus.ATTEMPT_FAILED, true)
        assertEquals(PressOutcome.FAILED, outcome.afterRulePress(rule, failed))
        assertEquals("still reported as failed", PressOutcome.FAILED, outcome.afterRulePress(rule, failed))
        // Same attempt reported with the other failure status: still one attempt.
        outcome.afterRulePress(rule, result(ActionStatus.OUTCOME_UNKNOWN, true))
        assertEquals(listOf("rule-1"), recorded)
        // A new attempt (another screen) is a new failure.
        outcome.afterRulePress(rule, result(ActionStatus.ATTEMPT_FAILED, true, at = screen(1)))
        assertEquals(listOf("rule-1", "rule-1"), recorded)
    }

    @Test fun concurrentDuplicatesAreRecordedOnce() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val outcome = DecisionRulePressOutcome { calls.incrementAndGet(); kotlinx.coroutines.delay(5) }
        val failed = result(ActionStatus.OUTCOME_UNKNOWN, true)
        kotlinx.coroutines.coroutineScope {
            repeat(20) { launch(kotlinx.coroutines.Dispatchers.Default) { outcome.afterRulePress(rule, failed) } }
        }
        assertEquals(1, calls.get())
    }

    @Test fun aRecorderErrorLeavesTheAttemptUnrecordedSoTheNextDeliveryRecordsIt() = runBlocking {
        var fail = true
        val recorded = mutableListOf<String>()
        val outcome = DecisionRulePressOutcome { if (fail) error("database busy") else recorded += it }
        val failed = result(ActionStatus.ATTEMPT_FAILED, true)
        assertTrue(runCatching { outcome.afterRulePress(rule, failed) }.isFailure)
        fail = false
        outcome.afterRulePress(rule, failed)
        assertEquals("at least once: the rule ends up stopped", listOf("rule-1"), recorded)
    }

    @Test fun onlyTheMostRecentAttemptsAreRemembered() = runBlocking {
        val recorded = mutableListOf<String>()
        val outcome = DecisionRulePressOutcome(rememberedAttempts = 2) { recorded += it }
        for (n in 1..3) outcome.afterRulePress(rule, result(ActionStatus.ATTEMPT_FAILED, true, at = screen(n)))
        // Attempt 1 was forgotten (bounded memory), so its late duplicate is recorded again;
        // attempt 3 is still remembered.
        outcome.afterRulePress(rule, result(ActionStatus.ATTEMPT_FAILED, true, at = screen(1)))
        outcome.afterRulePress(rule, result(ActionStatus.ATTEMPT_FAILED, true, at = screen(3)))
        assertEquals(4, recorded.size)
    }

    @Test fun notExecutedTodayIsNeverRecordedAsAFailure() = runBlocking {
        // A's gate does not click yet and answers NOT_EXECUTED: the rule must stay as it is.
        val recorded = mutableListOf<String>()
        val outcome = DecisionRulePressOutcome { recorded += it }
        assertEquals(PressOutcome.NOT_ATTEMPTED, outcome.afterRulePress(rule, ActionResult(token, ActionStatus.NOT_EXECUTED, SafetyReason.NOT_IMPLEMENTED)))
        assertTrue(recorded.isEmpty())
    }

    @Test fun aFailureNamingAnotherScreenIsStillRecorded() = runBlocking {
        val recorded = mutableListOf<String>()
        val other = MockPopupFixtures.advertisement().snapshot.token
        DecisionRulePressOutcome { recorded += it }.afterRulePress(rule, result(ActionStatus.OUTCOME_UNKNOWN, true, at = other))
        assertEquals("stopping the rule is the safe direction", listOf("rule-1"), recorded)
    }

    @Test(expected = CancellationException::class)
    fun cancellationFromTheRecorderIsNotSwallowed(): Unit = runBlocking {
        DecisionRulePressOutcome { throw CancellationException("cancelled") }.afterRulePress(rule, result(ActionStatus.ATTEMPT_FAILED, true))
        Unit
    }
}
