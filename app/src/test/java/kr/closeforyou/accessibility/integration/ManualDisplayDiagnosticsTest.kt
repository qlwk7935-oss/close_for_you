package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Real display reader/probe/checkpoint contracts; no Samsung Binder/native ownership simulation. */
class ManualDisplayDiagnosticsTest {
    private class Rig {
        val f = F9AutoCloserTest.Fixture()
        val c = WindowSnapshot(30, WindowType.ACCESSIBILITY_OVERLAY, f.snapshot.screenBounds, true, true, 3)
        val scene = f.before.copy(windows = f.windows.map { if (it.id == 20) it.copy(active = false, focused = false) else it } + c)
    }
    @Test fun nativeMissingBeforeReadShortCircuitsSavedAndLiveQueries() {
        for (id in listOf(null, -1)) {
            val p = ManualDisplayProbe().also { it.point = ManualDisplayPoint.NATIVE_BEFORE }
            assertNull(manualDisplayOwnedId({ id }, { error("NO_BEFORE_QUERY") }, { error("NO_LIVE_QUERY") }, p))
            assertEquals(ManualDisplayDetail.NATIVE_ID_UNAVAILABLE, p.detail); assertEquals(ManualDisplayPoint.NATIVE_BEFORE, p.failurePoint)
        }
    }
    @Test fun savedSetAndOwnedLiveSetAreSeparateOwnershipFailures() {
        for (before in listOf<Boolean?>(null, true)) {
            val p = ManualDisplayProbe()
            assertNull(manualDisplayOwnedId({ 30 }, { before }, { error("NO_LIVE_QUERY") }, p))
            assertEquals(ManualDisplayDetail.BEFORE_SET_UNCONFIRMED, p.detail)
        }
        for (live in listOf(null, emptySet(), setOf(31), setOf(30, 31))) {
            val p = ManualDisplayProbe()
            assertNull(manualDisplayOwnedId({ 30 }, { false }, { live }, p))
            assertEquals(ManualDisplayDetail.LIVE_SET_UNCONFIRMED, p.detail)
        }
    }
    @Test fun successfulOwnershipUsesEachExistingCallbackOnlyOnce() {
        var native = 0; var before = 0; var live = 0; val p = ManualDisplayProbe()
        assertEquals(30, manualDisplayOwnedId({ native++; 30 }, { before++; false }, { live++; setOf(30) }, p))
        assertEquals(listOf(1, 1, 1), listOf(native, before, live)); assertEquals(ManualDisplayDetail.NONE, p.detail)
    }
    @Test fun checkpointCurrentnessFailureDoesNotReadClockAndTimeoutHasExactExistingBoundary() {
        var clocks = 0; val p = ManualDisplayProbe()
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, checkedManualDisplay(p) {
            manualDisplayCheckpoint({ false }, { clocks++; 1_000 }, 1_000, p); error("NO_READ")
        })
        assertEquals(0, clocks); assertEquals(ManualDisplayDetail.CURRENTNESS_FAILED, p.detail)
        for (now in listOf(999L, 1_100L)) {
            val time = ManualDisplayProbe()
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, checkedManualDisplay(time) {
                manualDisplayCheckpoint({ true }, { clocks++; now }, 1_000, time); error("NO_READ")
            })
            assertEquals(ManualDisplayDetail.TIME_LIMIT, time.detail); assertEquals(ManualDisplayPoint.CHECKPOINT, time.failurePoint)
        }
        val good = ManualDisplayProbe(); manualDisplayCheckpoint({ true }, { 1_099 }, 1_000, good)
        assertEquals(ManualDisplayDetail.NONE, good.detail)
    }
    @Test fun displayTargetSelectionReportsActualExistingWindowRelations() {
        val r = Rig()
        val cases = listOf(
            r.scene.windows.filter { it.id != 30 } to ManualDisplayDetail.OVERLAY_WINDOW_UNAVAILABLE,
            r.scene.windows.filter { it.id != 20 } to ManualDisplayDetail.TARGET_WINDOW_UNAVAILABLE,
            r.scene.windows + r.c to ManualDisplayDetail.WINDOW_LIST_UNCONFIRMED,
            r.scene.windows.map { if (it.id == 30) it.copy(layer = 1) else it } to ManualDisplayDetail.OVERLAY_LAYER,
            r.scene.windows + WindowSnapshot(40, WindowType.INPUT_METHOD, r.scene.screen, false, false, 4) to ManualDisplayDetail.COMPETING_WINDOW,
            r.scene.windows.map { it.copy(active = false, focused = false) } to ManualDisplayDetail.ACTIVE_OR_FOCUS_UNCONFIRMED)
        for ((windows, detail) in cases) {
            val p = ManualDisplayProbe().also { it.point = ManualDisplayPoint.SCREEN }
            assertNull(ManualDisplayContinuity.target(windows, 20, 30, p)); assertEquals(detail, p.detail)
        }
        val p = ManualDisplayProbe(); assertNull(ManualDisplayContinuity.target(r.scene.windows, 30, 30, p))
        assertEquals(ManualDisplayDetail.TARGET_EQUALS_NATIVE, p.detail)
    }
    @Test fun sameComparisonWithProbeAndWithoutProbeHasIdenticalVerdictAndNoPermissionEffect() {
        val r = Rig(); val p = ManualDisplayProbe()
        assertEquals(ManualDisplayVerdict.SAME, ManualDisplayContinuity.assess(r.f.snapshot, r.scene, 30, p))
        assertEquals(ManualDisplayContinuity.assess(r.f.snapshot, r.scene, 30), ManualDisplayContinuity.assess(r.f.snapshot, r.scene, 30, p))
        assertEquals(ManualDisplayDetail.NONE, p.detail); assertEquals(0, r.f.clicks)
    }
    @Test fun comparisonFailuresIdentifyIncompleteGeometryPackageFrameTreeAndWindowSet() {
        val r = Rig()
        val cases = listOf(r.scene.copy(complete = false) to ManualDisplayDetail.INCOMPLETE,
            r.scene.copy(nodes = emptyList()) to ManualDisplayDetail.INVALID_GEOMETRY,
            r.scene.copy(packageName = "synthetic.other") to ManualDisplayDetail.PACKAGE_CHANGED,
            r.scene.copy(windowId = 21) to ManualDisplayDetail.WINDOW_CHANGED,
            r.scene.copy(screen = ScreenBounds(0, 0, 301, 500)) to ManualDisplayDetail.FRAME_CHANGED,
            r.scene.copy(nodes = listOf(r.f.node.copy(text = "다른 광고"))) to ManualDisplayDetail.NODES_CHANGED,
            r.scene.copy(windows = r.scene.windows.map { if (it.id == 10) it.copy(layer = 0) else it }) to ManualDisplayDetail.WINDOW_SET_CHANGED)
        for ((scene, detail) in cases) {
            val p = ManualDisplayProbe()
            val v = ManualDisplayContinuity.assess(r.f.snapshot, scene, 30, p)
            assertNotEquals(ManualDisplayVerdict.SAME, v); assertEquals(detail, p.detail)
            assertEquals(ManualDisplayContinuity.assess(r.f.snapshot, scene, 30), v)
        }
    }
    @Test fun screenUnavailableCannotBeRelabeledAsLaterNativeFailure() {
        val p = ManualDisplayProbe().also { it.point = ManualDisplayPoint.SCREEN }
        p.reject(ManualDisplayDetail.SCREEN_READ_UNAVAILABLE)
        p.point = ManualDisplayPoint.NATIVE_AFTER
        assertNull(manualDisplayOwnedId({ null }, { error("NO_QUERY") }, { error("NO_QUERY") }, p))
        assertEquals(ManualDisplayPoint.SCREEN, p.failurePoint); assertEquals(ManualDisplayDetail.SCREEN_READ_UNAVAILABLE, p.detail)
    }
    @Test fun afterNativeFailureIsDistinctFromBeforeAndNoReadResultOverridesIt() {
        val p = ManualDisplayProbe().also { it.point = ManualDisplayPoint.NATIVE_AFTER }
        assertNull(manualDisplayOwnedId({ 30 }, { false }, { emptySet() }, p))
        assertEquals(ManualDisplayPoint.NATIVE_AFTER, p.failurePoint)
        assertEquals(ManualDisplayDetail.LIVE_SET_UNCONFIRMED, p.detail)
    }
    @Test fun exceptionsAndCancellationUseSharedDriverBoundaryAndPreserveOriginalCancellation() {
        val p = ManualDisplayProbe().also { it.point = ManualDisplayPoint.NATIVE_BEFORE }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, checkedManualDisplay(p) { error("PRIVATE_FRAMEWORK_TEXT") })
        assertEquals(ManualDisplayDetail.EXCEPTION, p.detail); assertFalse(p.toString().contains("PRIVATE"))
        val cancellation = CancellationException("PRIVATE_CANCEL"); val q = ManualDisplayProbe()
        var removed = false
        assertSame(cancellation, runCatching {
            reviewManualDisplay({}, { checkedManualDisplay(q) { throw cancellation } }, { removed = true; error("SECONDARY") })
        }.exceptionOrNull())
        assertTrue(removed); assertEquals(ManualDisplayDetail.CANCELLED, q.detail)
    }
    @Test fun protectedContentAndSceneFailureNeverTurnUnavailableIntoSame() {
        val r = Rig(); val nodes = r.f.snapshot.nodes.map { it.copy(text = "결제") }
        val p = ManualDisplayProbe()
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, ManualDisplayContinuity.assess(r.f.snapshot.copy(nodes = nodes.toPersistentList()),
            r.scene.copy(nodes = nodes), 30, p))
        assertEquals(ManualDisplayDetail.PROTECTED_CONTENT, p.detail)
        val cleanup = ManualDisplayProbe().also { it.point = ManualDisplayPoint.SCREEN }
        cleanup.point = ManualDisplayPoint.COMPARISON // scan's callback returned SAME, then release failed
        cleanup.screenUnavailable(resourceFailure = true)
        assertEquals(ManualDisplayDetail.SCREEN_RESOURCE_FAILURE, cleanup.detail)
        assertEquals(ManualDisplayPoint.SCREEN, cleanup.failurePoint)
    }
    @Test fun detailedDebugReporterKeepsFixedCodeLimitAndFailureCannotChangeVerdict() {
        var calls = 0
        val trace = ManualPromptTrace(displayReviewSink = { _, _ -> calls++; error("PRIVATE_LOG") }, sink = { _, _, _ -> })
        val code = ManualDisplayReviewCode(ManualEventKind.WINDOW_SET, ManualWindowMask.BOUNDS, ManualDisplayVerdict.UNAVAILABLE,
            ManualDisplayPoint.NATIVE_BEFORE, ManualDisplayDetail.NATIVE_ID_UNAVAILABLE)
        repeat(50) { trace.displayReview(code) }; assertEquals(1, calls)
        var removed = false
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, reviewManualDisplay({}, { code.verdict }, { removed = true }, { trace.displayReview(code) }))
        assertTrue(removed)
    }
}
