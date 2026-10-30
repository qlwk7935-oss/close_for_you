package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.overlay.OverlayNativeWindowLifetime
import org.junit.Assert.*
import org.junit.Test

/** Actual display comparison and terminal codes. All geometry is synthetic immutable input. */
class ManualBoundsMismatchDiagnosticsTest {
    private class Rig(enabled: Boolean = true) {
        val f = F9AutoCloserTest.Fixture()
        val root = f.node.copy(path = "0", viewId = "synthetic-root", className = "android.widget.FrameLayout",
            text = null, clickable = false, bounds = f.bounds, actions = persistentSetOf())
        val text = root.copy(path = "0/1", viewId = "synthetic-body", className = "android.widget.TextView", text = "일반 안내", bounds = ScreenBounds(30, 40, 180, 80))
        val original = f.snapshot.copy(nodes = persistentListOf(root, f.node, text))
        var scene = f.before.copy(nodes = original.nodes)
        val probe = ManualDisplayProbe(nodeDiagnostics = enabled)
        var removed = 0; var rawCurrent = true
        fun review() = reviewManualDisplay({ rawCurrent = false }, {
            ManualDisplayContinuity.assess(original, scene, 30, probe, ManualDisplayListing.NOT_ENUMERATED)
        }, { removed++ })
        fun all(dx: Int, dy: Int) { scene = scene.copy(nodes = original.nodes.map { it.copy(bounds = shift(it.bounds, dx, dy)) }) }
    }
    companion object {
        private fun shift(b: ScreenBounds, dx: Int, dy: Int) = ScreenBounds(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
    }
    private fun Rig.rejected(): ManualBoundsCode {
        assertEquals(ManualDisplayVerdict.CHANGED, review()); assertEquals(1, removed); assertFalse(rawCurrent)
        assertEquals(ManualDisplayDetail.NODES_CHANGED, probe.detail); assertEquals(ManualNodeMismatch.BOUNDS, probe.failureNodes)
        assertEquals(0, f.clicks)
        return requireNotNull(probe.failureBounds)
    }
    @Test fun uniformWholeTreeShiftIsDiagnosedButNeverAcceptedAsHarmlessOrReady() {
        val r = Rig(); r.all(0, 4)
        val c = r.rejected()
        assertEquals(ManualBoundsNode.ROOT, c.node); assertEquals(ManualBoundsChange.TRANSLATE_Y, c.change)
        assertEquals(ManualBoundsCommon.ALL_NODES, c.common); assertEquals(ManualBoundsWindow.UNCHANGED, c.window)
    }
    @Test fun xAndXyTranslationsUseOnlyDirectionCategoriesAndKeepRejection() {
        for ((dx, dy, shape) in listOf(Triple(3, 0, ManualBoundsChange.TRANSLATE_X), Triple(-2, 3, ManualBoundsChange.TRANSLATE_XY))) {
            val r = Rig(); r.all(dx, dy); assertEquals(shape, r.rejected().change)
        }
    }
    @Test fun onlyRootMovingIsPartialRatherThanAWholeTreeCoordinateConversion() {
        val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root.copy(bounds = shift(r.root.bounds, 0, 4)), r.f.node, r.text))
        assertEquals(ManualBoundsCommon.PARTIAL, r.rejected().common)
    }
    @Test fun firstDifferentDescendantTypeComesOnlyFromFixedNativeClassMapping() {
        val kinds = listOf("android.widget.Button" to ManualBoundsNode.BUTTON,
            "android.widget.TextView" to ManualBoundsNode.TEXT,
            "android.widget.LinearLayout" to ManualBoundsNode.CONTAINER,
            "android.widget.ScrollView" to ManualBoundsNode.SCROLL_CONTAINER,
            "android.widget.ImageView" to ManualBoundsNode.IMAGE,
            "android.webkit.WebView" to ManualBoundsNode.WEB,
            "android.view.TextureView" to ManualBoundsNode.SURFACE,
            "synthetic.CustomWidget" to ManualBoundsNode.OTHER)
        for ((kind, expected) in kinds) {
            val r = Rig(); val a = r.f.node.copy(className = kind)
            val original = r.original.copy(nodes = persistentListOf(r.root, a, r.text))
            val fresh = r.scene.copy(nodes = listOf(r.root, a.copy(bounds = shift(a.bounds, 1, 0)), r.text))
            assertEquals(ManualDisplayVerdict.CHANGED, ManualDisplayContinuity.assess(original, fresh, 30, r.probe, ManualDisplayListing.NOT_ENUMERATED))
            assertEquals(expected, r.probe.failureBounds!!.node)
            assertFalse(r.probe.failureBounds.toString().contains(kind))
        }
    }
    @Test fun contractionExpansionAndReshapeAreGeometryPatternsNotClippingOrMagnificationProof() {
        val cases = listOf(ScreenBounds(20, 20, 200, 190) to ManualBoundsChange.CONTRACTED,
            ScreenBounds(18, 18, 202, 202) to ManualBoundsChange.EXPANDED,
            ScreenBounds(22, 18, 198, 202) to ManualBoundsChange.RESHAPED)
        for ((b, expected) in cases) {
            val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root.copy(bounds = b), r.f.node, r.text))
            val c = r.rejected(); assertEquals(expected, c.change); assertEquals(ManualBoundsCommon.NOT_TRANSLATION, c.common)
        }
    }
    @Test fun differentOffsetsOrLaterResizingCannotBeReportedAsUniformTranslation() {
        for (resize in listOf(true, false)) {
            val r = Rig(); r.all(0, 3)
            r.scene = r.scene.copy(nodes = r.scene.nodes.map { if (it.path == r.text.path) it.copy(bounds =
                if (resize) it.bounds.copy(right = it.bounds.right - 1) else shift(it.bounds, 1, 0)) else it })
            assertEquals(ManualBoundsCommon.NON_UNIFORM, r.rejected().common)
        }
    }
    @Test fun laterTextActionOrIdentityChangesMakeWholeTreeCorrespondenceUnaligned() {
        val changes: List<(NodeSnapshot) -> NodeSnapshot> = listOf({ it.copy(text = "다른 안내") },
            { it.copy(viewId = "synthetic-other") }, { it.copy(path = "0/9") },
            { it.copy(actions = persistentSetOf(NodeAction.CLICK)) })
        for (change in changes) {
            val r = Rig(); r.all(0, 3); r.scene = r.scene.copy(nodes = r.scene.nodes.map { if (it.path == r.text.path) change(it) else it })
            assertEquals(ManualBoundsCommon.UNALIGNED, r.rejected().common)
            assertEquals(ManualNodeMismatch.BOUNDS, r.probe.failureNodes) // first observed field stays first
        }
    }
    @Test fun actualPopupAndAllNodesMovingTogetherStillRejectAndReportWindowRelation() {
        val r = Rig(); r.all(2, 3)
        r.scene = r.scene.copy(windows = r.f.windows.map { if (it.id == r.f.token.windowId) it.copy(bounds = shift(it.bounds, 2, 3)) else it })
        val c = r.rejected(); assertEquals(ManualBoundsWindow.MATCHES_NODE_TRANSLATION, c.window)
        assertEquals(ManualBoundsCommon.ALL_NODES, c.common)
    }
    @Test fun differentlyChangedWindowBoundsAreNotMistakenForAnApplicableOffset() {
        val r = Rig(); r.all(0, 3)
        r.scene = r.scene.copy(windows = r.f.windows.map { if (it.id == r.f.token.windowId) it.copy(bounds = it.bounds.copy(right = 210)) else it })
        assertEquals(ManualBoundsWindow.OTHER_CHANGE, r.rejected().window)
    }
    @Test fun missingOrAmbiguousReferenceWindowIsUnavailableNotInvented() {
        val r = Rig(); r.all(0, 3)
        for (windows in listOf(persistentListOf(), persistentListOf(r.f.windows[1], r.f.windows[1]))) {
            val old = r.original.copy(windows = windows)
            val c = describeManualBoundsMismatch(old.nodes[0], r.scene.nodes[0], old, r.scene)
            assertEquals(ManualBoundsWindow.UNAVAILABLE, c.window)
        }
    }
    @Test fun disabledDiagnosticsKeepSameRejectionWithoutBoundsAnalysisOrExtraDtoTraversal() {
        val r = Rig(false); var gets = 0
        val changed = r.root.copy(bounds = shift(r.root.bounds, 0, 3))
        r.scene = r.scene.copy(nodes = object : AbstractList<NodeSnapshot>() {
            override val size = 3
            override fun get(index: Int): NodeSnapshot {
                gets++; check(gets <= 4) { "NO_DIAGNOSTIC_TRAVERSAL" }
                return if (index == 0) changed else r.original.nodes[index]
            }
        })
        assertEquals(ManualDisplayVerdict.CHANGED, r.review()); assertEquals(1, r.removed); assertEquals(4, gets)
        assertEquals(ManualNodeMismatch.NOT_CHECKED, r.probe.failureNodes); assertNull(r.probe.failureBounds)
    }
    @Test fun secondarySummaryFailureCannotEraseTheActualBoundsMismatchOrRetainDisplay() {
        val r = Rig(); var gets = 0
        val changed = r.root.copy(bounds = shift(r.root.bounds, 0, 3))
        r.scene = r.scene.copy(nodes = object : AbstractList<NodeSnapshot>() {
            override val size = 3
            override fun get(index: Int): NodeSnapshot {
                gets++
                if (gets > 5) throw CancellationException("SYNTHETIC_DIAGNOSTIC")
                return if (index == 0) changed else r.original.nodes[index]
            }
        })
        assertEquals(ManualBoundsCode.UNKNOWN, r.rejected())
        assertEquals(ManualNodeMismatch.BOUNDS, r.probe.failureNodes)
    }
    @Test fun firstBoundsSummarySurvivesLaterNativeAndCleanupFailures() {
        val r = Rig(); r.all(0, 3); val first = r.rejected()
        r.probe.point = ManualDisplayPoint.NATIVE_AFTER; r.probe.reject(ManualDisplayDetail.NATIVE_ID_CHANGED)
        r.probe.nodesChanged(r.original.nodes, listOf(r.f.node))
        assertEquals(first, r.probe.failureBounds); assertEquals(ManualDisplayDetail.NODES_CHANGED, r.probe.detail)
    }
    @Test fun nonBoundsAndEarlierGeometryFailureDoNotInventBoundsStatistics() {
        val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root.copy(enabled = false), r.f.node, r.text))
        assertEquals(ManualDisplayVerdict.CHANGED, r.review()); assertEquals(ManualNodeMismatch.ENABLED, r.probe.failureNodes)
        assertNull(r.probe.failureBounds)
        val s = Rig(); s.all(0, 3); s.scene = s.scene.copy(screen = ScreenBounds(0, 0, 301, 500))
        assertEquals(ManualDisplayVerdict.CHANGED, s.review()); assertEquals(ManualDisplayDetail.FRAME_CHANGED, s.probe.detail)
        assertNull(s.probe.failureBounds)
    }
    @Test fun largeSignedCoordinateDifferencesUseLongArithmeticWithoutWrappingOrCorrection() {
        val r = Rig(); val a = r.f.node.copy(bounds = ScreenBounds(Int.MIN_VALUE, 10, Int.MIN_VALUE + 10, 20))
        val b = a.copy(bounds = ScreenBounds(Int.MAX_VALUE - 10, 10, Int.MAX_VALUE, 20))
        val old = r.original.copy(nodes = persistentListOf(a)); val fresh = r.scene.copy(nodes = listOf(b))
        val c = describeManualBoundsMismatch(a, b, old, fresh)
        assertEquals(ManualBoundsChange.TRANSLATE_X, c.change); assertEquals(ManualBoundsCommon.ALL_NODES, c.common)
        // Pure numerical fixture only; an Android frame/reader is not asserted to accept it.
    }
    @Test fun emptyOriginalBoundsNeverBecomeARepairedRectangleOrSafeUniformShift() {
        val r = Rig(); val a = r.root.copy(bounds = ScreenBounds(20, 20, 20, 200))
        val old = r.original.copy(nodes = persistentListOf(a, r.f.node, r.text)); val p = ManualDisplayProbe(true)
        assertEquals(ManualDisplayVerdict.CHANGED, ManualDisplayContinuity.assess(old, r.scene, 30, p, ManualDisplayListing.NOT_ENUMERATED))
        assertEquals(ManualBoundsChange.INVALID_RECT, p.failureBounds!!.change)
        assertNotEquals(ManualBoundsCommon.ALL_NODES, p.failureBounds!!.common)
    }
    @Test fun helpersHaveExplicitNodeAndWindowBoundsAndStoreOnlyFixedEnums() {
        val r = Rig(); val huge = object : AbstractList<NodeSnapshot>() {
            override val size = 1_201
            override fun get(index: Int): NodeSnapshot = error("NO_NODE_TRAVERSAL")
        }
        val c = describeManualBoundsMismatch(r.root, r.root.copy(bounds = shift(r.root.bounds, 1, 0)), r.original.copy(nodes = List(1_201) { r.root }.toPersistentList()), r.scene.copy(nodes = huge))
        assertEquals(ManualBoundsCommon.UNALIGNED, c.common)
        val oversizedWindows = object : AbstractList<WindowSnapshot>() {
            override val size = 33
            override fun get(index: Int): WindowSnapshot = error("NO_WINDOW_TRAVERSAL")
        }
        assertEquals(ManualBoundsWindow.UNAVAILABLE, describeManualBoundsMismatch(r.root,
            r.root.copy(bounds = shift(r.root.bounds, 1, 0)), r.original,
            r.scene.copy(windows = oversizedWindows)).window)
        assertTrue(ManualBoundsCode::class.java.declaredFields.filterNot {
            it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers)
        }.all { it.type.isEnum })
        assertFalse(c.toString().contains("synthetic"))
        assertEquals("ManualDisplayProbe(redacted)", r.probe.toString())
    }
    @Test fun terminalRecordUsesExistingOneRowAndSinkCancellationDoesNotChangeRejection() {
        val r = Rig(); r.all(0, 3); val rows = mutableListOf<ManualDisplayReviewCode>()
        assertEquals(ManualDisplayVerdict.CHANGED, reviewManualDisplay({}, {
            ManualDisplayContinuity.assess(r.original, r.scene, 30, r.probe, ManualDisplayListing.NOT_ENUMERATED)
        }, { r.removed++ }, { verdict ->
            rows += ManualDisplayReviewCode(ManualEventKind.WINDOW_SET, ManualWindowMask.BOUNDS, verdict,
                r.probe.failurePoint, r.probe.detail, r.probe.failureOwned, r.probe.failureListing,
                r.probe.failureNodes, r.probe.failureBounds)
            throw CancellationException("SYNTHETIC_SINK")
        }))
        assertEquals(1, rows.size); assertEquals(1, r.removed)
        assertEquals(ManualBoundsCommon.ALL_NODES, rows.single().bounds!!.common)
    }
    @Test fun queuedAddedRemainsRejectedAndLaterActualDetachAbsenceCannotReviveFailedCleanup() {
        val r = Rig(); r.all(0, 3); r.rejected()
        val owner = Any(); val n = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true); it.removalRequested() }
        val failures = mutableListOf<CleanupFailure>(); val b = OverlayCleanupBarrier(failureDiagnostic = failures::add)
        b.retiring(null, 1, 20, n, owner); n.removalReturned(true); b.removalReturned(true)
        assertNull(b.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ADDED))
        assertEquals(listOf(CleanupFailure.RETIRED_ID_EVENT), failures)
        n.detached(); assertFalse(b.nativeProcessed(n, true) { CleanupWindowPresence.ABSENT }); assertNull(b.permit())
        assertEquals(ManualBoundsCommon.ALL_NODES, r.probe.failureBounds!!.common); assertEquals(0, r.f.clicks)
    }
    @Test fun laterRemovalReceiptStillCannotSucceedWithoutNativeDetachOrWithUnknownPresence() {
        for (unknown in listOf(true, false)) {
            val owner = Any(); val n = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true); it.removalRequested() }
            val b = OverlayCleanupBarrier(); b.retiring(null, 1, 20, n, owner); n.removalReturned(true); b.removalReturned(true)
            val receipt = requireNotNull(b.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
            if (unknown) n.detached()
            b.processed(receipt, true) { if (unknown) CleanupWindowPresence.UNKNOWN else CleanupWindowPresence.ABSENT }
            assertFalse(b.ready); assertNull(b.permit())
        }
    }
}
