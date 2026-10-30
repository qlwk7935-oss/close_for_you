package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityNodeInfo as N
import android.view.accessibility.AccessibilityEvent as E
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kr.closeforyou.accessibility.CollectionCleanupFailure
import kr.closeforyou.accessibility.NativeActionPresence
import kr.closeforyou.accessibility.action.F9Block
import kotlinx.collections.immutable.*
import kr.closeforyou.accessibility.NativeActionSummary
import kr.closeforyou.accessibility.integration.ManualDemoBoundsReason.*
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Real selector -> retire -> registered read -> whole-popup comparison -> retain/remove.
 * Native responses/geometry are synthetic, not evidence of Samsung's actual ROOT capabilities. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualStructuralRootDisplayTest {
    private val capabilities = listOf(N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION, N.ACTION_FOCUS,
        N.ACTION_ACCESSIBILITY_FOCUS, android.R.id.accessibilityActionShowOnScreen)
    private fun rig(ids: List<Int> = capabilities) = ManualDemoBoundsRejectionTest.Rig().apply {
        expand(reflow = true)
        originalSummaries = mapOf("0" to NativeActionSummary.fromIds(ids)); freshSummaries = originalSummaries
    }
    private fun mode(r: ManualDemoBoundsRejectionTest.Rig, mode: Int) {
        if (mode == 0) r.expand()
        if (mode == 1) {
            fun shift(b: ScreenBounds) = ScreenBounds(b.left + 2, b.top + 2, b.right + 2, b.bottom + 2)
            r.fresh = r.fresh.copy(nodes = r.source.nodes.map { it.copy(bounds = shift(it.bounds)) },
                windows = r.source.windows.map { if (it.id == 20) it.copy(bounds = shift(it.bounds)) else it })
        }
    }
    private fun retained(r: ManualDemoBoundsRejectionTest.Rig) {
        val source = r.source; val analysis = r.analysis
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
        assertSame(source, r.source); assertSame(analysis, r.analysis)
        assertNull(r.raw); assertNull(r.analysis!!.autoEligibility)
        assertEquals(0, r.removed); assertEquals(0, r.f.clicks)
    }
    @Test fun structuralRootSelectionCapabilitiesRetainOnlyAfterEveryGeometryPatternIsVerified() {
        for (kind in listOf("android.widget.FrameLayout", "android.widget.LinearLayout")) for (m in 0..2) {
            val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map {
                if (it.path == "0") it.copy(className = kind) else it }.toPersistentList())
            r.attach(); r.expand(reflow = true); mode(r, m); retained(r)
            assertEquals(listOf(POLICY_RETAIN_ROOT_RESIZE, POLICY_RETAIN_TRANSLATION, POLICY_RETAIN_PASSIVE_REFLOW)[m], r.row!!.demoBounds)
        }
    }
    @Test fun capabilityFacetDoesNotReclassifySelectionAsNavigationOrAbsence() {
        val selected = NativeActionSummary.fromIds(listOf(N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION))
        assertEquals(NativeActionPresence.PRESENT, selected.presence)
        assertFalse(selected.displayRootSafe)
        assertTrue(selected.hasOnlyStructuralRootCapabilities)
        assertFalse(NativeActionSummary.UNKNOWN.hasOnlyStructuralRootCapabilities)
        assertFalse(NativeActionSummary.presenceOnly(NativeActionPresence.PRESENT).hasOnlyStructuralRootCapabilities)
    }
    @Test fun stableCapabilitiesAloneCannotProveTheRootDescriptorRole() {
        val changes: List<(NodeSnapshot) -> NodeSnapshot> = listOf(
            { it.copy(className = "custom.Root") }, { it.copy(className = "android.widget.TextView") },
            { it.copy(className = "android.widget.Button") }, { it.copy(text = "제목") },
            { it.copy(contentDescription = "설명") })
        for (change in changes) {
            val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path == "0") change(it) else it }.toPersistentList())
            r.attach(); r.expand(reflow = true); r.rejected(POLICY_DESCENDANT_BOUNDS)
        }
    }
    @Test fun clickableRootStillRejectsBeforeStructuralCapabilityPermission() {
        val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map {
            if (it.path == "0") it.copy(clickable = true) else it }.toPersistentList())
        r.attach(); r.expand(reflow = true); r.rejected(POLICY_ROOT_CLICKABLE)
    }
    @Test fun clickLongClickDismissScrollAndOtherOperativeRootActionsAreNeverAutoAllowed() {
        for (id in listOf(N.ACTION_CLICK, N.ACTION_LONG_CLICK, N.ACTION_DISMISS, N.ACTION_SCROLL_FORWARD,
                N.ACTION_SCROLL_BACKWARD, N.ACTION_COPY, N.ACTION_PASTE, N.ACTION_SET_TEXT, N.ACTION_EXPAND)) {
            val r = rig(capabilities + id)
            val projected = when (id) { N.ACTION_CLICK -> persistentSetOf(NodeAction.CLICK)
                N.ACTION_DISMISS -> persistentSetOf(NodeAction.DISMISS); else -> persistentSetOf() }
            r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path == "0") it.copy(actions = projected) else it }.toPersistentList())
            r.attach(); r.expand(reflow = true); r.rejected(POLICY_ROOT_INTERACTIVE)
        }
    }
    @Test fun rootActionAdditionRemovalOrReplacementRejectsGeometryAndSameFallback() {
        for ((before, after) in listOf(listOf(N.ACTION_SELECT) to listOf(N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION),
            capabilities to capabilities.drop(1), listOf(N.ACTION_SELECT) to listOf(N.ACTION_CLEAR_SELECTION))) for (m in 0..3) {
            val r = rig(before); mode(r, m)
            if (m == 3) r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
            r.freshSummaries = mapOf("0" to NativeActionSummary.fromIds(after))
            assertEquals(ManualDisplayVerdict.CHANGED, r.run()); assertEquals(POLICY_NATIVE_ACTIONS_CHANGED, r.row!!.demoBounds)
            assertEquals(1, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun customUnknownLabelledIncompleteAndUnboundMetadataStillRejects() {
        for (summary in listOf(NativeActionSummary.UNKNOWN,
            NativeActionSummary.fromIds(capabilities + Int.MAX_VALUE),
            NativeActionSummary.fromIds(capabilities, hasUnverifiedLabel = true),
            NativeActionSummary.fromIds(capabilities + N.ACTION_SELECT),
            NativeActionSummary.presenceOnly(NativeActionPresence.PRESENT))) {
            val r = rig(); r.originalSummaries = mapOf("0" to summary); r.freshSummaries = r.originalSummaries
            r.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
        }
        val original = rig(); original.originalProofEnabled = false; original.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
        val fresh = rig(); fresh.freshProofEnabled = false; fresh.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
    }
    @Test fun closeCandidateNamesPathsRolesActionsAndVisibilityRemainStrict() {
        val changes: List<(NodeSnapshot) -> NodeSnapshot> = listOf(
            { it.copy(text = "구매") }, { it.copy(contentDescription = "동의") },
            { it.copy(path = "0/0/0/0/9") }, { it.copy(viewId = "synthetic-other") },
            { it.copy(className = "android.widget.ImageButton") }, { it.copy(enabled = false) },
            { it.copy(clickable = false) }, { it.copy(actions = persistentSetOf()) })
        for (change in changes) {
            val r = rig(); val path = r.analysis!!.closeCandidates.single().nodePath
            r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == path) change(it) else it })
            r.rejected(POLICY_NODE_FIELDS)
        }
        val hidden = rig(); hidden.fresh = hidden.fresh.copy(visiblePaths = emptySet()); hidden.rejected(POLICY_CANDIDATE_NOT_VISIBLE)
        val wrongRole = rig(); wrongRole.analysis = wrongRole.analysis!!.copy(closeCandidates = persistentListOf(wrongRole.analysis!!.closeCandidates.single().copy(role = ButtonRole.SKIP)))
        wrongRole.rejected(POLICY_CANDIDATE_ROLE_EVIDENCE)
    }
    @Test fun closeAndOtherInteractiveNodeCannotMoveIndependentlyInAnyGeometryPattern() {
        for (index in listOf(6, 7)) for (m in 0..2) {
            val r = rig(); mode(r, m); val path = r.source.nodes[index].path
            r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == path) it.copy(bounds = it.bounds.copy(left = it.bounds.left + 1)) else it })
            assertNotEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run()); assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun passiveBodyReflowCanRetainButChildrenNeverInheritRootSelectionPermission() {
        val safe = rig(); safe.fresh = safe.fresh.copy(nodes = safe.fresh.nodes.map { if (it.path.endsWith("/1"))
            it.copy(bounds = ScreenBounds(42, 82, 218, 150)) else it }); retained(safe)
        for (index in listOf(1, 5)) {
            val r = rig(); val path = r.source.nodes[index].path
            r.originalSummaries = r.originalSummaries + (path to NativeActionSummary.fromIds(listOf(N.ACTION_SELECT)))
            r.freshSummaries = r.originalSummaries
            if (index == 5) r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == path) it.copy(bounds = it.bounds.copy(bottom = 150)) else it })
            r.rejected(if (index == 1) POLICY_REFLOW_PARENT else POLICY_INTERACTIVE_BOUNDS)
        }
    }
    @Test fun siblingButtonOcclusionCannotBeExcusedByStructuralRootAncestry() {
        for (b in listOf(ScreenBounds(40, 250, 220, 320), ScreenBounds(40, 180, 220, 245))) {
            val r = rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/1")) it.copy(bounds = b) else it })
            r.rejected(POLICY_REFLOW_OVERLAP)
        }
    }
    @Test fun invalidGraphWindowRootRelationOrGeometryCannotBecomeStructuralProof() {
        val graph = rig(); graph.source = graph.source.copy(nodes = graph.source.nodes.map {
            if (it.path.endsWith("/1")) it.copy(path = "0/9/1") else it }.toPersistentList())
        graph.attach(); graph.expand(reflow = true); graph.rejected(POLICY_REFLOW_PATH)
        val root = rig(); root.fresh = root.fresh.copy(nodes = root.fresh.nodes.map { if (it.path == "0")
            it.copy(bounds = it.bounds.copy(right = it.bounds.right - 1)) else it }); root.rejected(POLICY_ROOT_WINDOW)
        val parent = rig(); parent.fresh = parent.fresh.copy(nodes = parent.fresh.nodes.map {
            if (it.path.endsWith("/1")) it.copy(bounds = ScreenBounds(20, 80, 225, 150)) else it }); parent.rejected(POLICY_REFLOW_PARENT)
    }
    @Test fun contentStructurePopupReplacementAndProtectedDecisionsStillReject() {
        val changes: List<(List<NodeSnapshot>) -> List<NodeSnapshot>> = listOf(
            { it.dropLast(1) }, { it + it.last().copy(path = "0/0/0/0/4") },
            { it.reversed() }, { ns -> ns.map { if (it.path.endsWith("/1")) it.copy(text = "다른 팝업") else it } })
        for (change in changes) {
            val r = rig(); r.fresh = r.fresh.copy(nodes = change(r.fresh.nodes))
            assertEquals(ManualDisplayVerdict.CHANGED, r.run()); assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
        }
        val protected = rig(); protected.source = protected.source.copy(nodes = protected.source.nodes.map {
            if (it.path.endsWith("/1")) it.copy(text = "결제 비밀번호") else it }.toPersistentList())
        protected.attach(); protected.expand(reflow = true); protected.rejected(POLICY_PROTECTED_CONTENT)
    }
    @Test fun competingWindowsWrongFrameAndUncertainNativeOwnershipStillRemove() {
        val changes: List<(ManualDemoBoundsRejectionTest.Rig) -> Unit> = listOf(
            { r -> r.native = null }, { r -> r.fresh = r.fresh.copy(packageName = "synthetic-other") },
            { r -> r.fresh = r.fresh.copy(windowId = 99) }, { r -> r.fresh = r.fresh.copy(screen = ScreenBounds(0, 0, 301, 500)) },
            { r -> r.fresh = r.fresh.copy(windows = r.fresh.windows + WindowSnapshot(50, WindowType.INPUT_METHOD, ScreenBounds(0, 300, 300, 500), true, true, 5)) },
            { r -> r.fresh = r.fresh.copy(windows = r.fresh.windows + WindowSnapshot(50, WindowType.SYSTEM, ScreenBounds(0, 0, 300, 40), false, false, 5)) })
        for (change in changes) {
            val r = rig(); change(r); assertNotEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
            assertEquals(1, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun repeatedBoundsNeverRebaseOriginalTokenTimeOrGrantEligibility() {
        val r = rig(); val original = r.source; val token = original.token; val captured = original.capturedAtUptimeMillis
        repeat(20) { i -> r.expand(reflow = true); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map {
            if (it.path.endsWith("/1")) it.copy(bounds = it.bounds.copy(bottom = 130 + i)) else it })
            retained(r); r.now += 300 }
        assertSame(original, r.source); assertEquals(token, r.source.token); assertEquals(captured, r.source.capturedAtUptimeMillis)
        assertEquals(20, r.reads); assertNull(r.raw); assertNull(r.analysis!!.autoEligibility)
    }
    @Test fun retainedDisplayStillFailsFreshManualSnapshotOrF9PreClickAndNeverPresses() = runTest {
        val r = rig(); retained(r)
        val fresh = r.source.copy(token = r.source.token.copy(screenVersion = 2, captureEpoch = 2),
            nodes = r.fresh.nodes.toPersistentList(), windows = r.fresh.windows.toPersistentList())
        val choice = UserChoice(r.source.token, ChoiceKind.CLOSE_ONCE, r.analysis!!.closeCandidates.single().id, true)
        assertNull(ManualChoiceRefresh.collect(r.source, r.analysis!!, choice, r.detection!!, { r.now }, { true }) { check -> check(); fresh })
        r.f.scene = r.fresh
        val result = r.f.closer(StandardTestDispatcher(testScheduler)).execute(r.f.reference.copy(
            snapshot = r.source, candidate = r.analysis!!.closeCandidates.single()))
        assertEquals(F9Block.SCREEN_UNVERIFIED, result.block); assertFalse(result.attempted); assertEquals(0, r.f.clicks)
    }
    @Test fun deadlineCancellationNativeAfterAndCleanupFailureCannotProduceSuccess() {
        val timeout = rig(); timeout.read = { timeout.now += 100 }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, timeout.run()); assertEquals(1, timeout.removed)
        val native = rig(); native.read = { native.native = null }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, native.run()); assertEquals(1, native.removed)
        val cancel = rig(); val primary = CancellationException("SYNTHETIC_CANCEL")
        cancel.read = { throw primary }; cancel.failCleanup = true
        try { cancel.run(); fail("CANCEL_REQUIRED") } catch (e: CancellationException) { assertSame(primary, e) }
        assertEquals(1, cancel.removed); assertEquals(0, cancel.f.clicks)
        val cleanup = rig(); cleanup.failCleanup = true; cleanup.fresh = cleanup.fresh.copy(nodes = cleanup.fresh.nodes.dropLast(1))
        try { cleanup.run(); fail("CLEANUP_REQUIRED") } catch (_: CollectionCleanupFailure) { }
        assertEquals(1, cleanup.removed); assertEquals(0, cleanup.f.clicks)
    }
    @Test fun onlySelectedDebugBoundsMayUseTheStructuralRootRole() {
        val feature = rig(); val result = feature.run(enabled = ManualDemoBoundsFeature.enabled)
        assertEquals(if (ManualDemoBoundsFeature.enabled) ManualDisplayVerdict.BOUNDS_RETAINED else ManualDisplayVerdict.CHANGED, result)
        val cases = listOf(E.TYPE_WINDOW_CONTENT_CHANGED to E.WINDOWS_CHANGE_BOUNDS,
            E.TYPE_WINDOW_STATE_CHANGED to E.WINDOWS_CHANGE_BOUNDS, E.TYPE_WINDOWS_CHANGED to E.WINDOWS_CHANGE_ADDED,
            E.TYPE_WINDOWS_CHANGED to E.WINDOWS_CHANGE_REMOVED, E.TYPE_WINDOWS_CHANGED to (E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_ADDED))
        for ((type, mask) in cases) {
            val r = rig(); assertEquals(ManualDisplayVerdict.CHANGED, r.run(type = type, changes = mask)); assertEquals(SELECT_EVENT, r.row!!.demoBounds)
        }
        val same = rig(); same.fresh = same.fresh.copy(nodes = same.source.nodes, windows = same.source.windows)
        assertEquals(ManualDisplayVerdict.SAME, same.run()) // Existing exact SAME, no geometry exemption.
        assertEquals(SELECTED, same.row!!.demoBounds)
    }

}
