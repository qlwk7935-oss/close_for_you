package kr.closeforyou.accessibility.capture

import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.accessibility.CollectionCleanupFailure
import kr.closeforyou.accessibility.CollectionTaskLease
import org.junit.Assert.*
import org.junit.Test

/** Production BitmapFrame uses exactly this gate; callbacks do not simulate Android native memory. */
class CaptureResourceLifetimeTest {
    @Test fun permitReleasedOnlyAfterBothPixelCleanups() {
        val order = mutableListOf<String>()
        CollectionResourceSafety.releasePixels({ order += "erase" }, { order += "recycle" }, { order += "permit" })
        assertEquals(listOf("erase", "recycle", "permit"), order)
    }
    @Test fun anyPixelCleanupFailureStillAttemptsOtherCleanupButQuarantinesPermit() {
        for (failErase in listOf(false, true)) {
            var erase = false; var recycle = false; var permit = false
            assertThrows(CollectionCleanupFailure::class.java) {
                CollectionResourceSafety.releasePixels({ erase = true; if (failErase) throw IllegalStateException("PRIVATE") },
                    { recycle = true; if (!failErase) throw IllegalStateException("PRIVATE") }, { permit = true })
            }
            assertTrue(erase); assertTrue(recycle); assertFalse(permit)
        }
    }
    @Test fun cancellationDuringPixelCleanupCannotReleasePermitOrSkipOtherCleanup() {
        val original = kotlinx.coroutines.CancellationException("PRIVATE"); var recycled = false; var released = false
        assertSame(original, assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            CollectionResourceSafety.releasePixels({ throw original }, { recycled = true }, { released = true })
        })
        assertTrue(recycled); assertFalse(released)
    }
    @Test fun failureBeforeNativeSubmissionReleasesLeaseOnce() {
        var count = 0; val lease = CollectionTaskLease { count++ }
        lease.submissionFailed(); lease.submissionFailed(); lease.completed()
        assertEquals(1, count)
    }
    @Test fun nativeSubmissionExceptionCannotRecyclePixelsBeforeActualCompletion() {
        var count = 0; val lease = CollectionTaskLease { count++ }
        lease.beforeSubmit(); lease.submissionFailed()
        assertEquals(0, count)
        lease.completed(); assertEquals(1, count)
    }
    @Test fun listenerRegistrationFailureAndCancellationKeepTheSameLeaseUntilCompletion() {
        var count = 0; val lease = CollectionTaskLease { count++ }
        lease.beforeSubmit()
        repeat(3) { lease.submissionFailed() }
        assertEquals(0, count)
        lease.completed(); lease.completed(); assertEquals(1, count)
    }
    @Test fun synchronousCompletionFollowedBySubmissionExceptionDoesNotReleaseTwice() {
        var count = 0; val lease = CollectionTaskLease { count++ }
        lease.beforeSubmit(); lease.completed(); lease.submissionFailed()
        assertEquals(1, count)
    }
    @Test fun failedCompletionCleanupCannotBeRetriedOrBecomeSuccess() {
        var count = 0; val lease = CollectionTaskLease { count++; throw CollectionCleanupFailure() }
        lease.beforeSubmit()
        assertThrows(CollectionCleanupFailure::class.java) { lease.completed() }
        lease.submissionFailed()
        assertThrows(CollectionCleanupFailure::class.java) { lease.completed() }
        assertEquals(1, count)
    }
    @Test fun completedOrAlreadySubmittedLeaseCannotSubmitAgain() {
        val first = CollectionTaskLease {}; first.beforeSubmit()
        assertThrows(IllegalStateException::class.java) { first.beforeSubmit() }
        first.completed()
        val second = CollectionTaskLease {}; second.submissionFailed()
        assertThrows(IllegalStateException::class.java) { second.beforeSubmit() }
    }
    @Test fun nativeLeaseRepresentationDoesNotExposeItsCallbackOrValues() {
        val secret = "PRIVATE_TEXT_COORDINATES_TOKEN"
        val lease = CollectionTaskLease { assertTrue(secret.isNotEmpty()) }
        assertEquals("CollectionTaskLease(redacted)", lease.toString())
        lease.submissionFailed()
    }
}
