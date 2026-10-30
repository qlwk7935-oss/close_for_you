package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityNodeInfo as N
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.integration.ManualDemoBoundsReason.*
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Uses the actual selector -> registered read -> policy rejection -> probe -> terminal report.
 * Native sources/clock are the existing fake IPC fixture, never claimed as Samsung evidence. */
class ManualInteractiveBoundsDiagnosticsTest {
    private fun rig(diagnostics: Boolean = true) = ManualDemoBoundsRejectionTest.Rig(diagnostics).apply { expand(reflow = true) }
    private fun move(r: ManualDemoBoundsRejectionTest.Rig, index: Int) {
        r.fresh = r.fresh.copy(nodes = r.fresh.nodes.mapIndexed { i, n ->
            if (i == index) n.copy(bounds = n.bounds.copy(bottom = n.bounds.bottom - 1)) else n })
    }
    private fun reject(r: ManualDemoBoundsRejectionTest.Rig): ManualInteractiveBoundsCode {
        assertEquals(ManualDisplayVerdict.CHANGED, r.run())
        assertEquals(POLICY_INTERACTIVE_BOUNDS, r.row!!.demoBounds)
        assertEquals(1, r.reads); assertEquals(1, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
        assertSame(r.probe.interactiveBounds, r.row!!.interactiveBounds)
        return requireNotNull(r.row!!.interactiveBounds)
    }
    private fun native(r: ManualDemoBoundsRejectionTest.Rig, index: Int, ids: List<Int>) {
        r.originalSummaries = mapOf(r.source.nodes[index].path to NativeActionSummary.fromIds(ids))
        r.freshSummaries = r.originalSummaries
    }
    @Test fun movingCloseCandidateReportsAllThreeExistingRejectionConditions() {
        val r = rig(); move(r, 7); val code = reject(r)
        assertEquals(ManualInteractiveNode.CLOSE_CANDIDATE, code.node)
        assertEquals(ManualInteractiveCauses.CLICKABLE_DTO_ACTION_NATIVE_ACTION, code.causes)
    }
    @Test fun movingOtherClickableIsNotTheCloseCandidate() {
        val r = rig(); move(r, 6)
        assertEquals(ManualInteractiveNode.OTHER_CLICKABLE, reject(r).node)
    }
    @Test fun nonclickSelectCapabilityRemainsRejectedAndIsNotCalledPassive() {
        val r = rig(); native(r, 5, listOf(N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION)); move(r, 5)
        val code = reject(r)
        assertEquals(ManualInteractiveNode.NONCLICK_NATIVE_CAPABILITY, code.node)
        assertEquals(ManualInteractiveCauses.NATIVE_ACTION, code.causes)
    }
    @Test fun nonclickLongClickAndScrollAreCapabilitiesNotEvidenceOfSafety() {
        for (id in listOf(N.ACTION_LONG_CLICK, N.ACTION_SCROLL_FORWARD, N.ACTION_ACCESSIBILITY_FOCUS)) {
            val r = rig(); native(r, 5, listOf(id)); move(r, 5)
            assertEquals(ManualInteractiveNode.NONCLICK_NATIVE_CAPABILITY, reject(r).node)
        }
    }
    @Test fun clickableWithoutProjectedOrNativeActionsReportsOnlyClickable() {
        val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.mapIndexed { i, n ->
            if (i == 6) n.copy(actions = persistentSetOf()) else n }.toPersistentList())
        r.attach(); r.expand(reflow = true); move(r, 6)
        assertEquals(ManualInteractiveCauses.CLICKABLE, reject(r).causes)
    }
    @Test fun nonclickWithDtoActionReportsBothDtoAndNativeNotJustFirstOperand() {
        val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.mapIndexed { i, n ->
            if (i == 5) n.copy(actions = persistentSetOf(NodeAction.DISMISS)) else n }.toPersistentList())
        r.attach(); r.expand(reflow = true); move(r, 5)
        val code = reject(r)
        assertEquals(ManualInteractiveNode.NONCLICK_NATIVE_CAPABILITY, code.node)
        assertEquals(ManualInteractiveCauses.DTO_ACTION_NATIVE_ACTION, code.causes)
    }
    @Test fun everyBooleanCombinationHasAnExactFixedCombinationCode() {
        val seen = mutableSetOf<ManualInteractiveCauses>()
        for (c in listOf(false, true)) for (d in listOf(false, true)) for (n in listOf(false, true))
            seen += ManualInteractiveCauses.from(c, d, n)
        assertEquals(ManualInteractiveCauses.entries.toSet(), seen)
    }
    @Test fun firstRejectingChildIsReportedRatherThanFirstOverallBoundsMismatchOrLaterClose() {
        val r = rig(); move(r, 6); move(r, 7)
        assertEquals(ManualInteractiveNode.OTHER_CLICKABLE, reject(r).node)
        assertEquals(ManualNodeMismatch.BOUNDS, r.row!!.nodes) // Root is the overall first DTO mismatch.
        assertEquals(ManualBoundsNode.ROOT, r.row!!.bounds!!.node)
    }
    @Test fun missingUnknownCustomOrChangedMetadataCannotProduceAnInteractiveClassification() {
        for (mode in 0..4) {
            val r = rig(); move(r, 5)
            when (mode) {
                0 -> r.originalProofEnabled = false
                1 -> r.freshProofEnabled = false
                2 -> { r.originalNativeStates = mapOf(r.source.nodes[5].path to NativeActionPresence.UNKNOWN) }
                3 -> native(r, 5, listOf(Int.MAX_VALUE))
                4 -> r.freshSummaries = mapOf(r.source.nodes[5].path to NativeActionSummary.fromIds(listOf(N.ACTION_SELECT)))
            }
            assertNotEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run()); assertNull(r.probe.interactiveBounds)
            assertEquals(0, r.f.clicks)
        }
    }
    @Test fun foreignOriginalAndFreshMetadataNeverReachTheNewRejectionObserver() {
        for (original in listOf(false, true)) {
            val r = rig(); move(r, 7); val probe = ManualDisplayProbe(true)
            val scene = if (original) r.withFreshProof() else r.fresh.copy(nativeActions =
                NativeNodeActions.forNodeSummaries(r.fresh.nodes.toMutableList(), r.fresh.nodes.map { NativeActionSummary.fromIds(emptyList()) }))
            val proof = if (original) NativeNodeActions.forSnapshotSummaries(r.source.copy(),
                r.source.nodes.map { NativeActionSummary.fromIds(emptyList()) }) else r.originalProof()
            assertEquals(ManualDisplayVerdict.CHANGED, ManualDisplayContinuity.assess(r.source, scene, 30, probe,
                ManualDisplayListing.NOT_ENUMERATED, r.analysis!!.closeCandidates, proof))
            assertEquals(POLICY_NATIVE_ACTIONS_UNCONFIRMED, probe.demoBounds); assertNull(probe.interactiveBounds)
        }
    }
    @Test fun uncertainOrContradictoryDiagnosticInputsRemainUnknown() {
        val r = rig(); move(r, 7)
        fun describe(i: Int, p: NativeActionPresence = NativeActionPresence.PRESENT, candidates: List<CloseCandidate> = r.analysis!!.closeCandidates) =
            ManualInteractiveBoundsDiagnostics.describe(r.source, r.withFreshProof(), candidates, i, p, r.originalProof())
        for (code in listOf(describe(-1), describe(0), describe(99), describe(7, NativeActionPresence.UNKNOWN),
            describe(7, NativeActionPresence.NONE), describe(7, candidates = emptyList()),
            describe(7, candidates = listOf(r.analysis!!.closeCandidates.single().copy(viewId = "SYNTHETIC_WRONG")))))
            assertEquals(ManualInteractiveBoundsDiagnostics.unknown(), code)
        val unchanged = rig()
        assertEquals(ManualInteractiveBoundsDiagnostics.unknown(), ManualInteractiveBoundsDiagnostics.describe(
            unchanged.source, unchanged.withFreshProof(), unchanged.analysis!!.closeCandidates, 7, NativeActionPresence.PRESENT, unchanged.originalProof()))
    }
    @Test fun probePreservesFirstCodeAndOnlyEnumsEscapeTheComparison() {
        val r = rig(); move(r, 6); move(r, 7)
        r.probe.recordInteractiveBounds(r.source, r.withFreshProof(), r.analysis!!.closeCandidates, 6, NativeActionPresence.PRESENT, r.originalProof())
        val first = r.probe.interactiveBounds
        r.probe.recordInteractiveBounds(r.source, r.withFreshProof(), r.analysis!!.closeCandidates, 7, NativeActionPresence.PRESENT, r.originalProof())
        assertSame(first, r.probe.interactiveBounds)
        assertTrue(ManualInteractiveBoundsCode::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .all { it.type.isEnum })
        assertEquals(";INTERACTIVE_NODE=OTHER_CLICKABLE;INTERACTIVE_CAUSES=CLICKABLE_DTO_ACTION_NATIVE_ACTION" +
            ";REJECTED_ROLE=OTHER;CAPABILITY_PROFILE=OTHER_STANDARD;CLOSE_GEOMETRY=CHANGED;OTHER_OPERABLE_GEOMETRY=NO_TARGETS", first!!.fields())
    }
    @Test fun diagnosticOffAndOnHaveIdenticalAcceptRejectRemovalAndEvidenceResults() {
        for (changed in listOf(false, true)) {
            val off = rig(false); val on = rig(true)
            if (changed) { move(off, 7); move(on, 7) }
            assertEquals(off.run(), on.run()); assertEquals(off.removed, on.removed)
            assertEquals(off.row!!.detail, on.row!!.detail); assertEquals(off.reads, on.reads)
            assertEquals(NOT_CHECKED, off.row!!.demoBounds)
            assertEquals(if (changed) POLICY_INTERACTIVE_BOUNDS else POLICY_RETAIN_PASSIVE_REFLOW, on.row!!.demoBounds)
            assertNull(off.probe.interactiveBounds); assertNull(off.raw); assertNull(on.raw)
            assertEquals(0, on.f.clicks); assertEquals(0, off.f.clicks)
        }
    }
    @Test fun collectedContentIdentifiersAndPathsCannotEnterTheFormattedDiagnostic() {
        val r = rig(); val marker = "SYNTHETIC_PRIVATE_MARKER"
        r.source = r.source.copy(nodes = r.source.nodes.mapIndexed { i, n ->
            if (i == 5) n.copy(text = marker, contentDescription = marker, viewId = marker) else n }.toPersistentList())
        r.attach(); r.expand(reflow = true); native(r, 5, listOf(N.ACTION_SELECT)); move(r, 5)
        val code = reject(r)
        assertFalse(code.fields().contains(marker)); assertFalse(code.toString().contains(marker))
        assertFalse(code.fields().contains(r.source.nodes[5].path))
        assertEquals(";INTERACTIVE_NODE=NONCLICK_NATIVE_CAPABILITY;INTERACTIVE_CAUSES=NATIVE_ACTION" +
            ";REJECTED_ROLE=TEXTVIEW_LEAF;CAPABILITY_PROFILE=SELECTION_STATE;CLOSE_GEOMETRY=UNCHANGED;OTHER_OPERABLE_GEOMETRY=UNCHANGED", code.fields())
    }
    @Test fun observerExceptionOrDiagnosticCancellationCannotAlterTheOriginalRejection() {
        for (cancel in listOf(false, true)) {
            val r = rig(); move(r, 7); var calls = 0
            val accepted = ManualDemoBoundsPolicy.retains(r.source, r.withFreshProof(), 30, ManualDisplayListing.NOT_ENUMERATED,
                r.analysis!!.closeCandidates, r.originalProof(), interactiveRejection = { _, _ ->
                    calls++; if (cancel) throw CancellationException("SYNTHETIC_DIAGNOSTIC") else error("SYNTHETIC_DIAGNOSTIC") })
            assertFalse(accepted); assertEquals(1, calls)
        }
    }
    private fun code() = rig().let { r -> move(r, 7); reject(r); requireNotNull(r.row) }
    @Test fun traceEmitsFirstRelatedRejectionOncePerAttemptAndResetsOnlyAtBegin() {
        val emitted = mutableListOf<Pair<Long, ManualDisplayReviewCode>>()
        val trace = ManualPromptTrace(displayReviewSink = { a, c -> emitted += a to c }) { _, _, _ -> }
        val row = code(); trace.begin(); repeat(20) { trace.displayReview(row.copy(point = ManualDisplayPoint.entries[it % ManualDisplayPoint.entries.size])) }
        assertEquals(1, emitted.count { it.second.interactiveBounds != null })
        trace.begin(); trace.displayReview(row)
        assertEquals(listOf(1L, 2L), emitted.filter { it.second.interactiveBounds != null }.map { it.first })
    }
    @Test fun routineCapCannotHideFirstRelatedRejectionAndSinkFailureNeverRetriesIt() {
        var related = 0; val row = code()
        val trace = ManualPromptTrace(displayReviewSink = { _, c -> if (c.interactiveBounds != null) {
            related++; throw CancellationException("SYNTHETIC_LOG") } }) { _, _, _ -> }
        trace.begin()
        ManualDisplayDetail.entries.take(20).forEach { trace.displayReview(row.copy(detail = it, interactiveBounds = null)) }
        repeat(5) { trace.displayReview(row) }
        assertEquals(1, related)
    }
    @Test fun actualCancellationAndCleanupFailuresStillPropagateWithDiagnosticsEnabled() {
        val cancelled = rig(); val primary = CancellationException("SYNTHETIC_CAPTURE")
        cancelled.read = { throw primary }; cancelled.failCleanup = true
        try { cancelled.run(); fail("CANCEL_REQUIRED") } catch (e: CancellationException) { assertSame(primary, e) }
        assertNull(cancelled.probe.interactiveBounds); assertEquals(1, cancelled.removed)
        val cleanup = rig(); move(cleanup, 7); cleanup.failCleanup = true
        try { cleanup.run(); fail("CLEANUP_REQUIRED") } catch (_: CollectionCleanupFailure) { }
        assertEquals(ManualInteractiveNode.CLOSE_CANDIDATE, cleanup.row!!.interactiveBounds!!.node)
        assertEquals(0, cleanup.f.clicks)
    }
}
