package kr.closeforyou.decision

import kr.closeforyou.core.contract.ActionExecutor
import kr.closeforyou.core.contract.ActionPolicy
import kr.closeforyou.core.contract.ActionRequest
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.testing.mock.NeverExecuteActionExecutor
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionChoiceHandlerTest {
    private val fixture = MockPopupFixtures.notice()
    private val token = fixture.snapshot.token
    // A's runtime marks every analysis like this and carries no candidates; B attaches them.
    private val event = PopupEvent.Candidate(fixture.snapshot, PopupDetection(token, persistentSetOf(PopupSignal.NEW_WINDOW)))
    private val evidence = fixture.analysis.copy(
        closeCandidates = persistentListOf(),
        limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED),
    )
    private val analysis = DecisionCandidateAttacher().attach(event, evidence)
    private val candidate = analysis.closeCandidates.single()

    private class FakeSource(var token: SnapshotToken?) : SnapshotSource {
        override val events: Flow<PopupEvent> = emptyFlow()
        override fun currentToken(): SnapshotToken? = token
    }

    private class RecordingExecutor(private val result: (ActionRequest) -> ActionResult) : ActionExecutor {
        val requests = mutableListOf<ActionRequest>()
        override suspend fun execute(request: ActionRequest): ActionResult {
            requests += request
            return result(request)
        }
    }

    private val success = { r: ActionRequest -> ActionResult(r.decision.token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true) }

    private fun handler(
        source: SnapshotSource = FakeSource(token),
        executor: ActionExecutor = RecordingExecutor(success),
        interacting: Boolean = false,
        announcement: Boolean = true,
        policy: ActionPolicy = DecisionActionPolicy(),
    ) = DecisionChoiceHandler(source, executor, { interacting }, { announcement }, policy)

    private fun press(kind: ChoiceKind = ChoiceKind.CLOSE_ONCE, confirmed: Boolean = true, id: String? = candidate.id) =
        UserChoice(token, kind, id, buttonMeaningConfirmed = confirmed)

    @Test fun confirmedPressOnAVerifiedButtonReachesTheGateExactlyOnce() = runBlocking {
        val executor = RecordingExecutor(success)
        val result = handler(executor = executor).handle(press(), analysis)
        assertEquals(ActionStatus.MECHANICAL_SUCCESS, result?.status)
        val request = executor.requests.single()
        assertEquals(ActionAuthorization.ALLOW_ONCE, request.decision.authorization)
        assertEquals(candidate, request.candidate)
        assertEquals(candidate.id, request.decision.candidateId)
    }

    @Test fun choicesThatPressNothingNeverReachTheGate() = runBlocking {
        val executor = RecordingExecutor(success)
        for (kind in listOf(ChoiceKind.KEEP_OPEN, ChoiceKind.READ_CONTENT, ChoiceKind.STOP_ASKING, ChoiceKind.REVOKE_RULE, ChoiceKind.REMEMBER_EXACT_POPUP)) {
            assertNull(kind.name, handler(executor = executor).handle(UserChoice(token, kind), analysis))
        }
        assertTrue(executor.requests.isEmpty())
    }

    @Test fun refusalsAreReportedWithoutCallingTheGate() = runBlocking {
        val executor = RecordingExecutor(success)
        fun check(result: ActionResult?, status: ActionStatus, reason: SafetyReason) {
            assertEquals(status, result?.status)
            assertEquals(reason, result?.reason)
            assertFalse(result!!.attempted)
            assertFalse(result.semanticSafetyConfirmedByUser)
            assertEquals(token, result.token)
        }
        check(handler(executor = executor).handle(press(confirmed = false), analysis), ActionStatus.NOT_EXECUTED, SafetyReason.EXPLICIT_PERMISSION_REQUIRED)
        check(handler(executor = executor, interacting = true).handle(press(), analysis), ActionStatus.BLOCKED, SafetyReason.USER_INTERACTION)
        check(handler(executor = executor).handle(press(kind = ChoiceKind.SKIP_ONCE), analysis), ActionStatus.BLOCKED, SafetyReason.UNVERIFIED_TARGET)
        check(handler(executor = executor).handle(press(id = "missing"), analysis), ActionStatus.BLOCKED, SafetyReason.UNVERIFIED_TARGET)
        val decisionScreen = analysis.copy(texts = persistentListOf(ExtractedText("결제 선택 필요", analysis.texts.single().bounds, TextSource.OCR, null)))
        check(handler(executor = executor).handle(press(), decisionScreen), ActionStatus.BLOCKED, SafetyReason.IMPORTANT_DECISION)
        assertTrue(executor.requests.isEmpty())
    }

    @Test fun aChangedScreenIsStaleBeforeOrDuringTheDecision() = runBlocking {
        val executor = RecordingExecutor(success)
        val other = MockPopupFixtures.advertisement().snapshot.token
        val before = handler(source = FakeSource(other), executor = executor).handle(press(), analysis)
        assertEquals(ActionStatus.STALE, before?.status)
        assertEquals(SafetyReason.STALE_SCREEN, before?.reason)

        // The screen changes while the policy is deciding: the final recheck must catch it.
        val source = FakeSource(token)
        val changingPolicy = object : ActionPolicy {
            override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext): ActionDecision =
                DecisionActionPolicy().decide(analysis, context).also { source.token = other }
        }
        val during = handler(source = source, executor = executor, policy = changingPolicy).handle(press(), analysis)
        assertEquals(ActionStatus.STALE, during?.status)
        assertTrue(executor.requests.isEmpty())
    }

    @Test fun aFailingGateIsNotRetriedAndKeepsNoMessage() = runBlocking {
        var calls = 0
        val failing = object : ActionExecutor {
            override suspend fun execute(request: ActionRequest): ActionResult {
                calls++
                throw IllegalStateException("screen text that must not leak")
            }
        }
        val result = handler(executor = failing).handle(press(), analysis)
        assertEquals(1, calls)
        assertEquals(ActionStatus.OUTCOME_UNKNOWN, result?.status)
        assertNull(result?.reason)
        assertTrue("possibly attempted, so nobody retries", result!!.attempted)
        assertFalse(result.toString().contains("screen text"))
    }

    @Test(expected = CancellationException::class)
    fun cancellationPassesThrough(): Unit = runBlocking {
        val cancelling = object : ActionExecutor {
            override suspend fun execute(request: ActionRequest): ActionResult = throw CancellationException("cancelled")
        }
        handler(executor = cancelling).handle(press(), analysis)
        Unit
    }

    @Test fun todaysNonExecutingGateResultIsPassedThroughUnchanged() = runBlocking {
        val expected = ActionResult(token, ActionStatus.NOT_EXECUTED, SafetyReason.NOT_IMPLEMENTED)
        val result = handler(executor = NeverExecuteActionExecutor()).handle(press(), analysis)
        assertEquals(expected, result)
    }

    @Test fun attacherAddsCandidatesOnlyForTheSameScreen() {
        assertEquals(listOf("node:1"), analysis.closeCandidates.map { it.id })

        val other = MockPopupFixtures.advertisement().analysis
        assertTrue(DecisionCandidateAttacher().attach(event, other).closeCandidates.isEmpty())
        val staleDetection = PopupEvent.Candidate(fixture.snapshot, PopupDetection(other.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
        assertTrue(DecisionCandidateAttacher().attach(staleDetection, evidence).closeCandidates.isEmpty())

        // Candidates already on the analysis are replaced by B's finder, never merged.
        val preset = evidence.copy(closeCandidates = persistentListOf(candidate.copy(id = "from-elsewhere")))
        assertEquals(listOf("node:1"), DecisionCandidateAttacher().attach(event, preset).closeCandidates.map { it.id })

        // A's decision-summary path publishes no nodes, so nothing can be pressed.
        val summary = PopupEvent.Candidate(fixture.snapshot.copy(nodes = persistentListOf()), event.detection)
        assertTrue(DecisionCandidateAttacher().attach(summary, evidence).closeCandidates.isEmpty())
    }

    @Test fun flowShapedLikeCsPromptWorksEndToEnd() = runBlocking {
        // event -> A extract -> B attach -> B classify -> person taps the named button -> B handler
        val classified = analysis.copy(classification = DecisionPopupClassifier().classify(analysis))
        assertEquals(PopupClassification.NOTICE, classified.classification)
        val executor = RecordingExecutor(success)
        val result = handler(executor = executor).handle(press(), classified)
        assertEquals(ActionStatus.MECHANICAL_SUCCESS, result?.status)
        assertSame(candidate, executor.requests.single().candidate)
    }
}
