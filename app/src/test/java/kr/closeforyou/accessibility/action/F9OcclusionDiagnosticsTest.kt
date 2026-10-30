package kr.closeforyou.accessibility.action

import kotlinx.coroutines.CancellationException
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.WindowType
import org.junit.Assert.*
import org.junit.Test

/** Real scan predicate/passive-bar/report contracts, with synthetic scalar getter callbacks.
 * This does not identify a Samsung/SystemUI window or reproduce native getter races. */
class F9OcclusionDiagnosticsTest {
    private val frame = ScreenBounds(0, 0, 400, 800)
    private val target = ScreenBounds(20, 100, 380, 600)
    private class Rig {
        var targetId = false; var overlayId = false; var type = WindowType.SYSTEM
        var otherLayer = 3; var targetLayer = 2
        var rect = ScreenBounds(0, 0, 400, 800)
        var passive = false
        val calls = mutableListOf<String>(); val codes = mutableListOf<F9OcclusionCode>()
        fun check(target: ScreenBounds, frame: ScreenBounds, noPath: Boolean = true,
            sink: (F9OcclusionCode) -> Unit = codes::add) = f9OccludingWindow(
            { calls += "TARGET_ID"; targetId }, { calls += "C_ID"; overlayId },
            { calls += "TYPE"; type }, { calls += "LAYERS"; otherLayer to targetLayer },
            { calls += "BOUNDS"; rect }, target, frame, noPath,
            { p -> calls += "PASSIVE"; p.result = if (passive) F9PassiveBarResult.PASSED else F9PassiveBarResult.ACTIVE; passive }, sink)
    }
    @Test fun targetAndExactOwnedCWindowShortCircuitWithoutFurtherReads() {
        val targetRig = Rig().also { it.targetId = true }
        assertFalse(targetRig.check(target, frame)); assertEquals(listOf("TARGET_ID"), targetRig.calls)
        val c = Rig().also { it.overlayId = true }
        assertFalse(c.check(target, frame)); assertEquals(listOf("TARGET_ID", "C_ID"), c.calls)
        assertTrue(c.codes.isEmpty())
    }
    @Test fun applicationAndLowerLayerWindowsDoNotReadBoundsOrPassiveMetadata() {
        val app = Rig().also { it.type = WindowType.APPLICATION }
        assertFalse(app.check(target, frame)); assertEquals(listOf("TARGET_ID", "C_ID", "TYPE"), app.calls)
        val low = Rig().also { it.otherLayer = 1 }
        assertFalse(low.check(target, frame)); assertFalse(low.calls.contains("BOUNDS")); assertTrue(low.codes.isEmpty())
    }
    @Test fun edgeTouchAndDisjointBoundsDoNotCountAsOcclusion() {
        for (rect in listOf(ScreenBounds(380, 100, 400, 600), ScreenBounds(0, 600, 400, 700), ScreenBounds(0, 0, 400, 99))) {
            val r = Rig().also { it.rect = rect }; assertFalse(r.check(target, frame))
            assertFalse(r.calls.contains("PASSIVE")); assertTrue(r.codes.isEmpty())
        }
    }
    @Test fun firstBlockingWindowReportsActualTypeLayerAndOverlapWithoutIdentifiers() {
        for (type in listOf(WindowType.SYSTEM, WindowType.OTHER, WindowType.INPUT_METHOD, WindowType.ACCESSIBILITY_OVERLAY)) {
            val r = Rig().also { it.type = type; it.otherLayer = 2; it.rect = target }
            assertTrue(r.check(target, frame)); val c = r.codes.single()
            assertEquals(type, c.type); assertEquals(F9OcclusionLayer.EQUAL, c.layer)
            assertEquals(F9OcclusionOverlap.EQUAL, c.overlap)
            assertEquals(F9PassiveBarResult.NOT_CHECKED_BOUNDED_TARGET, c.passive)
            assertFalse(r.calls.contains("PASSIVE"))
        }
    }
    @Test fun coverWithinAndPartialOverlapAreDifferentFixedCodes() {
        val cases = listOf(frame to F9OcclusionOverlap.COVERS_TARGET,
            ScreenBounds(40, 120, 360, 580) to F9OcclusionOverlap.WITHIN_TARGET,
            ScreenBounds(0, 0, 200, 200) to F9OcclusionOverlap.PARTIAL)
        for ((bounds, expected) in cases) {
            val r = Rig().also { it.rect = bounds }; assertTrue(r.check(target, frame))
            assertEquals(expected, r.codes.single().overlap); assertEquals(F9OcclusionLayer.ABOVE, r.codes.single().layer)
        }
    }
    @Test fun passiveBarExceptionIsNeverEvaluatedForBoundedPopupOrPress() {
        val bounded = Rig().also { it.passive = true }
        assertTrue(bounded.check(target, frame)); assertFalse(bounded.calls.contains("PASSIVE"))
        val press = Rig().also { it.passive = true }
        assertTrue(press.check(frame, frame, noPath = false)); assertFalse(press.calls.contains("PASSIVE"))
        assertEquals(F9PassiveBarResult.NOT_CHECKED_TARGET_PATH, press.codes.single().passive)
    }
    @Test fun existingFullFramePassiveExceptionAndFailureKeepTheirOriginalVerdicts() {
        val allowed = Rig().also { it.passive = true }
        assertFalse(allowed.check(frame, frame)); assertTrue(allowed.codes.isEmpty())
        assertEquals(1, allowed.calls.count { it == "PASSIVE" })
        val denied = Rig(); assertTrue(denied.check(frame, frame))
        assertEquals(F9PassiveBarResult.ACTIVE, denied.codes.single().passive)
    }
    private fun bar(type: Boolean = true, active: Boolean = false, focused: Boolean = false,
        bounds: ScreenBounds = ScreenBounds(0, 0, 400, 40), owner: Boolean = true,
        calls: MutableList<String> = mutableListOf()): Pair<Boolean, F9PassiveBarResult> {
        val p = F9PassiveBarProbe()
        val result = f9PassiveSystemBar({ calls += "TYPE"; type }, { calls += "ACTIVE"; active },
            { calls += "FOCUSED"; focused }, { calls += "BOUNDS"; bounds }, frame, { probe ->
                calls += "OWNER"; if (!owner) probe.rejected(F9PassiveBarResult.OWNER_UNCONFIRMED) else true
            }, p)
        return result to p.result
    }
    @Test fun passiveTypeActiveAndFocusFailuresPreserveShortCircuitGetterOrder() {
        val calls = mutableListOf<String>()
        assertEquals(false to F9PassiveBarResult.NOT_SYSTEM, bar(type = false, calls = calls))
        assertEquals(listOf("TYPE"), calls); calls.clear()
        assertEquals(false to F9PassiveBarResult.ACTIVE, bar(active = true, calls = calls))
        assertEquals(listOf("TYPE", "ACTIVE"), calls); calls.clear()
        assertEquals(false to F9PassiveBarResult.FOCUSED, bar(focused = true, calls = calls))
        assertEquals(listOf("TYPE", "ACTIVE", "FOCUSED"), calls)
    }
    @Test fun passiveWidthHeightAndEdgeFailuresDoNotReadOwnerMetadata() {
        for ((bounds, detail) in listOf(ScreenBounds(1, 0, 400, 40) to F9PassiveBarResult.WIDTH,
            ScreenBounds(0, 0, 400, 101) to F9PassiveBarResult.HEIGHT,
            ScreenBounds(0, 100, 400, 140) to F9PassiveBarResult.EDGE)) {
            val calls = mutableListOf<String>(); assertEquals(false to detail, bar(bounds = bounds, calls = calls))
            assertFalse(calls.contains("OWNER"))
        }
    }
    @Test fun exactTopAndBottomBarBoundariesRemainUnchanged() {
        for (bounds in listOf(ScreenBounds(0, 0, 400, 1), ScreenBounds(0, 700, 400, 800), ScreenBounds(0, 799, 400, 800)))
            assertEquals(true to F9PassiveBarResult.PASSED, bar(bounds = bounds))
        assertEquals(false to F9PassiveBarResult.HEIGHT, bar(bounds = ScreenBounds(0, 0, 400, 0)))
        assertEquals(false to F9PassiveBarResult.OWNER_UNCONFIRMED, bar(owner = false))
    }
    @Test fun windowAnyStopsAfterFirstRejectingWindowAndNeverReadsLaterWindows() {
        val first = Rig(); val second = Rig()
        assertTrue(listOf(first, second).any { it.check(target, frame) })
        assertEquals(1, first.codes.size); assertTrue(second.calls.isEmpty())
    }
    @Test fun recordingFailureCannotSuppressTheExistingOcclusionRejection() {
        val r = Rig()
        assertTrue(r.check(target, frame, sink = { throw CancellationException("PRIVATE_LOG") }))
    }
    @Test fun exceptionAndCancellationStillReleaseAndReportAtOriginalScanStage() {
        for (failure in listOf(IllegalStateException("PRIVATE_GETTER"), CancellationException("PRIVATE_CANCEL"))) {
            val codes = mutableListOf<F9ReadCode>(); var released = false
            val trace = F9ReadObservation(F9ReadPurpose.DISPLAY_REVIEW, codes::add)
            val result = runCatching { trace.run(body = {
                trace.stage = F9ReadStage.OCCLUSION
                f9OccludingWindow({ false }, { false }, { WindowType.SYSTEM }, { throw failure },
                    { error("NO_BOUNDS") }, target, frame, true, { error("NO_PASSIVE") }, trace::occluding)
            }, cleanup = { released = true }) }
            if (failure is CancellationException) assertSame(failure, result.exceptionOrNull()) else assertNull(result.getOrThrow())
            assertTrue(released); assertEquals(F9ReadStage.OCCLUSION, codes.single().stage)
            assertNull(codes.single().occlusion); assertFalse(codes.toString().contains("PRIVATE"))
        }
    }
    @Test fun actualScanReportPreservesFirstOccluderAlongsideLaterReleaseFailure() {
        val rows = mutableListOf<F9ReadCode>(); val trace = F9ReadObservation(F9ReadPurpose.DISPLAY_REVIEW, rows::add)
        val r = Rig()
        assertNull(trace.run(body = {
            trace.stage = F9ReadStage.OCCLUSION
            if (r.check(target, frame, sink = trace::occluding)) trace.require(false, F9ReadDetail.OCCLUDING_WINDOW)
            true
        }, cleanup = { trace.release(true) { error("PRIVATE_RELEASE") } }))
        val code = rows.single(); assertEquals(F9ReadDetail.OCCLUDING_WINDOW, code.detail)
        assertEquals(WindowType.SYSTEM, code.occlusion!!.type); assertEquals(F9ReadRelease.NODE, code.release)
        assertEquals(F9ReadOutcome.CLEANUP_FAILED, code.outcome)
    }
    @Test fun firstOccluderIsNotReplacedAndSeparateScansCannotShareWindowEvidence() {
        val rows = mutableListOf<F9ReadCode>(); val a = F9ReadObservation(F9ReadPurpose.DISPLAY_REVIEW, rows::add)
        val first = F9OcclusionCode(WindowType.SYSTEM, F9OcclusionLayer.ABOVE, F9OcclusionOverlap.PARTIAL, F9PassiveBarResult.NOT_CHECKED_BOUNDED_TARGET)
        a.occluding(first); a.occluding(first.copy(type = WindowType.OTHER)); a.report(); a.report()
        assertEquals(first, rows.single().occlusion)
        F9ReadObservation(F9ReadPurpose.MANUAL_ELIGIBILITY, rows::add).report()
        assertNull(rows.last().occlusion)
    }
    @Test fun boundedSystemEdgeStripStillRejectsAndDoesNotInventOwnerProof() {
        val popup = ScreenBounds(20, 10, 380, 600)
        val r = Rig().also { it.type = WindowType.SYSTEM; it.rect = ScreenBounds(0, 0, 400, 40); it.passive = true }
        assertTrue(r.check(popup, frame))
        val code = r.codes.single()
        assertEquals(F9OcclusionShape.TOP_EDGE_STRIP, code.shape)
        assertEquals(F9PassiveBarResult.NOT_CHECKED_BOUNDED_TARGET, code.passive)
        assertFalse(r.calls.contains("PASSIVE"))
    }
    @Test fun shapeUsesOnlyExistingRawBoundsAndNeverRepairsOutsideOrEmptyMetadata() {
        val cases = listOf(ScreenBounds(0, 0, 400, 800) to F9OcclusionShape.FULL_FRAME,
            ScreenBounds(0, 760, 400, 800) to F9OcclusionShape.BOTTOM_EDGE_STRIP,
            ScreenBounds(0, 0, 400, 40) to F9OcclusionShape.TOP_EDGE_STRIP,
            ScreenBounds(0, -1, 400, 40) to F9OcclusionShape.OUTSIDE_FRAME,
            ScreenBounds(0, 0, 400, 0) to F9OcclusionShape.EMPTY,
            ScreenBounds(0, 100, 400, 140) to F9OcclusionShape.OTHER,
            ScreenBounds(1, 0, 400, 40) to F9OcclusionShape.OTHER)
        for ((bounds, expected) in cases) assertEquals(expected, f9OcclusionShape(bounds, frame))
    }

    @Test fun realPassiveOwnerContractRequiresRootThenOwnPackageThenExactWindow() {
        val order = mutableListOf<String>(); val p = F9PassiveBarProbe()
        assertTrue(f9PassiveBarOwner({ order += "ROOT"; 7 }, { order += "OWN"; it },
            { order += "PACKAGE"; true }, { order += "WINDOW"; true }, p))
        assertEquals(listOf("ROOT", "OWN", "PACKAGE", "WINDOW"), order)
        assertEquals(F9PassiveBarResult.NOT_CHECKED, p.result) // only entire bar helper can mark PASSED
    }
    @Test fun missingSystemRootAndWrongPackageNeverReachFurtherGetters() {
        val missing = F9PassiveBarProbe()
        assertFalse(f9PassiveBarOwner<Int>({ null }, { error("NO_OWN") }, { error("NO_PACKAGE") }, { error("NO_ID") }, missing))
        assertEquals(F9PassiveBarResult.ROOT_UNAVAILABLE, missing.result)
        val wrong = F9PassiveBarProbe()
        assertFalse(f9PassiveBarOwner({ 7 }, { it }, { false }, { error("NO_ID") }, wrong))
        assertEquals(F9PassiveBarResult.OWNER_UNCONFIRMED, wrong.result)
    }
    @Test fun exactSystemRootWindowMismatchCannotEarnPassiveAllowance() {
        val p = F9PassiveBarProbe()
        assertFalse(f9PassiveSystemBar({ true }, { false }, { false }, { ScreenBounds(0, 0, 400, 20) }, frame,
            { f9PassiveBarOwner({ 7 }, { it }, { true }, { false }, it) }, p))
        assertEquals(F9PassiveBarResult.ROOT_WINDOW_MISMATCH, p.result)
    }
    @Test fun systemOwnerRootAcquisitionCancellationStillPropagatesThroughActualScanBoundary() {
        val cancel = CancellationException("SYNTHETIC_CANCEL"); var releases = 0; val rows = mutableListOf<F9ReadCode>()
        val trace = F9ReadObservation(F9ReadPurpose.PAGE, rows::add)
        assertSame(cancel, runCatching { trace.run(body = {
            trace.stage = F9ReadStage.OCCLUSION
            f9PassiveBarOwner<Int>({ throw cancel }, { it }, { true }, { true }, F9PassiveBarProbe())
        }, cleanup = { releases++ }) }.exceptionOrNull())
        assertEquals(1, releases); assertEquals(F9ReadOutcome.CANCELLED, rows.single().outcome)
    }

}
