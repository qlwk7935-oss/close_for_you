package kr.closeforyou.core.model

import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract v2 for automatic closing: five separate parts (approval source, next step, A's
 * eligibility evidence, the observed click outcome, the person's consent scope). Every new field
 * defaults to today's behaviour, so callers written before v2 do not change.
 */
class AutoCloseContractTest {
    private val token: SnapshotToken = MockPopupFixtures.notice().snapshot.token

    @Test fun defaultsKeepTodaysBehaviour() {
        val context = DecisionContext(token, null, null, false, true)
        assertTrue("no automatic close unless the person chose a preset", context.autoCloseKinds.isEmpty())
        assertNull(PopupAnalysis(token).autoEligibility)
        assertNull(ActionResult(token, ActionStatus.NOT_EXECUTED, null).outcome)
        for (authorization in ActionAuthorization.entries) {
            val decision = ActionDecision(token, authorization, persistentSetOf(), candidateId = "c")
            assertNull(decision.source)
            assertEquals(defaultNextStep(authorization), decision.next)
        }
        assertEquals(NextStep.EXECUTE, defaultNextStep(ActionAuthorization.ALLOW_ONCE))
        assertEquals(NextStep.ASK, defaultNextStep(ActionAuthorization.NEEDS_CONFIRMATION))
        assertEquals(NextStep.DENY, defaultNextStep(ActionAuthorization.DENY))
    }

    @Test fun existingOrdinalsAreUnchanged() {
        // New values were appended, so anything that stored or compared ordinals keeps working.
        assertEquals(listOf("ADVERTISEMENT", "NOTICE", "USER_DECISION", "UNKNOWN", "COUPON"), PopupClassification.entries.map { it.name })
        assertEquals(SafetyReason.FEEDBACK_UNAVAILABLE.ordinal + 1, SafetyReason.SUSPICIOUS_CONTENT.ordinal)
        assertEquals(11, SafetyReason.FEEDBACK_UNAVAILABLE.ordinal)
    }

    @Test fun onlyAnApprovalExecutesAndDenyNeverOffersAPress() {
        val ok = ActionDecision(token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), "c", DecisionSource.PRESET, NextStep.EXECUTE)
        assertEquals(DecisionSource.PRESET, ok.source)
        for (next in NextStep.entries - NextStep.EXECUTE) {
            assertThrows(next.name, IllegalArgumentException::class.java) {
                ActionDecision(token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), "c", next = next)
            }
        }
        for (authorization in ActionAuthorization.entries - ActionAuthorization.ALLOW_ONCE) {
            assertThrows(authorization.name, IllegalArgumentException::class.java) {
                ActionDecision(token, authorization, persistentSetOf(), next = NextStep.EXECUTE)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ActionDecision(token, ActionAuthorization.NEEDS_CONFIRMATION, persistentSetOf(), next = NextStep.DENY)
        }
        // WAIT, ANNOUNCE and ASK may sit on either refusal.
        for (next in listOf(NextStep.WAIT, NextStep.ANNOUNCE, NextStep.ASK)) {
            for (authorization in listOf(ActionAuthorization.DENY, ActionAuthorization.NEEDS_CONFIRMATION)) {
                assertEquals(next, ActionDecision(token, authorization, persistentSetOf(SafetyReason.WAITING_FOR_TARGET), next = next).next)
            }
        }
    }

    @Test fun decisionsAndScamsAreOnlyExplainedAndWaitingSaysWhy() {
        // #38 review (C) ①: a decision or scam-like popup always ends in DENY, whoever builds it.
        for (reason in ActionDecision.DENY_ONLY_REASONS) {
            assertEquals(NextStep.DENY, ActionDecision(token, ActionAuthorization.DENY, persistentSetOf(reason)).next)
            for (next in NextStep.entries - NextStep.DENY - NextStep.EXECUTE) {
                assertThrows("$reason $next", IllegalArgumentException::class.java) {
                    ActionDecision(token, ActionAuthorization.DENY, persistentSetOf(reason), next = next)
                }
            }
            assertThrows(reason.name, IllegalArgumentException::class.java) {
                ActionDecision(token, ActionAuthorization.NEEDS_CONFIRMATION, persistentSetOf(reason))
            }
        }
        // ②: WAIT always carries what it waits for.
        assertThrows(IllegalArgumentException::class.java) {
            ActionDecision(token, ActionAuthorization.NEEDS_CONFIRMATION, persistentSetOf(SafetyReason.FIRST_SEEN), next = NextStep.WAIT)
        }
        for (reason in ActionDecision.WAIT_REASONS) {
            assertEquals(NextStep.WAIT, ActionDecision(token, ActionAuthorization.DENY, persistentSetOf(reason), next = NextStep.WAIT).next)
        }
    }

    @Test fun aClickOutcomeExistsOnlyAfterAnAttemptedPress() {
        assertEquals(ClickOutcome.CLOSED_CONFIRMED,
            ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED).outcome)
        for (outcome in ClickOutcome.entries) {
            assertThrows(outcome.name, IllegalArgumentException::class.java) {
                ActionResult(token, ActionStatus.NOT_EXECUTED, null, attempted = false, outcome = outcome)
            }
        }
    }

    @Test fun eligibilityNeedsEveryCheckThisTokenAndFiveSeconds() {
        val all = EligibilityCheck.entries.toSet()
        val full = AutoEligibility(token, kotlinx.collections.immutable.persistentSetOf(*all.toTypedArray()), issuedAtUptimeMillis = 1_000)
        assertTrue(full.complete)
        assertTrue(full.validFor(token, 1_000))
        assertTrue(full.validFor(token, 5_999))
        assertFalse("expired at 5 s, like A's evidence", full.validFor(token, 6_000))
        assertFalse("not before it was issued", full.validFor(token, 999))
        assertFalse("another screen", full.validFor(token.copy(screenVersion = token.screenVersion + 1), 1_000))
        for (missing in all) {
            val partial = full.copy(checks = kotlinx.collections.immutable.persistentSetOf(*(all - missing).toTypedArray()))
            assertFalse(missing.name, partial.complete)
            assertFalse(missing.name, partial.validFor(token, 1_000))
        }
        assertEquals(5_000L, AutoEligibility.TTL_MILLIS)
    }

    @Test fun newTypesCarryNoScreenData() {
        val e = AutoEligibility(token, persistentSetOf(EligibilityCheck.F0_PASSED), 0)
        assertFalse(e.toString().contains(token.packageName))
        assertFalse(PopupAnalysis(token, autoEligibility = e).toString().contains(token.packageName))
    }
}
