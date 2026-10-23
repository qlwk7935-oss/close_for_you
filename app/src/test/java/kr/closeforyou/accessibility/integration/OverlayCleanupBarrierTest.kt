package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayCleanupBarrierTest {
    private fun retiring(barrier: OverlayCleanupBarrier) {
        barrier.retiring(20, 1, 10); barrier.removalReturned(true)
    }
    private fun ack(barrier: OverlayCleanupBarrier) {
        val receipt = requireNotNull(barrier.observe(20, true, true))
        barrier.processed(receipt, true) { CleanupWindowPresence.ABSENT }
    }
    @Test fun lateRemovalMustBeProcessedBeforeAnyNewObservationPermit() = runTest {
        val barrier = OverlayCleanupBarrier(); val old = barrier.awaitObservationPermit()
        retiring(barrier); assertFalse(barrier.permits(old))
        val waiting = async { barrier.awaitObservationPermit() }; runCurrent()
        assertFalse(waiting.isCompleted)
        val receipt = requireNotNull(barrier.observe(20, true, true)); runCurrent()
        assertFalse(waiting.isCompleted) // Receiving the event is not handling it.
        barrier.processed(receipt, true) { CleanupWindowPresence.ABSENT }; runCurrent()
        assertTrue(barrier.permits(waiting.await())); assertFalse(barrier.permits(old))
    }
    @Test fun refreshCancelsOldWaiterBeforeInlineWakeupAndStartsOnlyFreshObservation() = runTest {
        val barrier = OverlayCleanupBarrier(); retiring(barrier)
        val order = mutableListOf<String>()
        val stale = async(UnconfinedTestDispatcher(testScheduler)) {
            barrier.awaitObservationPermit(); order += "STALE_PUBLICATION"
        }
        val receipt = requireNotNull(barrier.observe(20, true, true))
        var fresh: Job? = null
        barrier.processed(receipt, true, beforeRelease = {
            assertTrue(barrier.pending); stale.cancel(); order += "REFRESH"
            fresh = launch(UnconfinedTestDispatcher(testScheduler)) {
                barrier.awaitObservationPermit(); order += "F0"
            }
        }) { CleanupWindowPresence.ABSENT }
        stale.join(); fresh!!.join()
        assertEquals(listOf("REFRESH", "F0"), order)
    }
    @Test fun failedFreshObservationSchedulingCannotReleaseAdmission() {
        val barrier = OverlayCleanupBarrier(); retiring(barrier)
        barrier.processed(requireNotNull(barrier.observe(20, true, true)), true,
            beforeRelease = { error("SYNTHETIC_SECRET") }) { CleanupWindowPresence.ABSENT }
        assertFalse(barrier.ready); assertNull(barrier.permit())
    }
    @Test fun repeatedWaiterCancellationCannotRenewCleanupDeadlineAndLateReceiptCannotReopen() = runTest {
        val barrier = OverlayCleanupBarrier(50, clock = { testScheduler.currentTime }); retiring(barrier)
        repeat(4) {
            val waiter = launch { barrier.awaitObservationPermit() }
            runCurrent(); advanceTimeBy(10); waiter.cancelAndJoin()
        }
        advanceTimeBy(10)
        val receipt = requireNotNull(barrier.observe(20, true, true))
        barrier.processed(receipt, true) { CleanupWindowPresence.ABSENT }
        assertFalse(barrier.ready); assertNull(barrier.permit())
    }
    @Test fun absenceAndRemovalReturnWithoutEventCannotCompleteAndTimeoutNeverGrantsPermission() = runTest {
        val health = ChoicePresentationHealth()
        val barrier = OverlayCleanupBarrier(50, health::fail); retiring(barrier)
        val waiting = async { runCatching { barrier.awaitObservationPermit() }.exceptionOrNull() }
        runCurrent(); advanceTimeBy(50); runCurrent()
        assertTrue(waiting.await() is CancellationException); assertFalse(barrier.ready); assertTrue(health.failed)
        assertNull(barrier.observe(20, true, true)); assertNull(barrier.permit())
    }
    @Test fun cancellingOneWaiterDoesNotCancelSharedCleanupOrAnotherWaiter() = runTest {
        val barrier = OverlayCleanupBarrier(); retiring(barrier)
        val cancelled = async { barrier.awaitObservationPermit() }
        val survivor = async { barrier.awaitObservationPermit() }; runCurrent()
        cancelled.cancelAndJoin(); ack(barrier); runCurrent()
        assertTrue(barrier.permits(survivor.await())); assertTrue(cancelled.isCancelled)
    }
    @Test fun sameTokenPresentationThatWaitedIsDiscardedRatherThanReportedBlockedOrReplayed() = runTest {
        val barrier = OverlayCleanupBarrier(); retiring(barrier)
        val wait = async { barrier.awaitPresentation() }; runCurrent(); assertFalse(wait.isCompleted)
        ack(barrier); runCurrent(); assertFalse(wait.await())
        assertTrue(barrier.awaitPresentation()) // A newly observed candidate can enter separately.
    }
    @Test fun mixedMaskReappearanceOrOtherEventWithRetiredIdFailsClosed() = runTest {
        for (windowsChanged in listOf(true, false)) {
            val barrier = OverlayCleanupBarrier(); retiring(barrier)
            assertNull(barrier.observe(20, windowsChanged, false)); assertFalse(barrier.ready)
            assertTrue(runCatching { barrier.awaitObservationPermit() }.exceptionOrNull() is CancellationException)
        }
    }
    @Test fun unrelatedTargetEventDoesNotAcknowledgeAnOldWindow() {
        val barrier = OverlayCleanupBarrier(); retiring(barrier)
        assertNull(barrier.observe(10, true, true)); assertTrue(barrier.pending)
        ack(barrier); assertTrue(barrier.ready)
    }
    @Test fun unknownGenerationIdentityTargetCollisionOrOverlappingRetirementCannotAuthorize() {
        val cases = listOf(Triple(null, 1L, 10), Triple(20, 0L, 10), Triple(10, 1L, 10), Triple(-1, 1L, 10))
        for ((id, generation, target) in cases) {
            val barrier = OverlayCleanupBarrier(); barrier.retiring(id, generation, target)
            assertFalse(barrier.ready); assertNull(barrier.permit())
        }
        val barrier = OverlayCleanupBarrier(); retiring(barrier); barrier.retiring(30, 2, 11)
        assertFalse(barrier.ready); assertNull(barrier.permit())
    }
    @Test fun reusedIdStillPresentQueryFailureOrRemovalFailureNeverComplete() {
        for (presence in listOf(CleanupWindowPresence.PRESENT, CleanupWindowPresence.UNKNOWN)) {
            val barrier = OverlayCleanupBarrier(); retiring(barrier)
            val receipt = requireNotNull(barrier.observe(20, true, true))
            barrier.processed(receipt, true) { presence }; assertFalse(barrier.ready)
        }
        val failed = OverlayCleanupBarrier(); failed.retiring(20, 1, 10); failed.removalReturned(false)
        assertFalse(failed.ready); assertNull(failed.observe(20, true, true))
        val thrown = OverlayCleanupBarrier(); retiring(thrown)
        thrown.processed(requireNotNull(thrown.observe(20, true, true)), true) { error("SYNTHETIC_SECRET") }
        assertFalse(thrown.ready)
    }
    @Test fun noRemovalReturnOrStillShowingIsNotSuccessEvenAfterEventAndAbsence() {
        for (returned in listOf(true, false)) {
            val barrier = OverlayCleanupBarrier(); barrier.retiring(20, 1, 10)
            if (returned) barrier.removalReturned(true)
            barrier.processed(requireNotNull(barrier.observe(20, true, true)), !returned) { CleanupWindowPresence.ABSENT }
            assertFalse(barrier.ready)
        }
    }
    @Test fun staleReceiptCannotReleaseAnotherGenerationAndDuplicatePendingReceiptFailsClosed() {
        val barrier = OverlayCleanupBarrier(); retiring(barrier)
        val first = requireNotNull(barrier.observe(20, true, true))
        barrier.processed(first, true) { CleanupWindowPresence.ABSENT }
        barrier.retiring(30, 2, 11); barrier.removalReturned(true)
        var reads = 0
        barrier.processed(first, true) { reads++; CleanupWindowPresence.ABSENT }
        assertEquals(0, reads); assertTrue(barrier.pending)
        assertNotNull(barrier.observe(30, true, true)); assertNull(barrier.observe(30, true, true))
        assertFalse(barrier.ready)
    }
    @Test fun closeCancelsAdmissionAndOnlyUnconfirmedCleanupQuarantinesReconnection() = runTest {
        val clean = ChoicePresentationHealth(); OverlayCleanupBarrier(onUncertain = clean::fail).close()
        assertFalse(clean.failed)
        val health = ChoicePresentationHealth(); val barrier = OverlayCleanupBarrier(onUncertain = health::fail)
        retiring(barrier); val wait = async { runCatching { barrier.awaitObservationPermit() }.exceptionOrNull() }
        runCurrent(); barrier.close(); runCurrent()
        assertTrue(wait.await() is CancellationException); assertTrue(health.failed)
        // Service factory shares this health; a new barrier alone cannot reset it.
    }
    private class Window(val id: Int)
    @Test fun fullListQueryReleasesAllWindowsAndDoesNotTreatUnreadableOrEmptyListAsAbsence() {
        val cases = listOf(emptyList(), listOf(Window(20)), listOf(Window(10)), listOf(Window(-1)),
            listOf(Window(10), Window(10)), (1..33).map { Window(it) })
        val expected = listOf(CleanupWindowPresence.UNKNOWN, CleanupWindowPresence.PRESENT, CleanupWindowPresence.ABSENT,
            CleanupWindowPresence.UNKNOWN, CleanupWindowPresence.UNKNOWN, CleanupWindowPresence.UNKNOWN)
        for ((index, windows) in cases.withIndex()) {
            val released = mutableListOf<Window>()
            assertEquals(expected[index], cleanupWindowPresence(20, { windows }, { it.id }, { released += it }))
            assertEquals(windows.size, released.size)
        }
    }
    @Test fun queryIdAndReleaseExceptionsIncludingCancellationAreUnknownAndAllAcquiredWindowsAreReleased() {
        for (step in listOf("READ", "ID", "RELEASE")) for (cancel in listOf(true, false)) {
            val windows = listOf(Window(10), Window(11)); var releases = 0
            fun fault() { if (cancel) throw CancellationException("SYNTHETIC_SECRET") else error("SYNTHETIC_SECRET") }
            assertEquals(CleanupWindowPresence.UNKNOWN, cleanupWindowPresence(20,
                { if (step == "READ") fault(); windows }, { if (step == "ID") fault(); it.id },
                { releases++; if (step == "RELEASE") fault() }))
            assertEquals(if (step == "READ") 0 else 2, releases)
        }
    }
    @Test fun duplicateObjectIsReleasedOnceAndPublicStringsAreRedacted() {
        val shared = Window(10); var released = 0
        assertEquals(CleanupWindowPresence.UNKNOWN, cleanupWindowPresence(20, { listOf(shared, shared) }, { it.id }, { released++ }))
        assertEquals(1, released)
        val barrier = OverlayCleanupBarrier(); retiring(barrier)
        assertEquals("OverlayCleanupBarrier(redacted)", barrier.toString())
        assertEquals("CleanupReceipt(redacted)", barrier.observe(20, true, true).toString())
    }
}
