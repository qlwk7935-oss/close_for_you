package kr.closeforyou.accessibility.action

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test

class ActionAttemptLedgerTest {
    @Test fun unknownEpisodeCannotBeRegisteredByAUuidOrToken() {
        val owner = ActionAttemptLedger()
        val foreign = ActionAttemptLedger().syntheticEpisode()
        assertEquals(GateDenial.POPUP_IDENTITY_UNPROVEN, owner.reserveReview(foreign, GateCheck.PASSED).denial)
        assertEquals(GateDenial.POPUP_IDENTITY_UNPROVEN, owner.reserveReview(PopupEpisode(), GateCheck.PASSED).denial)
        assertEquals(ActionAttemptLedger.ReviewState.AVAILABLE, owner.state())
    }

    @Test fun continuityMustBeProvenAndLossCannotBeReset() {
        val ledger = ActionAttemptLedger()
        GateCheck.entries.filter { it != GateCheck.PASSED }.forEach {
            assertEquals(GateDenial.POPUP_IDENTITY_UNPROVEN, ledger.reserveReview(ledger.syntheticEpisode(), it).denial)
        }
        ledger.invalidateContinuity()
        assertEquals(GateDenial.POPUP_IDENTITY_UNPROVEN, ledger.reserveReview(ledger.syntheticEpisode(), GateCheck.PASSED).denial)
    }

    @Test fun concurrentThreadsCanReserveExactlyOneReview() {
        val ledger = ActionAttemptLedger()
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = pool.invokeAll((1..32).map { Callable { ledger.reserveReview(ledger.syntheticEpisode(), GateCheck.PASSED) } }).map { it.get() }
            assertEquals(1, results.count { it.reservation != null })
            assertEquals(31, results.count { it.denial == GateDenial.REVIEW_ALREADY_RESERVED })
            assertFalse(ledger.attempted)
        } finally { pool.shutdownNow() }
    }

    @Test fun completedFailedAndCancelledReviewsCannotRetryAndAreNotAttempts() {
        for (terminal in listOf(ActionAttemptLedger.ReviewState.COMPLETED, ActionAttemptLedger.ReviewState.FAILED, ActionAttemptLedger.ReviewState.CANCELLED)) {
            val ledger = ActionAttemptLedger()
            val episode = ledger.syntheticEpisode()
            val reservation = ledger.reserveReview(episode, GateCheck.PASSED).reservation!!
            if (terminal == ActionAttemptLedger.ReviewState.COMPLETED)
                assertNull(ledger.completeReview(reservation, 0, 5_000, GateClock { 100 }, Job()))
            else ledger.finishReview(reservation, terminal)
            assertEquals(terminal, ledger.state())
            assertEquals(GateDenial.REVIEW_ALREADY_FINISHED, ledger.reserveReview(episode, GateCheck.PASSED).denial)
            assertFalse(ledger.attempted)
        }
    }

    @Test fun staleOrForgedReservationCannotFinishAnotherReview() {
        val ledger = ActionAttemptLedger()
        ledger.reserveReview(ledger.syntheticEpisode(), GateCheck.PASSED)
        ledger.finishReview(ActionAttemptLedger.Reservation(), ActionAttemptLedger.ReviewState.FAILED)
        assertEquals(ActionAttemptLedger.ReviewState.RESERVED, ledger.state())
        assertEquals(GateDenial.REVIEW_ALREADY_FINISHED,
            ledger.completeReview(ActionAttemptLedger.Reservation(), 0, 5_000, GateClock { 100 }, Job()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun finishingCannotReopenTheLedger() {
        val ledger = ActionAttemptLedger()
        val reservation = ledger.reserveReview(ledger.syntheticEpisode(), GateCheck.PASSED).reservation!!
        ledger.finishReview(reservation, ActionAttemptLedger.ReviewState.AVAILABLE)
    }

    @Test fun completionReadsFreshClockUnderMonitorAtExclusiveDeadline() {
        for (time in listOf(4_999L, 5_000L)) {
            val ledger = ActionAttemptLedger()
            val reservation = ledger.reserveReview(ledger.syntheticEpisode(), GateCheck.PASSED).reservation!!
            var reads = 0
            val reason = ledger.completeReview(reservation, 0, 5_000, GateClock {
                reads++
                assertTrue(Thread.holdsLock(ledger))
                time
            }, Job())
            assertEquals(1, reads)
            if (time == 4_999L) {
                assertNull(reason)
                assertEquals(ActionAttemptLedger.ReviewState.COMPLETED, ledger.state())
            } else {
                assertEquals(GateDenial.TTL_EXPIRED, reason)
                assertEquals(ActionAttemptLedger.ReviewState.RESERVED, ledger.state())
                // As in inspect(): denial cleanup closes the reserved review without retry.
                ledger.finishReview(reservation, ActionAttemptLedger.ReviewState.FAILED)
                assertEquals(ActionAttemptLedger.ReviewState.FAILED, ledger.state())
            }
            assertFalse(ledger.attempted)
        }
    }

    @Test fun continuityIsRecheckedAfterCompletionClockCallback() {
        val ledger = ActionAttemptLedger()
        val reservation = ledger.reserveReview(ledger.syntheticEpisode(), GateCheck.PASSED).reservation!!
        val denial = ledger.completeReview(reservation, 0, 5_000, GateClock {
            ledger.invalidateContinuity()
            100
        }, Job())
        assertEquals(GateDenial.POPUP_IDENTITY_UNPROVEN, denial)
        ledger.finishReview(reservation, ActionAttemptLedger.ReviewState.FAILED)
        assertEquals(ActionAttemptLedger.ReviewState.FAILED, ledger.state())
        assertEquals(GateDenial.POPUP_IDENTITY_UNPROVEN,
            ledger.reserveReview(ledger.syntheticEpisode(), GateCheck.PASSED).denial)
    }
}
