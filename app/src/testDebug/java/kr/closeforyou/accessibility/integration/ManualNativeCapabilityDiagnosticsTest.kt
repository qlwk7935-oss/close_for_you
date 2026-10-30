package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityNodeInfo as N
import kotlinx.collections.immutable.*
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Shared real policy/probe/report path. Frozen DTO/native lists are synthetic, not Samsung data. */
class ManualNativeCapabilityDiagnosticsTest {
    private fun rig(diagnostics: Boolean = true) = ManualDemoBoundsRejectionTest.Rig(diagnostics).apply { expand(reflow = true) }
    private fun move(r: ManualDemoBoundsRejectionTest.Rig, index: Int) {
        r.fresh = r.fresh.copy(nodes = r.fresh.nodes.mapIndexed { i, n ->
            if (i == index) n.copy(bounds = n.bounds.copy(bottom = n.bounds.bottom - 1)) else n })
    }
    private fun capability(r: ManualDemoBoundsRejectionTest.Rig, index: Int, ids: List<Int>) {
        r.originalSummaries = r.originalSummaries + (r.source.nodes[index].path to NativeActionSummary.fromIds(ids))
        r.freshSummaries = r.originalSummaries
    }
    private fun reject(r: ManualDemoBoundsRejectionTest.Rig): ManualInteractiveBoundsCode {
        assertEquals(ManualDisplayVerdict.CHANGED, r.run())
        assertEquals(ManualDemoBoundsReason.POLICY_INTERACTIVE_BOUNDS, r.row!!.demoBounds)
        assertEquals(1, r.reads); assertEquals(1, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
        return requireNotNull(r.row!!.interactiveBounds)
    }
    private fun describe(r: ManualDemoBoundsRejectionTest.Rig, original: NativeNodeActions? = r.originalProof(),
        fresh: F9Scene = r.withFreshProof()) = ManualInteractiveBoundsDiagnostics.describe(r.source, fresh,
        r.analysis!!.closeCandidates, 5, NativeActionPresence.PRESENT, original)

    @Test fun actualNonclickTextLeafAndMixedAospStyleCapabilitiesAreDescribedButStillRejected() {
        val r = rig(); capability(r, 5, listOf(N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION, N.ACTION_ACCESSIBILITY_FOCUS,
            android.R.id.accessibilityActionShowOnScreen, N.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            N.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, N.ACTION_SET_SELECTION)); move(r, 5)
        val code = reject(r)
        assertEquals(ManualRejectedRole.TEXTVIEW_LEAF, code.role)
        assertEquals(ManualCapabilityProfile.NAVIGATION_AND_SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION, code.capabilities)
        assertEquals(ManualOperableGeometry.UNCHANGED, code.closeGeometry)
        // ROOT and the rejected leaf changed, but the remaining clickable button did not.
        assertEquals(ManualOtherOperableGeometry.UNCHANGED, code.otherGeometry)
    }
    @Test fun emptyLayoutLeafRequiresExactKnownClassAndEmptyContentOnTheSameFrozenRows() {
        for (kind in listOf("android.widget.FrameLayout", "android.widget.LinearLayout")) {
            val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.mapIndexed { i, n ->
                if (i == 5) n.copy(className = kind, text = null, contentDescription = null) else n }.toPersistentList())
            r.attach(); r.expand(reflow = true); capability(r, 5, listOf(N.ACTION_SELECT)); move(r, 5)
            assertEquals(ManualRejectedRole.EMPTY_LAYOUT_LEAF, reject(r).role)
        }
    }
    @Test fun nonemptyLayoutOrCustomReportedClassIsOtherAndMissingClassIsUnknown() {
        for (kind in listOf("android.widget.FrameLayout", "custom.SYNTHETIC_CLASS", null, "")) {
            val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.mapIndexed { i, n ->
                if (i == 5) n.copy(className = kind) else n }.toPersistentList())
            r.attach(); r.expand(reflow = true); capability(r, 5, listOf(N.ACTION_SELECT)); move(r, 5)
            assertEquals(if (kind.isNullOrBlank()) ManualRejectedRole.UNKNOWN else ManualRejectedRole.OTHER, reject(r).role)
        }
    }
    @Test fun nonleafIsNotReportedAsTextLeafAndActualParentPolicyStillRejectsItEarlier() {
        val r = rig(); capability(r, 1, listOf(N.ACTION_SELECT))
        assertEquals(ManualDisplayVerdict.CHANGED, r.run())
        assertEquals(ManualDemoBoundsReason.POLICY_REFLOW_PARENT, r.row!!.demoBounds)
        assertNull(r.row!!.interactiveBounds)
    }
    @Test fun everyRequestedFamilyAndEveryCombinationIsLosslesslyCategorized() {
        val expected = listOf(ManualCapabilityProfile.NAVIGATION, ManualCapabilityProfile.SELECTION_STATE,
            ManualCapabilityProfile.NAVIGATION_AND_SELECTION_STATE, ManualCapabilityProfile.TEXT_NAVIGATION_SELECTION,
            ManualCapabilityProfile.NAVIGATION_AND_TEXT_NAVIGATION_SELECTION,
            ManualCapabilityProfile.SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION,
            ManualCapabilityProfile.NAVIGATION_AND_SELECTION_STATE_AND_TEXT_NAVIGATION_SELECTION)
        val representatives = listOf(N.ACTION_FOCUS, N.ACTION_SELECT, N.ACTION_SET_SELECTION)
        for (mask in 1..7) {
            val ids = representatives.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            assertEquals(expected[mask - 1], ManualInteractiveBoundsDiagnostics.profile(NativeActionSummary.fromIds(ids)))
        }
    }
    @Test fun pureOtherStandardDoesNotMeanSafeAndMixedOtherStandardIsUnknown() {
        for (id in listOf(N.ACTION_CLICK, N.ACTION_LONG_CLICK, N.ACTION_DISMISS, N.ACTION_SCROLL_FORWARD,
            N.ACTION_PASTE, N.ACTION_SET_TEXT, N.ACTION_NEXT_HTML_ELEMENT)) {
            assertEquals(ManualCapabilityProfile.OTHER_STANDARD, ManualInteractiveBoundsDiagnostics.profile(NativeActionSummary.fromIds(listOf(id))))
            assertEquals(ManualCapabilityProfile.UNKNOWN, ManualInteractiveBoundsDiagnostics.profile(NativeActionSummary.fromIds(listOf(id, N.ACTION_FOCUS))))
        }
    }
    @Test fun missingIncompleteCustomLabelledDuplicateAndOverflowProfilesRemainUnknown() {
        val rows = listOf(NativeActionSummary.UNKNOWN, NativeActionSummary.presenceOnly(NativeActionPresence.PRESENT),
            NativeActionSummary.fromIds(emptyList()), NativeActionSummary.fromIds(listOf(Int.MAX_VALUE)),
            NativeActionSummary.fromIds(listOf(N.ACTION_SELECT), hasUnverifiedLabel = true),
            NativeActionSummary.fromIds(listOf(N.ACTION_SELECT, N.ACTION_SELECT)), NativeActionSummary.fromIds(List(65) { N.ACTION_SELECT }))
        for (summary in rows) assertEquals(ManualCapabilityProfile.UNKNOWN, ManualInteractiveBoundsDiagnostics.profile(summary))
    }
    @Test fun equalProfileCannotSubstituteForExactNativeActionIdentity() {
        val r = rig(); capability(r, 5, listOf(N.ACTION_FOCUS)); move(r, 5)
        r.freshSummaries = mapOf(r.source.nodes[5].path to NativeActionSummary.fromIds(listOf(N.ACTION_CLEAR_FOCUS)))
        assertEquals(ManualDisplayVerdict.CHANGED, r.run())
        assertEquals(ManualDemoBoundsReason.POLICY_NATIVE_ACTIONS_CHANGED, r.row!!.demoBounds)
        assertNull(r.row!!.interactiveBounds); assertEquals(0, r.f.clicks)
    }
    @Test fun movingOrResizingCloseIsReportedEvenWhenAnEarlierNonclickLeafRejectedFirst() {
        for (resize in listOf(false, true)) {
            val r = rig(); capability(r, 5, listOf(N.ACTION_SELECT)); move(r, 5)
            r.fresh = r.fresh.copy(nodes = r.fresh.nodes.mapIndexed { i, n -> if (i != 7) n else n.copy(bounds =
                if (resize) n.bounds.copy(bottom = n.bounds.bottom - 1) else n.bounds.copy(left = n.bounds.left + 1, right = n.bounds.right + 1)) })
            val code = reject(r)
            assertEquals(ManualInteractiveNode.NONCLICK_NATIVE_CAPABILITY, code.node)
            assertEquals(ManualOperableGeometry.CHANGED, code.closeGeometry)
        }
    }
    @Test fun closeOnlyMovementDoesNotCountItselfAsOtherOperableMovement() {
        val r = rig(); move(r, 7); val code = reject(r)
        assertEquals(ManualInteractiveNode.CLOSE_CANDIDATE, code.node)
        assertEquals(ManualOperableGeometry.CHANGED, code.closeGeometry)
        assertEquals(ManualOtherOperableGeometry.UNCHANGED, code.otherGeometry)
    }
    @Test fun firstRejectedOtherClickableIsNotDoubleCountedAsAnotherOperable() {
        val r = rig(); move(r, 6); val code = reject(r)
        assertEquals(ManualOperableGeometry.UNCHANGED, code.closeGeometry)
        assertEquals(ManualOtherOperableGeometry.NO_TARGETS, code.otherGeometry)
    }
    @Test fun nativeOnlyNodeAfterTheFirstRejectionIsStillScannedForGeometry() {
        val r = rig(); val extra = r.source.nodes[5].copy(path = "0/0/0/0/4", viewId = "synthetic-extra",
            bounds = ScreenBounds(40, 320, 220, 340))
        r.source = r.source.copy(nodes = (r.source.nodes + extra).toPersistentList())
        r.attach(); r.expand(reflow = true); capability(r, 8, listOf(N.ACTION_LONG_CLICK)); move(r, 7); move(r, 8)
        val code = reject(r)
        assertEquals(ManualInteractiveNode.CLOSE_CANDIDATE, code.node)
        assertEquals(ManualOtherOperableGeometry.CHANGED, code.otherGeometry)
    }
    @Test fun operableRootMovementIsExcludedWhileRemainingUnchangedButtonIsObserved() {
        val r = rig(); capability(r, 0, listOf(N.ACTION_SELECT)); move(r, 7)
        assertEquals(ManualOtherOperableGeometry.UNCHANGED, reject(r).otherGeometry)
    }
    @Test fun rootOnlyMovementDoesNotFabricateAnInteractiveRejectionOrChangeDisplayOutcome() {
        val r = rig(); r.expand(); capability(r, 0, listOf(N.ACTION_SELECT))
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
        assertEquals(ManualDemoBoundsReason.POLICY_RETAIN_ROOT_RESIZE, r.row!!.demoBounds)
        assertNull(r.row!!.interactiveBounds); assertEquals(0, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
    }
    @Test fun anotherButtonMovementIsVisibleWhenTheFirstRejectedLeafIsExcluded() {
        val r = rig(); capability(r, 5, listOf(N.ACTION_SELECT)); move(r, 5); move(r, 6)
        val code = reject(r)
        assertEquals(ManualInteractiveNode.NONCLICK_NATIVE_CAPABILITY, code.node)
        assertEquals(ManualOperableGeometry.UNCHANGED, code.closeGeometry)
        assertEquals(ManualOtherOperableGeometry.CHANGED, code.otherGeometry)
    }
    private fun withoutOtherButton() = rig().apply {
        source = source.copy(nodes = source.nodes.mapIndexed { i, n ->
            if (i == 6) n.copy(clickable = false, actions = persistentSetOf()) else n }.toPersistentList())
        attach(); expand(reflow = true); capability(this, 0, listOf(N.ACTION_SELECT))
    }
    @Test fun noRemainingOperableNodesIsNotReportedAsAllRemainingUnchanged() {
        val none = withoutOtherButton(); capability(none, 5, listOf(N.ACTION_SELECT)); move(none, 5)
        val code = reject(none)
        assertEquals(ManualOtherOperableGeometry.NO_TARGETS, code.otherGeometry)
        assertEquals(ManualOperableGeometry.UNCHANGED, code.closeGeometry)
        val fixed = rig(); capability(fixed, 5, listOf(N.ACTION_SELECT)); move(fixed, 5)
        assertEquals(ManualOtherOperableGeometry.UNCHANGED, reject(fixed).otherGeometry)
    }
    @Test fun closeCandidateThatIsAlsoFirstRejectionIsExcludedExactlyOnce() {
        val r = withoutOtherButton(); move(r, 7); val code = reject(r)
        assertEquals(ManualInteractiveNode.CLOSE_CANDIDATE, code.node)
        assertEquals(ManualOperableGeometry.CHANGED, code.closeGeometry)
        assertEquals(ManualOtherOperableGeometry.NO_TARGETS, code.otherGeometry)
    }
    @Test fun excludedAndRemainingMetadataUncertaintyNeverBecomesNoTargetsOrUnchanged() {
        for (index in listOf(0, 5, 6, 7)) {
            val r = rig(); capability(r, 5, listOf(N.ACTION_SELECT)); move(r, 5)
            r.freshSummaries = r.freshSummaries + (r.source.nodes[index].path to NativeActionSummary.UNKNOWN)
            assertEquals(ManualInteractiveBoundsDiagnostics.unknown(), describe(r))
            assertEquals(ManualDisplayVerdict.CHANGED, r.run()); assertNull(r.row!!.interactiveBounds)
        }
    }
    @Test fun foreignOriginalOrFreshBindingAndMissingMetadataCannotYieldGeometryCertificates() {
        val r = rig(); capability(r, 5, listOf(N.ACTION_SELECT)); move(r, 5)
        val badOriginal = NativeNodeActions.forSnapshotSummaries(r.source.copy(), r.source.nodes.map { NativeActionSummary.fromIds(emptyList()) })
        val badFresh = r.fresh.copy(nativeActions = NativeNodeActions.forNodeSummaries(r.fresh.nodes.toMutableList(),
            r.fresh.nodes.map { NativeActionSummary.fromIds(emptyList()) }))
        for (code in listOf(describe(r, original = null), describe(r, original = badOriginal),
            describe(r, fresh = r.fresh.copy(nativeActions = null)), describe(r, fresh = badFresh)))
            assertEquals(ManualInteractiveBoundsDiagnostics.unknown(), code)
    }
    @Test fun diagnosticBadLookupsCannotPoisonOrResurrectThePolicyMetadataLatch() {
        val r = rig(); val proof = requireNotNull(r.originalProof()); val count = r.source.nodes.size
        val baseline = proof.summary(r.source, count, 7)
        assertTrue(baseline.hasExactKnownSet)
        assertEquals(NativeActionSummary.UNKNOWN, proof.observedSummary(r.source, count - 1, 7))
        assertEquals(NativeActionSummary.UNKNOWN, proof.observedSummary(r.source.copy(), count, 7))
        assertEquals(baseline, proof.summary(r.source, count, 7))
        assertEquals(NativeActionSummary.UNKNOWN, proof.summary(r.source, count - 1, 7))
        assertEquals(NativeActionSummary.UNKNOWN, proof.observedSummary(r.source, count, 7))
    }
    @Test fun candidateBindingGraphOrderContentAndFrameUncertaintyProduceOnlyUnknown() {
        val r = rig(); capability(r, 5, listOf(N.ACTION_SELECT)); move(r, 5)
        val cases = listOf(r.fresh.copy(nodes = r.fresh.nodes.reversed()), r.fresh.copy(nodes = r.fresh.nodes.dropLast(1)),
            r.fresh.copy(packageName = "synthetic-other"), r.fresh.copy(windowId = 99),
            r.fresh.copy(screen = ScreenBounds(0, 0, 301, 500)), r.fresh.copy(complete = false))
        for (fresh in cases) assertEquals(ManualInteractiveBoundsDiagnostics.unknown(), describe(r, fresh = fresh))
        val wrongCandidate = r.analysis!!.closeCandidates.single().copy(bounds = ScreenBounds(1, 1, 2, 2))
        assertEquals(ManualInteractiveBoundsDiagnostics.unknown(), ManualInteractiveBoundsDiagnostics.describe(r.source,
            r.withFreshProof(), listOf(wrongCandidate), 5, NativeActionPresence.PRESENT, r.originalProof()))
    }
    @Test fun onlyEnumFieldsAreRetainedAndSensitiveCollectedValuesNeverEnterOutput() {
        val r = rig(); val marker = "SYNTHETIC_PRIVATE_MARKER"
        r.source = r.source.copy(nodes = r.source.nodes.mapIndexed { i, n -> if (i != 5) n else
            n.copy(text = marker, contentDescription = marker, viewId = marker, className = "custom.$marker") }.toPersistentList())
        r.attach(); r.expand(reflow = true); capability(r, 5, listOf(N.ACTION_SELECT)); move(r, 5)
        val code = reject(r)
        assertEquals(ManualRejectedRole.OTHER, code.role)
        assertTrue(Regex("[;A-Z_=]+").matches(code.fields()))
        assertFalse(code.fields().contains(marker)); assertFalse(code.toString().contains(marker))
        assertTrue(ManualInteractiveBoundsCode::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.all { it.type.isEnum })
    }
    @Test fun diagnosisDoesNotModifyAcceptanceCleanupEvidenceTokenOrTtlInAnyCase() {
        for (moveClose in listOf(false, true)) for (changeBody in listOf(false, true)) {
            fun make(diagnostics: Boolean) = rig(diagnostics).apply {
                if (changeBody) { capability(this, 5, listOf(N.ACTION_SELECT)); move(this, 5) }
                if (moveClose) move(this, 7)
            }
            val off = make(false); val on = make(true); val original = on.source
            assertEquals(off.run(), on.run()); assertEquals(off.removed, on.removed); assertEquals(off.reads, on.reads)
            assertSame(original, on.source); assertNull(off.raw); assertNull(on.raw)
            assertEquals(1_000L, on.source.capturedAtUptimeMillis); assertEquals(0, on.f.clicks)
        }
    }
}
