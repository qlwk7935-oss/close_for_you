package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.EvidenceMismatch
import kr.closeforyou.accessibility.EvidenceSnapshotComparison
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChoicePresentationGateTest {
    private class Driver {
        var now = 1_100L
        val original = MockPopupFixtures.notice().snapshot.copy(capturedAtUptimeMillis = 1_000)
        var current: SnapshotToken? = original.token
        val permit = Any()
        var cleanupPermit: Any? = permit
        val queued = ArrayDeque<Runnable>()
        val calls = mutableListOf<String>()
        var allowed = true
        var touching = false
        var fresh = original
        var verificationFailure: Exception? = null
        var duringVerification: (() -> Unit)? = null
        var rejects = 0
        val gate = ChoicePresentationGate({ now }, {
            calls += "CURRENT"; allowed && !touching && current == it
        }, { calls += "CLEANUP"; cleanupPermit === it }, {
            calls += "POST"
            awaitChoicePresentationTurn({ queued += it; true }, { queued.remove(it) })
        }, { baseline, active ->
            calls += "F0"
            assertTrue(baseline.nodes.isEmpty()) // no retained tree/decision text
            assertEquals(original.capturedAtUptimeMillis, baseline.capturedAtUptimeMillis)
            active(); verificationFailure?.let { throw it }; duringVerification?.invoke(); active()
            EvidenceSnapshotComparison.firstMismatch(baseline, fresh) == EvidenceMismatch.NONE
        }, { rejects++; current = null })
        init { gate.prepare(original, permit) }
        fun runTurn() { queued.removeFirst().run() }
    }

    @Test fun geometryDigestChurnMustNotReopenBoundedPresentationRecovery() {
        val token = P01StabilityFixture.sample(0).snapshot.token
        val budget = ChoiceReviewBudget(boundGeometryRecovery = true)
        assertTrue(budget.admit(token))
        assertTrue(budget.admit(token.copy(structureDigest = "synthetic-geometry-2")))
        assertFalse(budget.admit(token.copy(structureDigest = "synthetic-geometry-3")))
    }

    @Test fun newWindowContentOrAppGetsAnIndependentDenialBudgetButNeverAPopupIdentityGrant() {
        val token = P01StabilityFixture.sample(0).snapshot.token
        for (next in listOf(token.copy(windowId = 9), token.copy(contentDigest = "new-body"), token.copy(packageName = "kr.synthetic.other"))) {
            val budget = ChoiceReviewBudget(boundGeometryRecovery = true)
            assertTrue(budget.admit(token)); assertTrue(budget.admit(token)); assertFalse(budget.admit(token))
            assertTrue(budget.admit(next)); assertTrue(budget.admit(next)); assertFalse(budget.admit(next))
        }
    }
    @Test fun originalStrictReleaseBudgetStillDistinguishesStructureChanges() {
        val token = P01StabilityFixture.sample(0).snapshot.token
        val budget = ChoiceReviewBudget(boundGeometryRecovery = false)
        assertTrue(budget.admit(token)); assertTrue(budget.admit(token)); assertFalse(budget.admit(token))
        assertTrue(budget.admit(token.copy(structureDigest = "other-structure")))
        assertEquals(ManualReviewAdmission.IDENTITY_CHANGED, budget.lastAdmission)
    }

    @Test fun stableScreenRequiresPostedTurnAndFreshF0ThenKeepsOriginalTokenAndDeadline() = runTest {
        val d = Driver(); val ready = async { d.gate.awaitReady(d.original.token) }; runCurrent()
        assertFalse(ready.isCompleted); assertFalse(d.calls.contains("F0"))
        d.runTurn(); runCurrent()
        assertFalse(ready.isCompleted); assertEquals(1, d.calls.count { it == "F0" })
        d.runTurn(); assertTrue(ready.await()); assertEquals(d.original.token, d.current)
        assertEquals(1, d.calls.count { it == "F0" })
        d.now = 6_000
        assertFalse(d.gate.awaitReady(d.original.token)) // final verification did not renew capturedAt
    }

    @Test fun interruptedReviewEligibilityRequiresOriginalCurrentPolicyTtlAndCleanupPermit() {
        for (cause in listOf("CURRENT", "TOKEN", "CONSENT", "TOUCH", "CLEANUP", "TTL", "CLOCK", "CLEARED")) {
            val d = Driver()
            assertTrue(d.gate.hasCurrentObservation()) // Candidate exists, C has not been created/bound.
            assertFalse(d.calls.contains("F0")); assertTrue(d.queued.isEmpty())
            when (cause) {
                "CURRENT" -> d.current = null
                "TOKEN" -> d.current = d.original.token.copy(captureEpoch = d.original.token.captureEpoch + 1)
                "CONSENT" -> d.allowed = false
                "TOUCH" -> d.touching = true
                "CLEANUP" -> d.cleanupPermit = null
                "TTL" -> d.now = 6_000
                "CLOCK" -> d.now = 999
                else -> d.gate.clear()
            }
            assertFalse(cause, d.gate.hasCurrentObservation())
            assertEquals(0, d.rejects) // Eligibility check neither repairs nor invalidates evidence.
        }
    }

    @Test fun eventQueuedBeforePresentationInvalidatesNormallyAndNoFreshCheckOrCIsStarted() = runTest {
        val d = Driver(); val wait = async { d.gate.awaitReady(d.original.token) }; runCurrent()
        // Even an old event whose ID/source is unconfirmed gets ordinary invalidation.
        d.current = null; d.gate.clear(); d.runTurn()
        assertFalse(wait.await()); assertFalse(d.calls.contains("F0")); assertEquals(0, d.rejects)
    }

    @Test fun actualWindowContentStructureFrameOrWindowSetChangeFailsFinalComparison() = runTest {
        val base = Driver().original
        val changes = listOf(
            base.copy(token = base.token.copy(windowId = base.token.windowId + 1)),
            base.copy(token = base.token.copy(packageName = "SYNTHETIC_OTHER")),
            base.copy(token = base.token.copy(contentDigest = "SYNTHETIC_CHANGED")),
            base.copy(token = base.token.copy(structureDigest = "SYNTHETIC_CHANGED")),
            base.copy(screenBounds = base.screenBounds.copy(right = base.screenBounds.right + 1)),
            base.copy(windows = kotlinx.collections.immutable.persistentListOf()),
        )
        for (fresh in changes) {
            val d = Driver(); d.fresh = fresh
            val wait = async { d.gate.awaitReady(d.original.token) }; runCurrent(); d.runTurn()
            assertFalse(wait.await()); assertNull(d.current); assertEquals(1, d.rejects)
        }
    }

    @Test fun touchScrollConsentDisconnectAndCleanupRevocationBeforeVerificationCannotShow() = runTest {
        for (cause in 0..4) {
            val d = Driver(); val wait = async { d.gate.awaitReady(d.original.token) }; runCurrent()
            when (cause) {
                0 -> d.touching = true
                1 -> { d.current = null; d.gate.clear() } // scroll/event boundary
                2 -> d.allowed = false
                3 -> { d.current = null; d.gate.clear() } // disconnect
                4 -> d.cleanupPermit = null
            }
            d.runTurn(); assertFalse(wait.await()); assertFalse(d.calls.contains("F0"))
        }
    }

    @Test fun revocationOrCancellationDuringCollectorCannotPassTheFinalBoundary() = runTest {
        for (cause in 0..3) {
            val d = Driver(); d.duringVerification = {
                when (cause) {
                    0 -> d.touching = true
                    1 -> d.allowed = false
                    2 -> { d.current = null; d.gate.clear() }
                    3 -> d.cleanupPermit = null
                }
            }
            val wait = async { d.gate.awaitReady(d.original.token) }; runCurrent(); d.runTurn()
            assertTrue(runCatching { wait.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, d.rejects)
        }
    }

    @Test fun originalTtlCanExpireDuringQueueWaitOrVerificationAndNeverRenews() = runTest {
        for (during in listOf(false, true)) {
            val d = Driver()
            if (during) d.duringVerification = { d.now = 6_000 }
            val wait = async { d.gate.awaitReady(d.original.token) }; runCurrent()
            if (!during) d.now = 6_000
            d.runTurn()
            if (during) assertTrue(runCatching { wait.await() }.exceptionOrNull() is CancellationException)
            else assertFalse(wait.await())
        }
        val backward = Driver(); backward.now = 999
        assertFalse(backward.gate.awaitReady(backward.original.token))
    }

    @Test fun lookupFailureRejectsWhileCollectorCancellationPropagatesUnchanged() = runTest {
        val d = Driver(); d.verificationFailure = IllegalStateException("SYNTHETIC_SECRET")
        val wait = async { d.gate.awaitReady(d.original.token) }; runCurrent(); d.runTurn()
        assertFalse(wait.await()); assertNull(d.current); assertEquals(1, d.rejects)
        val cancelled = Driver(); val cause = CancellationException("SYNTHETIC_SECRET")
        cancelled.verificationFailure = cause
        val stopped = async { cancelled.gate.awaitReady(cancelled.original.token) }; runCurrent(); cancelled.runTurn()
        assertTrue(runCatching { stopped.await() }.exceptionOrNull() is CancellationException)
        assertTrue(stopped.isCancelled); assertEquals(0, cancelled.rejects)
    }

    @Test fun cancellingWaitRemovesPostedCallbackAndLateCallbackCannotAuthorizeAnything() = runTest {
        val d = Driver(); val wait = async { d.gate.awaitReady(d.original.token) }; runCurrent()
        val oldCallback = d.queued.first(); wait.cancelAndJoin()
        assertTrue(d.queued.isEmpty()); oldCallback.run(); assertFalse(d.calls.contains("F0"))
    }

    @Test fun failedPostIsSafeRefusalAndDoesNotRunCollector() = runTest {
        val snapshot = Driver().original; var rejected = 0; var verified = 0
        val gate = ChoicePresentationGate({ 1_100 }, { true }, { true },
            { awaitChoicePresentationTurn({ false }, {}) }, { _, _ -> verified++; true }, { rejected++ })
        gate.prepare(snapshot, Any())
        assertFalse(gate.awaitReady(snapshot.token)); assertEquals(0, verified); assertEquals(1, rejected)
    }

    @Test fun oldTicketOldWaitAndOldTokenCannotClearOrAuthorizeNewPopup() = runTest {
        val d = Driver(); val oldTicket = d.gate.prepare(d.original, d.permit)
        val oldWait = async { d.gate.awaitReady(d.original.token) }; runCurrent()
        val next = d.original.copy(token = d.original.token.copy(screenVersion = 2, captureEpoch = 2, popupInstanceId = "SYNTHETIC_NEXT"))
        d.current = next.token; d.fresh = next
        d.gate.prepare(next, d.permit); d.gate.abandon(oldTicket); d.runTurn()
        assertFalse(oldWait.await()); assertEquals(next.token, d.current)
        assertFalse(d.gate.awaitReady(d.original.token))
        val nextWait = async { d.gate.awaitReady(next.token) }; runCurrent(); d.runTurn(); runCurrent(); d.runTurn()
        assertTrue(nextWait.await()); assertEquals(0, d.rejects)
    }

    @Test fun missingBaselineAndRedactedStringsNeverGrantOrExposeInformation() = runTest {
        val d = Driver(); d.gate.clear()
        assertFalse(d.gate.awaitReady(d.original.token))
        assertEquals("ChoicePresentationGate(redacted)", d.gate.toString())
        assertEquals("PresentationTicket(redacted)", d.gate.prepare(d.original, d.permit).toString())
    }

    @Test fun eventConsentTouchOrTtlAfterFinalF0ButBeforeCStillCancelsAdmission() = runTest {
        for (cause in 0..4) {
            val d = Driver(); val ready = async { d.gate.awaitReady(d.original.token) }; runCurrent()
            d.runTurn(); runCurrent(); assertEquals(1, d.calls.count { it == "F0" })
            assertFalse(ready.isCompleted)
            when (cause) {
                0 -> { d.current = null; d.gate.clear() }
                1 -> d.allowed = false
                2 -> d.touching = true
                3 -> d.now = 6_000
                4 -> d.cleanupPermit = null
            }
            d.runTurn(); assertFalse(ready.await())
        }
    }

    @Test fun cancellingSecondTurnRemovesItsCallbackWithoutReusingVerifiedCandidate() = runTest {
        val d = Driver(); val wait = async { d.gate.awaitReady(d.original.token) }; runCurrent()
        d.runTurn(); runCurrent(); assertEquals(1, d.queued.size)
        val late = d.queued.first(); wait.cancelAndJoin(); assertTrue(d.queued.isEmpty())
        late.run(); assertTrue(wait.isCancelled); assertEquals(1, d.calls.count { it == "F0" })
    }
}
