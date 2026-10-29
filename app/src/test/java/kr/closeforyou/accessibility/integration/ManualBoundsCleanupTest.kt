package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

/** Shared Android route/review and native/barrier contract. Android Binder/View operations fake. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualBoundsCleanupTest {
    private class Rig(clock: () -> Long = { 1_000L }) {
        val f = F9AutoCloserTest.Fixture()
        val overlay = WindowSnapshot(30, WindowType.ACCESSIBILITY_OVERLAY, f.snapshot.screenBounds, true, true, 3)
        var scene = f.before.copy(windows = f.windows.map {
            if (it.id == 20) it.copy(active = false, focused = false) else it
        } + overlay)
        val owner = Any()
        val native = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true) }
        val codes = mutableListOf<ManualCleanupCode>()
        val cleanupTrace = ManualCleanupTrace(codes::add)
        var unhealthy = false
        val barrier = OverlayCleanupBarrier(clock = clock, onUncertain = { unhealthy = true },
            failureAttemptDiagnostic = { reason, stage ->
                cleanupTrace.record(ManualCleanupSite.BARRIER, ManualCleanupIssue.BARRIER_REJECTED, reason, stage)
            })
        var hidden = false
        fun request() {
            native.removalRequested(); barrier.retiring(null, 1, 20, native, owner)
            native.removalReturned(true); hidden = true; barrier.removalReturned(true)
        }
        fun classify(type: Int, changes: Int = 0, own: Boolean = true) = inspectNativeCleanupRetirement(type, changes,
            { hidden }, { own }, { native.registrationConfirmed(owner, 1) &&
                !native.identityUnavailable && native.retirementWindow(owner, 1) == 30 })
        fun deliver(type: Int, changes: Int = 0, own: Boolean = true) =
            barrier.observeWindowEvent(30, type, changes, classify(type, changes, own).check == ManualRetirementCheck.CLEANUP_WAIT)
        fun confirm(presence: CleanupWindowPresence = CleanupWindowPresence.ABSENT): Boolean {
            native.detached()
            return barrier.nativeProcessed(native, hidden) { presence }
        }
    }
    @Test fun targetPureBoundsEntersReviewButUnrelatedUnknownOrStructuralMasksDoNot() {
        assertTrue(manualDisplayReviewEvent(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 20, 20))
        for (mask in listOf(0, E.WINDOWS_CHANGE_ADDED, E.WINDOWS_CHANGE_REMOVED,
            E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_ADDED, E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_REMOVED))
            assertFalse(manualDisplayReviewEvent(E.TYPE_WINDOWS_CHANGED, mask, 20, 20))
        for (id in listOf(-1, 10, 30)) assertFalse(manualDisplayReviewEvent(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, id, 20))
        assertFalse(manualDisplayReviewEvent(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 20, null))
        for (type in listOf(E.TYPE_VIEW_CLICKED, E.TYPE_VIEW_SCROLLED, E.TYPE_TOUCH_INTERACTION_START,
            E.TYPE_VIEW_TEXT_CHANGED, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED))
            assertFalse(manualDisplayReviewEvent(type, E.WINDOWS_CHANGE_BOUNDS, 20, 20))
    }
    @Test fun unchangedTargetBoundsTreeAndFrameRetainDisplayOnlyAfterRetiringExecutionEvidence() {
        val r = Rig(); val raw = F9CollectedSource { r.f.now }
        raw.publish(r.f.snapshot, PopupDetection(r.f.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
        val order = mutableListOf<String>()
        assertTrue(manualDisplayReviewEvent(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 20, 20))
        val result = reviewManualDisplay({ order += "RETIRE"; raw.invalidate(2) }, {
            assertNull(raw.currentToken()); order += "READ"
            ManualDisplayContinuity.assess(r.f.snapshot, r.scene, 30)
        }, { error("UNCHANGED_MUST_NOT_REMOVE") }, { order += "LOG_" + it.name })
        assertEquals(ManualDisplayVerdict.SAME, result)
        assertEquals(listOf("RETIRE", "READ", "LOG_SAME"), order); assertNull(raw.currentToken())
        assertEquals(0, r.f.clicks)
    }
    @Test fun actualPopupBoundsTreeFrameOrOtherWindowChangeRemovesWithoutClamping() {
        for (field in listOf("BOUNDS", "TREE", "FRAME", "WINDOW")) {
            val r = Rig(); var removed = 0
            r.scene = when (field) {
                "BOUNDS" -> r.scene.copy(windows = r.scene.windows.map {
                    if (it.id == 20) it.copy(bounds = ScreenBounds(21, 20, 200, 200)) else it
                })
                "TREE" -> r.scene.copy(nodes = listOf(r.f.node.copy(text = "다른 광고")))
                "FRAME" -> r.scene.copy(screen = ScreenBounds(0, 0, 301, 500))
                else -> r.scene.copy(windows = r.scene.windows.map { if (it.id == 10) it.copy(layer = 0) else it })
            }
            val v = reviewManualDisplay({}, { ManualDisplayContinuity.assess(r.f.snapshot, r.scene, 30) }, { removed++ })
            assertNotEquals(field, ManualDisplayVerdict.SAME, v); assertEquals(1, removed); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun missingOverlayTargetMalformedOrIncompleteSceneNeverRetains() {
        for (mode in listOf("C_MISSING", "TARGET_MISSING", "INCOMPLETE", "RECT")) {
            val r = Rig(); var removed = false
            r.scene = when (mode) {
                "C_MISSING" -> r.scene.copy(windows = r.scene.windows.filter { it.id != 30 })
                "TARGET_MISSING" -> r.scene.copy(windows = r.scene.windows.filter { it.id != 20 })
                "RECT" -> r.scene.copy(nodes = listOf(r.f.node.copy(bounds = ScreenBounds(5, 20, 6, 20))))
                else -> r.scene.copy(complete = false)
            }
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, reviewManualDisplay({},
                { ManualDisplayContinuity.assess(r.f.snapshot, r.scene, 30) }, { removed = true }))
            assertTrue(removed); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun actualF9StillRejectsWhileDisplayReviewSaysSameAndOverlayIsPresent() = runTest {
        val r = Rig(); assertEquals(ManualDisplayVerdict.SAME, ManualDisplayContinuity.assess(r.f.snapshot, r.scene, 30))
        r.f.scene = r.scene
        assertFalse(r.f.closer(StandardTestDispatcher(testScheduler)).execute(r.f.reference).attempted)
        assertEquals(0, r.f.clicks)
    }
    @Test fun nonChoiceRemovalPreviouslyFailedOnQueuedWindowEventButNowWaitsForRealDetachAndAbsence() {
        val old = Rig(); old.request()
        old.barrier.observeWindowEvent(30, E.TYPE_WINDOW_STATE_CHANGED, 0)
        assertEquals(CleanupFailure.RETIRED_ID_EVENT, old.codes.single().first.barrier)
        val r = Rig(); r.request()
        for ((type, mask) in listOf(E.TYPE_WINDOW_STATE_CHANGED to 0, E.TYPE_WINDOW_CONTENT_CHANGED to 0,
            E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED to 0, E.TYPE_WINDOWS_CHANGED to E.WINDOWS_CHANGE_BOUNDS)) {
            assertEquals(ManualRetirementCheck.CLEANUP_WAIT, r.classify(type, mask).check)
            assertNull(r.deliver(type, mask)); assertTrue(r.barrier.pending); assertNull(r.barrier.permit())
        }
        assertTrue(r.codes.isEmpty()); assertFalse(r.unhealthy)
        assertTrue(r.confirm()); assertTrue(r.barrier.ready); assertEquals(0, r.f.clicks)
    }
    @Test fun cleanupWaitNeverPreservesAChoiceOrRestoresRawEvidence() {
        val r = Rig(); r.request()
        val raw = F9CollectedSource { r.f.now }
        raw.publish(r.f.snapshot, PopupDetection(r.f.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
        assertNull(r.deliver(E.TYPE_VIEW_CLICKED))
        raw.invalidate(2)
        var revoked = false
        assertFalse(applyManualInvalidation(ManualPromptCause.CLICK_INVALIDATED, true, true, true,
            ownedRetirement = false, revoke = { revoked = true }, remove = { error("ALREADY_REMOVED") }))
        assertTrue(revoked); assertNull(raw.currentToken()); assertNull(r.barrier.permit()); assertEquals(0, r.f.clicks)
    }
    @Test fun noDetachOrUnknownPresenceCannotBeReleasedAndDeadlineDoesNotRestart() = runTest {
        val r = Rig { testScheduler.currentTime }; r.request()
        val waiter = async { runCatching { r.barrier.awaitObservationPermit() } }; runCurrent()
        repeat(5) { r.deliver(E.TYPE_WINDOW_STATE_CHANGED); advanceTimeBy(1_000) }
        runCurrent(); assertTrue(waiter.await().exceptionOrNull() is CancellationException)
        assertEquals(CleanupFailure.DEADLINE, r.codes.first().first.barrier)
        assertFalse(r.confirm()); assertFalse(r.barrier.ready); assertTrue(r.unhealthy)
        val unknown = Rig(); unknown.request(); unknown.deliver(E.TYPE_WINDOW_STATE_CHANGED)
        assertFalse(unknown.confirm(CleanupWindowPresence.UNKNOWN)); assertFalse(unknown.barrier.ready)
    }
    @Test fun foreignPackageMixedMaskAndMissingNativeNeverReceiveCleanupCompatibility() {
        val r = Rig(); r.request()
        assertEquals(ManualRetirementCheck.PACKAGE, r.classify(E.TYPE_WINDOW_STATE_CHANGED, own = false).check)
        r.deliver(E.TYPE_WINDOW_STATE_CHANGED, own = false)
        assertEquals(CleanupFailure.RETIRED_ID_EVENT, r.codes.first().first.barrier)
        val mixed = Rig(); mixed.request()
        val mask = E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_REMOVED
        assertEquals(ManualRetirementCheck.EVENT_TYPE, mixed.classify(E.TYPE_WINDOWS_CHANGED, mask).check)
        mixed.deliver(E.TYPE_WINDOWS_CHANGED, mask); assertFalse(mixed.barrier.ready)
        assertEquals(ManualRetirementCheck.NATIVE,
            inspectNativeCleanupRetirement(E.TYPE_WINDOW_STATE_CHANGED, 0, { true }, { true }, { false }).check)
    }
    @Test fun actualRemoveFailureRemainsStickyAndNoSubsequentHealthySampleCanClick() = runTest {
        val r = Rig(); r.request()
        dismissManualOverlay({}, { OverlayWindowStatus.REMOVE_FAILED }, r.barrier, r.cleanupTrace)
        r.deliver(E.TYPE_WINDOW_STATE_CHANGED); assertFalse(r.confirm()); assertTrue(r.unhealthy)
        val first = r.codes.first().first
        assertEquals(ManualCleanupIssue.REMOVE_FAILED_STATE, first.issue)
        assertTrue(r.codes.all { it.first == first })
        r.f.state = r.f.state.copy(cleanupReady = r.barrier.ready)
        assertFalse(r.f.closer(StandardTestDispatcher(testScheduler)).execute(r.f.reference).attempted)
        assertEquals(0, r.f.clicks)
    }
    @Test fun presentReusedIdAfterRemovalReceiptCannotBecomeCleanupSuccess() {
        val r = Rig(); r.request(); r.deliver(E.TYPE_WINDOW_STATE_CHANGED)
        val receipt = requireNotNull(r.barrier.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        r.native.detached()
        r.barrier.processed(receipt, true) { CleanupWindowPresence.PRESENT }
        assertFalse(r.barrier.ready); assertTrue(r.unhealthy)
        assertEquals(CleanupFailure.WINDOW_PRESENT, r.codes.first().first.barrier)
    }
    @Test fun reviewResultIsRecordedBeforeRemovalErrorAndCleanupFailureIsNotSuccess() {
        val order = mutableListOf<String>(); val failure = IllegalStateException("FIXED_REMOVE")
        assertTrue(runCatching {
            reviewManualDisplay({ order += "RETIRE" }, { order += "VERIFY"; ManualDisplayVerdict.CHANGED },
                { order += "REMOVE"; throw failure }, { order += it.name })
        }.exceptionOrNull() is kr.closeforyou.accessibility.CollectionCleanupFailure)
        assertEquals(listOf("RETIRE", "VERIFY", "CHANGED", "REMOVE"), order)
    }
    @Test fun cancellationAndReadFailureAreLoggedBeforeCleanupWithOriginalExceptionPreserved() {
        for (failure in listOf(CancellationException("FIXED_CANCEL"), IllegalStateException("FIXED_READ"))) {
            val order = mutableListOf<String>()
            assertSame(failure, runCatching {
                reviewManualDisplay({}, { throw failure }, { order += "REMOVE"; error("SECONDARY") }, { order += it.name })
            }.exceptionOrNull())
            assertEquals(listOf("UNAVAILABLE", "REMOVE"), order)
        }
    }
    @Test fun failingDebugSinkCannotChangeRetainOrRemoveAndJournalFirstFailureSurvivesNewOffer() {
        var removes = 0
        assertEquals(ManualDisplayVerdict.SAME, reviewManualDisplay({}, { ManualDisplayVerdict.SAME }, { removes++ },
            { throw CancellationException("FIXED_LOG") }))
        assertEquals(0, removes)
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, reviewManualDisplay({}, { ManualDisplayVerdict.UNAVAILABLE }, { removes++ },
            { error("FIXED_LOG") }))
        assertEquals(1, removes)
        val rows = mutableListOf<ManualCleanupCode>()
        val trace = ManualPromptTrace(cleanupSink = { _, code -> rows += code }, sink = { _, _, _ -> })
        trace.begin(); trace.cleanup.record(ManualCleanupSite.NATIVE_CALLBACK, ManualCleanupIssue.EXCEPTION)
        trace.begin(); trace.cleanup.record(ManualCleanupSite.BARRIER, ManualCleanupIssue.BARRIER_REJECTED, CleanupFailure.RETIRED_ID_EVENT)
        assertEquals(ManualCleanupPhase.CHAIN, rows.last().phase)
        assertEquals(ManualCleanupSite.NATIVE_CALLBACK, rows.last().first.site)
    }
    @Test fun actualOwnedWindowReadAndReleaseFailuresRecordHealthOriginWithoutSkippingOtherReleases() {
        for (readFails in listOf(true, false)) {
            val health = ChoicePresentationHealth(); val rows = mutableListOf<ManualCleanupCode>()
            val trace = ManualCleanupTrace(rows::add); val released = mutableListOf<Int>()
            val owned = readOwnedOverlayIds(readWindows = {
                if (readFails) error("FIXED_READ_FAILURE") else listOf(1, 2)
            }, isOverlayOnDefaultDisplay = { true }, readRoot = { it + 10 }, isOwnedRoot = { _, _ -> true },
                hasOwnedTitle = { true }, windowId = { it }, releaseRoot = { released += it; error("FIXED_RELEASE_FAILURE") },
                releaseWindow = { released += it }, onUnhealthy = { manualOwnedReadFailed(health, trace) }, diagnostic = null)
            assertNull(owned); assertTrue(health.failed)
            assertEquals(ManualCleanupSite.OWNED_WINDOW_READ, rows.single().first.site)
            assertEquals(ManualCleanupIssue.READ_OR_RELEASE_FAILED, rows.single().first.issue)
            if (!readFails) assertEquals(listOf(11, 12, 1, 2), released)
            trace.record(ManualCleanupSite.BARRIER, ManualCleanupIssue.BARRIER_REJECTED, CleanupFailure.EXTERNAL_UNCERTAINTY)
            assertEquals(ManualCleanupSite.OWNED_WINDOW_READ, rows.last().first.site)
        }
    }
}
