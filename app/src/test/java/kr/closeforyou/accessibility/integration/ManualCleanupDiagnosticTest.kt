package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

/** Real barrier + native model + actual shared A dismiss boundary. No Android removal is faked
 * into a production receipt; JVM lambdas supply synthetic state only within this test. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualCleanupDiagnosticTest {
    private class Rig(val now: () -> Long = { 1_000L }) {
        val codes = mutableListOf<ManualCleanupCode>()
        val trace = ManualCleanupTrace(codes::add)
        val runtime = mutableListOf<CleanupFailure>()
        var unhealthy = false
        val barrier = OverlayCleanupBarrier(clock = now, onUncertain = { unhealthy = true },
            failureDiagnostic = runtime::add,
            failureAttemptDiagnostic = { r, n -> trace.record(ManualCleanupSite.BARRIER, ManualCleanupIssue.BARRIER_REJECTED, r, n) })
        val owner = Any()
        val native = OverlayNativeWindowLifetime(2, owner).also { it.attached(99); it.addReturned(true) }
        fun request() {
            native.removalRequested(); barrier.retiring(null, 2, 20, native, owner)
        }
        fun removed() { native.detached(); native.removalReturned(true) }
        fun receipt() = requireNotNull(barrier.observeWindowEvent(99, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
    }
    @Test fun directOldInvalidationReturnCanFailBeforePostedNativeCallback() {
        val r = Rig(); r.request(); r.removed() // old direct overlay.dismiss() path omitted A removalReturned
        r.barrier.processed(r.receipt(), true) { CleanupWindowPresence.ABSENT }
        assertFalse(r.barrier.ready); assertEquals(listOf(CleanupFailure.REMOVE_NOT_RETURNED), r.runtime)
    }
    @Test fun sharedDismissMarksRealReturnBeforeReceiptButDoesNotSkipDetachOrAbsence() {
        val r = Rig()
        dismissManualOverlay({ r.request(); r.removed() }, { OverlayWindowStatus.HIDDEN }, r.barrier, r.trace)
        assertFalse(r.barrier.ready); assertTrue(r.barrier.pending)
        var queries = 0
        r.barrier.processed(r.receipt(), true) { queries++; CleanupWindowPresence.ABSENT }
        assertTrue(r.barrier.ready); assertEquals(1, queries); assertTrue(r.codes.isEmpty())
    }
    @Test fun hiddenAndReturnedWithoutNativeCompletionStayBlockedAndDeadlineReportsThatStage() = runTest {
        val r = Rig { testScheduler.currentTime }
        dismissManualOverlay({ r.request() }, { OverlayWindowStatus.HIDDEN }, r.barrier, r.trace)
        assertFalse(r.barrier.nativeProcessed(r.native, true) { error("NO_QUERY_WITHOUT_NATIVE_COMPLETION") })
        assertFalse(r.barrier.ready)
        val work = async { runCatching { r.barrier.awaitObservationPermit() } }
        runCurrent(); advanceTimeBy(5_000); runCurrent()
        assertTrue(work.await().exceptionOrNull() is CancellationException)
        val code = r.codes.first()
        assertEquals(CleanupFailure.DEADLINE, code.current.barrier)
        assertEquals(CleanupNativeProgress.WAITING_NATIVE_COMPLETION, code.current.native)
        assertFalse(r.barrier.ready)
    }
    @Test fun fullNativeCompletionWithWindowStillPresentNeverGrantsAPermit() = runTest {
        val r = Rig { testScheduler.currentTime }
        dismissManualOverlay({ r.request(); r.removed() }, { OverlayWindowStatus.HIDDEN }, r.barrier, r.trace)
        assertFalse(r.barrier.nativeProcessed(r.native, true) { CleanupWindowPresence.PRESENT })
        val work = async { runCatching { r.barrier.awaitObservationPermit() } }
        runCurrent(); advanceTimeBy(5_000); runCurrent(); assertTrue(work.await().isFailure)
        assertEquals(CleanupNativeProgress.WAITING_WINDOW_ABSENCE, r.codes.first().current.native)
        assertFalse(r.barrier.ready)
    }
    @Test fun removeFailedStateHasAConcreteSourceAndPreservesTheBarrierDetailedCode() {
        val r = Rig(); r.request()
        dismissManualOverlay({}, { OverlayWindowStatus.REMOVE_FAILED }, r.barrier, r.trace)
        assertFalse(r.barrier.ready); assertTrue(r.unhealthy)
        assertEquals(ManualCleanupSite.OVERLAY_REMOVE, r.codes.first().first.site)
        assertEquals(ManualCleanupIssue.REMOVE_FAILED_STATE, r.codes.first().first.issue)
        assertEquals(listOf(CleanupFailure.REMOVE_NOT_HIDDEN), r.runtime)
        assertEquals(CleanupFailure.REMOVE_NOT_HIDDEN, r.codes.last().current.barrier)
        assertEquals(ManualCleanupPhase.CHAIN, r.codes.last().phase)
    }
    @Test fun removeExceptionAndCancellationAreDistinctAndOriginalObjectsPropagate() {
        for (failure in listOf(IllegalStateException("SYNTHETIC"), CancellationException("SYNTHETIC"))) {
            val r = Rig()
            val thrown = runCatching { dismissManualOverlay({ throw failure }, { error("NO_STATE_AFTER_EXCEPTION") }, r.barrier, r.trace) }.exceptionOrNull()
            assertSame(failure, thrown); assertFalse(r.barrier.ready)
            assertEquals(ManualCleanupSite.OVERLAY_REMOVE, r.codes.first().first.site)
            assertEquals(if (failure is CancellationException) ManualCleanupIssue.CANCELLED else ManualCleanupIssue.EXCEPTION,
                r.codes.first().first.issue)
        }
    }
    @Test fun realCKeeperCatchingATtsCallbackErrorCannotHideItOrPermitAnotherPromptInA() {
        val r = Rig(); var removes = 0
        val keeper = OverlayWindowKeeper(object : OverlayWindowOps<Any> {
            override fun add(window: Any) = Unit
            override fun announceShown(window: Any) = Unit
            override fun announceDismissed(window: Any) {
                r.request()
                manualCleanupCall(ManualCleanupSite.CALLBACK_DISMISSED, r.trace, r.barrier) {
                    manualCleanupCall(ManualCleanupSite.TTS_STOP, r.trace, r.barrier) { error("SYNTHETIC") }
                }
            }
            override fun stop(window: Any) = Unit
            override fun remove(window: Any, immediate: Boolean): RemoveOutcome { removes++; r.removed(); return RemoveOutcome.REMOVED }
            override fun blank(window: Any) = Unit
            override fun destroy(window: Any) = Unit
            override fun enterRevalidating(window: Any) = Unit
        })
        assertEquals(PromptShowResult.SHOWN, keeper.show { Any() })
        dismissManualOverlay({ keeper.dismiss() }, { keeper.status }, r.barrier, r.trace)
        assertEquals(1, removes); assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertFalse(r.barrier.ready); assertTrue(r.unhealthy)
        assertEquals(ManualCleanupSite.TTS_STOP, r.codes.first().first.site)
        assertTrue(r.codes.any { it.current.site == ManualCleanupSite.CALLBACK_DISMISSED })
        assertTrue(r.codes.drop(1).all { it.first == r.codes.first().first && it.phase == ManualCleanupPhase.CHAIN })
    }
    @Test fun eachRemainingCleanupRunsAfterRemoveStopAndCallbackFailures() {
        val r = Rig(); val calls = mutableListOf<ManualCleanupSite>()
        val batch = CollectionReleaseBatch()
        for (site in listOf(ManualCleanupSite.OVERLAY_REMOVE, ManualCleanupSite.TTS_STOP, ManualCleanupSite.CALLBACK_DISMISSED)) {
            batch.attempt { manualCleanupCall(site, r.trace, r.barrier) { calls += site; error("SYNTHETIC") } }
        }
        assertTrue(runCatching { batch.finish() }.isFailure)
        assertEquals(3, calls.size); assertFalse(r.barrier.ready)
        assertEquals(ManualCleanupSite.OVERLAY_REMOVE, r.codes.first().first.site)
        assertTrue(r.codes.any { it.current.site == ManualCleanupSite.TTS_STOP })
        assertTrue(r.codes.any { it.current.site == ManualCleanupSite.CALLBACK_DISMISSED })
    }
    @Test fun originalCancellationSurvivesLaterCleanupExceptionsAndAllCallbacksRun() {
        val r = Rig(); val cancellation = CancellationException("SYNTHETIC")
        val calls = mutableListOf<Int>()
        val thrown = runCatching {
            CollectionResourceSafety.run(body = {
                manualCleanupCall(ManualCleanupSite.CALLBACK_DISMISSED, r.trace, r.barrier) { throw cancellation }
            }, cleanup = {
                val batch = CollectionReleaseBatch()
                for (i in 1..3) batch.attempt {
                    calls += i
                    manualCleanupCall(ManualCleanupSite.SERVICE_CLOSE, r.trace, r.barrier) { error("SYNTHETIC") }
                }
                batch.finish()
            }, cleanupFailure = { error("NO_SUCCESS") })
        }.exceptionOrNull()
        assertSame(cancellation, thrown); assertEquals(listOf(1, 2, 3), calls)
        assertEquals(ManualCleanupIssue.CANCELLED, r.codes.first().first.issue)
    }
    @Test fun aThrowingFailureNotificationNeverReplacesTheOriginalCallbackError() {
        val codes = mutableListOf<ManualCleanupCode>(); val trace = ManualCleanupTrace(codes::add)
        val barrier = OverlayCleanupBarrier(onUncertain = { error("SECONDARY") })
        val first = CancellationException("PRIMARY")
        assertSame(first, runCatching {
            manualCleanupCall(ManualCleanupSite.CALLBACK_SHOWN, trace, barrier) { throw first }
        }.exceptionOrNull())
        assertFalse(barrier.ready); assertEquals(ManualCleanupSite.CALLBACK_SHOWN, codes.first().first.site)
        assertTrue(codes.any { it.current.issue == ManualCleanupIssue.FAILURE_CALLBACK_EXCEPTION })
    }
    @Test fun firstFailureStaysFixedAcrossLaterFailCallsAndNewOfferTraceBegin() {
        val seen = mutableListOf<ManualCleanupCode>()
        val prompt = ManualPromptTrace(cleanupSink = { _, c -> seen += c }, sink = { _, _, _ -> })
        val runtime = mutableListOf<CleanupFailure>()
        val b = OverlayCleanupBarrier(failureDiagnostic = runtime::add,
            failureAttemptDiagnostic = { r, n -> prompt.cleanup.record(ManualCleanupSite.BARRIER, ManualCleanupIssue.BARRIER_REJECTED, r, n) })
        prompt.begin(); b.fail(CleanupFailure.RETIREMENT_UNCONFIRMED)
        prompt.begin(); b.fail(CleanupFailure.REMOVE_NOT_HIDDEN)
        assertEquals(listOf(CleanupFailure.RETIREMENT_UNCONFIRMED), runtime)
        assertEquals(CleanupFailure.RETIREMENT_UNCONFIRMED, seen.last().first.barrier)
        assertEquals(CleanupFailure.REMOVE_NOT_HIDDEN, seen.last().current.barrier)
        assertEquals(ManualCleanupPhase.CHAIN, seen.last().phase); assertFalse(b.ready)
    }
    @Test fun detailedRuntimeAndManualPromptBarrierCodesComeFromTheSameFailure() {
        val r = Rig(); r.barrier.retiring(null, 0, null)
        assertEquals(CleanupFailure.RETIREMENT_UNCONFIRMED, r.runtime.single())
        assertEquals(r.runtime.single(), r.codes.single().current.barrier)
    }
    @Test fun disabledOrThrowingDiagnosticsCannotUnblockCleanupOrAuthorizeF9() = runTest {
        for (throwing in listOf(false, true)) {
            val trace = if (throwing) ManualCleanupTrace { throw CancellationException("DIAGNOSTIC") } else null
            val barrier = OverlayCleanupBarrier(failureAttemptDiagnostic = { r, n ->
                trace?.record(ManualCleanupSite.BARRIER, ManualCleanupIssue.BARRIER_REJECTED, r, n)
            })
            barrier.fail(CleanupFailure.RETIREMENT_UNCONFIRMED)
            val f = F9AutoCloserTest.Fixture(); f.state = f.state.copy(cleanupReady = barrier.ready)
            val result = f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference)
            assertFalse(result.attempted); assertEquals(0, f.clicks); assertEquals(F9Block.CLEANUP, result.block)
        }
    }
    @Test fun cleanupJournalIsBoundedAndDoesNotRetainExceptionMessagesOrRawValues() {
        val codes = mutableListOf<ManualCleanupCode>(); val trace = ManualCleanupTrace(codes::add)
        repeat(100) { trace.record(ManualCleanupSite.NATIVE_CALLBACK, ManualCleanupIssue.EXCEPTION) }
        assertEquals(1, codes.size); assertEquals("ManualCleanupTrace(redacted)", trace.toString())
        assertFalse(codes.toString().contains("SYNTHETIC"))
    }
}
