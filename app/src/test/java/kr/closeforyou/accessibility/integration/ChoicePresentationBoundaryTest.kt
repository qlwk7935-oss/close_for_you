package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.*
import kr.closeforyou.ui.voice.overlay.*
import kr.closeforyou.ui.voice.overlay.PromptAnnouncementListener
import kr.closeforyou.ui.voice.overlay.PromptAnnounceMode
import kr.closeforyou.ui.voice.settings.AnnouncementDetail
import org.junit.Assert.*
import org.junit.Test

/** These exercise the A port used by the real C flow; no framework add/remove is simulated as proven. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChoicePresentationBoundaryTest {
    private class Port : ChoicePromptPresenter {
        var showing = false
        var shows = 0
        var dismisses = 0
        var showFailure: Exception? = null
        var dismissFailure: Exception? = null
        var showingFailure: Exception? = null
        var duringShow: (() -> Unit)? = null
        var duringDismiss: (() -> Unit)? = null
        val callbacks = mutableListOf<(UserChoice) -> Unit>()
        var state = OverlayWindowState.HIDDEN
        var nextResult = PromptShowResult.SHOWN
        private var generation = 0L
        private var presenting = false
        val answers = mutableListOf<(PromptAnswer) -> Unit>()
        override val windowState: OverlayWindowState get() {
            showingFailure?.let { throw it }
            return state
        }
        override val isShowing get() = showing
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            val ownGeneration = ++generation
            shows++; showing = true; state = OverlayWindowState(OverlayWindowStatus.SHOWING, ownGeneration)
            answers += onAnswer
            callbacks += { choice ->
                if (!presenting && choice.kind != ChoiceKind.READ_CONTENT &&
                    state.status == OverlayWindowStatus.SHOWING && state.generation == ownGeneration) {
                    showing = false; state = OverlayWindowState.HIDDEN
                }
                onAnswer(PromptAnswer.Picked(choice, ChoiceOrigin.TAP, ownGeneration))
            }
            presenting = true
            try { duringShow?.invoke(); showFailure?.let { throw it } }
            finally { presenting = false }
            if (nextResult != PromptShowResult.SHOWN) {
                showing = false
                state = if (nextResult == PromptShowResult.BLOCKED)
                    OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, ownGeneration) else OverlayWindowState.HIDDEN
            }
            return nextResult
        }
        override fun dismiss() {
            dismisses++; showing = false
            duringDismiss?.invoke()
            dismissFailure?.let {
                state = OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, generation)
                throw it
            }
            state = OverlayWindowState.HIDDEN
        }
    }
    private class Fixture(val health: ChoicePresentationHealth = ChoicePresentationHealth()) {
        val input = MockPopupFixtures.notice()
        val prompt = ChoicePromptPlanner.plan(input.analysis, PopupClassification.NOTICE)
        var current: SnapshotToken? = prompt.token
        var interacting = false
        val port = Port()
        var failures = 0
        val accepted = mutableListOf<UserChoice>()
        val guard = GuardedChoicePresenter(port, { current }, { interacting }, health) { failures++ }
        fun choice(kind: PromptOptionKind = PromptOptionKind.PRESS_CANDIDATE): UserChoice =
            ChoicePromptPlanner.toUserChoice(prompt, prompt.options.first { it.kind == kind })
        fun show() = guard.show(prompt, {}) { accepted += it }
    }

    @Test fun partialShowCannotDeliverAndRawShowFailureIsNotExposed() {
        val f = Fixture()
        f.port.duringShow = { f.port.callbacks.last()(f.choice()) }
        f.port.showFailure = IllegalStateException("PRIVATE_BODY")
        val error = assertThrows(IllegalStateException::class.java) { f.show() }
        f.port.callbacks.last()(f.choice())
        assertEquals("CHOICE_PRESENTATION_FAILED", error.message)
        assertNull(error.cause); assertEquals(0, error.suppressed.size)
        assertTrue(f.health.failed); assertEquals(1, f.failures); assertTrue(f.accepted.isEmpty())
    }
    @Test fun normallyReturnedShowAloneActivatesItsCallback() {
        val f = Fixture()
        f.port.duringShow = { f.port.callbacks.last()(f.choice()) }
        f.show(); assertTrue(f.accepted.isEmpty())
        f.port.callbacks.last()(f.choice())
        assertEquals(1, f.accepted.size)
    }
    @Test fun dismissRevokesBeforeReentryAndFailureCannotEnableAnotherShow() {
        val f = Fixture(); f.show()
        val old = f.port.callbacks.single()
        f.port.duringDismiss = { old(f.choice()) }
        f.port.dismissFailure = IllegalStateException("PRIVATE_WINDOW")
        val error = assertThrows(IllegalStateException::class.java) { f.guard.dismiss() }
        old(f.choice())
        assertEquals("CHOICE_DISMISS_FAILED", error.message)
        assertTrue(f.health.failed); assertFalse(f.guard.isShowing); assertTrue(f.accepted.isEmpty())
        assertEquals(PromptShowResult.BLOCKED, f.show())
        assertEquals(1, f.port.shows); assertEquals(1, f.failures)
    }
    @Test fun sameTokenReplacementCannotReceiveThePreviousPromptCallback() {
        val f = Fixture(); f.show()
        val old = f.port.callbacks.last()
        f.show()
        old(f.choice(PromptOptionKind.READ_CONTENT)); old(f.choice())
        assertTrue(f.accepted.isEmpty())
        f.port.callbacks.last()(f.choice())
        assertEquals(1, f.accepted.size)
    }
    @Test fun unofferedCandidateRoleFlagsAndKindAreRejectedBeforeDelivery() {
        val f = Fixture(); f.show()
        val correct = f.choice(); val send = f.port.callbacks.last()
        send(correct.copy(candidateId = "SYNTHETIC_OTHER"))
        send(correct.copy(kind = ChoiceKind.SKIP_ONCE))
        send(correct.copy(buttonMeaningConfirmed = false))
        send(correct.copy(kind = ChoiceKind.REMEMBER_EXACT_POPUP, repeatAutomationExplicitlyAllowed = true))
        send(f.choice(PromptOptionKind.READ_CONTENT).copy(candidateId = correct.candidateId))
        assertTrue(f.accepted.isEmpty())
        send(correct); send(correct)
        assertEquals(listOf(correct), f.accepted)
    }
    @Test fun readCanRepeatButReentrantDeliveryAndConsumedTerminalChoiceCannot() {
        val f = Fixture()
        val read = f.choice(PromptOptionKind.READ_CONTENT)
        f.guard.show(f.prompt, {}) {
            f.accepted += it
            f.port.callbacks.last()(read)
        }
        val send = f.port.callbacks.last()
        send(read); send(read); send(f.choice()); send(read); send(f.choice())
        assertEquals(listOf(read, read, f.choice()), f.accepted)
    }
    @Test fun invalidationRevocationSurvivesAReappearingEqualToken() {
        val f = Fixture(); f.show()
        val old = f.port.callbacks.single()
        f.guard.revoke(); f.current = null; old(f.choice())
        f.current = f.prompt.token; old(f.choice())
        assertTrue(f.accepted.isEmpty()); assertFalse(f.health.failed)
    }
    @Test fun interactionAndDifferentCurrentTokenRejectAllOldChoices() {
        val f = Fixture(); f.show()
        val send = f.port.callbacks.single()
        f.interacting = true; send(f.choice(PromptOptionKind.READ_CONTENT)); send(f.choice())
        f.interacting = false; f.current = f.prompt.token.copy(screenVersion = 2); send(f.choice())
        assertTrue(f.accepted.isEmpty())
    }
    @Test fun callbackFailureClosesOnceWithoutEscapingRawExceptionIntoC() {
        val f = Fixture()
        f.guard.show(f.prompt, {}) { throw IllegalStateException("PRIVATE_CALLBACK") }
        val send = f.port.callbacks.single()
        send(f.choice()); send(f.choice())
        assertTrue(f.health.failed); assertEquals(1, f.failures)
    }
    @Test fun callbackCancellationRemainsCancellationAndRefusesReuse() {
        val f = Fixture(); val cancelled = CancellationException("SYNTHETIC_CANCEL")
        f.guard.show(f.prompt, {}) { throw cancelled }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { f.port.callbacks.single()(f.choice()) })
        assertTrue(f.health.failed); assertEquals(1, f.failures)
    }
    @Test fun showCancellationSurvivesAFinallyDismissFailure() {
        val f = Fixture(); val cancelled = CancellationException("SYNTHETIC_CANCEL")
        f.port.showFailure = cancelled; f.port.dismissFailure = IllegalStateException("PRIVATE_RELEASE")
        val actual = assertThrows(CancellationException::class.java) {
            try { f.show() } finally { f.guard.dismiss() }
        }
        assertSame(cancelled, actual); assertTrue(f.health.failed)
        assertEquals(1, f.port.dismisses); assertEquals(1, f.failures)
    }
    @Test fun showingGetterFailureIsFixedAndPermanentlyRefusesCallbacks() {
        val f = Fixture(); f.show()
        f.port.showingFailure = IllegalStateException("PRIVATE_GETTER")
        val error = assertThrows(IllegalStateException::class.java) { f.guard.isShowing }
        assertEquals("CHOICE_PRESENTATION_FAILED", error.message)
        f.port.callbacks.single()(f.choice())
        assertTrue(f.health.failed); assertTrue(f.accepted.isEmpty())
    }
    @Test fun uncertainRemovalCannotBeClearedByANewSessionOrEqualToken() {
        val old = Fixture(); old.show()
        old.port.dismissFailure = IllegalStateException("PRIVATE_RELEASE")
        assertThrows(IllegalStateException::class.java) { old.guard.dismiss() }
        val fresh = Fixture(old.health)
        assertEquals(PromptShowResult.BLOCKED, fresh.show())
        old.port.callbacks.single()(old.choice())
        assertEquals(0, fresh.port.shows); assertTrue(old.accepted.isEmpty()); assertTrue(fresh.accepted.isEmpty())
        assertEquals("ChoicePresentationHealth(redacted)", fresh.health.toString())
        assertEquals("GuardedChoicePresenter(redacted)", old.guard.toString())
    }
    @Test fun staleScreenDuringShowIsDismissedWithoutInventingRemovalFailure() {
        val f = Fixture(); f.port.duringShow = { f.current = null }
        assertEquals(PromptShowResult.BLOCKED, f.show())
        assertEquals(1, f.port.dismisses); assertFalse(f.health.failed)
        f.port.callbacks.single()(f.choice()); assertTrue(f.accepted.isEmpty())
    }
    @Test fun realCListenerUsesAFailureBoundStopPortAndDoesNotLeakStopFailure() = runTest {
        var failed = 0; val cleanup = mutableListOf<Int>()
        val delegate = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest) = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override fun stop() { throw IllegalStateException("PRIVATE_STOP") }
        }
        val releases = ChoiceResources(listOf({ cleanup += 1; error("PRIVATE_RELEASE") }, { cleanup += 2 }))
        val guarded = FailureBoundChoiceControls(delegate) { failed++; releases.close() }
        val listener = PromptAnnouncementListener(guarded, backgroundScope, { PromptAnnounceMode.PANE_TITLE_ONLY },
            700, { AnnouncementDetail.SHORT }, { _, _ -> "SYNTHETIC" })
        listener.onDismissed(); listener.onDismissed()
        assertEquals(1, failed); assertEquals(listOf(1, 2), cleanup); assertTrue(releases.failed)
    }

    @Test fun normalCListenerCancellationDoesNotCloseTheNextAnnouncement() = runTest {
        val f = Fixture(); var current = f.prompt.token; var failures = 0; var calls = 0
        val delegate = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
                calls++
                if (calls == 1) awaitCancellation()
                return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
            }
            override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override fun stop() = Unit
        }
        val bound = CurrentChoiceAnnouncer(delegate, { current }, {})
        val fenced = FailureBoundChoiceControls(bound) { failures++ }
        val listener = PromptAnnouncementListener(fenced, backgroundScope, { PromptAnnounceMode.PANE_TITLE_AND_TTS },
            700, { AnnouncementDetail.SHORT }, { _, _ -> "SYNTHETIC" })
        listener.onShown(f.prompt, { false })
        advanceTimeBy(700); runCurrent(); assertEquals(1, calls)
        listener.onDismissed(); current = current.copy(screenVersion = 2, captureEpoch = 2)
        listener.onShown(f.prompt.copy(token = current), { false })
        advanceTimeBy(700); runCurrent()
        assertEquals(2, calls); assertEquals(0, failures); assertTrue(bound.permitsSpeech())
    }
    @Test fun oldCancelledSpeechFailureCannotCloseAReplacementThroughTheOuterBoundary() = runTest {
        val f = Fixture(); var current = f.prompt.token; var failures = 0; var stopped = 0
        val finishOld = CompletableDeferred<Unit>(); val finishNew = CompletableDeferred<Unit>()
        val first = current
        val delegate = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
                if (request.token == first) {
                    withContext(NonCancellable) { finishOld.await() }
                    error("PRIVATE_LATE_ERROR")
                }
                finishNew.await()
                return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
            }
            override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override fun stop() { stopped++ }
        }
        val bound = CurrentChoiceAnnouncer(delegate, { current }, {})
        val fenced = FailureBoundChoiceControls(bound) { failures++ }
        val old = launch { fenced.announce(AnnouncementRequest(first, AnnouncementKind.CONTENT, "SYNTHETIC")) }
        runCurrent(); old.cancel(); current = current.copy(screenVersion = 2, captureEpoch = 2)
        val fresh = async { fenced.announce(AnnouncementRequest(current, AnnouncementKind.CONTENT, "SYNTHETIC")) }
        runCurrent(); finishOld.complete(Unit); runCurrent()
        assertTrue(old.isCancelled); assertEquals(0, failures); assertEquals(0, stopped)
        assertTrue(bound.permitsSpeech())
        finishNew.complete(Unit)
        assertEquals(AnnouncementState.TTS_COMPLETED, fresh.await().state)
    }
}
