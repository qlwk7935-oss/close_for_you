package kr.closeforyou.accessibility

import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kr.closeforyou.core.model.PrivacyBlockReason
import org.junit.Assert.*
import org.junit.Test

class CollectionContinuityTest {
    @Test fun normalAndExactChildLimitAreAccepted() {
        CollectionChildCountCheck.requireCount(0, 0); CollectionChildCountCheck.requireCount(1_200, 1_200)
    }
    @Test fun negativeCountCannotHideUnobservedChildren() {
        val f = assertThrows(CollectionGuardFailure::class.java) { CollectionChildCountCheck.requireCount(-1) }
        assertEquals(PrivacyBlockReason.UNREADABLE_TREE, f.reason)
    }
    @Test fun childCountChangesCannotUseOriginalFingerprintAsComplete() {
        for (end in listOf(0, 2)) assertThrows(CollectionGuardFailure::class.java) { CollectionChildCountCheck.requireCount(end, 1) }
    }
    @Test fun oversizedCountKeepsOriginalLimit() {
        assertEquals(CollectionFinding.CHILD_COUNT_LIMIT, assertThrows(CollectionGuardFailure::class.java) {
            CollectionChildCountCheck.requireCount(1_201)
        }.finding)
    }
    @Test fun activeOwnerAndCurrentScreenPermitOnlyContinuation() {
        val state = ScreenFreshness(); val epoch = state.invalidate(); val owner = Job()
        state.checkActive(epoch, owner) { true }; owner.cancel()
    }
    @Test fun cancelledOwnerIsCheckedBeforeFurtherAvailabilityReads() {
        val state = ScreenFreshness(); val owner = Job().apply { cancel() }; var reads = 0
        assertThrows(CancellationException::class.java) { state.checkActive(0, owner) { reads++; true } }
        assertEquals(0, reads)
    }
    @Test fun consecutiveEventsRejectEveryPreviousEpoch() {
        val state = ScreenFreshness(); val old = state.invalidate(); state.invalidate(); val latest = state.invalidate()
        val owner = Job()
        assertThrows(CancellationException::class.java) { state.checkActive(old, owner) { true } }
        state.checkActive(latest, owner) { true }; owner.cancel()
    }
    @Test fun disconnectTouchOrPolicyRevocationRejectContinuation() {
        val state = ScreenFreshness(); val owner = Job()
        assertThrows(CancellationException::class.java) { state.checkActive(0, owner) { false } }; owner.cancel()
    }
    @Test fun cancellationDuringAvailabilityReadCannotBeAccepted() {
        val state = ScreenFreshness(); val owner = Job()
        assertThrows(CancellationException::class.java) { state.checkActive(0, owner) { owner.cancel(); true } }
    }
    @Test fun screenChangeDuringAvailabilityReadCannotBeAccepted() {
        val state = ScreenFreshness(); val owner = Job()
        assertThrows(CancellationException::class.java) { state.checkActive(0, owner) { state.invalidate(); true } }
        owner.cancel()
    }
}
