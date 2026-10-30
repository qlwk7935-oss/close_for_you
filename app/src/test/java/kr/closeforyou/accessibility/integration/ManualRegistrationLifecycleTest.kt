package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.overlay.OverlayNativeWindowLifetime
import org.junit.Assert.*
import org.junit.Test

/** Actual shared ownership read/registration/review/barrier boundaries. Android handles are fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualRegistrationLifecycleTest {
    private class Rig {
        val f = F9AutoCloserTest.Fixture()
        val owner = Any()
        val native = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true) }
        val registration = ManualDisplayRegistration().also { it.created(1) }
        val probe = ManualDisplayProbe()
        var now = 1_000L; var current = true; var rawCurrent = true
        var showing = true; var removes = 0; var scans = 0; var queries = 0
        var queryMode = "NONE"; var rootMode = "OWNED"; var releaseFails = false
        var scene = f.before; var afterRead: () -> Unit = {}
        var unhealthy = false; var generation = 1L
        val released = mutableListOf<Int>()
        fun read(): Set<Int>? {
            queries++
            val p = ManualOwnedReadProbe()
            return try {
                readOwnedOverlayIds(readWindows = {
                    if (queryMode == "THROW") error("SYNTHETIC")
                    if (queryMode == "EMPTY") emptyList() else if (queryMode == "LIMIT") (1..33).toList()
                    else listOf(10, 20) + when (queryMode) { "LISTED" -> listOf(30); "OTHER" -> listOf(31); "MULTIPLE" -> listOf(30, 31); else -> emptyList() }
                }, isOverlayOnDefaultDisplay = { it >= 30 }, readRoot = {
                    when (rootMode) { "MISSING" -> null; "CANCEL" -> throw CancellationException("SYNTHETIC"); else -> it + 100 }
                }, isOwnedRoot = { _, _ -> rootMode != "FOREIGN" }, hasOwnedTitle = { rootMode != "TITLE" },
                    windowId = { it }, releaseRoot = { released += it; if (releaseFails) error("SYNTHETIC") },
                    releaseWindow = { released += it }, onUnhealthy = { unhealthy = true }, diagnostic = null, probe = p)
            } finally { probe.ownedRead = p.code() }
        }
        fun identity() = registration.read(generation,
            { readManualNativeWindow(native, owner, generation, null) }, { false }, ::read, probe)
        fun verify(): ManualDisplayVerdict = reviewManualDisplay(retire = { rawCurrent = false }, verify = {
            val started = now
            verifyRegisteredManualDisplay(probe,
                { manualDisplayCheckpoint({ current && showing && !unhealthy }, { now }, started, probe) }, ::identity) { id ->
                scans++
                val target = ManualDisplayContinuity.target(scene.windows, f.token.windowId, id.window, probe, id.listing)
                val result = if (target == null) ManualDisplayVerdict.UNAVAILABLE else
                    ManualDisplayContinuity.assess(f.snapshot, scene, id.window, probe, id.listing)
                afterRead(); result
            }
        }, remove = { removes++; showing = false })
        fun listed() {
            queryMode = "LISTED"
            val overlay = WindowSnapshot(30, WindowType.ACCESSIBILITY_OVERLAY, f.snapshot.screenBounds, false, false, 3)
            scene = f.before.copy(windows = f.windows + overlay)
        }
    }
    @Test fun initialNoEligibleRemoteWindowRetainsOnlyAfterWholeTargetAndNativeBeforeAfterMatch() {
        val r = Rig()
        assertEquals(ManualDisplayVerdict.SAME, r.verify())
        assertTrue(r.showing); assertFalse(r.rawCurrent); assertEquals(0, r.removes); assertEquals(0, r.f.clicks)
        assertEquals(2, r.queries); assertEquals(1, r.scans)
        assertEquals(ManualDisplayListing.NOT_ENUMERATED, r.probe.listing)
        assertEquals(ManualOwnedCount.NONE, r.probe.ownedRead.count)
        assertEquals(OwnershipRelation.UNAVAILABLE, r.probe.ownedRead.native) // never OWNED
    }
    @Test fun listedNativeKeepsOriginalFullDisplayVerification() {
        val r = Rig(); r.listed()
        assertEquals(ManualDisplayVerdict.SAME, r.verify()); assertTrue(r.showing)
        assertEquals(ManualDisplayListing.LISTED, r.probe.listing)
    }
    @Test fun onceListedAnyLaterAbsenceFailsWithoutResettingGeneration() {
        val r = Rig(); r.listed(); assertEquals(ManualDisplayVerdict.SAME, r.verify())
        r.queryMode = "NONE"; r.scene = r.f.before
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify()); assertFalse(r.showing)
        assertEquals(ManualDisplayDetail.LIVE_SET_UNCONFIRMED, r.probe.detail)
    }
    @Test fun confirmedOwnedEventAlsoPermanentlyClosesInitialRegistrationRoute() {
        val r = Rig(); r.registration.confirmed(1)
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify()); assertFalse(r.showing)
    }
    @Test fun remoteRegistrationAppearingDuringTheReadCannotBeAcceptedAsAnAtomicSample() {
        val r = Rig(); r.afterRead = { r.queryMode = "LISTED" }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify()); assertFalse(r.showing)
        assertEquals(ManualDisplayDetail.REGISTRATION_CHANGED, r.probe.detail)
    }
    @Test fun nativeIdAppearingInSecondWindowListCannotUseAnAbsenceIdentity() {
        for (type in WindowType.entries) {
            val r = Rig(); r.scene = r.scene.copy(windows = r.f.windows + WindowSnapshot(30, type, r.f.bounds, false, false, 4))
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify()); assertFalse(r.showing)
            assertEquals(ManualDisplayDetail.REGISTRATION_CHANGED, r.probe.detail)
        }
    }
    @Test fun emptyQueryWrongOwnerTitleMissingRootMultipleOtherAndReadFailureNeverUseInitialRoute() {
        for (mode in listOf("EMPTY", "LIMIT", "OTHER", "MULTIPLE", "THROW", "MISSING", "FOREIGN", "TITLE")) {
            val r = Rig()
            if (mode in listOf("MISSING", "FOREIGN", "TITLE")) { r.listed(); r.rootMode = mode } else r.queryMode = mode
            assertEquals(mode, ManualDisplayVerdict.UNAVAILABLE, r.verify())
            assertFalse(r.showing); assertEquals(0, r.scans); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun rootReleaseFailureStillBlocksAndReleasesAllOtherHandles() {
        val r = Rig(); r.listed(); r.releaseFails = true
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify()); assertTrue(r.unhealthy)
        assertEquals(listOf(130, 10, 20, 30), r.released); assertEquals(0, r.scans)
        assertEquals(ManualOwnedQuery.RELEASE_FAILED, r.probe.failureOwned.query)
    }
    @Test fun nativeOwnershipMissingWrongGenerationOrRemovedViewFailsBeforeWindowQuery() {
        for (mode in listOf("WRONG_GENERATION", "REMOVING", "MISSING_ID")) {
            val r = Rig()
            if (mode == "WRONG_GENERATION") r.generation = 2
            if (mode == "REMOVING") r.native.removalRequested()
            if (mode == "MISSING_ID") {
                val unknown = OverlayNativeWindowLifetime(1, r.owner).also { it.attached(null); it.addReturned(true) }
                assertNull(r.registration.read(1, { readManualNativeWindow(unknown, r.owner, 1, null) }, { error("NO_BEFORE") }, { error("NO_REMOTE") }, r.probe))
            } else assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify())
            assertEquals(0, r.queries); assertEquals(ManualDisplayDetail.NATIVE_ID_UNAVAILABLE, r.probe.detail)
        }
    }
    @Test fun nativeDetachOrRemovalDuringTargetReadCannotRetainTheWindow() {
        val r = Rig(); r.afterRead = { r.native.removalRequested() }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify()); assertFalse(r.showing)
        assertEquals(ManualDisplayPoint.NATIVE_AFTER, r.probe.failurePoint)
    }
    @Test fun lateFirstDrawMaySupplyIdButNoOwnershipIsInventedBeforeIt() {
        val owner = Any(); val n = OverlayNativeWindowLifetime(1, owner).also { it.attached(null); it.addReturned(true) }
        assertNull(readManualNativeWindow(n, owner, 1, null))
        n.observeWindowId(kr.closeforyou.ui.voice.overlay.NativeWindowIdObservation.FIRST_DRAW) { 30 }
        assertEquals(30, readManualNativeWindow(n, owner, 1, null))
    }
    @Test fun actualTargetTreeFrameWindowRemovalOrForeignPackageAlwaysRemoves() {
        val changes: List<(Rig) -> Unit> = listOf(
            { it.scene = it.scene.copy(nodes = listOf(it.f.node.copy(text = "다른 안내"))) },
            { it.scene = it.scene.copy(screen = ScreenBounds(0, 0, 301, 500)) },
            { it.scene = it.scene.copy(windows = it.f.windows.filter { w -> w.id != 20 }) },
            { it.scene = it.scene.copy(windows = it.f.windows.map { w -> if (w.id == 20) w.copy(bounds = ScreenBounds(21, 20, 200, 200)) else w }) },
            { it.scene = it.scene.copy(packageName = "synthetic.other") },
            { it.scene = it.scene.copy(complete = false) },
            { it.scene = it.scene.copy(windows = it.f.windows.reversed()) })
        for (change in changes) {
            val r = Rig(); change(r); assertNotEquals(ManualDisplayVerdict.SAME, r.verify())
            assertFalse(r.showing); assertEquals(1, r.removes); assertFalse(r.rawCurrent); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun absenceRouteNeverNormalizesLostTargetFocusOrCompetingInputOverlay() {
        for (mode in listOf("FOCUS", "ACTIVE", "IME", "OTHER_OVERLAY", "OTHER_FOCUSED")) {
            val r = Rig()
            r.scene = when (mode) {
                "FOCUS" -> r.scene.copy(windows = r.f.windows.map { if (it.id == 20) it.copy(focused = false) else it })
                "ACTIVE" -> r.scene.copy(windows = r.f.windows.map { if (it.id == 20) it.copy(active = false) else it })
                "OTHER_FOCUSED" -> r.scene.copy(windows = r.f.windows.map { if (it.id == 10) it.copy(focused = true) else it })
                else -> r.scene.copy(windows = r.f.windows + WindowSnapshot(40,
                    if (mode == "IME") WindowType.INPUT_METHOD else WindowType.ACCESSIBILITY_OVERLAY, r.f.bounds, false, false, 5))
            }
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify()); assertFalse(r.showing)
        }
    }
    @Test fun timeoutOrEpochChangeDuringScanNeverRetainsAndDoesNotRetry() {
        for (timeout in listOf(true, false)) {
            val r = Rig(); r.afterRead = { if (timeout) r.now += 100 else r.current = false }
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.verify()); assertFalse(r.showing)
            assertEquals(1, r.scans); assertEquals(1, r.queries); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun cancellationReleasesResourcesAndRemovesWithoutBecomingAResult() {
        val r = Rig(); r.listed(); r.rootMode = "CANCEL"
        val failure = runCatching { r.verify() }.exceptionOrNull()
        assertTrue(failure is CancellationException); assertFalse(r.showing); assertTrue(r.unhealthy)
        assertEquals(listOf(10, 20, 30), r.released); assertEquals(0, r.scans)
    }
    @Test fun newGenerationMayRegisterButOldCallbacksAndDuplicateCreationCannotResetALatch() {
        val r = ManualDisplayRegistration(); r.created(1); r.confirmed(1)
        fun read(g: Long) = r.read(g, { 30 }, { false }, { emptySet() }, ManualDisplayProbe().also {
            it.ownedRead = ManualOwnedReadCode(ManualOwnedCount.NONE, ManualOwnedQuery.COMPLETE, OverlayOwnedScanDetail.NO_ELIGIBLE_OVERLAY)
        })
        assertNull(read(1)); r.created(2); assertNull(read(1)); assertNotNull(read(2))
        r.created(2); assertNull(read(2)) // no resetting an already admitted generation
    }
    private class Cleanup {
        var time = 1_000L; val owner = Any()
        val n = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true) }
        val failures = mutableListOf<CleanupFailure>(); val events = mutableListOf<ManualCleanupEventCode>()
        var unhealthy = false; var hidden = false; var presence = CleanupWindowPresence.PRESENT
        val barrier = OverlayCleanupBarrier(clock = { time }, onUncertain = { unhealthy = true },
            failureDiagnostic = failures::add, eventDiagnostic = events::add)
        init { n.removalRequested(); barrier.retiring(null, 1, 20, n, owner); n.removalReturned(true); hidden = true; barrier.removalReturned(true) }
        fun classify(type: Int, mask: Int) = inspectNativeCleanupRetirement(type, mask, { hidden }, { true },
            { n.registrationConfirmed(owner, 1) && n.retirementWindow(owner, 1) == 30 },
            { n.removalComplete(owner, 1, 30) })
        fun event(type: Int, mask: Int = 0): Boolean {
            val waitOnly = classify(type, mask).check == ManualRetirementCheck.CLEANUP_WAIT
            val receipt = barrier.observeWindowEvent(30, type, mask, waitOnly)
            // Driver always invalidates normally; this bool NEVER preserves a choice.
            if (receipt != null) barrier.processed(receipt, hidden) { presence }
            else if (waitOnly) barrier.nativeProcessed(n, hidden) { presence }
            return waitOnly
        }
    }
    @Test fun stateThenMixedRemovalAfterActualDetachAndAbsenceNoLongerPoisonsCleanup() {
        val r = Cleanup(); r.n.detached()
        assertFalse(r.barrier.nativeProcessed(r.n, true) { r.presence })
        assertTrue(r.event(E.TYPE_WINDOW_STATE_CHANGED)); assertTrue(r.barrier.pending)
        r.presence = CleanupWindowPresence.ABSENT
        assertTrue(r.event(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_BOUNDS))
        assertTrue(r.barrier.ready); assertFalse(r.unhealthy); assertTrue(r.failures.isEmpty())
        assertEquals(ManualCleanupEventResult.NO_RECEIPT, r.events.last().result)
    }
    @Test fun mixedRemovalCannotBeAReceiptOrCompleteBeforeNativeDetach() {
        val r = Cleanup()
        assertFalse(r.event(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_BOUNDS))
        assertEquals(listOf(CleanupFailure.RETIRED_ID_EVENT), r.failures); assertFalse(r.barrier.ready)
    }
    @Test fun addedUnknownMixedMasksAndUnregisteredOwnerStillFailRatherThanResetHealth() {
        for (mask in listOf(E.WINDOWS_CHANGE_ADDED, E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_ADDED,
            E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_PARENT, 0, Int.MIN_VALUE)) {
            val r = Cleanup(); r.n.detached(); r.presence = CleanupWindowPresence.ABSENT
            assertFalse(r.event(E.TYPE_WINDOWS_CHANGED, mask)); assertTrue(r.unhealthy); assertFalse(r.barrier.ready)
        }
    }
    @Test fun mixedEventWithStillPresentOrUnknownWindowsNeverReleases() {
        for (presence in listOf(CleanupWindowPresence.PRESENT, CleanupWindowPresence.UNKNOWN)) {
            val r = Cleanup(); r.n.detached(); r.presence = presence
            r.event(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_BOUNDS)
            assertFalse(r.barrier.ready); assertNull(r.barrier.permit())
        }
    }
    @Test fun pendingCleanupDeadlineNeverRestartsAcrossCreationRemovalReordering() = runTest {
        val r = Cleanup(); r.n.detached()
        val waiter = async { runCatching { r.barrier.awaitObservationPermit() } }; runCurrent()
        repeat(5) { r.event(E.TYPE_WINDOW_STATE_CHANGED); r.time += 1_000 }
        r.time = 6_000; advanceTimeBy(5_000); runCurrent()
        assertTrue(waiter.await().exceptionOrNull() is CancellationException)
        r.presence = CleanupWindowPresence.ABSENT
        assertFalse(r.barrier.nativeProcessed(r.n, true) { r.presence }); assertTrue(r.unhealthy)
    }
    @Test fun firstFailureAndCancellationCannotBeReplacedByCleanupOrDiagnosticFailures() {
        val r = Rig(); val primary = CancellationException("SYNTHETIC")
        assertSame(primary, runCatching { reviewManualDisplay({}, { throw primary }, {
            r.removes++; error("SYNTHETIC_SECONDARY")
        }, { throw CancellationException("SYNTHETIC_LOG") }) }.exceptionOrNull())
        assertEquals(1, r.removes)
        val c = Cleanup(); c.event(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ADDED)
        c.barrier.fail(CleanupFailure.EXTERNAL_UNCERTAINTY)
        assertEquals(CleanupFailure.RETIRED_ID_EVENT, c.failures.first()); assertTrue(c.unhealthy)
    }
}
