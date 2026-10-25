package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.testing.mock.MockSnapshotSource
import kr.closeforyou.ui.voice.*
import kr.closeforyou.ui.voice.choice.*
import kr.closeforyou.ui.voice.integration.*
import kr.closeforyou.ui.voice.overlay.*
import kr.closeforyou.ui.voice.settings.*
import org.junit.Assert.*
import org.junit.Test

/** Actual #51 listener/resources/batcher; fake announcer/choice UI, no Android press. */
@OptIn(ExperimentalCoroutinesApi::class)
class F9FeedbackRouterTest {
    private val token = SnapshotToken(1, 1, "synthetic.app", "p", "s", "c", 1)
    private val execute = ActionDecision(token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), "close", source = DecisionSource.PRESET)
    private val closed = ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED)
    private fun report(result: ActionResult? = closed, source: DecisionSource = DecisionSource.PRESET) =
        HandlingReport(token, PopupClassification.COUPON, execute.copy(source = source), result, title = "비공개 제목")
    private fun waitReport() = HandlingReport(token, PopupClassification.COUPON,
        ActionDecision(token, ActionAuthorization.NEEDS_CONFIRMATION, persistentSetOf(SafetyReason.WAITING_FOR_TARGET),
            source = DecisionSource.PRESET, next = NextStep.WAIT))
    private class Announcer : PopupAnnouncer {
        val said = mutableListOf<AnnouncementRequest>()
        override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
            said += request; return AnnouncementReceipt(AnnouncementState.UI_UPDATED)
        }
        override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
        override fun stop() = Unit
    }
    private class Rig(scope: TestScope, key: (HandlingReport) -> String? = { null }) {
        val announcer = Announcer()
        val batcher = AnnouncementBatcher(scope, SpeechHandlingListener.batchFlush(announcer, Strings.format))
        val router = F9FeedbackRouter(announcer, batcher, Strings.format, key)
    }
    @Test fun realCListenerIsTheOnlyAutomaticVoiceAndReceivesNoTitle() = runTest {
        val r = Rig(this); r.router.deliver(report())
        assertTrue(r.announcer.said.isEmpty())
        advanceTimeBy(2_001); runCurrent()
        assertEquals(listOf("쿠폰을 닫았어요."), r.announcer.said.map { it.text })
        assertNull(r.announcer.said.single().token)
        assertFalse(r.announcer.said.single().text.contains("비공개"))
    }
    @Test fun missingOrUnattemptedResultNeverFallsBackToLegacySpeech() = runTest {
        val r = Rig(this); r.router.deliver(report(null))
        r.router.deliver(report(ActionResult(token, ActionStatus.BLOCKED, SafetyReason.UNVERIFIED_TARGET)))
        advanceTimeBy(2_001); runCurrent(); assertTrue(r.announcer.said.isEmpty())
    }
    @Test fun allThreeSuccessConditionsAreRequiredByTheActualCListener() = runTest {
        for (result in listOf(closed.copy(status = ActionStatus.ATTEMPT_FAILED), closed.copy(status = ActionStatus.OUTCOME_UNKNOWN),
            closed.copy(outcome = ClickOutcome.STILL_PRESENT), closed.copy(outcome = ClickOutcome.UNKNOWN))) {
            val r = Rig(this); r.router.deliver(report(result)); advanceTimeBy(2_001); runCurrent()
            assertEquals(1, r.announcer.said.size)
            assertFalse(r.announcer.said.single().text.contains("닫았어요"))
            assertTrue(r.announcer.said.single().text.contains("확인하지 못했어요"))
        }
    }
    @Test fun leftAppIsImmediateAndDoesNotEnterTheSuccessBatch() = runTest {
        val r = Rig(this); r.router.deliver(report(closed.copy(outcome = ClickOutcome.LEFT_APP)))
        assertEquals(1, r.announcer.said.size); assertTrue(r.announcer.said.single().text.contains("다른 화면"))
        advanceTimeBy(2_001); runCurrent(); assertEquals(1, r.announcer.said.size)
    }
    @Test fun unknownLiveWaitIdentityNeverUsesTheCMockKey() = runTest {
        val r = Rig(this); repeat(2) { r.router.deliver(waitReport()) }
        assertEquals(2, r.announcer.said.size)
    }
    @Test fun knownSyntheticWaitIsResetAtEachLifecycleBoundaryAndStopRejectsLateReports() = runTest {
        val r = Rig(this) { "synthetic-known-popup" }
        r.router.deliver(waitReport()); r.router.deliver(waitReport()); assertEquals(1, r.announcer.said.size)
        for (reset in listOf(r.router::onNewPopup, r.router::onPopupRemoved, r.router::onServiceReconnected)) {
            val before = r.announcer.said.size
            reset(); r.router.deliver(waitReport()); r.router.deliver(waitReport())
            assertEquals(before + 1, r.announcer.said.size)
        }
        r.router.close(); val before = r.announcer.said.size
        r.router.deliver(waitReport()); r.router.deliver(report())
        advanceTimeBy(2_001); runCurrent(); assertEquals(before, r.announcer.said.size)
    }
    @Test fun observedRemovalClearsWaitEvenWhenTheMechanicalPressFailed() = runTest {
        val r = Rig(this) { "synthetic-known-popup" }
        r.router.deliver(waitReport()); r.router.deliver(waitReport()); assertEquals(1, r.announcer.said.size)
        r.router.deliver(report(closed.copy(status = ActionStatus.ATTEMPT_FAILED)))
        r.router.deliver(waitReport()); assertEquals(2, r.announcer.said.size)
        advanceTimeBy(2_001); runCurrent(); assertFalse(r.announcer.said.any { it.text.contains("닫았어요") })
    }
    @Test fun contractAllowsOnlyPresetAndRememberedRuleReports() {
        report(source = DecisionSource.PRESET); report(source = DecisionSource.REMEMBERED_RULE)
        try { report(source = DecisionSource.USER_CHOICE); fail("USER_CHOICE must use PopupChoiceFlow") }
        catch (_: IllegalArgumentException) { /* shared contract, unchanged */ }
    }
    @Test fun actualChoiceFlowAndAutomaticListenerShareOneBatchAndOneResultSentence() = runTest {
        val r = Rig(this); val fixture = MockPopupFixtures.advertisement(); val source = MockSnapshotSource()
        var answer: ((PromptAnswer) -> Unit)? = null
        val presenter = object : ChoicePromptPresenter {
            override var isShowing = false
            override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
                isShowing = true; answer = onAnswer; return PromptShowResult.SHOWN
            }
            override fun dismiss() { isShowing = false }
        }
        val flow = PopupChoiceFlow(source, object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot) = fixture.analysis
        }, object : PopupClassifier {
            override suspend fun classify(analysis: PopupAnalysis) = PopupClassification.ADVERTISEMENT
        }, presenter, r.announcer, r.batcher, ChoiceHandler { _, analysis -> closed.copy(token = analysis.token) },
            UiText { "test-resource-$it" }, contentText = { "synthetic content" })
        val flowJob = launch { flow.run() }; runCurrent()
        source.emitFixture(fixture); runCurrent()
        requireNotNull(answer)(PromptAnswer.Picked(UserChoice(fixture.snapshot.token, ChoiceKind.CLOSE_ONCE,
            fixture.analysis.closeCandidates.single().id, buttonMeaningConfirmed = true), ChoiceOrigin.TAP,
            OverlayWindowKeeper.NO_GENERATION))
        runCurrent(); r.router.deliver(report()); assertTrue(r.announcer.said.isEmpty())
        advanceTimeBy(2_001); runCurrent()
        assertEquals(1, r.announcer.said.size); assertTrue(r.announcer.said.single().text.contains("2개"))
        flowJob.cancelAndJoin()
    }
    @Test fun allOperationalFeatureGatesRemainOff() {
        assertFalse(PresetAutoCloseFeature.live); assertFalse(RememberChoiceFeature.enabled)
        assertFalse(RevalidatingFeature.enabled); assertFalse(HandlingTitleSpeechFeature.enabled)
        val settings = UiVoiceSettings(onboardingDone = true, handlingPreset = HandlingPreset.CLOSE_ALL,
            autoCloseConsent = true, autoCloseConsentPreset = HandlingPreset.CLOSE_ALL)
        assertFalse(settings.autoCloseConsentNow().granted)
    }
}
