package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.LiveEvidenceState
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.*
import kr.closeforyou.ui.voice.choice.*
import kr.closeforyou.ui.voice.integration.UiText
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NonExecutingChoiceIntegrationTest {
    private class Presenter : ChoicePromptPresenter {
        var prompt: ChoicePrompt? = null; var callback: ((UserChoice) -> Unit)? = null; var shows = 0
        var showFailure: Exception? = null; var dismissFailure: Exception? = null
        var duringDismiss: (() -> Unit)? = null
        var state = OverlayWindowState.HIDDEN
        var nextResult = PromptShowResult.SHOWN
        private var generation = 0L
        var answerCallback: ((PromptAnswer) -> Unit)? = null
        override val windowState get() = state
        override val isShowing get() = state.status == OverlayWindowStatus.SHOWING
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            val ownGeneration = ++generation
            this.prompt = prompt; shows++
            state = OverlayWindowState(OverlayWindowStatus.SHOWING, ownGeneration)
            answerCallback = onAnswer
            callback = { choice ->
                if (choice.kind != ChoiceKind.READ_CONTENT && state.status == OverlayWindowStatus.SHOWING &&
                    state.generation == ownGeneration) { this.prompt = null; state = OverlayWindowState.HIDDEN }
                onAnswer(PromptAnswer.Picked(choice, ChoiceOrigin.TAP, ownGeneration))
            }
            showFailure?.let { throw it }
            if (nextResult != PromptShowResult.SHOWN) {
                this.prompt = null
                state = if (nextResult == PromptShowResult.BLOCKED)
                    OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, ownGeneration) else OverlayWindowState.HIDDEN
            }
            return nextResult
        }
        override fun dismiss() {
            duringDismiss?.invoke()
            prompt = null
            dismissFailure?.let {
                state = OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, generation)
                throw it
            }
            state = OverlayWindowState.HIDDEN
        }
        fun pick(kind: PromptOptionKind) {
            val p = requireNotNull(prompt)
            val selection = ChoicePromptPlanner.select(p, p.options.first { it.kind == kind }) as PromptSelection.Choice
            if (kind != PromptOptionKind.READ_CONTENT) dismiss()
            callback?.invoke(selection.choice)
        }
    }
    private class Speech : PopupAnnouncer {
        val said = mutableListOf<AnnouncementRequest>(); var stops = 0
        override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
            said += request; return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        override fun stop() { stops++ }
    }
    private class Harness(val scope: TestScope, val health: ChoicePresentationHealth = ChoicePresentationHealth()) {
        var allowed = true; var interacting = false; var extractions = 0
        var extractionWait: CompletableDeferred<Unit>? = null
        val live = LiveEvidenceState({ 1_000 + scope.testScheduler.currentTime }, { allowed })
        val bus = MutableSharedFlow<PopupEvent>(replay = 0, extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST)
        val subscription = ChoiceSubscription()
        val presenter = Presenter(); val speech = Speech(); var cleanup = 0
        val source = object : SnapshotSource {
            override val events = bus.onSubscription { subscription.subscribed() }
            override fun currentToken() = live.currentToken()
        }
        val extractor = object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
                extractions++; extractionWait?.await()
                currentCoroutineContext().ensureActive()
                return live.extract(snapshot) ?: throw CancellationException("STALE_SYNTHETIC")
            }
        }
        val session = NonExecutingChoiceSession(source, extractor, presenter, speech, { interacting }, { true },
            { 1_000 + scope.testScheduler.currentTime }, UiText { "CODE_$it" }, { "SYNTHETIC_BODY" },
            ChoiceResources(listOf({ presenter.dismiss() }, { speech.stop() }, { cleanup++ })), health)
        var failureCode: String? = null
        val job = scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { session.run() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: IllegalStateException) { failureCode = failure.message }
        }
        suspend fun emit(version: Long = 1, important: Boolean = false,
            signals: kotlinx.collections.immutable.ImmutableSet<PopupSignal> = persistentSetOf(PopupSignal.NEW_WINDOW)) {
            subscription.await()
            val f = if (important) MockPopupFixtures.decision() else MockPopupFixtures.notice()
            val token = f.snapshot.token.copy(screenVersion = version, captureEpoch = version)
            val snapshot = f.snapshot.copy(token = token, capturedAtUptimeMillis = 1_000 + scope.testScheduler.currentTime)
            val detection = PopupDetection(token, signals, requiresUserReview = true)
            live.connect(); live.invalidate(version)
            val permit = requireNotNull(live.begin(snapshot))
            check(live.publish(permit, snapshot, detection, f.analysis.copy(token = token), EvidenceIssue.NONE))
            check(live.deliver(permit, snapshot) { bus.tryEmit(PopupEvent.Candidate(snapshot, detection)) })
            scope.runCurrent()
        }
    }
    @Test fun replayZeroConsumerRegistersBeforeFirstActualStatePublication() = runTest {
        val h = Harness(this); runCurrent(); h.subscription.await()
        assertEquals(1, h.bus.subscriptionCount.value)
        h.emit(); assertNotNull(h.presenter.prompt)
    }
    @Test fun syntheticP01UsesRealBCandidatesCChoiceAndAReviewWithoutExecution() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val p = requireNotNull(h.presenter.prompt)
        assertEquals(PromptSummary.LIKELY_NOTICE, p.summary)
        assertTrue(p.options.any { it.kind == PromptOptionKind.PRESS_CANDIDATE && it.buttonLabel == "닫기" })
        h.presenter.pick(PromptOptionKind.PRESS_CANDIDATE); runCurrent()
        assertFalse("PRESENTATION_HEALTH_FAILED", h.health.failed)
        assertNull(h.failureCode)
        assertTrue("SESSION_CLOSED", h.job.isActive)
        assertEquals(1, h.session.executor.reviews)
        assertTrue(h.speech.said.any { it.kind == AnnouncementKind.ACTION_RESULT })
        assertEquals(PressFailure.NOT_PRESSED, h.presenter.prompt?.pressFailure)
        assertFalse(h.presenter.prompt!!.options.any { it.kind == PromptOptionKind.PRESS_CANDIDATE })
    }
    @Test fun importantDecisionOffersNoPressRememberOrFocusMovement() = runTest {
        val h = Harness(this); runCurrent(); h.emit(important = true)
        assertEquals(PromptSummary.NEEDS_REVIEW, h.presenter.prompt?.summary)
        assertTrue(h.presenter.prompt!!.options.all { it.kind in setOf(PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN) })
        assertEquals(0, h.session.executor.reviews)
    }
    @Test fun blockedCollectionDoesNotOfferAnyChoice() = runTest {
        val h = Harness(this); runCurrent()
        h.bus.emit(PopupEvent.Blocked(PrivacyBlockReason.PASSWORD, 1)); runCurrent()
        assertNull(h.presenter.prompt); assertEquals(0, h.extractions); assertEquals(0, h.session.executor.reviews)
    }
    @Test fun uncertainCandidateCannotBeOfferedOrBecomeAnActionRequest() = runTest {
        val h = Harness(this); runCurrent()
        h.emit(signals = persistentSetOf(PopupSignal.INSUFFICIENT_EVIDENCE))
        assertNull(h.presenter.prompt); assertEquals(0, h.extractions)
    }
    @Test fun screenChangeDropsPromptAndAnOldChoiceCallback() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val p = h.presenter.prompt!!; val old = h.presenter.callback!!
        val choice = ChoicePromptPlanner.toUserChoice(p, p.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        h.live.invalidate(2); h.bus.emit(PopupEvent.Invalidated(2)); runCurrent()
        old(choice); runCurrent()
        assertNull(h.presenter.prompt); assertEquals(0, h.session.executor.reviews)
    }
    @Test fun exactOriginalTtlRejectsStaleUserChoice() = runTest {
        val h = Harness(this); runCurrent(); h.emit(); advanceTimeBy(5_000); runCurrent()
        h.presenter.pick(PromptOptionKind.PRESS_CANDIDATE); runCurrent()
        assertNull(h.live.currentToken()); assertEquals(0, h.session.executor.reviews)
    }
    @Test fun policyRevocationAndServiceDisconnectDiscardPendingAnalysis() = runTest {
        for (disconnect in listOf(false, true)) {
            val h = Harness(this); runCurrent(); h.extractionWait = CompletableDeferred(); h.emit()
            if (disconnect) h.live.disconnect() else h.allowed = false
            h.extractionWait!!.complete(Unit); runCurrent()
            assertNull(h.presenter.prompt); assertEquals(0, h.session.executor.reviews)
            h.job.cancel(); runCurrent()
        }
    }
    @Test fun slowOldEvidenceCannotReplaceNextPopup() = runTest {
        val h = Harness(this); runCurrent(); h.extractionWait = CompletableDeferred(); h.emit()
        val oldWait = h.extractionWait!!
        h.extractionWait = null; h.emit(version = 2)
        oldWait.complete(Unit); runCurrent()
        assertEquals(2L, h.presenter.prompt?.token?.screenVersion); assertEquals(1, h.presenter.shows)
    }
    @Test fun duplicateCandidateDoesNotOpenASecondPrompt() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val f = MockPopupFixtures.notice(); val t = requireNotNull(h.live.currentToken())
        h.bus.emit(PopupEvent.Candidate(f.snapshot.copy(token = t), PopupDetection(t, persistentSetOf(PopupSignal.NEW_WINDOW), true)))
        runCurrent(); assertEquals(1, h.presenter.shows); assertEquals(1, h.extractions)
    }
    @Test fun userInteractionRejectsCallbackBeforeBOrAGate() = runTest {
        val h = Harness(this); runCurrent(); h.emit(); h.interacting = true
        h.presenter.pick(PromptOptionKind.PRESS_CANDIDATE); runCurrent()
        assertEquals(0, h.session.executor.reviews); assertNull(h.presenter.prompt?.pressFailure)
        assertFalse(h.speech.said.any { it.kind == AnnouncementKind.ACTION_RESULT })
    }
    @Test fun serviceOwnedSessionCancellationClosesChoicesAndSpeech() = runTest {
        val h = Harness(this); runCurrent(); h.emit(); h.job.cancel(); runCurrent()
        assertTrue(h.job.isCancelled); assertNull(h.presenter.prompt); assertEquals(1, h.cleanup)
    }
    @Test fun independentCleanupTriesEveryReleaseAndKeepsFailureSticky() {
        val seen = mutableListOf<Int>()
        val resources = ChoiceResources(listOf({ seen += 1; throw IllegalStateException("PRIVATE") },
            { seen += 2; throw CancellationException("PRIVATE") }, { seen += 3 }))
        assertFalse(resources.close()); assertFalse(resources.close())
        assertEquals(listOf(1, 2, 3), seen); assertTrue(resources.failed)
    }
    @Test fun repeatCannotReadEarlierPopupHistoryAfterInvalidation() = runTest {
        val f = MockPopupFixtures.notice(); var current: SnapshotToken? = f.snapshot.token
        val engine = object : SpeechEngine {
            override val readiness = MutableStateFlow(SpeechReadiness.READY)
            var calls = 0
            override suspend fun speak(text: String): Boolean { calls++; return true }
            override fun stop() {}
            override fun shutdown() {}
        }
        val history = AnnouncementHistory(1)
        val bound = CurrentChoiceAnnouncer(TtsPopupAnnouncer(engine, "NONE", history), { current }, history::clear)
        bound.announce(AnnouncementRequest(current, AnnouncementKind.CONTENT, "SYNTHETIC_BODY"))
        current = null
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, bound.repeatLast().state)
        assertEquals(1, engine.calls); assertEquals(0, history.size())
    }
    @Test fun onlyProvenOwnHarmlessOverlayEventsCanBeExcluded() {
        assertTrue(OwnedOverlayEventPolicy.mayIgnore(OverlayEventKind.CONTENT, true, true, true, false, true))
        for (kind in listOf(OverlayEventKind.INTERACTION, OverlayEventKind.UNKNOWN))
            assertFalse(OwnedOverlayEventPolicy.mayIgnore(kind, true, true, true, false, true))
        assertFalse(OwnedOverlayEventPolicy.mayIgnore(OverlayEventKind.CONTENT, false, true, true, false, true))
        assertFalse(OwnedOverlayEventPolicy.mayIgnore(OverlayEventKind.CONTENT, true, false, true, false, true))
        assertFalse(OwnedOverlayEventPolicy.mayIgnore(OverlayEventKind.CONTENT, true, true, false, false, true))
        assertFalse(OwnedOverlayEventPolicy.mayIgnore(OverlayEventKind.CONTENT, true, true, true, false, false))
        assertFalse(OwnedOverlayEventPolicy.mayIgnore(OverlayEventKind.CONTENT, true, true, false, true, true))
        assertTrue(OwnedOverlayEventPolicy.mayIgnore(OverlayEventKind.OWN_WINDOW_LAYOUT, true, true, false, true, true))
    }
    @Test fun arbitraryAllowRequestStillReturnsNotExecutedAndNeverAttempted() = runTest {
        val f = MockPopupFixtures.notice(); val candidate = f.analysis.closeCandidates.single()
        val result = ReviewOnlyChoiceExecutor { 1_000 }.execute(ActionRequest(
            ActionDecision(f.snapshot.token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), candidate.id), candidate))
        assertEquals(ActionStatus.NOT_EXECUTED, result.status); assertFalse(result.attempted)
        assertFalse(result.semanticSafetyConfirmedByUser)
    }
    @Test fun delayedOwnedWindowAddCanBindButExistingAmbiguousOrReusedWindowsCannot() {
        val token = MockPopupFixtures.notice().snapshot.token
        val binding = OwnedOverlayBinding()
        binding.expect(setOf(10), token, 1L)
        assertFalse(binding.matches(20, setOf(10), token, 1L))
        assertTrue(binding.matches(20, setOf(10,20), token, 1L))
        assertFalse(binding.matches(20, setOf(10), token, 1L))
        assertFalse(binding.matches(20, setOf(10,20), token.copy(screenVersion = 2), 1L))
        binding.expect(setOf(10,20), token, 1L)
        assertFalse(binding.matches(20, setOf(10,20), token, 1L))
        binding.expect(emptySet(), token, 1L)
        assertFalse(binding.matches(20, setOf(20,30), token, 1L))
        binding.clear(); assertFalse(binding.matches(20, setOf(20), token, 1L))
    }
    @Test fun reconnectCannotReviveAnOldSessionChoiceEvenWithEqualTokens() = runTest {
        val old = Harness(this); runCurrent(); old.emit()
        val p = old.presenter.prompt!!; val callback = old.presenter.callback!!
        val choice = ChoicePromptPlanner.toUserChoice(p,p.options.first {it.kind==PromptOptionKind.PRESS_CANDIDATE})
        old.job.cancel(); runCurrent()
        val fresh = Harness(this); runCurrent(); fresh.emit()
        callback(choice); runCurrent()
        assertEquals(0,old.session.executor.reviews); assertEquals(0,fresh.session.executor.reviews)
        assertNotNull(fresh.presenter.prompt)
    }
    @Test fun localVoiceMetadataMustBeKnownInstalledAndStillSelected() {
        assertTrue(OfflineVoiceCheck.usable(true,false,true,true))
        assertFalse(OfflineVoiceCheck.usable(true,true,true,true))
        assertFalse(OfflineVoiceCheck.usable(true,null,true,true))
        assertFalse(OfflineVoiceCheck.usable(false,false,true,true))
        assertFalse(OfflineVoiceCheck.usable(true,false,false,true))
        assertFalse(OfflineVoiceCheck.usable(true,false,true,false))
    }
    @Test fun closedAnnouncerNeverSpeaksOrRepeatsEvenIfSameTokenReturns() = runTest {
        val token = MockPopupFixtures.notice().snapshot.token
        val speech = Speech(); var forgotten = 0
        val bound = CurrentChoiceAnnouncer(speech,{token},{forgotten++})
        bound.announce(AnnouncementRequest(token,AnnouncementKind.POPUP_FOUND,"SYNTHETIC"))
        bound.dispose()
        assertEquals(AnnouncementState.NOT_IMPLEMENTED,bound.repeatLast().state)
        assertEquals(AnnouncementState.NOT_IMPLEMENTED,bound.announce(AnnouncementRequest(token,AnnouncementKind.CONTENT,"SYNTHETIC")).state)
        assertEquals(1,speech.said.size);assertTrue(forgotten>0)
    }
    @Test fun lateOldSpeechCancellationCannotStopOrForgetNewPopupSpeech() = runTest {
        val first = MockPopupFixtures.notice().snapshot.token
        val second = first.copy(screenVersion = 2, captureEpoch = 2)
        var current = first
        val oldFinished = CompletableDeferred<Unit>(); val newFinished = CompletableDeferred<Unit>()
        var stops = 0; var forgotten = 0
        val delegate = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
                if (request.token == first) withContext(NonCancellable) { oldFinished.await() }
                else newFinished.await()
                return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
            }
            override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override fun stop() { stops++ }
        }
        val bound = CurrentChoiceAnnouncer(delegate, { current }, { forgotten++ })
        var boundaryFailures = 0
        val fenced = FailureBoundChoiceControls(bound) { boundaryFailures++ }
        val old = launch { fenced.announce(AnnouncementRequest(first, AnnouncementKind.CONTENT, "SYNTHETIC_OLD")) }
        runCurrent(); old.cancel(); current = second
        val fresh = async { fenced.announce(AnnouncementRequest(second, AnnouncementKind.CONTENT, "SYNTHETIC_NEW")) }
        runCurrent(); oldFinished.complete(Unit); runCurrent()
        assertTrue(old.isCancelled); assertEquals(0, stops); assertEquals(0, forgotten)
        assertTrue(bound.permitsSpeech())
        assertEquals(0, boundaryFailures)
        newFinished.complete(Unit)
        assertEquals(AnnouncementState.TTS_COMPLETED, fresh.await().state)
    }
    @Test fun lateOldSpeechCompletionCannotBeReportedOrClearNewOperation() = runTest {
        val first = MockPopupFixtures.notice().snapshot.token
        val second = first.copy(screenVersion = 2, captureEpoch = 2)
        var current = first; var stops = 0
        val finishOld = CompletableDeferred<Unit>(); val finishNew = CompletableDeferred<Unit>()
        val delegate = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
                (if (request.token == first) finishOld else finishNew).await()
                return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
            }
            override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override fun stop() { stops++ }
        }
        val bound = CurrentChoiceAnnouncer(delegate, { current }, {})
        var boundaryFailures = 0
        val fenced = FailureBoundChoiceControls(bound) { boundaryFailures++ }
        val old = async { fenced.announce(AnnouncementRequest(first, AnnouncementKind.CONTENT, "SYNTHETIC_OLD")) }
        runCurrent(); current = second
        val fresh = async { fenced.announce(AnnouncementRequest(second, AnnouncementKind.CONTENT, "SYNTHETIC_NEW")) }
        runCurrent(); finishOld.complete(Unit); runCurrent()
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, old.await().state)
        assertEquals(0, stops); assertTrue(bound.permitsSpeech())
        assertEquals(0, boundaryFailures)
        finishNew.complete(Unit); assertEquals(AnnouncementState.TTS_COMPLETED, fresh.await().state)
    }
    @Test fun speechSubmissionIdentityCannotClearItsReplacement() {
        val owner = SpeechOperationOwner(); val first = owner.begin(); val second = owner.begin()
        assertFalse(owner.owns(first)); assertFalse(owner.clear(first)); assertTrue(owner.owns(second))
        assertTrue(owner.clear(second)); assertFalse(owner.owns(second))
        val third = owner.begin(); owner.clear(); assertFalse(owner.owns(third))
    }
    @Test fun overwrittenInvalidationCannotReviveAnOldChoiceBeforeConsumerResumes() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val prompt = h.presenter.prompt!!; val callback = h.presenter.callback!!
        val choice = ChoicePromptPlanner.toUserChoice(prompt,
            prompt.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        h.live.invalidate(2)
        assertTrue(h.bus.tryEmit(PopupEvent.Invalidated(2)))
        // Match Runtime's bounded DROP_OLDEST: the invalidation is replaced before collection.
        assertTrue(h.bus.tryEmit(PopupEvent.Blocked(PrivacyBlockReason.PASSWORD, 2)))
        callback(choice)
        assertEquals(0, h.session.executor.reviews)
        runCurrent(); assertNull(h.presenter.prompt)
        h.emit(version = 3)
        assertEquals(3L, h.presenter.prompt?.token?.screenVersion)
        assertEquals(0, h.session.executor.reviews)
    }
    @Test fun speechAndStopFailuresNeverPropagateDelegateMessagesAndStillClearHistory() = runTest {
        val token = MockPopupFixtures.notice().snapshot.token; var forgotten = 0; var stopped = 0
        val delegate = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt = error("PRIVATE_BODY")
            override suspend fun repeatLast(): AnnouncementReceipt = error("PRIVATE_BODY")
            override fun stop() { stopped++; error("PRIVATE_STOP") }
        }
        val bound = CurrentChoiceAnnouncer(delegate, { token }, { forgotten++ })
        val failure = assertThrows(IllegalStateException::class.java) {
            runBlocking { bound.announce(AnnouncementRequest(token, AnnouncementKind.CONTENT, "SYNTHETIC")) }
        }
        assertEquals("CHOICE_SPEECH_FAILED", failure.message); assertNull(failure.cause)
        assertEquals(0, failure.suppressed.size); assertEquals(1, forgotten); assertEquals(1, stopped)
        val stop = assertThrows(IllegalStateException::class.java) { bound.stop() }
        assertEquals("COLLECTION_CLEANUP_FAILED", stop.message)
        assertEquals(2, forgotten); assertEquals(2, stopped); assertFalse(bound.permitsSpeech())
    }
    @Test fun actualListenerChildFailureBoundaryClosesAllOwnedResources() = runTest {
        val seen = mutableListOf<Int>()
        val resources = ChoiceResources(listOf({ seen += 1; error("PRIVATE") }, { seen += 2 }))
        val parent = SupervisorJob()
        val scope = CoroutineScope(coroutineContext + parent + choiceFailureHandler { resources.close() })
        val failed = scope.launch { error("PRIVATE_CHILD") }
        runCurrent(); failed.join()
        assertTrue(failed.isCancelled); assertTrue(parent.isActive)
        assertEquals(listOf(1, 2), seen); assertTrue(resources.failed)
        parent.cancel()
    }
    @Test fun repeatAndSynchronousStopPortFailuresCloseSessionWithoutLeakingIntoCControls() = runTest {
        var calls = 0; var failures = 0
        val delegate = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt { calls++; error("PRIVATE") }
            override suspend fun repeatLast(): AnnouncementReceipt { calls++; error("PRIVATE") }
            override fun stop() { calls++; error("PRIVATE") }
        }
        val repeat = FailureBoundChoiceControls(delegate) { failures++ }
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, repeat.repeatLast().state)
        repeat.stop(); assertEquals(AnnouncementState.NOT_IMPLEMENTED, repeat.repeatLast().state)
        assertEquals(1, calls); assertEquals(1, failures)
        val stop = FailureBoundChoiceControls(delegate) { failures++ }
        stop.stop(); stop.stop()
        assertEquals(2, calls); assertEquals(2, failures)
    }
    @Test fun controlsPortCancellationClosesOnceAndRemainsCancellation() = runTest {
        var failures = 0
        val cancelled = CancellationException("SYNTHETIC_CANCEL")
        val delegate = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt = throw cancelled
            override suspend fun repeatLast(): AnnouncementReceipt = throw cancelled
            override fun stop() = Unit
        }
        val controls = FailureBoundChoiceControls(delegate) { failures++ }
        assertSame(cancelled, assertThrows(CancellationException::class.java) { runBlocking { controls.repeatLast() } })
        assertEquals(1, failures)
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, controls.repeatLast().state)
    }

    @Test fun partialOverlayShowFailureClosesActualFlowAndDiscardsRetainedCallback() = runTest {
        val h = Harness(this); runCurrent()
        h.presenter.showFailure = IllegalStateException("PRIVATE_ADD")
        h.emit()
        val p = MockPopupFixtures.notice().analysis.let { ChoicePromptPlanner.plan(it, PopupClassification.NOTICE) }
        h.presenter.callback!!(ChoicePromptPlanner.toUserChoice(p, p.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE }))
        runCurrent()
        assertTrue(h.job.isCompleted); assertTrue(h.health.failed)
        assertEquals("CHOICE_PRESENTATION_FAILED", h.failureCode)
        assertEquals(1, h.cleanup); assertEquals(0, h.session.executor.reviews)
        assertNull(h.presenter.prompt)
    }
    @Test fun removeFailureDiscardsActualFlowAndQuarantinesReconnection() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val p = h.presenter.prompt!!; val old = h.presenter.callback!!
        val picked = ChoicePromptPlanner.toUserChoice(p, p.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        h.presenter.dismissFailure = IllegalStateException("PRIVATE_REMOVE")
        h.live.invalidate(2); h.bus.emit(PopupEvent.Invalidated(2)); runCurrent()
        old(picked); runCurrent()
        assertTrue(h.health.failed); assertEquals(1, h.cleanup)
        assertEquals("CHOICE_DISMISS_FAILED", h.failureCode)
        assertEquals(0, h.session.executor.reviews)
        val fresh = Harness(this, h.health); runCurrent(); fresh.emit()
        assertEquals(0, fresh.presenter.shows); assertEquals(0, fresh.session.executor.reviews)
    }
    @Test fun synchronousInvalidationRevokesBeforeRuntimeOrDismissCallbackRuns() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val p = h.presenter.prompt!!; val old = h.presenter.callback!!
        val picked = ChoicePromptPlanner.toUserChoice(p, p.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        h.presenter.duringDismiss = { old(picked) }
        h.session.invalidate() // Same ordering used by ServiceChoiceIntegration.invalidate().
        assertNotNull(h.live.currentToken())
        h.presenter.dismiss(); old(picked); runCurrent()
        assertEquals(0, h.session.executor.reviews)
        h.live.invalidate(2); h.bus.emit(PopupEvent.Invalidated(2)); runCurrent()
        h.presenter.duringDismiss = null; h.emit(version = 3)
        assertNotNull(h.presenter.prompt); assertFalse(h.health.failed)
    }
    @Test fun duplicateCallbackCannotRequestASecondReviewOrReuseFollowUpOptions() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val p = h.presenter.prompt!!; val old = h.presenter.callback!!
        val picked = ChoicePromptPlanner.toUserChoice(p, p.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        old(picked); old(picked); runCurrent()
        assertEquals(1, h.session.executor.reviews)
        old(picked); runCurrent()
        assertEquals(1, h.session.executor.reviews)
        assertTrue(h.presenter.prompt!!.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE })
    }
    @Test fun originalTtlStillAllowsReviewJustBeforeExpiryWithoutAnAndroidAction() = runTest {
        val h = Harness(this); runCurrent(); h.emit(); advanceTimeBy(4_999); runCurrent()
        h.presenter.pick(PromptOptionKind.PRESS_CANDIDATE); runCurrent()
        assertEquals(1, h.session.executor.reviews)
        assertEquals(PressFailure.NOT_PRESSED, h.presenter.prompt?.pressFailure)
        advanceTimeBy(1); runCurrent()
        assertNull(h.live.currentToken())
        assertEquals(1, h.session.executor.reviews)
    }
    @Test fun cancelledShowAndFailedCleanupPreserveCancellationAndAttemptOtherReleases() = runTest {
        val h = Harness(this); runCurrent()
        h.presenter.showFailure = CancellationException("SYNTHETIC_CANCEL")
        h.presenter.dismissFailure = IllegalStateException("PRIVATE_REMOVE")
        h.emit(); runCurrent()
        assertTrue(h.job.isCancelled); assertTrue(h.health.failed)
        assertNull(h.failureCode); assertEquals(1, h.cleanup); assertTrue(h.speech.stops > 0)
        assertEquals(0, h.session.executor.reviews)
    }
    @Test fun serviceCloseFailureRevokesOldCallbacksAndAttemptsRemainingCleanup() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val p = h.presenter.prompt!!; val old = h.presenter.callback!!
        val picked = ChoicePromptPlanner.toUserChoice(p, p.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        h.presenter.dismissFailure = IllegalStateException("PRIVATE_REMOVE")
        h.session.close(); old(picked); h.job.cancel(); runCurrent()
        assertTrue(h.health.failed); assertEquals(1, h.cleanup); assertTrue(h.speech.stops > 0)
        assertEquals(0, h.session.executor.reviews)
    }

    @Test fun cFailedAndBlockedResultsCloseActualFlowWithoutReviewOrNewGenerationRecovery() = runTest {
        for (result in listOf(PromptShowResult.FAILED, PromptShowResult.BLOCKED)) {
            val h = Harness(this); runCurrent(); h.presenter.nextResult = result; h.emit()
            assertTrue(h.health.failed); assertEquals(0, h.session.executor.reviews)
            assertEquals(1, h.cleanup); assertTrue(h.job.isCancelled)
            val fresh = Harness(this, h.health); runCurrent(); fresh.emit()
            assertEquals(0, fresh.presenter.shows); assertEquals(0, fresh.session.executor.reviews)
        }
    }
    @Test fun cLostAfterRemovalFailureCannotBecomeABChoiceAndCannotBeReconnectedAway() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val generation = h.presenter.windowState.generation
        h.presenter.state = OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, generation)
        h.presenter.answerCallback!!(PromptAnswer.Lost(generation)); runCurrent()
        assertTrue(h.health.failed); assertEquals(0, h.session.executor.reviews)
        assertEquals(1, h.cleanup); assertTrue(h.job.isCancelled)
        val fresh = Harness(this, h.health); runCurrent(); fresh.emit()
        assertEquals(0, fresh.presenter.shows)
    }
    @Test fun cVoiceOriginRemainsMetadataOnTheSameNonExecutingBReviewPath() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val prompt = h.presenter.prompt!!; val generation = h.presenter.windowState.generation
        val picked = ChoicePromptPlanner.toUserChoice(prompt, prompt.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        h.presenter.dismiss() // C closes a terminal choice before it delivers Picked.
        h.presenter.answerCallback!!(PromptAnswer.Picked(picked, ChoiceOrigin.VOICE, generation)); runCurrent()
        assertFalse(h.health.failed); assertEquals(1, h.session.executor.reviews)
        assertEquals(PressFailure.NOT_PRESSED, h.presenter.prompt?.pressFailure)
        assertTrue(h.presenter.prompt!!.options.none { it.kind in setOf(PromptOptionKind.PRESS_CANDIDATE, PromptOptionKind.REMEMBER_ALWAYS) })
    }
    @Test fun cWindowGenerationMismatchDuringAnAnswerClosesInsteadOfReusingTheToken() = runTest {
        val h = Harness(this); runCurrent(); h.emit()
        val prompt = h.presenter.prompt!!; val generation = h.presenter.windowState.generation
        val picked = ChoicePromptPlanner.toUserChoice(prompt, prompt.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        h.presenter.state = OverlayWindowState(OverlayWindowStatus.SHOWING, generation + 1)
        h.presenter.answerCallback!!(PromptAnswer.Picked(picked, ChoiceOrigin.TAP, generation)); runCurrent()
        assertTrue(h.health.failed); assertEquals(0, h.session.executor.reviews); assertTrue(h.job.isCancelled)
    }
}
