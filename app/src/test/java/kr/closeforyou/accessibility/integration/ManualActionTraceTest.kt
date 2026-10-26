package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.EvidenceMismatch
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateAttacher
import org.junit.Assert.*
import org.junit.Test

/** Real refresh -> B policy/session -> F9 path. Only the Android F0/window/action port is fake.
 * No enum-only model substitutes for the actual execution decisions. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualActionTraceTest {
    private class Rig(scope: TestScope) {
        val f = F9AutoCloserTest.Fixture()
        val body = f.node.copy(path = "0/1", viewId = "body", className = "android.widget.TextView",
            text = "광고 신상 안내", clickable = false, actions = persistentSetOf())
        val old = f.snapshot.copy(nodes = persistentListOf(f.node, body))
        val detection = PopupDetection(old.token, persistentSetOf(PopupSignal.NEW_WINDOW))
        val shown = DecisionCandidateAttacher().attach(PopupEvent.Candidate(old, detection),
            PopupAnalysis(old.token, texts = F9CollectedSource.texts(old)))
        val choice = UserChoice(old.token, ChoiceKind.CLOSE_ONCE, shown.closeCandidates.single().id, true)
        var access = F9SessionAccess(true, true, true, false, false, announcementAvailable = true)
        var current = true
        var reads = 0
        val rows = mutableListOf<ManualActionCode>()
        val trace = ManualActionTrace(rows::add)
        val session = F9ServiceSession(F9CollectedSource { f.now }, f.port, F9Clock { f.now },
            { access }, { true }, {}, dispatcher = StandardTestDispatcher(scope.testScheduler))
        init { f.now += 6_000 }
        fun fresh() = old.copy(token = old.token.copy(screenVersion = 2, captureEpoch = 2, popupInstanceId = "fresh"),
            capturedAtUptimeMillis = f.now)
        suspend fun refresh(display: PopupAnalysis = shown, read: (() -> Unit) -> PopupSnapshot? = { fresh() }) =
            ManualChoiceRefresh.afterRemoval(old, display, choice, detection, { f.now }, { current }, {}, {}, trace) {
                reads++; read(it)
            }
        suspend fun execute(e: ManualChoiceEvidence, diagnostic: ManualActionTrace = trace): ActionResult {
            f.scene = f.before.copy(nodes = e.snapshot.nodes, windows = e.snapshot.windows)
            return session.executeUser(e.snapshot, e.analysis, e.choice, e.detection, diagnostic) { current }
        }
        fun has(step: ManualActionStep, state: ManualActionState) = rows.any { it.step == step && it.state == state }
    }

    @Test fun everyRealOfferGuardHasAnIndependentFirstReasonAndOriginalShortCircuitOrder() {
        val reasons = listOf(ManualPromptCause.OFFER_CLOSED, ManualPromptCause.OFFER_HEALTH_FAILED,
            ManualPromptCause.OFFER_COMPLETED_KEY, ManualPromptCause.OFFER_CLEANUP_NOT_READY,
            ManualPromptCause.OFFER_OVERLAY_SHOWING, ManualPromptCause.OFFER_SELECTION_PENDING)
        for (first in reasons.indices) {
            val calls = mutableListOf<Int>()
            fun hit(i: Int): Boolean { calls += i; return i >= first }
            assertEquals(reasons[first], manualOfferRejection(first == 0, { hit(1) }, { hit(2) },
                { !hit(3) }, { hit(4) }, { hit(5) }))
            assertEquals((1..first).toList(), calls)
        }
        assertNull(manualOfferRejection(false, { false }, { false }, { true }, { false }, { false }))
    }
    @Test fun completedKeyWinsOverShowingAndPendingChoiceWithoutBeingAReportedClickFailure() {
        val rows = mutableListOf<ManualActionCode>()
        val trace = ManualActionTrace(rows::add)
        trace.record(ManualActionStep.CHOICE_ACCEPTED, ManualActionState.PASSED)
        assertEquals(ManualPromptCause.OFFER_COMPLETED_KEY,
            manualOfferRejection(false, { false }, { true }, { false }, { true }, { true }))
        assertEquals(listOf(ManualActionStep.CHOICE_ACCEPTED), rows.map { it.step })
    }
    @Test fun newOfferRejectionCannotCancelAnAlreadyAcceptedRealManualExecution() = runTest {
        val r = Rig(this)
        r.trace.record(ManualActionStep.CHOICE_ACCEPTED, ManualActionState.PASSED)
        val e = requireNotNull(r.refresh())
        assertEquals(ManualPromptCause.OFFER_SELECTION_PENDING,
            manualOfferRejection(false, { false }, { false }, { true }, { false }, { true }))
        val result = r.execute(e)
        assertTrue(result.attempted); assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
        assertEquals(1, r.f.clicks)
    }
    @Test fun acceptedActionKeepsItsAttemptNumberAfterALaterOfferStarts() {
        val seen = mutableListOf<Pair<Long, ManualActionCode>>()
        val prompt = ManualPromptTrace(actionSink = { n, c -> seen += n to c }, sink = { _, _, _ -> })
        prompt.begin(); val first = prompt.action()
        first.record(ManualActionStep.CHOICE_ACCEPTED, ManualActionState.PASSED)
        prompt.begin(); first.record(ManualActionStep.CLICK_RESULT, ManualActionState.COMPLETED,
            outcome = ClickOutcome.STILL_PRESENT)
        assertEquals(listOf(1L, 1L), seen.map { it.first })
        prompt.action().record(ManualActionStep.CHOICE_ACCEPTED, ManualActionState.PASSED)
        assertEquals(2L, seen.last().first)
    }
    @Test fun cleanupWaitTimeoutReportsRemovalNotACollectionOrClickFailure() = runTest {
        val r = Rig(this)
        assertNull(ManualChoiceRefresh.afterRemoval(r.old, r.shown, r.choice, r.detection, { r.f.now }, { true },
            { awaitCancellation() }, {}, r.trace) { r.reads++; r.fresh() })
        assertTrue(r.has(ManualActionStep.OVERLAY_REMOVAL, ManualActionState.TIMED_OUT))
        assertFalse(r.has(ManualActionStep.MANUAL_REFRESH, ManualActionState.STARTED))
        assertEquals(0, r.reads); assertEquals(0, r.f.clicks)
    }
    @Test fun cleanupExceptionKeepsItsStageAndCannotReachTheCollector() = runTest {
        val r = Rig(this)
        try {
            ManualChoiceRefresh.afterRemoval(r.old, r.shown, r.choice, r.detection, { r.f.now }, { true },
                { error("SYNTHETIC") }, {}, r.trace) { r.reads++; r.fresh() }
            fail("EXCEPTION_REQUIRED")
        } catch (_: IllegalStateException) { }
        assertTrue(r.has(ManualActionStep.OVERLAY_REMOVAL, ManualActionState.FAILED))
        assertEquals(0, r.reads); assertEquals(0, r.f.clicks)
    }
    @Test fun cancellationDuringRemovalPropagatesAndCannotBecomeSuccessfulRemoval() = runTest {
        val r = Rig(this)
        val work = launch {
            ManualChoiceRefresh.afterRemoval(r.old, r.shown, r.choice, r.detection, { r.f.now }, { true },
                { awaitCancellation() }, {}, r.trace) { r.reads++; r.fresh() }
        }
        runCurrent(); work.cancelAndJoin()
        assertTrue(work.isCancelled); assertTrue(r.has(ManualActionStep.OVERLAY_REMOVAL, ManualActionState.CANCELLED))
        assertFalse(r.has(ManualActionStep.OVERLAY_REMOVAL, ManualActionState.PASSED)); assertEquals(0, r.reads)
    }
    @Test fun freshF0UnavailableReportsActualReadBoundaryWithoutSnapshotMatch() = runTest {
        val r = Rig(this); assertNull(r.refresh { null })
        assertEquals(1, r.reads)
        assertTrue(r.rows.any { it.reason == ManualActionReason.COLLECTION_UNAVAILABLE })
        assertFalse(r.has(ManualActionStep.SNAPSHOT_MATCH, ManualActionState.PASSED)); assertEquals(0, r.f.clicks)
    }
    @Test fun snapshotMismatchUsesExistingComparisonEnumAndStopsBeforeB() = runTest {
        val r = Rig(this)
        assertNull(r.refresh { r.fresh().copy(token = r.fresh().token.copy(contentDigest = "different")) })
        assertTrue(r.rows.any { it.step == ManualActionStep.SNAPSHOT_MATCH && it.mismatch == EvidenceMismatch.CONTENT })
        assertFalse(r.rows.any { it.step == ManualActionStep.B_DECISION }); assertEquals(0, r.f.clicks)
    }
    @Test fun changedNodeAndChangedOfferedTargetHaveDistinctActualRefreshRejections() = runTest {
        val r = Rig(this)
        assertNull(r.refresh { r.fresh().copy(nodes = persistentListOf(r.f.node.copy(enabled = false), r.body)) })
        assertTrue(r.rows.any { it.reason == ManualActionReason.NODES_CHANGED })
        val s = Rig(this)
        assertNull(s.refresh(display = s.shown.copy(closeCandidates = persistentListOf(s.shown.closeCandidates.single().copy(label = "창 닫기")))))
        assertTrue(s.rows.any { it.step == ManualActionStep.TARGET_MATCH && it.reason == ManualActionReason.TARGET_CHANGED })
        assertEquals(0, s.f.clicks)
    }
    @Test fun realBUserInteractionDenialIsNotMisreportedAsAnAttemptedF9Click() = runTest {
        val r = Rig(this); val e = requireNotNull(r.refresh()); r.access = r.access.copy(userInteracting = true)
        assertFalse(r.execute(e).attempted)
        assertTrue(r.rows.any { it.step == ManualActionStep.B_DECISION && it.state == ManualActionState.REJECTED && it.safety == SafetyReason.USER_INTERACTION })
        assertTrue(r.has(ManualActionStep.CLICK_ATTEMPT, ManualActionState.NOT_ATTEMPTED)); assertEquals(0, r.f.clicks)
    }
    @Test fun finalF9CandidateChangeAfterBApprovalReportsScreenUnverifiedAndZeroClicks() = runTest {
        val r = Rig(this); val e = requireNotNull(r.refresh())
        r.f.beforePress = { r.f.scene = r.f.scene?.copy(nodes = listOf(r.f.node.copy(text = "확인"), r.body)) }
        assertFalse(r.execute(e).attempted)
        assertTrue(r.has(ManualActionStep.B_DECISION, ManualActionState.APPROVED))
        assertTrue(r.rows.any { it.step == ManualActionStep.F9_VALIDATION && it.block == F9Block.SCREEN_UNVERIFIED })
        assertEquals(0, r.f.clicks)
    }
    @Test fun successfulRealManualPathSeparatesCRemovalF0BValidationAttemptAndConfirmedClosure() = runTest {
        val r = Rig(this); val e = requireNotNull(r.refresh()); val result = r.execute(e)
        assertTrue(result.attempted); assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
        for (step in listOf(ManualActionStep.OVERLAY_REMOVAL, ManualActionStep.SNAPSHOT_MATCH, ManualActionStep.TARGET_MATCH,
            ManualActionStep.F9_VALIDATION)) assertTrue(r.has(step, ManualActionState.PASSED))
        assertTrue(r.has(ManualActionStep.B_DECISION, ManualActionState.APPROVED))
        assertTrue(r.has(ManualActionStep.CLICK_ATTEMPT, ManualActionState.ATTEMPTED))
        assertTrue(r.rows.any { it.step == ManualActionStep.CLICK_RESULT && it.outcome == ClickOutcome.CLOSED_CONFIRMED })
        assertFalse(r.execute(e).attempted); assertEquals(1, r.f.clicks)
    }
    @Test fun cRemovalAndMechanicalTrueCannotBecomeOriginalPopupClosure() = runTest {
        val r = Rig(this); val e = requireNotNull(r.refresh())
        r.f.after = r.f.before.copy(nodes = e.snapshot.nodes, windows = e.snapshot.windows)
        val result = r.execute(e)
        assertTrue(r.has(ManualActionStep.OVERLAY_REMOVAL, ManualActionState.PASSED))
        assertEquals(1, r.f.clicks); assertEquals(ClickOutcome.STILL_PRESENT, result.outcome)
        assertTrue(r.rows.any { it.step == ManualActionStep.CLICK_RESULT && it.reason == ManualActionReason.DELIVERY_TRUE && it.outcome == ClickOutcome.STILL_PRESENT })
        assertFalse(r.rows.any { it.outcome == ClickOutcome.CLOSED_CONFIRMED })
    }
    @Test fun finalSettingsRevocationAndExpiredNewEvidenceStillDenyAfterBApproval() = runTest {
        for (mode in listOf("CONSENT", "TTL")) {
            val r = Rig(this); val e = requireNotNull(r.refresh())
            r.f.beforePress = {
                if (mode == "CONSENT") r.access = r.access.copy(collectionConsent = false) else r.f.now += 5_000
            }
            assertFalse(r.execute(e).attempted); assertEquals(0, r.f.clicks)
            assertTrue(r.rows.any { it.block == if (mode == "CONSENT") F9Block.SETTINGS else F9Block.STALE })
        }
    }
    @Test fun throwingOrCancellingDiagnosticSinkCannotChangePolicyOrRetryTheClick() = runTest {
        val r = Rig(this); val e = requireNotNull(r.refresh())
        val trace = ManualActionTrace { throw CancellationException("SYNTHETIC") }
        val result = r.execute(e, trace)
        assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome); assertEquals(1, r.f.clicks)
        assertFalse(r.execute(e, trace).attempted); assertEquals(1, r.f.clicks)
    }
    @Test fun cancellationAfterTheOnePressPreservesAttemptedUnknownInsteadOfZeroOrClosed() = runTest {
        val r = Rig(this); val e = requireNotNull(r.refresh())
        val work = launch { r.execute(e) }
        runCurrent(); assertEquals(1, r.f.clicks)
        work.cancelAndJoin(); assertTrue(work.isCancelled)
        assertTrue(r.rows.any { it.step == ManualActionStep.CLICK_RESULT && it.outcome == ClickOutcome.UNKNOWN })
        assertFalse(r.rows.any { it.step == ManualActionStep.CLICK_ATTEMPT && it.state == ManualActionState.NOT_ATTEMPTED })
        assertFalse(r.rows.any { it.outcome == ClickOutcome.CLOSED_CONFIRMED })
    }
    @Test fun noDiagnosticIoSplitsTheFinalValidationAndTheSinglePress() = runTest {
        val r = Rig(this); val e = requireNotNull(r.refresh()); var before = -1
        r.f.beforePress = { before = r.rows.size }
        r.f.afterPress = { assertEquals(before, r.rows.size) }
        assertTrue(r.execute(e).attempted); assertEquals(1, r.f.clicks)
        assertTrue(r.has(ManualActionStep.F9_VALIDATION, ManualActionState.PASSED))
    }
}
