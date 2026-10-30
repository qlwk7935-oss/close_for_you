package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityNodeInfo as N
import kotlinx.collections.immutable.*
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.integration.ManualDemoBoundsReason.*
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Actual selector/probe/registered read/policy/report. Native action lists are explicit fakes,
 * not derived absence claims made by production from projected NodeSnapshot.actions. */
class ManualDemoP2SafetyTest {
    private fun rig() = ManualDemoBoundsRejectionTest.Rig().apply { expand(reflow = true) }
    private fun moveBody(r: ManualDemoBoundsRejectionTest.Rig, b: ScreenBounds = ScreenBounds(42, 82, 218, 160)) {
        r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/1")) it.copy(bounds = b) else it })
    }
    private fun same() = rig().apply { fresh = fresh.copy(nodes = source.nodes, windows = source.windows) }
    @Test fun selectedSameCannotApproveMissingOriginalOrFreshActionInformation() {
        for (original in listOf(false, true)) {
            val r = same(); r.originalProofEnabled = original; r.freshProofEnabled = !original
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.run())
            assertEquals(POLICY_NATIVE_ACTIONS_UNCONFIRMED, r.row!!.demoBounds)
            assertEquals(1, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun selectedSameCannotApproveUnknownNativeRowsOnEitherSide() {
        for (original in listOf(false, true)) {
            val r = same(); val unknown = mapOf(r.source.nodes[5].path to NativeActionPresence.UNKNOWN)
            if (original) r.originalNativeStates = unknown else r.freshNativeStates = unknown
            assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.run())
            assertEquals(POLICY_NATIVE_ACTIONS_UNCONFIRMED, r.row!!.demoBounds); assertEquals(1, r.removed)
        }
    }
    @Test fun selectedSameStillRejectsNativeActionChangesHiddenByClickDismissProjection() {
        val r = same(); r.freshNativeStates = mapOf(r.source.nodes[5].path to NativeActionPresence.PRESENT)
        assertEquals(ManualDisplayVerdict.CHANGED, r.run())
        assertEquals(POLICY_NATIVE_ACTIONS_CHANGED, r.row!!.demoBounds); assertEquals(1, r.removed)
    }
    @Test fun selectedSameCannotApproveContradictoryNativeAbsenceForTheKnownCloseAction() {
        val r = same(); r.freshNativeStates = mapOf(requireNotNull(r.analysis!!.closeCandidates.single().nodePath) to NativeActionPresence.NONE)
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.run())
        assertEquals(POLICY_NATIVE_ACTIONS_UNCONFIRMED, r.row!!.demoBounds); assertEquals(1, r.removed)
    }
    @Test fun knownStableRootFocusDoesNotNeedAPassiveExceptionWhenThereWasNoGeometryChange() {
        val r = same(); r.originalNativeStates = mapOf("0" to NativeActionPresence.PRESENT); r.freshNativeStates = r.originalNativeStates
        assertEquals(ManualDisplayVerdict.SAME, r.run())
        assertEquals(SELECTED, r.row!!.demoBounds) // Strict equality, not POLICY_RETAIN_*.
        assertEquals(0, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
    }
    @Test fun ordinaryStrictPathWithoutDemoSelectionKeepsItsOriginalNoMetadataContract() {
        val r = same(); r.originalProofEnabled = false; r.freshProofEnabled = false
        assertEquals(ManualDisplayVerdict.SAME, r.run(enabled = false))
        assertEquals(SELECT_DISABLED, r.row!!.demoBounds); assertEquals(0, r.removed)
    }
    @Test fun longClickScrollFocusAndUnknownActionsWithClickableFalseCannotReflow() {
        for (id in listOf(N.ACTION_LONG_CLICK, N.ACTION_SCROLL_FORWARD, N.ACTION_ACCESSIBILITY_FOCUS, Int.MAX_VALUE)) {
            val r = rig(); val path = r.source.nodes[5].path
            assertFalse(r.source.nodes[5].clickable); assertTrue(r.source.nodes[5].actions.isEmpty())
            r.originalNativeStates = mapOf(path to nativeActionPresence(listOf(id).size))
            r.freshNativeStates = r.originalNativeStates; moveBody(r)
            r.rejected(POLICY_INTERACTIVE_BOUNDS)
        }
    }
    @Test fun eitherMissingNativeReadRejectsEvenWhenAllProjectedPassiveActionsAreEmpty() {
        for (original in listOf(false, true)) {
            val r = rig(); r.originalProofEnabled = original; r.freshProofEnabled = !original
            r.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
        }
    }
    @Test fun eitherUnknownNativeRowRejectsInsteadOfTreatingItAsEmpty() {
        for (original in listOf(false, true)) {
            val r = rig(); val unknown = mapOf(r.source.nodes[5].path to NativeActionPresence.UNKNOWN)
            if (original) r.originalNativeStates = unknown else r.freshNativeStates = unknown
            r.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
        }
    }
    @Test fun actionPresenceChangeIsAChangeEvenIfClickAndDismissProjectionDidNotChange() {
        val r = rig(); r.freshNativeStates = mapOf(r.source.nodes[5].path to NativeActionPresence.PRESENT)
        r.rejected(POLICY_NATIVE_ACTIONS_CHANGED)
    }
    @Test fun copiedSnapshotOrForeignFreshListCannotReuseMetadata() {
        for (badOriginal in listOf(false, true)) {
            val r = rig(); val codes = mutableListOf<ManualDemoBoundsReason>()
            val rows = r.source.nodes.map { if (it.actions.isEmpty()) NativeActionPresence.NONE else NativeActionPresence.PRESENT }
            val original = if (badOriginal) NativeNodeActions.forSnapshot(r.source.copy(), rows) else r.originalProof()
            val fresh = if (badOriginal) r.withFreshProof() else r.fresh.copy(
                nativeActions = NativeNodeActions.forNodes(r.fresh.nodes.toMutableList(), rows))
            assertFalse(ManualDemoBoundsPolicy.retains(r.source, fresh, 30, ManualDisplayListing.NOT_ENUMERATED,
                r.analysis!!.closeCandidates, original, codes::add))
            assertEquals(listOf(POLICY_NATIVE_ACTIONS_UNCONFIRMED), codes)
        }
    }
    @Test fun knownSupportedActionCannotContradictAnEmptyNativeCertificate() {
        val r = rig(); val close = requireNotNull(r.analysis!!.closeCandidates.single().nodePath)
        r.originalNativeStates = mapOf(close to NativeActionPresence.NONE)
        r.freshNativeStates = r.originalNativeStates
        r.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
    }
    @Test fun bodyExpansionIntoCloseButtonIsRejectedEvenWhenVisiblePathsStillContainsClose() {
        val r = rig(); moveBody(r, ScreenBounds(40, 250, 220, 320))
        assertTrue(r.analysis!!.closeCandidates.single().nodePath in r.fresh.visiblePaths)
        r.rejected(POLICY_REFLOW_OVERLAP)
    }
    @Test fun bodyExpansionIntoAnotherClickableButtonIsRejected() {
        val r = rig(); moveBody(r, ScreenBounds(40, 180, 220, 245)); r.rejected(POLICY_REFLOW_OVERLAP)
    }
    @Test fun overlapWithNativeActionableButNonClickableOtherNodeIsAlsoRejected() {
        val r = rig(); val path = r.source.nodes[6].path
        r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path == path) it.copy(clickable = false, actions = persistentSetOf()) else it }.toPersistentList())
        r.attach(); r.expand(reflow = true)
        r.originalNativeStates = mapOf(path to NativeActionPresence.PRESENT); r.freshNativeStates = r.originalNativeStates
        moveBody(r, ScreenBounds(40, 180, 220, 245)); r.rejected(POLICY_REFLOW_OVERLAP)
    }
    @Test fun normalAncestorsContainingButtonsAreNotMisclassifiedAsSiblingOcclusion() {
        val r = rig(); moveBody(r)
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
        assertEquals(POLICY_RETAIN_PASSIVE_REFLOW, r.row!!.demoBounds)
        assertEquals(0, r.removed); assertEquals(0, r.f.clicks); assertNull(r.raw)
    }
    @Test fun boundaryContactWithoutPositiveAreaOverlapDoesNotInventOcclusion() {
        val r = rig(); moveBody(r, ScreenBounds(40, 245, 220, 260))
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
    }
    @Test fun preexistingOverlapIsNotProofOfSafeOpaqueRegionForAChangingNode() {
        val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path.endsWith("/1"))
            it.copy(bounds = ScreenBounds(40, 250, 220, 320)) else it }.toPersistentList())
        r.attach(); r.expand(reflow = true); moveBody(r, ScreenBounds(41, 251, 219, 319))
        r.rejected(POLICY_REFLOW_OVERLAP)
    }
    @Test fun actionBearingRootNeverBypassesViaResizeTranslationOrPassiveReflow() {
        for (mode in 0..2) {
            val r = rig(); r.originalNativeStates = mapOf("0" to NativeActionPresence.PRESENT); r.freshNativeStates = r.originalNativeStates
            if (mode == 0) r.expand()
            if (mode == 1) {
                fun shift(b: ScreenBounds) = ScreenBounds(b.left + 2, b.top + 2, b.right + 2, b.bottom + 2)
                r.fresh = r.fresh.copy(nodes = r.source.nodes.map { it.copy(bounds = shift(it.bounds)) },
                    windows = r.source.windows.map { if (it.id == 20) it.copy(bounds = shift(it.bounds)) else it })
            }
            r.rejected(POLICY_ROOT_INTERACTIVE)
        }
    }
    @Test fun projectedRootDismissWithClickableFalseAlsoFailsOldRootOnlyResizePath() {
        val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path == "0")
            it.copy(actions = persistentSetOf(NodeAction.DISMISS)) else it }.toPersistentList())
        r.attach(); assertEquals(1, r.analysis!!.closeCandidates.size); r.expand()
        r.rejected(POLICY_ROOT_INTERACTIVE)
    }
    @Test fun noDemoGeometryPathCanUseMissingOriginalActionProof() {
        val r = rig(); r.expand(); r.originalProofEnabled = false; r.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
        val t = rig(); t.originalProofEnabled = false
        fun shift(b: ScreenBounds) = ScreenBounds(b.left + 2, b.top + 2, b.right + 2, b.bottom + 2)
        t.fresh = t.fresh.copy(nodes = t.source.nodes.map { it.copy(bounds = shift(it.bounds)) },
            windows = t.source.windows.map { if (it.id == 20) it.copy(bounds = shift(it.bounds)) else it })
        t.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
    }
    @Test fun fullGraphChecksApplyBeforeOldRootResizeCanReturnSuccess() {
        val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path.endsWith("/1")) it.copy(path = "0/9/1") else it }.toPersistentList())
        r.attach(); r.expand(); r.rejected(POLICY_REFLOW_PATH)
    }
    @Test fun pairBudgetRejectsRatherThanUsingAnIncompleteOcclusionCheck() {
        val r = rig(); val root = r.source.nodes[0]; val button = r.source.nodes[6]; val body = r.source.nodes[5]
        val close = r.source.nodes.last().copy(path = "0/0")
        val controls = (1..149).map { button.copy(path = "0/$it", viewId = "synthetic-button-$it") }
        val passive = (150..299).map { body.copy(path = "0/$it", viewId = "synthetic-body-$it", bounds = ScreenBounds(40, 40, 220, 70)) }
        r.source = r.source.copy(nodes = (listOf(root, close) + controls + passive).toPersistentList()); r.attach()
        val expanded = ScreenBounds(18, 18, 242, 362)
        r.fresh = r.fresh.copy(nodes = r.source.nodes.map { when {
            it.path == "0" -> it.copy(bounds = expanded)
            it.className == "android.widget.TextView" -> it.copy(bounds = it.bounds.copy(bottom = 71))
            else -> it
        } }, windows = r.source.windows.map { if (it.id == 20) it.copy(bounds = expanded) else it },
            visiblePaths = r.source.nodes.map { it.path }.toSet())
        r.rejected(POLICY_LAYOUT_LIMIT)
    }
}
