package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.*
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.overlay.*
import kr.closeforyou.ui.voice.settings.AnnouncementDetail
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManualPromptSpeechTest {
    private class Engine : SpeechEngine {
        val state = MutableStateFlow(SpeechReadiness.INITIALIZING)
        override val readiness: StateFlow<SpeechReadiness> = state
        var calls = 0; var stops = 0
        override suspend fun speak(text: String): Boolean { calls++; return true }
        override fun stop() { stops++ }
        override fun shutdown() { stop(); state.value = SpeechReadiness.UNAVAILABLE }
    }
    private class Rig(val scope: TestScope) {
        val prompt = ChoicePromptPlanner.plan(MockPopupFixtures.notice().analysis, PopupClassification.NOTICE)
        val engine = Engine()
        val tts = TtsPopupAnnouncer(engine, "SYNTHETIC")
        val lease = ManualPromptLifetime { 1_000 + scope.testScheduler.currentTime }.also {
            it.shown(prompt.token, OverlayWindowState(OverlayWindowStatus.SHOWING, 1))
        }
        val codes = mutableListOf<ManualSpeechState>()
        val announcer = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest) = announceManualPrompt(engine.state,
                { lease.current() == request.token }, codes::add) { tts.announce(request) }
            override suspend fun repeatLast() = tts.repeatLast()
            override fun stop() = tts.stop()
        }
        fun listener(mode: PromptAnnounceMode = PromptAnnounceMode.PANE_TITLE_AND_TTS) = PromptAnnouncementListener(
            announcer, scope.backgroundScope, { mode }, 700L, { AnnouncementDetail.SHORT }, { _, _ -> "SYNTHETIC" })
    }
    @Test fun actualCListenerAndAAnnouncerWaitForInitializationAndSpeakOnceAtSixSeconds() = runTest {
        val r = Rig(this); val listener = r.listener(); listener.onShown(r.prompt) { false }
        advanceTimeBy(700); runCurrent()
        assertEquals(0, r.engine.calls); assertTrue(ManualSpeechState.WAITING_FOR_ENGINE in r.codes)
        advanceTimeBy(5_300); r.engine.state.value = SpeechReadiness.READY; runCurrent()
        assertEquals(1, r.engine.calls); assertEquals(r.prompt.token, r.lease.current())
        assertTrue(ManualSpeechState.COMPLETED in r.codes)
        r.engine.state.value = SpeechReadiness.READY; runCurrent(); assertEquals(1, r.engine.calls)
    }
    @Test fun dismissalBeforeTheExistingSevenHundredMillisecondDelayDoesNotIssueSpeech() = runTest {
        val r = Rig(this); val listener = r.listener(); r.engine.state.value = SpeechReadiness.READY
        listener.onShown(r.prompt) { false }; advanceTimeBy(600); listener.onDismissed()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, r.engine.calls); assertTrue(r.codes.isEmpty()); assertEquals(1, r.engine.stops)
    }
    @Test fun dismissalWhileInitializingCancelsAndNeverSpeaksAfterReady() = runTest {
        val r = Rig(this); val listener = r.listener(); listener.onShown(r.prompt) { false }
        advanceTimeBy(700); runCurrent(); listener.onDismissed(); runCurrent()
        r.engine.state.value = SpeechReadiness.READY; runCurrent()
        assertEquals(0, r.engine.calls); assertTrue(ManualSpeechState.CANCELLED in r.codes)
        assertFalse(ManualSpeechState.COMPLETED in r.codes)
    }
    @Test fun expiredDisplayOrInvalidatedTokenIsCheckedAgainAfterEngineInitialization() = runTest {
        for (expiry in listOf(false, true)) {
            val r = Rig(this); val listener = r.listener(); listener.onShown(r.prompt) { false }
            advanceTimeBy(700); runCurrent()
            if (expiry) advanceTimeBy(30_000) else r.lease.clear()
            r.engine.state.value = SpeechReadiness.READY; runCurrent()
            assertEquals(0, r.engine.calls); assertTrue(ManualSpeechState.STALE in r.codes)
            listener.onDismissed()
        }
    }
    @Test fun genuineUnavailableEngineIsReportedWithoutForcingAudioOrRetrying() = runTest {
        val r = Rig(this); r.engine.state.value = SpeechReadiness.UNAVAILABLE
        val listener = r.listener(); listener.onShown(r.prompt) { false }; advanceTimeBy(700); runCurrent()
        assertTrue(ManualSpeechState.UNAVAILABLE in r.codes); assertTrue(ManualSpeechState.NOT_DELIVERED in r.codes)
        assertEquals(0, r.engine.calls)
        r.engine.state.value = SpeechReadiness.READY; advanceTimeBy(2_000); runCurrent(); assertEquals(0, r.engine.calls)
    }
    @Test fun paneOnlyPreferenceRemainsRespectedInsteadOfForcingOwnTts() = runTest {
        val r = Rig(this); r.engine.state.value = SpeechReadiness.READY
        r.listener(PromptAnnounceMode.PANE_TITLE_ONLY).onShown(r.prompt) { false }
        advanceTimeBy(1_000); runCurrent(); assertEquals(0, r.engine.calls); assertTrue(r.codes.isEmpty())
    }
    @Test fun serviceCancellationWhileWaitingPropagatesAndCannotBecomeCompletion() = runTest {
        val states = MutableStateFlow(SpeechReadiness.INITIALIZING); val codes = mutableListOf<ManualSpeechState>()
        var calls = 0
        val job = launch { announceManualPrompt(states, { true }, codes::add) {
            calls++; AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        } }
        runCurrent(); job.cancelAndJoin(); states.value = SpeechReadiness.READY; runCurrent()
        assertTrue(job.isCancelled); assertEquals(0, calls); assertTrue(ManualSpeechState.CANCELLED in codes)
    }
    @Test fun throwingDiagnosticSinkCannotPreventDeliveryOrReplacePrimaryCancellation() = runTest {
        val ready = MutableStateFlow(SpeechReadiness.READY)
        val receipt = announceManualPrompt(ready, { true }, { throw CancellationException("SYNTHETIC_SINK") }) {
            AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        assertEquals(AnnouncementState.TTS_COMPLETED, receipt.state)
        val primary = CancellationException("SYNTHETIC_PRIMARY")
        try {
            announceManualPrompt(ready, { true }, { error("SYNTHETIC_SINK") }) { throw primary }
            fail("CANCELLATION_REQUIRED")
        } catch (actual: CancellationException) { assertSame(primary, actual) }
    }
    @Test fun backendFailureIsUndeliveredWithFixedDiagnosisAndCannotCrashTheService() = runTest {
        val codes = mutableListOf<ManualSpeechState>(); val failure = IllegalStateException("SYNTHETIC")
        val receipt = announceManualPrompt(MutableStateFlow(SpeechReadiness.READY), { true }, codes::add) { throw failure }
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, receipt.state)
        assertTrue(ManualSpeechState.FAILED in codes); assertFalse(ManualSpeechState.COMPLETED in codes)
    }
    @Test fun newerContentRequestWinsWhileInitializationIsPending() = runTest {
        val states = MutableStateFlow(SpeechReadiness.INITIALIZING); val requests = ManualSpeechRequests()
        var oldCalls = 0; var newCalls = 0
        val first = requests.replace()
        val old = async { announceManualPrompt(states, { requests.permits(first) }, {}) {
            oldCalls++; AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        } }
        runCurrent(); val second = requests.replace()
        val current = async { announceManualPrompt(states, { requests.permits(second) }, {}) {
            newCalls++; AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        } }
        runCurrent(); states.value = SpeechReadiness.READY; runCurrent()
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, old.await().state)
        assertEquals(AnnouncementState.TTS_COMPLETED, current.await().state)
        assertEquals(0, oldCalls); assertEquals(1, newCalls)
    }
    @Test fun stopFencesDelayedReadyEvenWhenTheInitializationCallbackArrivesLater() = runTest {
        val states = MutableStateFlow(SpeechReadiness.INITIALIZING); val requests = ManualSpeechRequests()
        val first = requests.replace(); var calls = 0
        val job = async { announceManualPrompt(states, { requests.permits(first) }, {}) {
            calls++; AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        } }
        runCurrent(); requests.stop(); states.value = SpeechReadiness.READY; runCurrent()
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, job.await().state); assertEquals(0, calls)
    }
}
