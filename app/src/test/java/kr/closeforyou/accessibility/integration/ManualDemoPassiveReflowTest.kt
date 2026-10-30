package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.action.F9Block
import kr.closeforyou.accessibility.integration.ManualDemoBoundsReason.*
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Same nested, actual B-attached fixture and Android-used registered display boundary.
 * Synthetic bounds are not observations of the Samsung node that changed. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualDemoPassiveReflowTest {
    private fun rig() = ManualDemoBoundsRejectionTest.Rig().apply { expand(reflow = true) }
    private fun retained(r: ManualDemoBoundsRejectionTest.Rig) {
        val original = r.source; val analysis = r.analysis
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
        assertEquals(POLICY_RETAIN_PASSIVE_REFLOW, r.row!!.demoBounds)
        assertSame(original, r.source); assertSame(analysis, r.analysis)
        assertNull(r.raw); assertEquals(0, r.removed); assertEquals(0, r.f.clicks)
    }
    @Test fun nestedFrameAndLinearContainersReflowWithRootWindowWhileBothButtonsStayFixed() {
        val r = rig(); retained(r)
        assertEquals(r.source.nodes.filter { it.clickable }, r.fresh.nodes.filter { it.clickable })
        assertEquals(r.source.token, r.analysis!!.token)
        assertEquals(1_000L, r.source.capturedAtUptimeMillis)
    }
    @Test fun passiveBodyCanMoveAndResizeInsideItsUnchangedParentWithBothButtonsFixed() {
        val r = rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map {
            if (it.path.endsWith("/1")) it.copy(bounds = ScreenBounds(42, 82, 218, 160)) else it })
        retained(r)
    }
    @Test fun closeButtonIndependentMovementOrResizeStillRejects() {
        for (b in listOf(ScreenBounds(42, 260, 222, 310), ScreenBounds(40, 260, 220, 320))) {
            val r = rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/3")) it.copy(bounds = b) else it })
            r.rejected(POLICY_INTERACTIVE_BOUNDS)
        }
    }
    @Test fun anotherClickableButtonCannotMoveEvenIfTheCloseButtonDoesNot() {
        val r = rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map {
            if (it.path.endsWith("/2")) it.copy(bounds = it.bounds.copy(top = 202)) else it })
        r.rejected(POLICY_INTERACTIVE_BOUNDS)
    }
    @Test fun projectedDismissOrClickActionWithoutClickableFlagStillCannotReflow() {
        for (action in NodeAction.entries) {
            val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map {
                if (it.path.endsWith("/1")) it.copy(actions = persistentSetOf(action)) else it }.toPersistentList())
            r.attach(); r.expand(reflow = true)
            r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/1")) it.copy(bounds = it.bounds.copy(bottom = 150)) else it })
            r.rejected(POLICY_INTERACTIVE_BOUNDS)
        }
    }
    @Test fun buttonNameRoleDescriptionIdentityAndActionChangesRejectBeforeGeometryPermission() {
        val mutations: List<(NodeSnapshot) -> NodeSnapshot> = listOf(
            { it.copy(text = "구매") }, { it.copy(contentDescription = "동의") },
            { it.copy(className = "android.widget.ImageButton") }, { it.copy(viewId = "synthetic-replaced") },
            { it.copy(enabled = false) }, { it.copy(clickable = false) }, { it.copy(actions = persistentSetOf()) })
        for (mutate in mutations) {
            val r = rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/3")) mutate(it) else it })
            r.rejected(POLICY_NODE_FIELDS)
        }
        val role = rig(); role.analysis = role.analysis!!.copy(closeCandidates = persistentListOf(role.analysis!!.closeCandidates.single().copy(role = ButtonRole.SKIP)))
        role.rejected(POLICY_CANDIDATE_ROLE_EVIDENCE)
    }
    @Test fun sameWindowReplacementNodeAdditionDeletionReorderAndChangedBodyCannotRetain() {
        val changes: List<(List<NodeSnapshot>) -> List<NodeSnapshot>> = listOf(
            { it + it.last().copy(path = "0/0/0/0/4") }, { it.dropLast(1) },
            { it.toMutableList().apply { val a = this[4]; this[4] = this[5]; this[5] = a } },
            { it.map { n -> if (n.path.endsWith("/1")) n.copy(text = "다른 팝업") else n } })
        for (change in changes) {
            val r = rig(); r.fresh = r.fresh.copy(nodes = change(r.fresh.nodes))
            assertEquals(ManualDisplayVerdict.CHANGED, r.run()); assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun unknownCustomScrollImageAndUnlabelledOrActionNamedTextCannotIndependentlyReflow() {
        for ((kind, text) in listOf("custom.PassiveLookingView" to "안내", "android.widget.ScrollView" to null,
                "android.widget.ImageView" to null, "android.widget.TextView" to null, "android.widget.TextView" to "닫기")) {
            val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map {
                if (it.path.endsWith("/1")) it.copy(className = kind, text = text) else it }.toPersistentList())
            r.attach(); r.expand(reflow = true)
            r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/1")) it.copy(bounds = it.bounds.copy(bottom = 150)) else it })
            r.rejected(POLICY_DESCENDANT_BOUNDS)
        }
    }
    @Test fun missingMalformedNoncanonicalParentPathsCannotBeInvented() {
        for (path in listOf("0/9/1", "0/00/0", "0/0/x", "0/0/1200")) {
            val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map {
                if (it.path.endsWith("/1")) it.copy(path = path) else it }.toPersistentList())
            r.attach(); r.expand(reflow = true); r.rejected(POLICY_REFLOW_PATH)
        }
    }
    @Test fun everyOldAndNewParentMustContainEvenUnchangedChildrenNotJustTheWindow() {
        for (changeOriginal in listOf(false, true)) {
            val r = rig()
            if (changeOriginal) {
                r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path == "0/0/0/0")
                    it.copy(bounds = ScreenBounds(30, 30, 230, 300)) else it }.toPersistentList())
                r.attach(); r.expand(reflow = true)
            } else r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map {
                if (it.path == "0/0/0/0") it.copy(bounds = ScreenBounds(30, 30, 230, 300)) else it })
            r.rejected(POLICY_REFLOW_PARENT)
        }
    }
    @Test fun leafBodyCannotMasqueradeAsAContainerAndActionableParentsAreNotPassive() {
        for (mode in 0..2) {
            val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map {
                if (it.path == "0/0/0/0") when (mode) {
                    0 -> it.copy(className = "android.widget.TextView", text = "본문")
                    1 -> it.copy(clickable = true)
                    else -> it.copy(actions = persistentSetOf(NodeAction.DISMISS))
                } else it }.toPersistentList())
            r.attach(); r.expand(reflow = true); r.rejected(POLICY_REFLOW_PARENT)
        }
    }
    @Test fun emptyOutsideWindowAndUnchangedWindowReflowRemainRejected() {
        for (b in listOf(ScreenBounds(40, 80, 40, 150), ScreenBounds(0, 0, 10, 10))) {
            val r = rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/1")) it.copy(bounds = b) else it })
            assertNotEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run()); assertEquals(1, r.removed)
        }
        val r = rig(); r.fresh = r.fresh.copy(windows = r.source.windows)
        r.rejected(POLICY_WINDOW_UNCHANGED)
    }
    @Test fun anotherWindowFrameTargetOrNativeBindingChangeStillRejects() {
        val changes: List<(ManualDemoBoundsRejectionTest.Rig) -> Unit> = listOf(
            { it.fresh = it.fresh.copy(windows = it.fresh.windows + WindowSnapshot(50, WindowType.SYSTEM, ScreenBounds(0, 0, 300, 40), false, false, 5)) },
            { it.fresh = it.fresh.copy(screen = ScreenBounds(0, 0, 301, 500)) },
            { it.fresh = it.fresh.copy(packageName = "synthetic.other") },
            { it.fresh = it.fresh.copy(windowId = 21) }, { it.native = null })
        for (change in changes) {
            val r = rig(); change(r); assertNotEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
            assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun reflowIsNeverSelectedForContentStateAddedRemovedMixedOrReleaseStrictPolicy() {
        val cases = listOf(E.TYPE_WINDOW_CONTENT_CHANGED to E.WINDOWS_CHANGE_BOUNDS,
            E.TYPE_WINDOW_STATE_CHANGED to E.WINDOWS_CHANGE_BOUNDS,
            E.TYPE_WINDOWS_CHANGED to E.WINDOWS_CHANGE_ADDED, E.TYPE_WINDOWS_CHANGED to E.WINDOWS_CHANGE_REMOVED,
            E.TYPE_WINDOWS_CHANGED to (E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_ADDED))
        for ((type, changes) in cases) {
            val r = rig(); assertEquals(ManualDisplayVerdict.CHANGED, r.run(type = type, changes = changes))
            assertEquals(SELECT_EVENT, r.row!!.demoBounds); assertEquals(1, r.removed)
        }
        val strict = rig(); assertEquals(ManualDisplayVerdict.CHANGED, strict.run(enabled = false))
        assertEquals(SELECT_DISABLED, strict.row!!.demoBounds)
    }
    @Test fun repeatedReflowReadsNeverRebaseOriginalTokenTimeOrGrantExecutionEvidence() {
        val r = rig(); val source = r.source
        repeat(20) { i ->
            r.expand(reflow = true); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map {
                if (it.path.endsWith("/1")) it.copy(bounds = it.bounds.copy(bottom = 140 + i)) else it })
            retained(r); r.now += 10
        }
        assertEquals(20, r.reads); assertSame(source, r.source)
        assertEquals(1_000L, r.source.capturedAtUptimeMillis); assertNull(r.analysis!!.autoEligibility)
    }
    @Test fun reflowPermissionCannotOverrideDeadlineNativeFailureCancellationOrCleanupFailure() {
        val timeout = rig(); timeout.read = { timeout.now += 100 }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, timeout.run())
        assertEquals(POLICY_RETAIN_PASSIVE_REFLOW, timeout.row!!.demoBounds); assertEquals(1, timeout.removed)
        val native = rig(); native.read = { native.native = null }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, native.run()); assertEquals(1, native.removed)
        val cancelled = rig(); val primary = CancellationException("SYNTHETIC_CANCEL")
        cancelled.read = { throw primary }; cancelled.failCleanup = true
        try { cancelled.run(); fail("CANCEL_REQUIRED") } catch (e: CancellationException) { assertSame(primary, e) }
        assertEquals(1, cancelled.removed); assertEquals(0, cancelled.f.clicks)
    }
    @Test fun retainedPromptDoesNotLetManualRefreshAcceptTheChangedExecutionSnapshot() = runTest {
        val r = rig(); retained(r)
        val candidate = r.analysis!!.closeCandidates.single()
        val changed = r.source.copy(token = r.source.token.copy(screenVersion = 2, captureEpoch = 2),
            nodes = r.fresh.nodes.toPersistentList(), windows = r.fresh.windows.toPersistentList())
        val evidence = ManualChoiceRefresh.collect(r.source, r.analysis!!,
            UserChoice(r.source.token, ChoiceKind.CLOSE_ONCE, candidate.id, true), r.detection!!, { r.now }, { true }) { check -> check(); changed }
        assertNull(evidence); assertEquals(0, r.f.clicks)
    }
    @Test fun retainedPromptStillFailsRealF9PreClickMatchAndNeverPresses() = runTest {
        val r = rig(); retained(r)
        r.f.scene = r.fresh
        val result = r.f.closer(StandardTestDispatcher(testScheduler)).execute(r.f.reference.copy(
            snapshot = r.source, candidate = r.analysis!!.closeCandidates.single()))
        assertEquals(F9Block.SCREEN_UNVERIFIED, result.block); assertFalse(result.attempted)
        assertEquals(0, r.f.clicks)
    }
}
