package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.accessibility.NativeActionPresence
import kr.closeforyou.accessibility.NativeNodeActions
import kr.closeforyou.accessibility.NativeActionSummary
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Real event selector -> registered display boundary -> strict/demo comparison -> retain/remove.
 * Native, collected data and clock are synthetic; no Samsung/11st identity is fabricated. */
class ManualDemoBoundsPolicyTest {
    private class Rig {
        val f = F9AutoCloserTest.Fixture()
        val root = f.node.copy(path = "0", text = null, viewId = "synthetic-root", className = "android.widget.FrameLayout",
            clickable = false, actions = persistentSetOf(), bounds = f.bounds)
        val body = root.copy(path = "0/1", viewId = "synthetic-body", className = "android.widget.TextView",
            text = "일반 안내", bounds = ScreenBounds(30, 40, 180, 80))
        val original = f.snapshot.copy(nodes = persistentListOf(root, f.node, body))
        var fresh = f.before.copy(nodes = original.nodes)
        var analysis = PopupAnalysis(original.token, closeCandidates = persistentListOf(f.candidate))
        var detection = PopupDetection(original.token, persistentSetOf(PopupSignal.NEW_WINDOW))
        var raw: SnapshotToken? = original.token
        var showing = true; var stops = 0; var reads = 0; var now = 1_000L
        var current = true; var native: ManualDisplayIdentity? = ManualDisplayIdentity(30, ManualDisplayListing.NOT_ENUMERATED)
        var readFailure: (() -> Unit)? = null
        var cleanupFailure = false
        fun resize(b: ScreenBounds = ScreenBounds(18, 18, 202, 202)) {
            fresh = fresh.copy(nodes = listOf(root.copy(bounds = b), f.node, body),
                windows = f.windows.map { if (it.id == 20) it.copy(bounds = b) else it })
        }
        fun translate(dx: Int, dy: Int) {
            fun shift(b: ScreenBounds) = ScreenBounds(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy)
            fresh = fresh.copy(nodes = original.nodes.map { it.copy(bounds = shift(it.bounds)) },
                windows = f.windows.map { if (it.id == 20) it.copy(bounds = shift(it.bounds)) else it })
        }
        fun review(demo: Boolean = true, type: Int = E.TYPE_WINDOWS_CHANGED,
            mask: Int = E.WINDOWS_CHANGE_BOUNDS, eventId: Int = 20): ManualDisplayVerdict {
            val candidates = manualDemoBoundsCandidates(demo, type, mask, eventId, original, analysis, detection)
            val probe = ManualDisplayProbe()
            return reviewManualDisplay(retire = { raw = null }, verify = {
                val started = now
                verifyRegisteredManualDisplay(probe, {
                    manualDisplayCheckpoint({ current }, { now }, started, probe)
                }, { native }) { id ->
                    reads++; readFailure?.invoke()
                    // Explicit synthetic full-native observations, not inference in production.
                    fun states(nodes: List<NodeSnapshot>) = nodes.map { n -> NativeActionSummary.fromIds(n.actions.map { when (it) {
                        NodeAction.CLICK -> android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        NodeAction.DISMISS -> android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS
                    } }) }
                    val scene = fresh.copy(nativeActions = NativeNodeActions.forNodeSummaries(fresh.nodes, states(fresh.nodes)))
                    ManualDisplayContinuity.assess(original, scene, id.window, probe, id.listing, candidates,
                        NativeNodeActions.forSnapshotSummaries(original, states(original.nodes)))
                }
            }, remove = {
                showing = false; stops++
                if (cleanupFailure) throw IllegalStateException("SYNTHETIC_CLEANUP")
            })
        }
        fun retained() {
            assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, review())
            assertTrue(showing); assertEquals(0, stops); assertNull(raw); assertEquals(0, f.clicks)
        }
        fun rejected() {
            assertNotEquals(ManualDisplayVerdict.BOUNDS_RETAINED, review())
            assertFalse(showing); assertEquals(1, stops); assertNull(raw); assertEquals(0, f.clicks)
        }
    }
    @Test fun p01RootAndWindowExpandButStrictPolicyStillRejectsAndDemoRetainsOnlyDisplay() {
        val strict = Rig(); strict.resize(); assertEquals(ManualDisplayVerdict.CHANGED, strict.review(demo = false))
        assertFalse(strict.showing)
        val demo = Rig(); demo.resize(); demo.retained(); assertEquals(1, demo.reads)
        assertEquals(demo.f.token, demo.original.token); assertEquals(1_000L, demo.original.capturedAtUptimeMillis)
    }
    @Test fun wholeBottomSheetTranslationRequiresEveryNodeIncludingButtonToMoveCoherently() {
        for ((x, y) in listOf(0 to 100, 20 to 80, -10 to -10)) {
            val r = Rig(); r.translate(x, y); r.retained()
        }
    }
    @Test fun resizeAndContractionAreAllowedOnlyWithEveryDescendantFixedAndContained() {
        val r = Rig(); r.resize(ScreenBounds(25, 25, 190, 190)); r.retained()
        val clipped = Rig(); clipped.resize(ScreenBounds(40, 40, 190, 190)); clipped.rejected()
    }
    @Test fun windowTranslationWithoutCorrespondingDescendantMovementIsNotAWholePopupMove() {
        val r = Rig(); r.resize(ScreenBounds(25, 25, 205, 205)); r.rejected()
    }
    @Test fun repeatedAnimationEventsNeverRebaseSnapshotRenewTtlOrRestoreEvidenceOrRetry() {
        val r = Rig(); val snapshot = r.original
        repeat(20) { i -> r.translate(0, i + 1); r.now += 10; r.retained() }
        assertEquals(20, r.reads); assertSame(snapshot, r.original)
        assertEquals(1_000L, r.original.capturedAtUptimeMillis); assertEquals(snapshot.token, r.analysis.token)
        assertNull(r.analysis.autoEligibility)
    }
    @Test fun independentCloseButtonMovementDuringResizeOrTranslationIsRejected() {
        for (translate in listOf(false, true)) {
            val r = Rig(); if (translate) r.translate(0, 10) else r.resize()
            r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == r.f.node.path)
                it.copy(bounds = it.bounds.copy(left = it.bounds.left + 1, right = it.bounds.right + 1)) else it })
            r.rejected()
        }
    }
    @Test fun buttonNameDescriptionPathIdClassRoleAndActionsRemainBoundToShownCandidate() {
        val changes: List<(NodeSnapshot) -> NodeSnapshot> = listOf({ it.copy(text = "구매") },
            { it.copy(contentDescription = "다른 동작") }, { it.copy(path = "0/8") },
            { it.copy(viewId = "synthetic-new") }, { it.copy(className = "android.widget.TextView") },
            { it.copy(actions = persistentSetOf()) }, { it.copy(enabled = false) }, { it.copy(clickable = false) })
        for (change in changes) {
            val r = Rig(); r.resize(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == r.f.node.path) change(it) else it })
            r.rejected()
        }
        for (c in listOf(Rig().f.candidate.copy(role = ButtonRole.SKIP), Rig().f.candidate.copy(label = "확인"),
                Rig().f.candidate.copy(evidence = EvidenceLevel.E4_VISUAL_ONLY), Rig().f.candidate.copy(viewId = "other"))) {
            val r = Rig(); r.resize(); r.analysis = r.analysis.copy(closeCandidates = persistentListOf(c)); r.rejected()
        }
    }
    @Test fun sameWindowPopupReplacementContentNodeAdditionDeletionAndOrderChangesReject() {
        val changes: List<(List<NodeSnapshot>) -> List<NodeSnapshot>> = listOf(
            { it + it.last().copy(path = "0/9") }, { it.dropLast(1) }, { it.reversed() },
            { it.map { n -> if (n.path == "0/1") n.copy(text = "새 팝업") else n } })
        for (change in changes) { val r = Rig(); r.resize(); r.fresh = r.fresh.copy(nodes = change(r.fresh.nodes)); r.rejected() }
    }
    @Test fun matchingRootAndWindowAloneCannotApproveAnotherOrAmbiguousTarget() {
        val changes: List<(F9Scene) -> F9Scene> = listOf({ it.copy(packageName = "synthetic.other") },
            { it.copy(windowId = 21) }, { it.copy(complete = false) },
            { it.copy(windows = it.windows + it.windows.last()) },
            { it.copy(windows = it.windows.map { w -> if (w.id == 20) w.copy(type = WindowType.SYSTEM) else w }) })
        for (change in changes) { val r = Rig(); r.resize(); r.fresh = change(r.fresh); r.rejected() }
        val unknown = Rig(); unknown.resize(); unknown.native = null; unknown.rejected(); assertEquals(0, unknown.reads)
    }
    @Test fun otherWindowsLayersFocusFrameAndOcclusionMetadataCannotBeIgnored() {
        val changes: List<(F9Scene) -> F9Scene> = listOf(
            { it.copy(screen = ScreenBounds(0, 0, 301, 500)) },
            { it.copy(windows = it.windows.map { w -> if (w.id == 20) w.copy(layer = 3) else w }) },
            { it.copy(windows = it.windows.map { w -> if (w.id == 20) w.copy(active = false) else w }) },
            { it.copy(windows = it.windows.map { w -> if (w.id == 10) w.copy(bounds = ScreenBounds(0, 0, 299, 500)) else w }) },
            { it.copy(windows = it.windows + WindowSnapshot(40, WindowType.INPUT_METHOD, ScreenBounds(0, 300, 300, 500), true, true, 5)) })
        for (change in changes) { val r = Rig(); r.resize(); r.fresh = change(r.fresh); r.rejected() }
    }
    @Test fun rootOnlyOrWindowOnlyChangesMissingRootAndFullscreenRemainRejected() {
        for (change in 0..3) {
            val r = Rig(); r.resize()
            r.fresh = when (change) {
                0 -> r.fresh.copy(windows = r.f.windows)
                1 -> r.fresh.copy(nodes = r.original.nodes)
                2 -> r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == "0") it.copy(path = "synthetic-missing-root") else it })
                else -> r.fresh.copy(windows = r.f.windows.map { if (it.id == 20) it.copy(bounds = r.fresh.screen) else it })
            }
            r.rejected()
        }
    }
    @Test fun invisibleMissingMultipleOrUnshownCloseCandidatesCannotRetainPrompt() {
        for (kind in 0..3) {
            val r = Rig(); r.resize()
            when (kind) {
                0 -> r.fresh = r.fresh.copy(visiblePaths = emptySet())
                1 -> r.analysis = r.analysis.copy(closeCandidates = persistentListOf())
                2 -> r.analysis = r.analysis.copy(closeCandidates = persistentListOf(r.f.candidate, r.f.candidate))
                else -> r.analysis = r.analysis.copy(token = r.analysis.token.copy(screenVersion = 2))
            }
            r.rejected()
        }
    }
    @Test fun newDangerousOrAmbiguousContentStillCannotPassEvenInDemo() {
        val r = Rig(); r.resize()
        val unsafe = r.body.copy(text = "비밀번호 입력")
        val source = r.original.copy(nodes = persistentListOf(r.root, r.f.node, unsafe))
        val fresh = r.fresh.copy(nodes = listOf(r.fresh.nodes[0], r.f.node, unsafe))
        assertFalse(ManualDemoBoundsPolicy.retains(source, fresh, 30, ManualDisplayListing.NOT_ENUMERATED, listOf(r.f.candidate)))
    }
    @Test fun eventMetadataOnlySelectsReadPolicyAndNeverGrantsContinuity() {
        val r = Rig(); r.resize()
        assertNotNull(manualDemoBoundsCandidates(true, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 20, r.original, r.analysis, r.detection))
        for (mask in listOf(E.WINDOWS_CHANGE_ADDED, E.WINDOWS_CHANGE_REMOVED, E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_ADDED, 0)) {
            assertNull(manualDemoBoundsCandidates(true, E.TYPE_WINDOWS_CHANGED, mask, 20, r.original, r.analysis, r.detection))
        }
        for (type in listOf(E.TYPE_WINDOW_CONTENT_CHANGED, E.TYPE_WINDOW_STATE_CHANGED, E.TYPE_VIEW_CLICKED, E.TYPE_VIEW_SCROLLED, E.TYPE_TOUCH_INTERACTION_START)) {
            assertNull(manualDemoBoundsCandidates(true, type, E.WINDOWS_CHANGE_BOUNDS, 20, r.original, r.analysis, r.detection))
        }
        assertNull(manualDemoBoundsCandidates(true, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 21, r.original, r.analysis, r.detection))
    }
    @Test fun absentManualPresentationNeverInterceptsP23StyleBoundsRequestsOrUsesWaitingEvidence() {
        val r = Rig()
        // The Android driver also requires an existing C/native lease before this selector.
        assertNull(manualDemoBoundsCandidates(true, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 20, r.original, null, r.detection))
        assertNull(manualDemoBoundsCandidates(true, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 20, r.original, r.analysis,
            r.detection.copy(signals = persistentSetOf(PopupSignal.SAME_WINDOW_OVERLAY))))
        assertNull(manualDemoBoundsCandidates(false, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 20, r.original, r.analysis, r.detection))
        assertEquals(0, r.reads); assertEquals(0, r.f.clicks)
    }
    @Test fun timeoutCancellationAndUnavailableReadNeverKeepAFormerlySuccessfulVerdict() {
        val timeout = Rig(); timeout.resize(); timeout.readFailure = { timeout.now += 100 }
        timeout.rejected(); assertEquals(1, timeout.reads)
        val cancelled = Rig(); cancelled.resize(); val primary = CancellationException("SYNTHETIC_READ")
        cancelled.readFailure = { throw primary }; cancelled.cleanupFailure = true
        try { cancelled.review(); fail("CANCELLATION_REQUIRED") } catch (e: CancellationException) { assertSame(primary, e) }
        assertFalse(cancelled.showing); assertEquals(1, cancelled.stops)
        val stale = Rig(); stale.resize(); stale.current = false
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, stale.review())
        assertFalse(stale.showing); assertEquals(0, stale.reads)
    }
    @Test fun featureIsExplicitDebugOnlyAndNotAnExecutionFeature() {
        assertEquals(ManualPromptLog.create() != null, ManualDemoBoundsFeature.enabled)
        assertFalse(F9ServiceExecutionFeature.enabled)
    }
}
