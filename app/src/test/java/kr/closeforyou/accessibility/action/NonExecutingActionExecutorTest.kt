package kr.closeforyou.accessibility.action

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

class NonExecutingActionExecutorTest {
    private fun executor(f: ActionGateTestFixture, source: ActionGateContextSource = ActionGateContextSource { f.context },
        clock: GateClock = GateClock { 100 }) = NonExecutingActionExecutor(source, clock, f.ledger)

    @Test fun defaultProviderRejectsArbitraryAllowAndReturnsOnlyNotExecuted() = runTest {
        val f = ActionGateTestFixture()
        val executor = NonExecutingActionExecutor()
        assertTrue(GateDenial.UNREGISTERED_REQUEST in executor.inspect(f.request).denials)
        val result = executor.execute(f.request)
        assertEquals(ActionStatus.NOT_EXECUTED, result.status)
        assertFalse(result.attempted)
        assertFalse(result.semanticSafetyConfirmedByUser)
    }

    @Test fun successfulSyntheticReviewIsNotAnActionAttemptOrSuccess() = runTest {
        val f = ActionGateTestFixture()
        val result = executor(f).execute(f.request)
        assertEquals(ActionStatus.NOT_EXECUTED, result.status)
        assertEquals(SafetyReason.NOT_IMPLEMENTED, result.reason)
        assertFalse(result.attempted)
        assertFalse(result.semanticSafetyConfirmedByUser)
        assertEquals(ActionAttemptLedger.ReviewState.COMPLETED, f.ledger.state())
        assertFalse(f.ledger.attempted)
    }

    @Test fun sequentialDuplicateIsRefusedWithoutAnActualAttempt() = runTest {
        val f = ActionGateTestFixture()
        val executor = executor(f)
        assertTrue(executor.inspect(f.request).syntheticChecksSatisfied)
        assertTrue(GateDenial.REVIEW_ALREADY_FINISHED in executor.inspect(f.request).denials)
        assertFalse(executor.execute(f.request).attempted)
    }

    @Test fun newUuidAndTokenDoNotCreateAnotherPopupEpisode() = runTest {
        val f = ActionGateTestFixture()
        var context = f.context
        val executor = executor(f, ActionGateContextSource { context })
        executor.inspect(f.request)
        val token = f.token.copy(popupInstanceId = "new-uuid", screenVersion = 2, captureEpoch = 2)
        context = f.context.copy(currentToken = token, registration = f.registration.copy(token = token,
            choice = f.registration.choice.copy(token = token)))
        val request = f.request.copy(decision = f.request.decision.copy(token = token))
        assertTrue(GateDenial.REVIEW_ALREADY_FINISHED in executor.inspect(request).denials)
        assertFalse(f.ledger.attempted)
    }

    @Test fun concurrentRequestCannotEnterReservedReview() = runTest {
        val f = ActionGateTestFixture()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val executor = executor(f, ActionGateContextSource {
            if (++reads == 2) release.await()
            f.context
        })
        val first = async(start = CoroutineStart.UNDISPATCHED) { executor.inspect(f.request) }
        assertTrue(GateDenial.REVIEW_ALREADY_RESERVED in executor.inspect(f.request).denials)
        release.complete(Unit)
        assertTrue(first.await().syntheticChecksSatisfied)
        assertFalse(f.ledger.attempted)
    }

    @Test fun invalidationDuringSuspendingInspectionIsNotOverriddenByInitialSuccess() = runTest {
        val changes = listOf<(ActionGateContext) -> ActionGateContext?>(
            { null }, { it.copy(currentToken = null) },
            { it.copy(currentSessionGeneration = 10) }, { it.copy(connected = false) },
            { it.copy(userInteracting = true) }, { it.copy(consentGranted = false) },
            { it.copy(paused = true) }, { it.copy(appAllowed = false) },
            { it.copy(checks = it.checks.copy(f0A = GateCheck.BLOCKED)) },
            { it.copy(checks = it.checks.copy(f0B = GateCheck.UNCERTAIN)) },
            { it.copy(checks = it.checks.copy(collection = GateCollection.MALFORMED_NODE_BOUNDS)) },
            { it.copy(currentNode = it.currentNode!!.copy(text = "동의")) })
        for (changed in changes) {
            val local = ActionGateTestFixture()
            val release = CompletableDeferred<Unit>()
            var reads = 0
            val executor = executor(local, ActionGateContextSource {
                if (++reads == 1) local.context else { release.await(); changed(local.context) }
            })
            val pending = async(start = CoroutineStart.UNDISPATCHED) { executor.inspect(local.request) }
            release.complete(Unit)
            val result = pending.await()
            assertTrue(GateDenial.INVALIDATED_DURING_CHECK in result.denials)
            assertFalse(result.syntheticChecksSatisfied)
            assertEquals(ActionAttemptLedger.ReviewState.FAILED, local.ledger.state())
            assertFalse(local.ledger.attempted)
        }
    }

    @Test fun originalDeadlineIsCheckedAgainAfterSuspensionWithoutRenewal() = runTest {
        val f = ActionGateTestFixture()
        var time = 4_999L
        var reads = 0
        val executor = executor(f, ActionGateContextSource {
            if (++reads == 2) { yield(); time = 5_000 }
            f.context
        }, GateClock { time })
        val result = executor.inspect(f.request)
        assertTrue(GateDenial.TTL_EXPIRED in result.denials)
        assertFalse(result.syntheticChecksSatisfied)
        assertFalse(f.ledger.attempted)
    }

    @Test fun exceptionAfterReservationProducesOnlyFixedCodesAndBlocksRetry() = runTest {
        val f = ActionGateTestFixture()
        var reads = 0
        val executor = executor(f, ActionGateContextSource {
            if (++reads == 2) error("synthetic-private-message")
            f.context
        })
        val result = executor.inspect(f.request)
        assertEquals(setOf(GateDenial.ANDROID_REVALIDATION_NOT_IMPLEMENTED, GateDenial.INSPECTION_FAILED), result.denials)
        assertFalse(result.toString().contains("synthetic-private-message"))
        assertEquals(ActionAttemptLedger.ReviewState.FAILED, f.ledger.state())
        assertTrue(GateDenial.REVIEW_ALREADY_FINISHED in executor.inspect(f.request).denials)
    }

    @Test fun executeAlsoFailsClosedOnProviderException() = runTest {
        val f = ActionGateTestFixture()
        val result = executor(f, ActionGateContextSource { error("synthetic-private-message") }).execute(f.request)
        assertEquals(ActionStatus.NOT_EXECUTED, result.status)
        assertFalse(result.attempted)
        assertFalse(result.semanticSafetyConfirmedByUser)
    }

    @Test fun cancellationReleasesReviewAsCancelledWithoutReopeningIt() = runTest {
        val f = ActionGateTestFixture()
        val waiting = CompletableDeferred<Unit>()
        var reads = 0
        val executor = executor(f, ActionGateContextSource {
            if (++reads == 2) waiting.await()
            f.context
        })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { executor.execute(f.request) }
        assertEquals(ActionAttemptLedger.ReviewState.RESERVED, f.ledger.state())
        pending.cancelAndJoin()
        assertTrue(pending.isCancelled)
        assertEquals(ActionAttemptLedger.ReviewState.CANCELLED, f.ledger.state())
        assertTrue(GateDenial.REVIEW_ALREADY_FINISHED in executor.inspect(f.request).denials)
        assertFalse(f.ledger.attempted)
    }

    @Test fun alreadyCancelledCallerDoesNotEvenReadEvidence() = runTest {
        val f = ActionGateTestFixture()
        var reads = 0
        val executor = executor(f, ActionGateContextSource { reads++; f.context })
        val job = launch {
            currentCoroutineContext().cancel()
            executor.execute(f.request)
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, reads)
        assertEquals(ActionAttemptLedger.ReviewState.AVAILABLE, f.ledger.state())
    }

    @Test fun providerIgnoringCancellationCannotFinishAReview() = runTest {
        val f = ActionGateTestFixture()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val executor = executor(f, ActionGateContextSource {
            if (++reads == 2) withContext(NonCancellable) { release.await() }
            f.context
        })
        val pending = async(start = CoroutineStart.UNDISPATCHED) { executor.execute(f.request) }
        pending.cancel()
        release.complete(Unit)
        pending.join()
        assertTrue(pending.isCancelled)
        assertEquals(ActionAttemptLedger.ReviewState.CANCELLED, f.ledger.state())
        assertFalse(f.ledger.attempted)
    }

    @Test fun continuityLossDuringInspectionCannotCompleteAReview() = runTest {
        val f = ActionGateTestFixture()
        var reads = 0
        val executor = executor(f, ActionGateContextSource {
            if (++reads == 2) f.ledger.invalidateContinuity()
            f.context
        })
        val result = executor.inspect(f.request)
        assertFalse(result.productionReady)
        // Continuity loss must be rechecked after the provider suspension too.
        assertFalse(result.syntheticChecksSatisfied)
        assertTrue(GateDenial.POPUP_IDENTITY_UNPROVEN in result.denials)
    }

    private suspend fun cancellationInClock(clockCall: Int, scope: CoroutineScope) {
        val f = ActionGateTestFixture()
        var reads = 0
        var returned = false
        lateinit var pending: Deferred<GateEvaluation>
        val executor = executor(f, clock = GateClock {
            if (++reads == clockCall) pending.cancel()
            100
        })
        pending = scope.async(start = CoroutineStart.LAZY) {
            executor.inspect(f.request).also { returned = true }
        }
        pending.start()
        pending.join()
        assertTrue(pending.isCancelled)
        assertFalse(returned)
        assertEquals(clockCall, reads)
        assertEquals(ActionAttemptLedger.ReviewState.CANCELLED, f.ledger.state())
        assertFalse(f.ledger.attempted)
        assertEquals(GateDenial.REVIEW_ALREADY_FINISHED,
            f.ledger.reserveReview(f.registration.episode, GateCheck.PASSED).denial)
    }

    @Test fun cancellationInFinalValidationClockCannotCompleteReview() = runTest {
        cancellationInClock(clockCall = 2, scope = this)
    }

    @Test fun cancellationInCompletionClockCannotCompleteReview() = runTest {
        cancellationInClock(clockCall = 3, scope = this)
    }

    @Test fun exceptionInCompletionClockClosesReviewWithoutRetry() = runTest {
        val f = ActionGateTestFixture()
        var reads = 0
        val executor = executor(f, clock = GateClock {
            if (++reads == 3) error("synthetic-private-message")
            100
        })
        val result = executor.inspect(f.request)
        assertEquals(setOf(GateDenial.ANDROID_REVALIDATION_NOT_IMPLEMENTED, GateDenial.INSPECTION_FAILED), result.denials)
        assertEquals(ActionAttemptLedger.ReviewState.FAILED, f.ledger.state())
        assertFalse(result.productionReady)
        assertFalse(f.ledger.attempted)
        assertTrue(GateDenial.REVIEW_ALREADY_FINISHED in executor.inspect(f.request).denials)
    }

    @Test fun ttlExpiresDuringSynchronousFinalValidationBeforeCompletion() = runTest {
        val f = ActionGateTestFixture()
        var time = 4_999L
        var validations = 0
        var clockReads = 0
        val members = persistentSetOf(NodeAction.CLICK)
        // Contents stay immutable. The second action lookup advances synthetic time INSIDE
        // the final synchronous validator, after it has sampled 4,999ms. No sleep/scheduling.
        val actions = object : ImmutableSet<NodeAction> by members {
            override fun contains(element: NodeAction): Boolean {
                if (++validations == 2) time = 5_000
                return element in members
            }
        }
        val node = f.node.copy(actions = actions)
        val context = f.context.copy(registration = f.registration.copy(node = node), currentNode = node)
        val executor = executor(f, ActionGateContextSource { context }, GateClock {
            if (++clockReads == 3) assertTrue(Thread.holdsLock(f.ledger))
            time
        })
        val result = executor.inspect(f.request)
        assertEquals(2, validations)
        assertTrue(GateDenial.TTL_EXPIRED in result.denials)
        assertFalse(result.syntheticChecksSatisfied)
        assertFalse(result.productionReady)
        assertEquals(3, clockReads)
        assertEquals(ActionAttemptLedger.ReviewState.FAILED, f.ledger.state())
        assertFalse(f.ledger.attempted)
        assertEquals(GateDenial.REVIEW_ALREADY_FINISHED,
            f.ledger.reserveReview(f.registration.episode, GateCheck.PASSED).denial)
        val output = executor.execute(f.request)
        assertEquals(ActionStatus.NOT_EXECUTED, output.status)
        assertFalse(output.attempted)
    }
}
