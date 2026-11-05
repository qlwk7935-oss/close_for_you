package kr.closeforyou.accessibility.action

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.contract.ActionPolicy
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionActionPolicy
import org.junit.Assert.*
import org.junit.Test

/** A engine + real B session policy; automatic evidence is supplied ONLY by these JVM fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class F9ActionPolicyBridgeTest {
    private class Session {
        val f = F9AutoCloserTest.Fixture()
        val results = mutableListOf<ActionResult>()
        val reports = mutableListOf<HandlingReport>()
        var decisions = 0
        private val realPolicy = DecisionActionPolicy(allowAutoSkip = false, nowUptimeMillis = { f.now })
        val policy = object : ActionPolicy by realPolicy {
            override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext): ActionDecision {
                decisions++
                return realPolicy.decide(analysis, context)
            }
            override fun onActionResult(result: ActionResult) {
                results += result
                realPolicy.onActionResult(result)
            }
        }
        fun analysis(token: SnapshotToken = f.token) = PopupAnalysis(token, classification = PopupClassification.ADVERTISEMENT,
            texts = persistentListOf(ExtractedText("광고", f.bounds, TextSource.ACCESSIBILITY_TREE, null),
                ExtractedText("신상 운동화", f.bounds, TextSource.ACCESSIBILITY_TREE, null)),
            closeCandidates = persistentListOf(f.candidate),
            autoEligibility = AutoEligibility(token, EligibilityCheck.entries.toPersistentSet(), f.snapshot.capturedAtUptimeMillis))
        fun context(token: SnapshotToken = f.token) = DecisionContext(token, null, null, false, true,
            persistentSetOf(PopupClassification.ADVERTISEMENT))
        fun bridge() = F9ActionPolicyBridge(policy, evidence = { _, _ -> F9PolicyInput(analysis().copy(title = "private synthetic title"), context()) },
            onReport = { report ->
                if (report.result != null) assertEquals(report.result, results.last()) // B always precedes C.
                reports += report
            })
    }

    @Test fun confirmedClickReachesSameBInstanceOnceAndChangesItsNextDecision() = runTest {
        val s = Session(); val closer = s.f.closer(StandardTestDispatcher(testScheduler), s.bridge())
        val result = closer.execute(s.f.reference)
        assertTrue(result.closedConfirmed); assertEquals(1, s.f.clicks)
        assertEquals(listOf(result.toActionResult()), s.results)
        assertEquals(result.toActionResult(), s.reports.single().result)
        assertNull(s.reports.single().title)
        assertTrue(SafetyReason.REPEAT_NOT_ALLOWED in s.policy.decide(s.analysis(), s.context()).reasons)
    }

    @Test fun duplicateBeforeObservationCannotConsumeTheApprovedRequestsBResult() = runTest {
        val s = Session(); val closer = s.f.closer(StandardTestDispatcher(testScheduler), s.bridge())
        val inFlight = async { closer.execute(s.f.reference) }
        runCurrent() // B approved and the real press is waiting for its observation.
        assertEquals(1, s.f.clicks)
        assertFalse(closer.execute(s.f.reference).attempted)
        val result = inFlight.await()
        assertTrue(result.closedConfirmed); assertEquals(1, s.f.clicks)
        assertEquals(listOf(result.toActionResult()), s.results)
        assertEquals(1, s.reports.size) // The duplicate has no B receipt and no C report.
        assertTrue(SafetyReason.REPEAT_NOT_ALLOWED in s.policy.decide(s.analysis(), s.context()).reasons)
    }

    @Test fun noPressAtFinalGateFreesBPendingInsteadOfLeavingAwaitingResult() = runTest {
        val s = Session(); s.f.beforePress = { s.f.state = s.f.state.copy(cleanupReady = false) }
        val result = s.f.closer(StandardTestDispatcher(testScheduler), s.bridge()).execute(s.f.reference)
        assertFalse(result.attempted); assertEquals(0, s.f.clicks)
        assertEquals(listOf(result.toActionResult()), s.results)
        assertFalse(s.reports.single().result!!.attempted)
        val next = s.policy.decide(s.analysis(), s.context())
        assertEquals(NextStep.EXECUTE, next.next)
        assertFalse(SafetyReason.AWAITING_RESULT in next.reasons)
    }

    @Test fun failedOrUnknownClickWithObservedRemovalIsStillBFailure() = runTest {
        val s = Session(); s.f.returned = false
        val result = s.f.closer(StandardTestDispatcher(testScheduler), s.bridge()).execute(s.f.reference)
        assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
        assertFalse(result.closedConfirmed)
        assertEquals(ActionStatus.ATTEMPT_FAILED, s.results.single().status)
        assertTrue(SafetyReason.ALREADY_ATTEMPTED in s.policy.decide(s.analysis(), s.context()).reasons)
    }

    @Test fun departureReachesBAndLocksOtherPopupsInTheApp() = runTest {
        val s = Session(); s.f.after = s.f.before.copy(packageName = "other.synthetic.app")
        val result = s.f.closer(StandardTestDispatcher(testScheduler), s.bridge()).execute(s.f.reference)
        assertEquals(ClickOutcome.LEFT_APP, result.outcome); assertEquals(1, s.results.size)
        val token = s.f.token.copy(structureDigest = "other-popup")
        assertTrue(SafetyReason.LEFT_APP_BEFORE in s.policy.decide(s.analysis(token), s.context(token)).reasons)
    }

    @Test fun missingEvidenceConnectsResultsButCannotEnableAutomaticExecution() = runTest {
        val s = Session()
        val result = s.f.closer(StandardTestDispatcher(testScheduler), F9ActionPolicyBridge(s.policy)).execute(s.f.reference)
        assertFalse(result.attempted); assertEquals(0, s.f.clicks); assertEquals(0, s.decisions)
        assertEquals(listOf(result.toActionResult()), s.results)
        assertTrue(s.reports.isEmpty())
    }

    @Test fun cancellationAfterBApprovalStillReturnsOneNoPressResultAndOneSilentCReport() = runTest {
        val s = Session(); val closer = s.f.closer(StandardTestDispatcher(testScheduler), s.bridge())
        val job = launch { closer.execute(s.f.reference) }
        s.f.beforePress = { job.cancel() }
        runCurrent(); job.join()
        assertEquals(0, s.f.clicks); assertEquals(1, s.results.size)
        assertEquals(ActionStatus.NOT_EXECUTED, s.results.single().status)
        assertEquals(s.results.single(), s.reports.single().result)
        assertFalse(s.reports.single().result!!.attempted)
        assertFalse(SafetyReason.AWAITING_RESULT in s.policy.decide(s.analysis(), s.context()).reasons)
    }

    @Test fun onlyAutomaticSourcesCreateReportsAndF12ExecutionStaysBlocked() = runTest {
        for (source in DecisionSource.entries) {
            val s = Session(); val reports = mutableListOf<HandlingReport>()
            val suppliedPolicy = object : ActionPolicy {
                override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext) =
                    ActionDecision(analysis.token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), s.f.candidate.id, source)
            }
            val bridge = F9ActionPolicyBridge(suppliedPolicy, evidence = { _, _ -> F9PolicyInput(s.analysis(), s.context()) }, onReport = reports::add)
            val result = s.f.closer(StandardTestDispatcher(testScheduler), bridge).execute(s.f.reference)
            assertEquals(source == DecisionSource.PRESET, result.attempted)
            assertEquals(if (source == DecisionSource.USER_CHOICE) 0 else 1, reports.size)
            reports.forEach { assertNull(it.title); assertEquals(result.toActionResult(), it.result) }
        }
    }

    @Test fun waitDecisionProducesOnlyOneTitleFreeReportWithoutAnExecutionResult() = runTest {
        val s = Session(); val reports = mutableListOf<HandlingReport>()
        val suppliedPolicy = object : ActionPolicy {
            override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext) =
                ActionDecision(analysis.token, ActionAuthorization.NEEDS_CONFIRMATION, persistentSetOf(SafetyReason.WAITING_FOR_TARGET),
                    source = DecisionSource.PRESET, next = NextStep.WAIT)
        }
        val bridge = F9ActionPolicyBridge(suppliedPolicy, evidence = { _, _ -> F9PolicyInput(s.analysis(), s.context()) }, onReport = reports::add)
        assertFalse(s.f.closer(StandardTestDispatcher(testScheduler), bridge).execute(s.f.reference).attempted)
        assertEquals(NextStep.WAIT, reports.single().decision.next)
        assertNull(reports.single().result); assertNull(reports.single().title)
    }

    @Test fun automaticSkipStaysOffInBothBAndA() = runTest {
        val s = Session()
        val skip = s.f.candidate.copy(label = "건너뛰기", role = ButtonRole.SKIP)
        val skipAnalysis = s.analysis().copy(closeCandidates = persistentListOf(skip))
        assertNotEquals(NextStep.EXECUTE, s.policy.decide(skipAnalysis, s.context()).next)
        val node = s.f.node.copy(text = "건너뛰기")
        s.f.scene = s.f.before.copy(nodes = persistentListOf(node))
        val ref = s.f.reference.copy(candidate = skip, snapshot = s.f.snapshot.copy(nodes = persistentListOf(node)))
        val result = s.f.closer(StandardTestDispatcher(testScheduler), s.bridge()).execute(ref)
        assertFalse(result.attempted); assertEquals(0, s.f.clicks)
        assertTrue(s.results.isEmpty()) // A rejected SKIP before consulting B; no receipt to consume.
    }
}
