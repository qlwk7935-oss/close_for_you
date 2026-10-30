package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.overlay.OverlayNativeWindowLifetime
import org.junit.Assert.*
import org.junit.Test

/** Actual assess/review/registration/barrier paths. No Android getter or phone result is mocked as evidence. */
class ManualDisplayNodeMismatchTest {
    private class Rig(enabled: Boolean = true) {
        val f = F9AutoCloserTest.Fixture()
        val root = f.node.copy(path = "0", viewId = "synthetic-root", className = "android.widget.LinearLayout",
            text = null, clickable = false, bounds = f.bounds, actions = persistentSetOf())
        val original = f.snapshot.copy(nodes = persistentListOf(root, f.node))
        var scene = f.before.copy(nodes = original.nodes)
        val probe = ManualDisplayProbe(nodeDiagnostics = enabled)
        var removed = 0; var retired = false
        fun assess() = ManualDisplayContinuity.assess(original, scene, 30, probe, ManualDisplayListing.NOT_ENUMERATED)
        fun review() = reviewManualDisplay({ retired = true }, ::assess, { removed++ })
    }
    private data class Case(val code: ManualNodeMismatch, val transform: (NodeSnapshot) -> NodeSnapshot)
    private fun cases() = listOf(
        Case(ManualNodeMismatch.PATH_OR_ORDER) { it.copy(path = "0/1") },
        Case(ManualNodeMismatch.VIEW_ID) { it.copy(viewId = "synthetic-different") },
        Case(ManualNodeMismatch.CLASS_NAME) { it.copy(className = "android.widget.TextView") },
        Case(ManualNodeMismatch.TEXT) { it.copy(text = "창 닫기") },
        Case(ManualNodeMismatch.CONTENT_DESCRIPTION) { it.copy(contentDescription = "도움말") },
        Case(ManualNodeMismatch.CLICKABLE) { it.copy(clickable = false) },
        Case(ManualNodeMismatch.ENABLED) { it.copy(enabled = false) },
        Case(ManualNodeMismatch.BOUNDS) { it.copy(bounds = it.bounds.copy(left = it.bounds.left + 1)) },
        Case(ManualNodeMismatch.ACTIONS) { it.copy(actions = persistentSetOf()) })
    @Test fun allNineDtoFieldsKeepChangedRemovalAndExposeOnlyTheFirstFieldCode() {
        for (case in cases()) {
            val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root, case.transform(r.f.node)))
            assertEquals(case.code.name, ManualDisplayVerdict.CHANGED, r.review())
            assertTrue(r.retired); assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
            assertEquals(ManualDisplayDetail.NODES_CHANGED, r.probe.detail)
            assertEquals(case.code, r.probe.failureNodes)
        }
    }
    @Test fun diagnosticsDisabledKeepsEveryFieldVerdictAndRemovalWithoutRecordingDifferences() {
        for (case in cases()) {
            val r = Rig(false); r.scene = r.scene.copy(nodes = listOf(r.root, case.transform(r.f.node)))
            assertEquals(ManualDisplayVerdict.CHANGED, r.review()); assertEquals(1, r.removed)
            assertEquals(ManualNodeMismatch.NOT_CHECKED, r.probe.failureNodes)
            assertEquals(ManualDisplayDetail.NODES_CHANGED, r.probe.detail)
        }
    }
    @Test fun countDifferenceDominatesPositionalFieldDifferencesWithoutPrintingCountOrIndex() {
        val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.f.node.copy(text = "다른 안내")))
        assertEquals(ManualDisplayVerdict.CHANGED, r.review())
        assertEquals(ManualNodeMismatch.NODE_COUNT, r.probe.failureNodes); assertEquals(1, r.removed)
    }
    @Test fun sameSizeReorderIsNotNormalizedOrDeclaredAnIdenticalTree() {
        val r = Rig(); r.scene = r.scene.copy(nodes = r.original.nodes.reversed())
        assertEquals(ManualDisplayVerdict.CHANGED, r.review())
        assertEquals(ManualNodeMismatch.PATH_OR_ORDER, r.probe.failureNodes)
    }
    @Test fun firstPositionalNodeWinsBeforeAnEarlierFieldAtALaterNode() {
        val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root.copy(enabled = false), r.f.node.copy(path = "0/7")))
        assertEquals(ManualDisplayVerdict.CHANGED, r.review())
        assertEquals(ManualNodeMismatch.ENABLED, r.probe.failureNodes)
    }
    @Test fun firstFieldOrderMatchesDtoDeclarationAndDoesNotLogAllDifferences() {
        val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root, r.f.node.copy(
            viewId = "synthetic-other", className = "android.view.View", text = "다른 안내", enabled = false)))
        assertEquals(ManualDisplayVerdict.CHANGED, r.review())
        assertEquals(ManualNodeMismatch.VIEW_ID, r.probe.failureNodes)
        assertFalse(r.probe.toString().contains("synthetic"))
    }
    @Test fun equalDtosDoNotRunMismatchClassificationOrChangeHealthyRetention() {
        for (enabled in listOf(true, false)) {
            val r = Rig(enabled)
            assertEquals(ManualDisplayVerdict.SAME, r.review()); assertTrue(r.retired); assertEquals(0, r.removed)
            assertEquals(ManualNodeMismatch.NOT_CHECKED, r.probe.failureNodes)
        }
    }
    @Test fun nullAndEmptyStringsRemainDifferentAndAreNotPrintedOrNormalized() {
        val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root.copy(text = ""), r.f.node))
        assertEquals(ManualDisplayVerdict.CHANGED, r.review())
        assertEquals(ManualNodeMismatch.TEXT, r.probe.failureNodes)
    }
    @Test fun earlierFrameIncompleteAndEmptyGeometryFailuresNeverInventNodeMismatchEvidence() {
        for (mode in listOf("FRAME", "INCOMPLETE", "EMPTY")) {
            val r = Rig(); r.scene = when (mode) {
                "FRAME" -> r.scene.copy(screen = ScreenBounds(0, 0, 301, 500), nodes = listOf(r.f.node))
                "INCOMPLETE" -> r.scene.copy(complete = false, nodes = listOf(r.f.node))
                else -> r.scene.copy(nodes = emptyList())
            }
            assertNotEquals(ManualDisplayVerdict.SAME, r.review()); assertEquals(1, r.removed)
            assertEquals(ManualNodeMismatch.NOT_CHECKED, r.probe.failureNodes)
        }
    }
    @Test fun listedCWindowFocusTransferIsNotANodeFieldDifference() {
        val r = Rig()
        val c = WindowSnapshot(30, WindowType.ACCESSIBILITY_OVERLAY, r.scene.screen, true, true, 3)
        val fresh = r.scene.copy(windows = r.f.windows.map { if (it.id == 20) it.copy(active = false, focused = false) else it } + c)
        assertEquals(ManualDisplayVerdict.SAME,
            ManualDisplayContinuity.assess(r.original, fresh, 30, r.probe, ManualDisplayListing.LISTED))
        assertEquals(ManualNodeMismatch.NOT_CHECKED, r.probe.failureNodes)
        assertTrue(NodeSnapshot::class.java.declaredFields.none { it.name.contains("focus", ignoreCase = true) })
        assertEquals(setOf(NodeAction.CLICK, NodeAction.DISMISS), NodeAction.entries.toSet())
    }
    @Test fun unlistedCStillCannotNormalizeTargetFocusLossOrWindowChanges() {
        for (focusLost in listOf(true, false)) {
            val r = Rig(); r.scene = r.scene.copy(windows = r.f.windows.map {
                if (it.id == 20 && focusLost) it.copy(focused = false) else if (it.id == 10 && !focusLost) it.copy(layer = 0) else it
            })
            assertNotEquals(ManualDisplayVerdict.SAME, r.review()); assertEquals(1, r.removed)
            assertEquals(ManualNodeMismatch.NOT_CHECKED, r.probe.failureNodes)
        }
    }
    @Test fun firstNodeFailureSurvivesLaterComparisonAndNativeOrCleanupFailures() {
        val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root, r.f.node.copy(enabled = false)))
        r.review()
        r.probe.point = ManualDisplayPoint.NATIVE_AFTER
        r.probe.reject(ManualDisplayDetail.NATIVE_ID_CHANGED)
        r.probe.nodesChanged(r.original.nodes, listOf(r.f.node))
        assertEquals(ManualDisplayDetail.NODES_CHANGED, r.probe.detail)
        assertEquals(ManualDisplayPoint.COMPARISON, r.probe.failurePoint)
        assertEquals(ManualNodeMismatch.ENABLED, r.probe.failureNodes)
    }
    @Test fun terminalDebugRecordContainsOnlyScalarCodesAndThrowingSinkCannotRetainAChangedPopup() {
        val r = Rig(); r.scene = r.scene.copy(nodes = listOf(r.root, r.f.node.copy(enabled = false)))
        val rows = mutableListOf<ManualDisplayReviewCode>()
        assertEquals(ManualDisplayVerdict.CHANGED, reviewManualDisplay({}, r::assess, { r.removed++ }, { verdict ->
            rows += ManualDisplayReviewCode(ManualEventKind.WINDOW_SET, ManualWindowMask.BOUNDS, verdict,
                r.probe.failurePoint, r.probe.detail, r.probe.failureOwned, r.probe.failureListing, r.probe.failureNodes)
            throw CancellationException("SYNTHETIC_LOG")
        }))
        assertEquals(1, r.removed); assertEquals(ManualNodeMismatch.ENABLED, rows.single().nodes)
        assertTrue(ManualDisplayReviewCode::class.java.getDeclaredField("nodes").type.isEnum)
        assertFalse(rows.single().toString().contains("synthetic"))
    }
    @Test fun diagnosticComparisonFailureStaysUnknownAndCannotChangeTheExistingRejection() {
        for (cancel in listOf(true, false)) {
            val r = Rig(); var calls = 0
            val changed = r.f.node.copy(enabled = false)
            val fresh = object : AbstractList<NodeSnapshot>() {
                override val size = 2
                override fun get(index: Int): NodeSnapshot {
                    calls++
                    // Geometry reads two entries, original List.equals reads two entries;
                    // diagnostic-only third traversal must not affect policy on failure.
                    if (calls > 4) { if (cancel) throw CancellationException("SYNTHETIC_DIAG") else error("SYNTHETIC_DIAG") }
                    return if (index == 0) r.root else changed
                }
            }
            r.scene = r.scene.copy(nodes = fresh)
            assertEquals(ManualDisplayVerdict.CHANGED, r.review()); assertEquals(1, r.removed)
            assertEquals(ManualNodeMismatch.UNKNOWN, r.probe.failureNodes)
            assertEquals(ManualDisplayDetail.NODES_CHANGED, r.probe.detail)
        }
    }
    @Test fun disabledDiagnosticAddsNoDtoTraversalToTheOriginalEqualityRejection() {
        val r = Rig(false); var calls = 0
        r.scene = r.scene.copy(nodes = object : AbstractList<NodeSnapshot>() {
            override val size = 2
            override fun get(index: Int): NodeSnapshot {
                calls++; check(calls <= 4) { "NO_EXTRA_READ" }
                return if (index == 0) r.root else r.f.node.copy(enabled = false)
            }
        })
        assertEquals(ManualDisplayVerdict.CHANGED, r.review()); assertEquals(4, calls)
        assertEquals(ManualNodeMismatch.NOT_CHECKED, r.probe.failureNodes)
    }
    @Test fun sharedDiagnosticHasAnExplicitBoundAndReturnsNoInputValues() {
        val node = Rig().f.node
        assertEquals(ManualNodeMismatch.NONE, firstManualNodeMismatch(listOf(node), listOf(node)))
        val tooMany = object : AbstractList<NodeSnapshot>() {
            override val size = 1_201
            override fun get(index: Int): NodeSnapshot = error("NO_TRAVERSAL")
        }
        assertEquals(ManualNodeMismatch.LIMIT_EXCEEDED, firstManualNodeMismatch(tooMany, tooMany))
        assertEquals(ManualNodeMismatch.NODE_COUNT, firstManualNodeMismatch(emptyList(), listOf(node)))
    }
    @Test fun actualRegistrationReviewThenQueuedAddedReproducesChangedAndStickyCleanupFailure() {
        val r = Rig(); val owner = Any()
        val native = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true) }
        val registration = ManualDisplayRegistration().also { it.created(1) }
        val failures = mutableListOf<CleanupFailure>(); var unhealthy = false
        val barrier = OverlayCleanupBarrier(onUncertain = { unhealthy = true }, failureDiagnostic = failures::add)
        r.scene = r.scene.copy(nodes = listOf(r.root, r.f.node.copy(enabled = false)))
        val result = reviewManualDisplay({ r.retired = true }, {
            verifyRegisteredManualDisplay(r.probe, {}, {
                registration.read(1, { readManualNativeWindow(native, owner, 1, null) }, { false }, {
                    val p = ManualOwnedReadProbe()
                    try { readOwnedOverlayIds({ listOf(10, 20) }, { false }, { it }, { _, _ -> true },
                        { true }, { it }, {}, {}, { error("NO_RELEASE_FAILURE") }, null, p) }
                    finally { r.probe.ownedRead = p.code() }
                }, r.probe)
            }) { id -> ManualDisplayContinuity.assess(r.original, r.scene, id.window, r.probe, id.listing) }
        }, {
            r.removed++; native.removalRequested(); barrier.retiring(null, 1, 20, native, owner)
            native.removalReturned(true); barrier.removalReturned(true); native.detached()
        })
        assertEquals(ManualDisplayVerdict.CHANGED, result); assertEquals(ManualNodeMismatch.ENABLED, r.probe.failureNodes)
        assertEquals(ManualOwnedCount.NONE, r.probe.failureOwned.count)
        assertEquals(ManualDisplayListing.NOT_ENUMERATED, r.probe.failureListing)
        val code = inspectNativeCleanupRetirement(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ADDED,
            { error("NO_DISPLAY_CHECK") }, { error("NO_PACKAGE_CHECK") }, { error("NO_NATIVE_CHECK") },
            { error("NO_REMOVAL_ACK_FROM_ADDED") })
        assertEquals(ManualRetirementCheck.EVENT_TYPE, code.check)
        assertNull(barrier.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ADDED, code.check == ManualRetirementCheck.CLEANUP_WAIT))
        assertEquals(listOf(CleanupFailure.RETIRED_ID_EVENT), failures); assertTrue(unhealthy); assertFalse(barrier.ready)
        assertFalse(barrier.nativeProcessed(native, true) { CleanupWindowPresence.ABSENT })
        assertEquals(0, r.f.clicks); assertEquals(ManualNodeMismatch.ENABLED, r.probe.failureNodes)
    }
    @Test fun addedNeverAcknowledgesRemovalEvenWithForgedTransientFlagOrLaterActualAbsence() {
        for (detach in listOf(true, false)) {
            val owner = Any(); val n = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true); it.removalRequested() }
            val failures = mutableListOf<CleanupFailure>(); val b = OverlayCleanupBarrier(failureDiagnostic = failures::add)
            b.retiring(null, 1, 20, n, owner); n.removalReturned(true); b.removalReturned(true)
            if (detach) n.detached()
            assertNull(b.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ADDED, true))
            assertEquals(listOf(CleanupFailure.RETIRED_ID_EVENT), failures); assertNull(b.permit())
            assertFalse(b.nativeProcessed(n, true) { CleanupWindowPresence.ABSENT })
        }
    }
    @Test fun cancellationAndCleanupFailureCannotBecomeAHealthyRetentionOrOverwritePrimaryFailure() {
        val r = Rig(); val primary = CancellationException("SYNTHETIC_PRIMARY")
        assertSame(primary, runCatching { reviewManualDisplay({}, {
            verifyRegisteredManualDisplay(r.probe, { throw primary }, { error("NO_NATIVE_READ") }, { error("NO_SCREEN_READ") })
        }, { r.removed++; error("SYNTHETIC_CLEANUP") }, { error("SYNTHETIC_LOG") }) }.exceptionOrNull())
        assertEquals(1, r.removed); assertEquals(ManualNodeMismatch.NOT_CHECKED, r.probe.failureNodes)
        assertEquals(ManualDisplayDetail.CANCELLED, r.probe.detail)
    }
}
