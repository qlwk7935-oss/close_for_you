package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kr.closeforyou.ui.voice.overlay.NativeWindowIdObservation as Site
import kr.closeforyou.ui.voice.overlay.OverlayNativeWindowLifetime as Native
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PreBindingNativeIdentityTest {
    @Test fun unavailableAttachReadCanBeFollowedByTheSameViewsFirstDrawIdentity() {
        val owner = Any(); val n = Native(1, owner)
        n.addReturned(true); n.attached(null)
        n.observeWindowId(Site.ATTACH) { null }
        n.observeWindowId(Site.FIRST_DRAW) { 20 }
        n.removalRequested()
        assertEquals(20, n.retirementWindow(owner, 1))
    }

    @Test fun cancellationBeforeAttachWaitsWithoutPermitThenRequiresRealDetachAndAbsence() {
        val owner = Any(); val n = Native(1, owner); val b = OverlayCleanupBarrier()
        n.addReturned(true); n.removalRequested(); b.retiring(null, 1, 10, n, owner)
        assertTrue(b.pending); assertNull(b.permit())
        n.removalReturned(true); b.removalReturned(true)
        assertFalse(b.nativeProcessed(n, true) { error("NO_WINDOW_QUERY_WITHOUT_ID") })
        n.attached(null); n.observeWindowId(Site.ATTACH) { 20 }
        assertFalse(b.nativeProcessed(n, true) { error("NO_ABSENCE_WITHOUT_DETACH") })
        n.detached()
        assertTrue(b.nativeProcessed(n, true) { assertEquals(20, it); CleanupWindowPresence.ABSENT })
        assertTrue(b.ready)
    }

    @Test fun cancelBeforeAttachWithNoFurtherNativeIdentityNeverReleasesAndKeepsOriginalDeadline() = runTest {
        val owner = Any(); val n = Native(1, owner)
        val b = OverlayCleanupBarrier(20, clock = { testScheduler.currentTime })
        n.addReturned(true); n.removalRequested(); b.retiring(null, 1, 10, n, owner)
        n.removalReturned(true); b.removalReturned(true)
        assertTrue(b.pending)
        val result = async { runCatching { b.awaitObservationPermit() }.exceptionOrNull() }
        assertTrue(result.await() is CancellationException)
        assertFalse(b.ready); assertNull(b.permit())
        n.attached(20); n.detached()
        assertFalse(b.nativeProcessed(n, true) { error("DEADLINE_CANNOT_BE_RENEWED") })
    }

    @Test fun identityObservedInsideDetachCallbackCanCompleteButNeverAfterDetach() {
        val owner = Any(); val n = Native(1, owner); val b = OverlayCleanupBarrier()
        n.addReturned(true); n.attached(null); n.removalRequested()
        b.retiring(null, 1, 10, n, owner); b.removalReturned(true); n.removalReturned(true)
        assertTrue(b.pending)
        n.observeWindowId(Site.DETACH) { 20 } // Real adapter reads before recording callback completion.
        n.detached()
        assertTrue(b.nativeProcessed(n, true) { CleanupWindowPresence.ABSENT })

        val missing = Native(2, owner); val blocked = OverlayCleanupBarrier()
        missing.addReturned(true); missing.attached(null); missing.removalRequested()
        blocked.retiring(null, 2, 10, missing, owner); blocked.removalReturned(true); missing.removalReturned(true)
        missing.detached(); var reads = 0
        missing.observeWindowId(Site.DETACH) { reads++; 21 }
        assertEquals(0, reads)
        assertFalse(blocked.nativeProcessed(missing, true) { error("UNKNOWN_ID_MUST_NOT_QUERY") })
        assertNull(blocked.permit())
    }

    @Test fun lateOldViewAndOtherServiceCannotResolveTheNewViewsUnknownIdEvenWhenIdsAreReused() {
        val owner = Any(); val current = Native(2, owner); val b = OverlayCleanupBarrier()
        current.addReturned(true); current.removalRequested(); b.retiring(null, 2, 10, current, owner)
        current.removalReturned(true); b.removalReturned(true)
        val old = Native(1, owner).also { it.attached(20); it.removalRequested(); it.removalReturned(true); it.detached() }
        val foreign = Native(2, Any()).also { it.attached(20); it.removalRequested(); it.removalReturned(true); it.detached() }
        assertFalse(b.nativeProcessed(old, true) { error("OLD_VIEW_MUST_NOT_RESOLVE_CURRENT") })
        assertFalse(b.nativeProcessed(foreign, true) { error("OTHER_SERVICE_MUST_NOT_RESOLVE_CURRENT") })
        assertTrue(b.pending); assertNull(b.permit())
        var reads = 0
        current.observeWindowId(Site.FIRST_DRAW) { reads++; 20 }
        assertEquals(0, reads) // No actual attach observed yet.
        current.attached(null); current.observeWindowId(Site.ATTACH) { 20 }; current.detached()
        assertTrue(b.nativeProcessed(current, true) { CleanupWindowPresence.ABSENT })
    }

    @Test fun getterOpportunitiesAreBoundedAndStopAfterIdentityOrDetach() {
        val owner = Any(); val n = Native(1, owner); n.addReturned(true); n.attached(null)
        var reads = 0
        repeat(100) { for (site in Site.entries) n.observeWindowId(site) { reads++; null } }
        assertEquals(4, reads)
        n.removalRequested(); n.detached()
        repeat(100) { n.observeWindowId(Site.FIRST_DRAW) { reads++; 20 } }
        assertEquals(4, reads)
        val known = Native(2, owner).also { it.attached(20) }
        known.observeWindowId(Site.FIRST_DRAW) { reads++; 21 }
        assertEquals(4, reads)
    }

    @Test fun failedAddRemoveWrongSessionAndChangedGenerationStayBlocked() {
        val owner = Any()
        for (mode in listOf("ADD", "REMOVE", "SESSION", "GENERATION")) {
            val n = Native(1, if (mode == "SESSION") Any() else owner)
            n.addReturned(mode != "ADD")
            if (mode == "REMOVE") n.attached(20)
            n.removalRequested(); val b = OverlayCleanupBarrier()
            b.retiring(null, if (mode == "GENERATION") 2 else 1, 10, n, owner)
            if (mode == "REMOVE") { n.removalReturned(false); b.removalReturned(false) }
            assertFalse(b.ready); assertNull(b.permit())
            if (mode != "REMOVE") assertFalse(b.pending)
        }
    }

    @Test fun observedTargetIdAndASecondAttachmentNeverBecomeCleanupAuthority() {
        val owner = Any(); val n = Native(1, owner); val b = OverlayCleanupBarrier()
        n.addReturned(true); n.removalRequested(); b.retiring(null, 1, 10, n, owner)
        n.removalReturned(true); b.removalReturned(true)
        n.attached(null); n.observeWindowId(Site.ATTACH) { 10 }; n.detached()
        assertFalse(b.nativeProcessed(n, true) { error("TARGET_ID_MUST_NOT_BE_C") })
        assertNull(b.permit())
        val reattached = Native(2, owner)
        reattached.addReturned(true); reattached.attached(20); reattached.attached(20); reattached.removalRequested()
        assertNull(reattached.retirementWindow(owner, 2))
    }

    @Test fun cancelledBeforeFirstDrawCanUseTheLastAttachedReadBeforeRemoval() {
        val owner = Any(); val n = Native(1, owner); val b = OverlayCleanupBarrier()
        n.addReturned(true); n.attached(null); n.observeWindowId(Site.ATTACH) { null }
        n.observeWindowId(Site.BEFORE_REMOVE) { 20 }; n.removalRequested()
        b.retiring(null, 1, 10, n, owner)
        assertTrue(b.pending); assertNull(b.permit())
        b.removalReturned(true); n.removalReturned(true); n.detached()
        assertTrue(b.nativeProcessed(n, true) { CleanupWindowPresence.ABSENT })
    }
}
