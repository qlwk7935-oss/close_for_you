package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Same failed comparison used by the Android display reader; synthetic DTOs, no OEM proof. */
class ManualBoundsTargetDiagnosticsTest {
    private class Rig(val enabled: Boolean = true) {
        val f = F9AutoCloserTest.Fixture()
        val root = f.node.copy(path = "0", viewId = "synthetic-root", className = "android.widget.FrameLayout",
            text = null, clickable = false, bounds = f.bounds)
        val original = f.snapshot.copy(nodes = persistentListOf(root, f.node))
        var fresh = f.before.copy(nodes = original.nodes)
        val probe = ManualDisplayProbe(enabled)
        var removed = 0
        var retired = false
        fun expandRoot() { fresh = fresh.copy(nodes = listOf(root.copy(bounds = ScreenBounds(18, 18, 202, 202)), f.node)) }
        fun window(change: (WindowSnapshot) -> WindowSnapshot) {
            fresh = fresh.copy(windows = fresh.windows.map { if (it.id == f.token.windowId) change(it) else it })
        }
        fun review(): ManualDisplayVerdict = reviewManualDisplay({ retired = true }, {
            ManualDisplayContinuity.assess(original, fresh, 30, probe, ManualDisplayListing.NOT_ENUMERATED)
        }, { removed++ })
        fun rejected(): ManualBoundsCode {
            assertEquals(ManualDisplayVerdict.CHANGED, review())
            assertTrue(retired); assertEquals(1, removed); assertEquals(0, f.clicks)
            assertEquals(ManualDisplayDetail.NODES_CHANGED, probe.detail)
            assertEquals(ManualNodeMismatch.BOUNDS, probe.failureNodes)
            return requireNotNull(probe.failureBounds)
        }
    }

    @Test fun expandedRootAndWindowReproduceReportedPatternAndStillRemovePrompt() {
        val r = Rig(); r.expandRoot(); r.window { it.copy(bounds = ScreenBounds(18, 18, 202, 202)) }
        val c = r.rejected()
        assertEquals(ManualBoundsNode.ROOT, c.node); assertEquals(ManualBoundsChange.EXPANDED, c.change)
        assertEquals(ManualBoundsCommon.NOT_TRANSLATION, c.common); assertEquals(ManualBoundsWindow.OTHER_CHANGE, c.window)
        assertEquals(ManualBoundsTarget.SAME_ID_APPLICATION, c.target)
        assertEquals(ManualBoundsFlags.SAME, c.flags); assertEquals(ManualBoundsLayer.SAME, c.layer)
        assertEquals(ManualRootWindow.EQUAL, c.rootWindowBefore); assertEquals(ManualRootWindow.EQUAL, c.rootWindowAfter)
    }
    @Test fun rootExpandsBeyondUnchangedWindowWithoutClaimingNativeRootSwitch() {
        val r = Rig(); r.expandRoot(); val c = r.rejected()
        assertEquals(ManualBoundsWindow.UNCHANGED, c.window)
        assertEquals(ManualRootWindow.EQUAL, c.rootWindowBefore)
        assertEquals(ManualRootWindow.WINDOW_WITHIN_ROOT, c.rootWindowAfter)
    }
    @Test fun expandedWindowCanContainChangedRootWithoutMakingItSafe() {
        val r = Rig(); r.expandRoot(); r.window { it.copy(bounds = ScreenBounds(10, 10, 210, 210)) }
        assertEquals(ManualRootWindow.ROOT_WITHIN_WINDOW, r.rejected().rootWindowAfter)
    }
    @Test fun partialOverlapAndDisjointRelationsHaveNoCoordinatesOrAuthorization() {
        for ((b, expected) in listOf(ScreenBounds(100, 100, 250, 250) to ManualRootWindow.PARTIAL_OVERLAP,
                ScreenBounds(210, 210, 260, 260) to ManualRootWindow.DISJOINT)) {
            val r = Rig(); r.expandRoot(); r.window { it.copy(bounds = b) }
            assertEquals(expected, r.rejected().rootWindowAfter)
        }
    }
    @Test fun layerComparisonReportsDirectionButNeverAWindowIdentifier() {
        for ((delta, expected) in listOf(1 to ManualBoundsLayer.HIGHER, -1 to ManualBoundsLayer.LOWER)) {
            val r = Rig(); r.expandRoot(); r.window { it.copy(layer = it.layer + delta) }
            assertEquals(expected, r.rejected().layer)
        }
    }
    @Test fun diagnosticFlagsCompareBothBooleansWithoutRelaxingTargetSelection() {
        // The stricter NOT_ENUMERATED selection rejects these before the node comparison.
        // Pure helper assertions below only describe DTO differences, never an admission.
        for ((active, focused, expected) in listOf(Triple(false, true, ManualBoundsFlags.ACTIVE_CHANGED),
                Triple(true, false, ManualBoundsFlags.FOCUSED_CHANGED), Triple(false, false, ManualBoundsFlags.BOTH_CHANGED))) {
            val r = Rig(); r.expandRoot(); r.window { it.copy(active = active, focused = focused) }
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.review()); assertNull(r.probe.failureBounds)
            assertEquals(expected, describeManualBoundsMismatch(r.root, r.fresh.nodes[0], r.original, r.fresh).flags)
            assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun rootActionsAfterFirstBoundsMismatchCanDisproveDescriptorCorrespondence() {
        val r = Rig(); r.expandRoot()
        r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == "0") it.copy(actions = kotlinx.collections.immutable.persistentSetOf()) else it })
        assertEquals(ManualBoundsCommon.UNALIGNED, r.rejected().common)
    }
    @Test fun missingPreorderRootIsUnavailableRatherThanInventingSameNode() {
        val r = Rig(); val changed = r.root.copy(path = "0/2", bounds = ScreenBounds(18, 18, 202, 202))
        val original = r.original.copy(nodes = persistentListOf(r.root.copy(path = "0/2"), r.f.node))
        val p = ManualDisplayProbe(true)
        assertEquals(ManualDisplayVerdict.CHANGED, ManualDisplayContinuity.assess(original,
            r.fresh.copy(nodes = listOf(changed, r.f.node)), 30, p, ManualDisplayListing.NOT_ENUMERATED))
        assertEquals(ManualRootWindow.UNAVAILABLE, p.failureBounds!!.rootWindowBefore)
        assertEquals(ManualRootWindow.UNAVAILABLE, p.failureBounds!!.rootWindowAfter)
    }
    @Test fun packageWindowAndTypeChangesRejectBeforeAnyBoundsDiagnostic() {
        val mutations: List<(F9Scene) -> F9Scene> = listOf(
            { it.copy(packageName = "synthetic.other") }, { it.copy(windowId = 21) },
            { it.copy(windows = it.windows.map { w -> if (w.id == 20) w.copy(type = WindowType.SYSTEM) else w }) })
        for (change in mutations) {
            val r = Rig(); r.expandRoot(); r.fresh = change(r.fresh)
            assertNotEquals(ManualDisplayVerdict.SAME, r.review()); assertNull(r.probe.failureBounds)
            assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun helperDoesNotCertifyIdentityWhenPackageWindowOrTypeDiffers() {
        val r = Rig(); r.expandRoot()
        val variants = listOf(
            r.fresh.copy(packageName = "synthetic.other") to ManualBoundsTarget.PACKAGE_MISMATCH,
            r.fresh.copy(windowId = 21, windows = r.fresh.windows.map { if (it.id == 20) it.copy(id = 21) else it }) to ManualBoundsTarget.WINDOW_ID_MISMATCH,
            r.fresh.copy(windows = r.fresh.windows.map { if (it.id == 20) it.copy(type = WindowType.SYSTEM) else it }) to ManualBoundsTarget.TYPE_MISMATCH)
        for ((scene, expected) in variants) assertEquals(expected,
            describeManualBoundsMismatch(r.root, scene.nodes[0], r.original, scene).target)
    }
    @Test fun missingOrDuplicateWindowCannotBeClassifiedAsSameTarget() {
        val r = Rig(); r.expandRoot()
        for (windows in listOf(persistentListOf(), persistentListOf(r.f.windows[1], r.f.windows[1]))) {
            val original = r.original.copy(windows = windows)
            val c = describeManualBoundsMismatch(r.root, r.fresh.nodes[0], original, r.fresh)
            assertEquals(ManualBoundsTarget.UNAVAILABLE, c.target); assertEquals(ManualBoundsLayer.UNAVAILABLE, c.layer)
            assertEquals(ManualBoundsFlags.UNAVAILABLE, c.flags); assertEquals(ManualRootWindow.UNAVAILABLE, c.rootWindowBefore)
        }
    }
    @Test fun firstReportAndOriginalInputSurviveLaterFailuresAndLogCancellation() {
        val r = Rig(); r.expandRoot(); val original = r.original; val first = r.rejected()
        r.probe.reject(ManualDisplayDetail.REGISTRATION_CHANGED); r.probe.point = ManualDisplayPoint.NATIVE_AFTER
        r.probe.nodesChanged(original.copy(nodes = persistentListOf()), r.fresh)
        assertEquals(first, r.probe.failureBounds); assertEquals(original, r.original)
        var reports = 0
        val s = Rig(); s.expandRoot()
        assertEquals(ManualDisplayVerdict.CHANGED, reviewManualDisplay({}, {
            ManualDisplayContinuity.assess(s.original, s.fresh, 30, s.probe, ManualDisplayListing.NOT_ENUMERATED)
        }, { s.removed++ }, { reports++; throw kotlinx.coroutines.CancellationException("SYNTHETIC_SINK") }))
        assertEquals(1, reports); assertEquals(1, s.removed)
        assertEquals(ManualBoundsTarget.SAME_ID_APPLICATION, s.probe.failureBounds!!.target)
    }
    @Test fun debugOffAndOnKeepSameDisplayAndZeroClickDecisions() {
        for (enabled in listOf(false, true)) {
            val r = Rig(enabled); r.expandRoot(); r.window { it.copy(bounds = ScreenBounds(18, 18, 202, 202)) }
            assertEquals(ManualDisplayVerdict.CHANGED, r.review()); assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
            assertEquals(enabled, r.probe.failureBounds != null)
            val same = Rig(enabled); assertEquals(ManualDisplayVerdict.SAME, same.review()); assertEquals(0, same.removed)
            assertNull(same.probe.failureBounds); assertEquals(0, same.f.clicks)
        }
    }
    @Test fun summariesStoreOnlyFixedEnumsAndNeverSourceDescriptorsOrNumbers() {
        val r = Rig(); r.expandRoot(); val c = r.rejected()
        assertTrue(ManualBoundsCode::class.java.declaredFields.filterNot {
            it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.all { it.type.isEnum })
        assertFalse(c.toString().contains("synthetic-root")); assertFalse(c.toString().contains("synthetic.app"))
        val before = r.original.nodes.toPersistentList(); val after = r.fresh.nodes.toList()
        describeManualBoundsMismatch(r.root, r.fresh.nodes[0], r.original, r.fresh)
        assertEquals(before, r.original.nodes); assertEquals(after, r.fresh.nodes)
    }
}
