package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.LiveEvidenceState
import kr.closeforyou.accessibility.EvidenceMismatch
import kr.closeforyou.accessibility.EvidenceSnapshotComparison
import android.view.accessibility.AccessibilityEvent
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.*
import kr.closeforyou.ui.voice.choice.*
import kr.closeforyou.ui.voice.integration.UiText
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

/** Real C keeper + A guarded session/Runtime model, synthetic observation and event driver.
 * No Android remove/query delivery or real F0 pass is claimed by these JVM tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OverlayCleanupIntegrationTest {
    private class Harness(val scope: TestScope) {
        val health = ChoicePresentationHealth()
        val barrier = OverlayCleanupBarrier(onUncertain = health::fail)
        var allowed = true; var interacting = false; var epoch = 0L
        val live = LiveEvidenceState({ 1_000 + scope.testScheduler.currentTime }, { allowed && !interacting })
        val bus = MutableSharedFlow<PopupEvent>(extraBufferCapacity = 8)
        val subscription = ChoiceSubscription()
        var fixture = MockPopupFixtures.notice()
        var observations = 0; var begins = 0; var publications = 0
        var extractionWait: CompletableDeferred<Unit>? = null
        val speech = mutableListOf<AnnouncementRequest>()
        var speechStops = 0
        var holdPresentation = false
        val presentationTurns = ArrayDeque<Runnable>()
        var finalVerifications = 0
        val source = object : SnapshotSource {
            override val events = bus.onSubscription { subscription.subscribed() }
            override fun currentToken() = live.currentToken()
        }
        private inner class Window(val prompt: ChoicePrompt, val generation: Long, val answer: (PromptAnswer) -> Unit) {
            val id = 100 + generation.toInt()
        }
        private var window: Window? = null
        private val binding = OwnedOverlayBinding()
        val visibleIds = mutableSetOf<Int>()
        var removeFails = false; var queryFails = false
        var shows = 0; var removeRequests = 0
        var stopped = 0; var destroyed = 0
        val listener = CleanupOverlayListener(OverlayListener.NONE) {
            val old = requireNotNull(window)
            barrier.retiring(binding.boundWindowFor(old.generation), old.generation, old.prompt.token.windowId)
            binding.clear()
        }
        private val keeper = OverlayWindowKeeper(object : OverlayWindowOps<Window> {
            override fun add(window: Window) { visibleIds += window.id }
            override fun announceShown(window: Window) = Unit
            override fun announceDismissed(window: Window) = listener.onDismissed()
            override fun stop(window: Window) { stopped++ }
            override fun destroy(window: Window) { destroyed++ }
            override fun blank(window: Window) = Unit
            override fun remove(window: Window, immediate: Boolean): RemoveOutcome {
                removeRequests++
                if (removeFails) return RemoveOutcome.FAILED
                visibleIds -= window.id
                return RemoveOutcome.REMOVED
            }
        })
        val presenter = object : ChoicePromptPresenter {
            override val isShowing get() = keeper.status == OverlayWindowStatus.SHOWING
            override val windowState get() = OverlayWindowState(keeper.status, keeper.windowGeneration)
            override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
                check(barrier.ready)
                val before = visibleIds.toSet()
                val result = keeper.show { generation -> Window(prompt, generation) { answer ->
                    if (barrier.pending) barrier.removalReturned(keeper.status == OverlayWindowStatus.HIDDEN)
                    onAnswer(answer)
                }.also { window = it } }
                if (result == PromptShowResult.SHOWN) {
                    shows++
                    val own = requireNotNull(window)
                    binding.expect(before, prompt.token, own.generation)
                    // Explicit synthetic ownership observation, not a package-only inference.
                    check(binding.matches(own.id, visibleIds.toSet(), prompt.token, own.generation))
                }
                return result
            }
            override fun dismiss() {
                keeper.dismiss()
                if (barrier.pending) barrier.removalReturned(keeper.status == OverlayWindowStatus.HIDDEN)
                binding.clear()
            }
        }
        val presentationGate = ChoicePresentationGate({ 1_000 + scope.testScheduler.currentTime },
            { allowed && !interacting && source.currentToken() == it }, barrier::permits,
            { awaitChoicePresentationTurn({ task ->
                if (holdPresentation) presentationTurns += task
                else scope.backgroundScope.launch { task.run() }
                true
            }, { presentationTurns.remove(it) }) },
            { baseline, check ->
                check(); finalVerifications++
                !queryFails && EvidenceSnapshotComparison.firstMismatch(baseline, fixture.snapshot) == EvidenceMismatch.NONE
            }, { token -> if (source.currentToken() == token) invalidate() })
        val session = NonExecutingChoiceSession(source, object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
                extractionWait?.await(); currentCoroutineContext().ensureActive()
                return live.extract(snapshot) ?: throw CancellationException("STALE_SYNTHETIC")
            }
        }, presenter, object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
                speech += request; return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
            }
            override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
            override fun stop() { speechStops++ }
        }, { interacting }, { true }, { 1_000 + scope.testScheduler.currentTime }, UiText { "SYNTHETIC_$it" },
            { "SYNTHETIC_BODY" }, ChoiceResources(listOf(presenter::dismiss)), health,
            awaitPresentationReady = { token -> barrier.awaitPresentation() && presentationGate.awaitReady(token) && source.currentToken() == token })
        val job = scope.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { session.run() }
        init { live.connect() }

        fun invalidate() {
            presentationGate.clear()
            session.invalidate(); presenter.dismiss(); live.invalidate(++epoch)
            bus.tryEmit(PopupEvent.Invalidated(epoch))
        }
        fun manualPopupAdvance(next: MockPopupFixture) {
            // Models the TESTER advancing P12. The app executor never performs this transition.
            fixture = next; invalidate()
        }
        suspend fun observeFresh() {
            subscription.await()
            val version = epoch
            val admission = barrier.awaitObservationPermit()
            currentCoroutineContext().ensureActive()
            if (epoch != version || !allowed || interacting || !barrier.permits(admission)) return
            observations++ // The production service starts F0/collector only after this boundary.
            val token = fixture.snapshot.token.copy(screenVersion = epoch, captureEpoch = epoch,
                popupInstanceId = "SYNTHETIC_STAGE_$epoch")
            val snapshot = fixture.snapshot.copy(token = token, capturedAtUptimeMillis = 1_000 + scope.testScheduler.currentTime)
            if (!barrier.permits(admission)) return
            val permit = live.begin(snapshot) ?: return; begins++
            val detection = PopupDetection(token, persistentSetOf(PopupSignal.NEW_WINDOW), true)
            if (!barrier.permits(admission)) return
            presentationGate.prepare(snapshot, admission)
            check(live.publish(permit, snapshot, detection, fixture.analysis.copy(token = token), EvidenceIssue.NONE))
            live.deliver(permit, snapshot) { publications++; bus.tryEmit(PopupEvent.Candidate(snapshot, detection)) }
            scope.runCurrent()
        }
        fun currentPrompt() = requireNotNull(window).prompt
        fun showing() = presenter.isShowing
        fun stateEvent(id: Int) {
            val ignored = inspectOwnedOverlayEvent(presenter.windowState,
                { AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }, { error("UNEXPECTED_MASK") },
                { "SYNTHETIC_HOST" }, source::currentToken,
                { current, diagnostic -> binding.matches(id, visibleIds.toSet(), current, keeper.windowGeneration, diagnostic) },
                { "SYNTHETIC_HOST" }, null)
            if (!ignored) invalidate()
        }
        fun currentId() = requireNotNull(window).id
        fun currentGeneration() = requireNotNull(window).generation
        fun oldCallback(): () -> Unit {
            val old = requireNotNull(window)
            val option = old.prompt.options.first { it.kind == PromptOptionKind.KEEP_OPEN }
            val choice = (ChoicePromptPlanner.select(old.prompt, option) as PromptSelection.Choice).choice
            return { old.answer(PromptAnswer.Picked(choice, ChoiceOrigin.TAP, old.generation)) }
        }
        fun pick(kind: PromptOptionKind) {
            val own = requireNotNull(window)
            val option = own.prompt.options.first { it.kind == kind }
            val choice = (ChoicePromptPlanner.select(own.prompt, option) as PromptSelection.Choice).choice
            keeper.deliver(PromptAnswer.Picked(choice, ChoiceOrigin.TAP, own.generation),
                closesWindow = kind != PromptOptionKind.READ_CONTENT, onAnswer = own.answer)
        }
        suspend fun removalEvent(id: Int, pure: Boolean = true) {
            val receipt = barrier.observe(id, true, pure)
            // Exactly the service ordering: never skip EVENT invalidation for this receipt.
            invalidate(); scope.runCurrent(); yield()
            if (receipt != null) barrier.processed(receipt, keeper.status == OverlayWindowStatus.HIDDEN) {
                if (queryFails) CleanupWindowPresence.UNKNOWN
                else if (it in visibleIds) CleanupWindowPresence.PRESENT else CleanupWindowPresence.ABSENT
            }
            scope.runCurrent()
        }
    }

    @Test fun directCChoiceIsDeliveredOnceAndSameTokenFollowUpWaitsWithoutBlockedFailure() = runTest {
        val h = Harness(this); h.manualPopupAdvance(MockPopupFixtures.notice()); h.observeFresh()
        val token = h.source.currentToken(); val id = h.currentId()
        h.pick(PromptOptionKind.PRESS_CANDIDATE); runCurrent()
        assertEquals(1, h.session.executor.reviews); assertEquals(1, h.shows)
        assertEquals(token, h.source.currentToken()); assertTrue(h.barrier.pending); assertFalse(h.health.failed)
        assertEquals(1, h.removeRequests)
        h.removalEvent(id)
        assertNull(h.source.currentToken()); assertEquals(1, h.shows); assertFalse(h.health.failed)
        assertTrue(h.barrier.ready)
    }
    @Test fun p12ManualAdNoticeConsentHaveIndependentTokensAndGenerationsAfterCleanup() = runTest {
        val h = Harness(this); h.manualPopupAdvance(MockPopupFixtures.advertisement()); h.observeFresh()
        val ad = h.source.currentToken(); val firstGeneration = h.currentGeneration()
        val oldCallback = h.oldCallback(); val oldWindow = h.currentId()
        h.pick(PromptOptionKind.KEEP_OPEN); runCurrent()
        h.manualPopupAdvance(MockPopupFixtures.notice())
        val waitingNotice = launch { h.observeFresh() }; runCurrent()
        assertEquals(1, h.begins); assertEquals(1, h.publications); assertFalse(waitingNotice.isCompleted)
        h.removalEvent(oldWindow); waitingNotice.join()
        // The event changed the epoch: a waiting observation must discard its old request.
        assertEquals(1, h.begins)
        h.observeFresh(); val notice = h.source.currentToken()
        assertNotEquals(ad, notice); assertTrue(h.currentGeneration() > firstGeneration)
        val noticeShows = h.shows; oldCallback(); runCurrent()
        assertEquals(notice, h.source.currentToken()); assertEquals(noticeShows, h.shows)
        val secondWindow = h.currentId(); val secondGeneration = h.currentGeneration()
        h.manualPopupAdvance(MockPopupFixtures.decision())
        h.removalEvent(secondWindow); h.observeFresh()
        assertNotEquals(notice, h.source.currentToken()); assertTrue(h.currentGeneration() > secondGeneration)
        assertEquals(0, h.currentPrompt().options.count { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        assertEquals(0, h.session.executor.reviews); assertEquals(3, h.begins)
        assertEquals(2, h.stopped); assertEquals(2, h.destroyed)
    }
    @Test fun mixedQueryFailureAndReusedIdCannotActivateNextPopup() = runTest {
        for (mode in listOf("MIXED", "QUERY", "REUSED")) {
            val h = Harness(this); h.manualPopupAdvance(MockPopupFixtures.notice()); h.observeFresh()
            val old = h.currentId(); h.manualPopupAdvance(MockPopupFixtures.changedDateNotice())
            h.queryFails = mode == "QUERY"; if (mode == "REUSED") h.visibleIds += old
            h.removalEvent(old, mode != "MIXED")
            assertFalse(h.barrier.ready); assertTrue(h.health.failed)
            assertTrue(runCatching { h.observeFresh() }.exceptionOrNull() is CancellationException)
            assertEquals(1, h.shows); assertEquals(1, h.begins)
            h.job.cancel()
        }
    }
    @Test fun failedDirectRemovalBecomesLostAndCannotReviewOrReopen() = runTest {
        val h = Harness(this); h.manualPopupAdvance(MockPopupFixtures.notice()); h.observeFresh()
        h.removeFails = true; h.pick(PromptOptionKind.PRESS_CANDIDATE); runCurrent()
        assertEquals(0, h.session.executor.reviews); assertTrue(h.health.failed); assertFalse(h.barrier.ready)
    }
    @Test fun ttlConsentAndInteractionDiscardPendingFollowUpWithoutExtendingOriginalDeadline() = runTest {
        for (cause in listOf("TTL", "CONSENT", "TOUCH")) {
            val h = Harness(this); h.manualPopupAdvance(MockPopupFixtures.notice()); h.observeFresh()
            h.pick(PromptOptionKind.PRESS_CANDIDATE); runCurrent()
            when (cause) {
                "TTL" -> advanceTimeBy(5_000)
                "CONSENT" -> h.allowed = false
                else -> h.interacting = true
            }
            h.invalidate(); runCurrent()
            assertNull(h.source.currentToken()); assertEquals(1, h.shows); assertEquals(1, h.begins)
            h.job.cancel()
        }
    }
    @Test fun delayedExtractionAndDuplicateLateRemovalCannotRevivePreviousCandidate() = runTest {
        val h = Harness(this); val wait = CompletableDeferred<Unit>(); h.extractionWait = wait
        h.manualPopupAdvance(MockPopupFixtures.notice()); h.observeFresh(); assertEquals(0, h.shows)
        h.manualPopupAdvance(MockPopupFixtures.decision()); h.extractionWait = null; wait.complete(Unit)
        h.observeFresh(); assertEquals(1, h.shows)
        assertEquals(0, h.currentPrompt().options.count { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        val oldId = h.currentId(); h.pick(PromptOptionKind.KEEP_OPEN); runCurrent(); h.removalEvent(oldId)
        h.manualPopupAdvance(MockPopupFixtures.notice()); h.observeFresh(); assertEquals(2, h.shows)
        // A duplicate arriving AFTER the barrier is not exempted: safety wins over availability.
        h.removalEvent(oldId); assertNull(h.source.currentToken())
        assertEquals(0, h.session.executor.reviews)
    }

    @Test fun queuedOldUnmatchedEventIsHandledBeforeP01ShowAndFreshTokenHasNormalLifetime() = runTest {
        val h = Harness(this); h.holdPresentation = true
        h.manualPopupAdvance(MockPopupFixtures.advertisement()); h.observeFresh()
        val oldToken = h.source.currentToken()
        assertNotNull(oldToken); assertEquals(0, h.shows); assertEquals(0, h.finalVerifications)
        h.stateEvent(999); runCurrent() // old/unknown event is INVALIDATED, never ignored by age
        assertNull(h.source.currentToken()); assertTrue(h.presentationTurns.isEmpty())
        assertEquals(0, h.shows); assertFalse(h.health.failed); assertTrue(h.barrier.ready)
        h.holdPresentation = false; h.observeFresh()
        assertEquals(1, h.shows); assertNotEquals(oldToken, h.source.currentToken())
        assertEquals(1, h.finalVerifications); assertTrue(h.showing())
        advanceTimeBy(4_999); runCurrent()
        assertTrue(h.showing()); assertNotNull(h.source.currentToken()); assertEquals(0, h.session.executor.reviews)
        advanceTimeBy(1); h.invalidate(); runCurrent()
        assertNull(h.source.currentToken()); assertFalse(h.showing())
    }

    @Test fun actualChangeAndEvenOlderUnknownEventAfterShowStillInvalidateP01() = runTest {
        for (eventId in listOf(999, MockPopupFixtures.advertisement().snapshot.token.windowId)) {
            val h = Harness(this); h.manualPopupAdvance(MockPopupFixtures.advertisement()); h.observeFresh()
            assertEquals(1, h.shows)
            h.stateEvent(eventId); runCurrent()
            assertNull(h.source.currentToken()); assertFalse(h.showing())
            assertEquals(0, h.session.executor.reviews)
        }
    }

    @Test fun unmatchedEventQueuedDuringFinalF0IsHandledBeforeCExists() = runTest {
        val h = Harness(this); h.holdPresentation = true
        h.manualPopupAdvance(MockPopupFixtures.advertisement()); h.observeFresh()
        h.presentationTurns.removeFirst().run(); runCurrent()
        assertEquals(1, h.finalVerifications); assertEquals(0, h.shows)
        assertEquals(1, h.presentationTurns.size)
        h.stateEvent(999); runCurrent()
        assertEquals(0, h.shows); assertTrue(h.presentationTurns.isEmpty())
        assertNull(h.source.currentToken()); assertFalse(h.health.failed)
        h.holdPresentation = false; h.observeFresh()
        assertEquals(1, h.shows); assertEquals(2, h.finalVerifications)
    }

    @Test fun screenChangeBetweenAnalysisAndPresentationNeverFlashesOldCandidate() = runTest {
        val h = Harness(this); h.holdPresentation = true
        h.manualPopupAdvance(MockPopupFixtures.notice()); h.observeFresh()
        h.fixture = MockPopupFixtures.changedDateNotice() // synthetic screen changed without delivered event
        h.presentationTurns.removeFirst().run(); runCurrent()
        assertEquals(1, h.finalVerifications); assertEquals(0, h.shows)
        assertNull(h.source.currentToken()); assertFalse(h.health.failed)
    }
}
