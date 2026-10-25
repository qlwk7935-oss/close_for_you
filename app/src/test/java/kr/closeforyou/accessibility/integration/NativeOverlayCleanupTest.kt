package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import android.view.accessibility.AccessibilityEvent as E
import kr.closeforyou.ui.voice.overlay.OverlayNativeWindowLifetime
import org.junit.Assert.*
import org.junit.Test

class NativeOverlayCleanupTest {
    private fun native(session: Any, generation: Long = 1, id: Int = 20) =
        OverlayNativeWindowLifetime(generation, session).also { it.attached(id); it.removalRequested() }

    @Test fun unboundButAuthenticatedNativeViewRemovalAndFullAbsenceRestoreAdmission() {
        val session = Any(); val n = native(session); val b = OverlayCleanupBarrier()
        b.retiring(null, 1, 10, n, session)
        assertTrue(b.pending); assertNull(b.permit())
        b.removalReturned(true); n.removalReturned(true); n.detached()
        var refresh = 0
        assertTrue(b.nativeProcessed(n, true, { refresh++ }) { assertEquals(20, it); CleanupWindowPresence.ABSENT })
        assertEquals(1, refresh); assertTrue(b.ready)
    }

    @Test fun removalReturnOrAbsenceWithoutActualDetachCannotRelease() = runTest {
        val session = Any(); val n = native(session); val b = OverlayCleanupBarrier(10)
        b.retiring(null, 1, 10, n, session); b.removalReturned(true); n.removalReturned(true)
        assertFalse(b.nativeProcessed(n, true) { error("NO_QUERY_BEFORE_DETACH") })
        assertTrue(b.pending); assertNull(b.permit())
        b.fail(); assertTrue(runCatching { b.awaitObservationPermit() }.exceptionOrNull() is CancellationException)
    }

    @Test fun missingIdentityWrongSessionGenerationOrConflictingBoundIdStayUnavailable() {
        val owner = Any()
        val unknown = OverlayNativeWindowLifetime(1, owner).also { it.removalRequested() }
        for ((n, session, generation, bound) in listOf(
            listOf(unknown, owner, 1L, null),
            listOf(native(Any()), owner, 1L, null),
            listOf(native(owner, 2), owner, 1L, null),
            listOf(native(owner), owner, 1L, 21),
            listOf(native(owner, id = 10), owner, 1L, null))) {
            val b = OverlayCleanupBarrier()
            b.retiring(bound as Int?, generation as Long, 10, n as OverlayNativeWindowLifetime, session!!)
            assertFalse(b.ready); assertFalse(b.pending); assertNull(b.permit())
        }
    }

    @Test fun failedRemovalPresentUnknownAndHiddenMismatchNeverRelease() {
        for (mode in listOf("REMOVE", "PRESENT", "UNKNOWN", "VISIBLE")) {
            val owner = Any(); val n = native(owner); val b = OverlayCleanupBarrier()
            b.retiring(null, 1, 10, n, owner)
            b.removalReturned(mode != "REMOVE"); n.removalReturned(mode != "REMOVE"); n.detached()
            assertFalse(b.nativeProcessed(n, mode != "VISIBLE") {
                when (mode) { "PRESENT" -> CleanupWindowPresence.PRESENT; "UNKNOWN" -> CleanupWindowPresence.UNKNOWN
                    else -> CleanupWindowPresence.ABSENT }
            })
            assertNull(b.permit())
        }
    }

    @Test fun oldGenerationAndOtherServiceCallbacksCannotTouchNewRetirement() {
        val owner = Any(); val old = native(owner); val current = native(owner, 2, 20) // Reused numeric ID.
        old.removalReturned(true); old.detached()
        val b = OverlayCleanupBarrier(); b.retiring(null, 2, 10, current, owner)
        assertFalse(b.nativeProcessed(old, true) { error("OLD_VIEW_MUST_NOT_QUERY") })
        val foreign = native(Any(), 2, 20).also { it.removalReturned(true); it.detached() }
        assertFalse(b.nativeProcessed(foreign, true) { error("OTHER_SESSION_MUST_NOT_QUERY") })
        assertTrue(b.pending); assertNull(b.permit())
        b.removalReturned(true); current.removalReturned(true); current.detached()
        assertTrue(b.nativeProcessed(current, true) { CleanupWindowPresence.ABSENT })
    }

    @Test fun clockCannotRenewDeadlineAndLateProofCannotReviveFailure() {
        var now = 0L; val owner = Any(); val n = native(owner)
        val b = OverlayCleanupBarrier(10, clock = { now })
        b.retiring(null, 1, 10, n, owner); b.removalReturned(true)
        n.removalReturned(true); n.detached(); now = 10
        assertFalse(b.nativeProcessed(n, true) { CleanupWindowPresence.ABSENT })
        assertNull(b.permit()); now = 0
        assertFalse(b.nativeProcessed(n, true) { error("FAILED_LIFETIME_MUST_NOT_QUERY") })
        assertNull(b.permit())
    }

    @Test fun queryReentryRefreshFailureAndRefreshPastDeadlineCannotRelease() {
        for (mode in listOf("QUERY_REENTRY", "REFRESH_THROW", "REFRESH_DEADLINE")) {
            var now = 0L; val owner = Any(); val n = native(owner)
            val b = OverlayCleanupBarrier(10, clock = { now })
            b.retiring(null, 1, 10, n, owner); b.removalReturned(true)
            n.removalReturned(true); n.detached()
            assertFalse(b.nativeProcessed(n, true, {
                if (mode == "REFRESH_THROW") error("SYNTHETIC_REFRESH_FAILURE")
                if (mode == "REFRESH_DEADLINE") now = 10
            }) {
                if (mode == "QUERY_REENTRY") b.fail()
                CleanupWindowPresence.ABSENT
            })
            assertNull(b.permit())
        }
    }

    @Test fun delayedAndroidRemovalForReusedIdCannotReplaceThisGenerationsNativeDetach() {
        val owner = Any(); val n = native(owner, 2, 20); val b = OverlayCleanupBarrier()
        b.retiring(20, 2, 10, n, owner); b.removalReturned(true); n.removalReturned(true)
        val receipt = requireNotNull(b.observeWindowEvent(20, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        b.processed(receipt, true) { CleanupWindowPresence.ABSENT }
        assertTrue(b.pending); assertNull(b.permit())
        n.detached()
        assertTrue(b.nativeProcessed(n, true) { CleanupWindowPresence.ABSENT })
    }

    @Test fun nativeDetachBeforeWindowListRemovalWaitsForTheActualRemovalWithoutPoisoningAdmission() {
        val owner = Any(); val n = native(owner); var unhealthy = false
        val b = OverlayCleanupBarrier(onUncertain = { unhealthy = true })
        b.retiring(null, 1, 10, n, owner); b.removalReturned(true); n.removalReturned(true); n.detached()
        assertFalse(b.nativeProcessed(n, true) { CleanupWindowPresence.PRESENT })
        assertTrue(b.pending); assertFalse(unhealthy); assertNull(b.permit())
        val receipt = requireNotNull(b.observeWindowEvent(20, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        b.processed(receipt, true) { CleanupWindowPresence.ABSENT }
        assertTrue(b.ready); assertFalse(unhealthy)
    }

    @Test fun contentForTheAlreadyDetachedExactViewCannotReplaceOrPoisonPendingRemovalBut128StillFails() {
        val owner = Any(); val n = native(owner); var unhealthy = false
        val b = OverlayCleanupBarrier(onUncertain = { unhealthy = true })
        b.retiring(null, 1, 10, n, owner); b.removalReturned(true); n.removalReturned(true); n.detached()
        assertFalse(b.nativeProcessed(n, true) { CleanupWindowPresence.PRESENT })
        assertNull(b.observeWindowEvent(20, E.TYPE_WINDOW_CONTENT_CHANGED, 0))
        assertTrue(b.pending); assertFalse(unhealthy); assertNull(b.permit())
        val receipt = requireNotNull(b.observeWindowEvent(20, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        b.processed(receipt, true) { CleanupWindowPresence.ABSENT }; assertTrue(b.ready)

        val focus = native(owner, 2); val blocked = OverlayCleanupBarrier()
        blocked.retiring(null, 2, 10, focus, owner); blocked.removalReturned(true)
        focus.removalReturned(true); focus.detached()
        assertNull(blocked.observeWindowEvent(20, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED))
        assertFalse(blocked.ready); assertFalse(blocked.pending); assertNull(blocked.permit())
    }

    @Test fun contentBeforeDetachAfterReceiptOrWithoutServiceOwnershipStillFails() {
        for (mode in listOf("NO_DETACH", "AFTER_RECEIPT", "WRONG_OWNER")) {
            val owner = Any(); val n = native(if (mode == "WRONG_OWNER") Any() else owner)
            val b = OverlayCleanupBarrier()
            b.retiring(null, 1, 10, n, owner); b.removalReturned(true); n.removalReturned(true)
            if (mode != "NO_DETACH") n.detached()
            if (mode == "AFTER_RECEIPT")
                assertNotNull(b.observeWindowEvent(20, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
            assertNull(b.observeWindowEvent(20, E.TYPE_WINDOW_CONTENT_CHANGED, 0))
            assertFalse(b.ready); assertFalse(b.pending); assertNull(b.permit())
        }
    }
}
