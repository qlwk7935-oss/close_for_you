package kr.closeforyou.ui.voice.integration

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.contract.PopupClassifier
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.core.contract.PopupRuleRepository
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.decision.DecisionVideoAd
import kr.closeforyou.testing.mock.FixtureOnlyFingerprintProvider
import kr.closeforyou.testing.mock.InMemoryMockPopupRuleRepository
import kr.closeforyou.testing.mock.MockPopupFixture
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.testing.mock.MockSnapshotSource
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.BatchSummary
import kr.closeforyou.ui.voice.PressFailure
import kr.closeforyou.ui.voice.PressResultSpeech
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.MoveToButtonRequest
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSelection
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.choice.VideoAdInfo
import kr.closeforyou.ui.voice.overlay.ChoiceOrigin
import kr.closeforyou.ui.voice.overlay.ChoicePromptPresenter
import kr.closeforyou.ui.voice.overlay.OverlayWindowKeeper
import kr.closeforyou.ui.voice.overlay.OverlayWindowOps
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import kr.closeforyou.ui.voice.overlay.PromptAnswer
import kr.closeforyou.ui.voice.overlay.PromptShowResult
import kr.closeforyou.ui.voice.overlay.RemoveOutcome
import kr.closeforyou.ui.voice.overlay.RevalidationCause
import kr.closeforyou.ui.voice.overlay.RevalidationEnd
import kr.closeforyou.ui.voice.overlay.RevalidationTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PopupChoiceFlowTest {
    /**
     * Behaves like AccessibilityChoiceOverlay, on the real [OverlayWindowKeeper]: press/keep close
     * the prompt, read keeps it, answers of other generations are dropped. [addFails] and
     * [removeFails] stand in for WindowManager errors.
     */
    private class FakePresenter : ChoicePromptPresenter {
        /** One shown prompt's tap target, kept so a test can tap it late. */
        class Shown(val prompt: ChoicePrompt, val generation: Long, val onAnswer: (PromptAnswer) -> Unit)

        var shows = 0
        var addFails = false
        var removeFails = false
        var last: Shown? = null
        /** WindowManager.addView calls; a same-popup refill must not add a window. */
        var adds = 0
        /** Mirrors AccessibilityChoiceOverlay's flag (off by default). */
        var revalidatingEnabled = false
        var refills = 0
        val ended = mutableListOf<RevalidationEnd>()
        /** The pending REVALIDATING timeout; a test fires it by hand. */
        var timeout: (() -> Unit)? = null
        private val keeper = OverlayWindowKeeper(
            object : OverlayWindowOps<Long> {
                override fun add(window: Long) {
                    adds++
                    if (addFails) throw IllegalStateException("BadToken")
                }
                override fun announceShown(window: Long) = Unit
                override fun announceDismissed(window: Long) = Unit
                override fun stop(window: Long) = Unit
                override fun remove(window: Long, immediate: Boolean) =
                    if (removeFails) RemoveOutcome.FAILED else RemoveOutcome.REMOVED
                override fun blank(window: Long) = Unit
                override fun destroy(window: Long) = Unit
                override fun enterRevalidating(window: Long) = Unit
            },
            RevalidationTimer { _, action ->
                timeout = action
                { timeout = null }
            },
        )

        val prompt: ChoicePrompt? get() = if (isShowing) last?.prompt else null
        val status: OverlayWindowStatus get() = keeper.status
        override val isShowing: Boolean get() = keeper.status == OverlayWindowStatus.SHOWING
        override val windowState get() = OverlayWindowState(keeper.status, keeper.windowGeneration, keeper.windowNumber)

        override fun beginRevalidation(cause: RevalidationCause): Boolean {
            if (!revalidatingEnabled) {
                dismiss()
                return false
            }
            return keeper.beginRevalidation { ended += RevalidationEnd.TIMED_OUT }.also { if (!it) dismiss() }
        }

        override fun confirmSamePopup(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            if (keeper.status != OverlayWindowStatus.REVALIDATING) return present(prompt, onAnswer)
            val result = keeper.confirmRevalidation { _, _ -> }
            if (result == PromptShowResult.SHOWN) {
                last = Shown(prompt, keeper.currentGeneration, onAnswer)
                refills++
            }
            return result
        }

        override fun endRevalidation(end: RevalidationEnd) {
            if (keeper.endRevalidation()) ended += end else dismiss()
        }

        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            val result = keeper.show { it }
            if (result == PromptShowResult.SHOWN) {
                last = Shown(prompt, keeper.currentGeneration, onAnswer)
                shows++
            }
            return result
        }

        override fun dismiss() = keeper.dismiss()

        /** Taps [kind] on [target] (default: the prompt shown last), even if it is long gone. */
        fun tap(kind: PromptOptionKind, origin: ChoiceOrigin = ChoiceOrigin.TAP, target: Shown? = last) {
            val t = requireNotNull(target)
            when (val s = ChoicePromptPlanner.select(t.prompt, t.prompt.options.first { it.kind == kind })) {
                is PromptSelection.Choice -> keeper.deliver(
                    PromptAnswer.Picked(s.choice, origin, t.generation),
                    closesWindow = s.choice.kind != ChoiceKind.READ_CONTENT,
                    onAnswer = t.onAnswer,
                )
                is PromptSelection.MoveToButton ->
                    keeper.deliver(PromptAnswer.Move(s.request, origin, t.generation), closesWindow = true, onAnswer = t.onAnswer)
            }
        }
    }

    private class FakeAnnouncer : PopupAnnouncer {
        val said = mutableListOf<AnnouncementRequest>()
        var stops = 0
        override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
            said += request
            return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
        override fun stop() {
            stops++
        }
    }

    /**
     * Counts prepare/commit calls into [log] (shared with the handler to check the order);
     * never stores screen text.
     */
    private class FakeStore(
        private val answer: Boolean = true,
        private val keyAvailable: Boolean = true,
    ) : RememberedPopupStore {
        val log = mutableListOf<String>()
        val prepared = mutableListOf<UserChoice>()
        val saved = mutableListOf<PreparedRule>()
        override suspend fun prepare(choice: UserChoice, snapshot: PopupSnapshot, analysis: PopupAnalysis): PreparedRule? {
            log += "prepare"
            prepared += choice
            return if (keyAvailable) PreparedRule(choice.token, RuleKey("p", 1, "s", "c", "t")) else null
        }
        override suspend fun commit(prepared: PreparedRule): Boolean {
            log += "commit"
            saved += prepared
            return answer
        }
    }

    private class Harness(
        scope: TestScope,
        private val status: ActionStatus,
        remember: Boolean = false,
        store: RememberedPopupStore = RememberedPopupStore.NONE,
        autoClose: Boolean = false,
        videoAd: Boolean = false,
        revalidating: Boolean = false,
        isSamePopup: (PopupEvent.Candidate) -> Boolean = { false },
    ) {
        val source = MockSnapshotSource()
        var videoAdCalls = 0
        val presenter = FakePresenter()
        val announcer = FakeAnnouncer()
        val handled = mutableListOf<UserChoice>()
        val origins = mutableListOf<ChoiceOrigin>()
        var job: Job? = null
        /** Order of handler and store calls (shared with a [FakeStore]). */
        val log = (store as? FakeStore)?.log ?: mutableListOf()
        val flushed = mutableListOf<BatchSummary>()
        private var fixture: MockPopupFixture = MockPopupFixtures.advertisement()
        /** Replaces the fixture's analysis (e.g. a skip button); null uses the fixture's. */
        var analysisOverride: PopupAnalysis? = null
        val flow = PopupChoiceFlow(
            source = source,
            extractor = object : EvidenceExtractor {
                override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis =
                    analysisOverride ?: fixture.analysis
            },
            classifier = object : PopupClassifier {
                override suspend fun classify(analysis: PopupAnalysis) = fixture.expectedDisplayClassification
            },
            presenter = presenter,
            announcer = announcer,
            batcher = AnnouncementBatcher(scope.backgroundScope, onFlush = { flushed += it }),
            handler = object : ChoiceHandler {
                override suspend fun handle(choice: UserChoice, analysis: PopupAnalysis): ActionResult? {
                    handled += choice
                    log += "handle"
                    return if (choice.kind == ChoiceKind.KEEP_OPEN) null else ActionResult(choice.token, status, reason = null)
                }

                override suspend fun handle(choice: UserChoice, analysis: PopupAnalysis, origin: ChoiceOrigin): ActionResult? {
                    origins += origin
                    return handle(choice, analysis)
                }
            },
            text = UiText { "res$it" },
            contentText = { "content" },
            rememberChoicesEnabled = remember,
            rememberStore = store,
            autoCloseEnabledForApp = { autoClose },
            videoAdOf = {
                videoAdCalls++
                VideoAdInfo.from(DecisionVideoAd.inspect(it))
            },
            videoAdEnabled = videoAd,
            revalidatingEnabled = revalidating,
            isSamePopup = isSamePopup,
        )

        suspend fun emit(f: MockPopupFixture) {
            fixture = f
            source.emitFixture(f)
        }
    }

    private fun TestScope.start(
        status: ActionStatus = ActionStatus.MECHANICAL_SUCCESS,
        remember: Boolean = false,
        store: RememberedPopupStore = RememberedPopupStore.NONE,
        autoClose: Boolean = false,
        videoAd: Boolean = false,
        revalidating: Boolean = false,
        isSamePopup: (PopupEvent.Candidate) -> Boolean = { false },
    ): Harness {
        val h = Harness(this, status, remember, store, autoClose, videoAd, revalidating, isSamePopup)
        h.presenter.revalidatingEnabled = revalidating
        h.job = backgroundScope.launch { h.flow.run() }
        runCurrent()
        return h
    }

    @Test fun candidateShowsPromptAndSuccessIsBatched() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        assertEquals(PromptOptionKind.PRESS_CANDIDATE, h.presenter.prompt!!.options.first().kind)

        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE)
        runCurrent()
        assertEquals(ChoiceKind.CLOSE_ONCE, h.handled.single().kind)
        assertFalse(h.presenter.isShowing)
        assertTrue(h.announcer.said.none { it.kind == AnnouncementKind.ACTION_RESULT })
        advanceTimeBy(AnnouncementBatcher.DEFAULT_WINDOW_MILLIS + 1)
        assertEquals(listOf(BatchSummary(closed = 1, skipped = 0, notConfirmed = 0)), h.flushed)
    }

    @Test fun blockedPressIsSpokenAndFollowUpHasNoPressButton() = runTest {
        val h = start(ActionStatus.BLOCKED)
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE)
        runCurrent()

        assertEquals(AnnouncementKind.ACTION_RESULT, h.announcer.said.single().kind)
        val followUp = h.presenter.prompt!!
        assertEquals(PressFailure.NOT_PRESSED, followUp.pressFailure)
        assertTrue(followUp.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        advanceTimeBy(AnnouncementBatcher.DEFAULT_WINDOW_MILLIS + 1)
        assertTrue("A failure is never counted as closed", h.flushed.isEmpty())

        h.presenter.tap(PromptOptionKind.KEEP_OPEN)
        runCurrent()
        assertEquals(listOf(ChoiceKind.CLOSE_ONCE, ChoiceKind.KEEP_OPEN), h.handled.map { it.kind })
        assertFalse(h.presenter.isShowing)
    }

    @Test fun staleResultIsSpokenWithoutFollowUp() = runTest {
        val h = start(ActionStatus.STALE)
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE)
        runCurrent()
        assertEquals(1, h.announcer.said.size)
        assertNull(h.presenter.prompt)
        assertEquals(1, h.presenter.shows)
    }

    @Test fun readContentKeepsPromptAndWaitsForNextTap() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.notice())
        runCurrent()
        h.presenter.tap(PromptOptionKind.READ_CONTENT)
        runCurrent()
        assertEquals(AnnouncementKind.CONTENT, h.announcer.said.single().kind)
        assertTrue(h.presenter.isShowing)
        assertEquals("READ_CONTENT does not go to B/A", 0, h.handled.size)

        h.presenter.tap(PromptOptionKind.KEEP_OPEN)
        runCurrent()
        assertEquals(ChoiceKind.KEEP_OPEN, h.handled.single().kind)
        assertEquals(1, h.presenter.shows)
    }

    @Test fun invalidationClosesPromptAndStopsSpeech() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        assertTrue(h.presenter.isShowing)
        h.source.invalidate(screenVersion = 99)
        runCurrent()
        assertFalse(h.presenter.isShowing)
        assertTrue(h.announcer.stops > 0)
        // A late tap on the old prompt goes nowhere.
        assertTrue(h.handled.isEmpty())
    }

    @Test fun blockedEventClosesPrompt() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.source.emitBlocked(screenVersion = 5)
        runCurrent()
        assertFalse(h.presenter.isShowing)
    }

    @Test fun decisionPopupNeverOffersPress() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.decision())
        runCurrent()
        assertTrue(h.presenter.prompt!!.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE })
    }

    // --- F5 video ad wording (VideoAdAnnouncementFeature, off by default) ---

    private val countdownAd: PopupAnalysis = MockPopupFixtures.videoWithoutAdEvidence().analysis.let { v ->
        val bounds = v.texts.first().bounds
        v.copy(
            texts = persistentListOf(
                ExtractedText("광고 · 가상 상품", bounds, TextSource.ACCESSIBILITY_TREE, null),
                ExtractedText("5초 후 건너뛰기", bounds, TextSource.ACCESSIBILITY_TREE, null),
            ),
            closeCandidates = persistentListOf(),
            videoSurfaceObserved = true,
        )
    }

    @Test fun videoAdCountdownIsSaidAndNothingIsPressedWhenItEnds() = runTest {
        val h = start(videoAd = true)
        h.analysisOverride = countdownAd
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        val prompt = h.presenter.prompt!!
        assertEquals(PromptSummary.LIKELY_VIDEO_AD, prompt.summary)
        assertEquals(5, prompt.countdownSeconds)
        assertTrue(prompt.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue("no automatic press after the countdown", h.handled.isEmpty())
        assertTrue(h.presenter.isShowing)
    }

    @Test fun videoAdFlagOffDoesNotAskBAndKeepsTheOldPrompt() = runTest {
        val h = start()
        h.analysisOverride = countdownAd
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        assertEquals(PromptSummary.LIKELY_AD, h.presenter.prompt!!.summary)
        assertNull(h.presenter.prompt!!.videoAd)
        assertEquals(0, h.videoAdCalls)
    }

    // --- [앞으로 항상 닫기] (10/20 (가)): one-time press first, save only after success ---

    private fun TestScope.tapRemember(h: Harness) {
        backgroundScope.launch { h.emit(MockPopupFixtures.advertisement()) }
        runCurrent()
        h.presenter.tap(PromptOptionKind.REMEMBER_ALWAYS)
        runCurrent()
    }

    @Test fun rememberSendsCloseOnceThenSavesOnceOnSuccess() = runTest {
        val store = FakeStore()
        val h = start(remember = true, store = store)
        tapRemember(h)

        val sent = h.handled.single()
        assertEquals(ChoiceKind.CLOSE_ONCE, sent.kind)
        assertEquals("fixture-button", sent.candidateId)
        assertEquals(MockPopupFixtures.advertisement().snapshot.token, sent.token)
        assertTrue(sent.buttonMeaningConfirmed)
        assertFalse("A one-time press carries no repeat permission", sent.repeatAutomationExplicitlyAllowed)

        val asked = store.prepared.single()
        assertEquals(ChoiceKind.REMEMBER_EXACT_POPUP, asked.kind)
        assertTrue(asked.buttonMeaningConfirmed && asked.repeatAutomationExplicitlyAllowed)
        assertEquals(sent.candidateId, asked.candidateId)
        assertEquals(
            "Key made once before the press, saved once after success",
            listOf("prepare", "handle", "commit"),
            h.log,
        )
        assertEquals(sent.token, store.saved.single().token)

        val said = h.announcer.said.single()
        assertEquals(AnnouncementKind.ACTION_RESULT, said.kind)
        assertEquals("res${R.string.ui_remember_saved} res${R.string.ui_remember_auto_close_off}", said.text)
        assertFalse(h.presenter.isShowing)
        advanceTimeBy(AnnouncementBatcher.DEFAULT_WINDOW_MILLIS + 1)
        assertEquals(listOf(BatchSummary(closed = 1, skipped = 0, notConfirmed = 0)), h.flushed)
    }

    @Test fun rememberWithAutoCloseOnSaysSo() = runTest {
        val h = start(remember = true, store = FakeStore(), autoClose = true)
        tapRemember(h)
        assertEquals(
            "res${R.string.ui_remember_saved} res${R.string.ui_remember_auto_close_on}",
            h.announcer.said.single().text,
        )
    }

    @Test fun rememberSaveFailureIsToldAndCloseStillCounted() = runTest {
        val store = FakeStore(answer = false)
        val h = start(remember = true, store = store)
        tapRemember(h)
        assertEquals(1, h.handled.size)
        assertEquals(1, store.saved.size)
        assertEquals("res${R.string.ui_remember_not_saved}", h.announcer.said.single().text)
        advanceTimeBy(AnnouncementBatcher.DEFAULT_WINDOW_MILLIS + 1)
        assertEquals(1, h.flushed.single().closed)
    }

    @Test fun rememberOnSkipButtonSendsSkipOnce() = runTest {
        val store = FakeStore()
        val h = start(remember = true, store = store)
        val base = MockPopupFixtures.advertisement().analysis
        h.analysisOverride = base.copy(
            closeCandidates = persistentListOf(
                base.closeCandidates.single().copy(label = "건너뛰기", role = ButtonRole.SKIP),
            ),
        )
        tapRemember(h)
        assertEquals(ChoiceKind.SKIP_ONCE, h.handled.single().kind)
        assertEquals(1, store.saved.size)
    }

    @Test fun rememberSavesNothingWhenPressDidNotSucceed() = runTest {
        val notSuccess = listOf(
            ActionStatus.BLOCKED, ActionStatus.NOT_EXECUTED, ActionStatus.STALE,
            ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN,
        )
        for (status in notSuccess) {
            val store = FakeStore()
            val h = start(status = status, remember = true, store = store)
            tapRemember(h)

            assertEquals("$status: handler once", ChoiceKind.CLOSE_ONCE, h.handled.single().kind)
            assertEquals("$status: key prepared before the press, then dropped", listOf("prepare", "handle"), h.log)
            assertTrue("$status: nothing saved", store.saved.isEmpty())
            // Same failure sentence as a one-time press (MoveToButtonFeature is off here).
            val failure = requireNotNull(PressResultSpeech.failureOf(ActionResult(h.handled.single().token, status, null)))
            assertEquals(
                PressResultSpeech.sentence(failure, moveToButtonAvailable = false) { "res$it" },
                h.announcer.said.single().text,
            )
            // No retry: a follow-up offers neither press nor remember.
            h.presenter.prompt?.let { followUp ->
                assertTrue(
                    followUp.options.none {
                        it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS
                    },
                )
            }
            if (status == ActionStatus.STALE) assertNull(h.presenter.prompt)
        }
    }

    @Test fun rememberFailureThenKeepOpenStillSavesNothing() = runTest {
        val store = FakeStore()
        val h = start(status = ActionStatus.BLOCKED, remember = true, store = store)
        tapRemember(h)
        h.presenter.tap(PromptOptionKind.KEEP_OPEN)
        runCurrent()
        assertEquals(listOf(ChoiceKind.CLOSE_ONCE, ChoiceKind.KEEP_OPEN), h.handled.map { it.kind })
        assertTrue(store.saved.isEmpty())
    }

    @Test fun rememberKeyFailureStillPressesOnceButSavesNothing() = runTest {
        val store = FakeStore(keyAvailable = false)
        val h = start(remember = true, store = store)
        tapRemember(h)
        assertEquals(listOf("prepare", "handle"), h.log)
        assertEquals(ChoiceKind.CLOSE_ONCE, h.handled.single().kind)
        assertTrue(store.saved.isEmpty())
        assertEquals("res${R.string.ui_remember_not_saved}", h.announcer.said.single().text)
        advanceTimeBy(AnnouncementBatcher.DEFAULT_WINDOW_MILLIS + 1)
        assertEquals("The close itself still counts", 1, h.flushed.single().closed)
    }

    @Test fun rememberKeyFailureAndFailedPressSaysOnlyThePressFailure() = runTest {
        val store = FakeStore(keyAvailable = false)
        val h = start(status = ActionStatus.ATTEMPT_FAILED, remember = true, store = store)
        tapRemember(h)
        assertEquals(1, h.handled.size)
        assertTrue(store.saved.isEmpty())
        assertTrue(h.announcer.said.none { it.text == "res${R.string.ui_remember_not_saved}" })
    }

    @Test fun rememberWithoutConnectedStoreSaysNotSaved() = runTest {
        val h = start(remember = true)
        tapRemember(h)
        assertEquals(1, h.handled.size)
        assertEquals("res${R.string.ui_remember_not_saved}", h.announcer.said.single().text)
    }

    @Test fun rememberEndToEndWithMockRepository() = runTest {
        val repository = InMemoryMockPopupRuleRepository()
        val store = RepositoryRememberedPopupStore(
            fingerprints = FixtureOnlyFingerprintProvider(),
            repository = repository,
            appVersionCode = { 1L },
            nowEpochMillis = { 1_000L },
            newId = { "rule-1" },
        )
        val h = start(remember = true, store = store)
        tapRemember(h)
        val rule = repository.observeRules().first().single()
        assertEquals(ChoiceKind.REMEMBER_EXACT_POPUP, rule.choice)
        assertTrue(rule.buttonMeaningConfirmed && rule.repeatAutomationExplicitlyAllowed)
        assertEquals("rule-1", rule.id)
        assertEquals(1_000L, rule.createdAtEpochMillis)
        assertEquals("Expiry is creation + 30 days", 1_000L + RememberRetention.TTL_MILLIS, rule.expiresAtEpochMillis)
        assertEquals(1, h.handled.size)
    }

    @Test fun rememberKeyIsMadeBeforeThePopupDisappears() = runTest {
        // Like D's provider: the key needs the button in the current popup. Once the press has
        // succeeded the popup is gone, so a key made after the press would always fail.
        var popupGone = false
        val fingerprints = object : PopupFingerprintProvider {
            override suspend fun createKey(snapshot: PopupSnapshot, target: CloseCandidate, appVersionCode: Long): RuleKey {
                check(!popupGone) { "UNKNOWN_TARGET" }
                return FixtureOnlyFingerprintProvider().createKey(snapshot, target, appVersionCode)
            }
        }
        val repository = InMemoryMockPopupRuleRepository()
        val inner = RepositoryRememberedPopupStore(fingerprints, repository, { 1L })
        val store = object : RememberedPopupStore by inner {
            override suspend fun commit(prepared: PreparedRule): Boolean {
                popupGone = true
                return inner.commit(prepared)
            }
        }
        val h = start(remember = true, store = store)
        tapRemember(h)
        assertEquals(1, repository.observeRules().first().size)
        assertEquals(
            "res${R.string.ui_remember_saved} res${R.string.ui_remember_auto_close_off}",
            h.announcer.said.single().text,
        )
    }

    @Test fun rememberRejectedByStorageIsToldNotSwallowed() = runTest {
        // Like D's Room repository: it throws instead of saving (here always, after checking expiry).
        val repository = object : PopupRuleRepository by InMemoryMockPopupRuleRepository() {
            override suspend fun save(rule: PopupRule) {
                val expires = requireNotNull(rule.expiresAtEpochMillis) { "MISSING_EXPIRY" }
                require(expires - rule.createdAtEpochMillis <= RememberRetention.TTL_MILLIS) { "EXPIRY_TOO_LONG" }
                throw IllegalStateException("storage error")
            }
        }
        val store = RepositoryRememberedPopupStore(FixtureOnlyFingerprintProvider(), repository, { 1L })
        val h = start(remember = true, store = store)
        tapRemember(h)
        assertEquals(1, h.handled.size)
        assertEquals("res${R.string.ui_remember_not_saved}", h.announcer.said.single().text)
    }

    @Test fun rememberEndToEndSavesNothingWhenBlocked() = runTest {
        val repository = InMemoryMockPopupRuleRepository()
        val store = RepositoryRememberedPopupStore(FixtureOnlyFingerprintProvider(), repository, { 1L })
        val h = start(status = ActionStatus.BLOCKED, remember = true, store = store)
        tapRemember(h)
        assertTrue(repository.observeRules().first().isEmpty())
    }

    @Test fun onceChoicesNeverSave() = runTest {
        val store = FakeStore()
        val h = start(remember = true, store = store)
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE)
        runCurrent()
        assertEquals(ChoiceKind.CLOSE_ONCE, h.handled.single().kind)
        assertTrue(store.saved.isEmpty())
        assertTrue(h.announcer.said.none { it.kind == AnnouncementKind.ACTION_RESULT })
    }

    // --- Review #18 (A): window failures and late callbacks never reach B/A ---

    @Test fun showFailureEndsPopupWithoutChoice() = runTest {
        val h = start()
        h.presenter.addFails = true
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        assertFalse(h.presenter.isShowing)
        assertTrue(h.handled.isEmpty())
        val said = h.announcer.said.single()
        assertEquals(AnnouncementKind.POPUP_FOUND, said.kind)
        assertEquals("res${R.string.ui_prompt_show_failed}", said.text)

        // The next popup is shown normally once adding works again.
        h.presenter.addFails = false
        h.emit(MockPopupFixtures.notice())
        runCurrent()
        assertTrue(h.presenter.isShowing)
    }

    @Test fun choiceWhoseWindowCannotCloseIsNotSentAndBlocksNextPrompt() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.presenter.removeFails = true
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE)
        runCurrent()
        assertTrue("nothing goes to B/A", h.handled.isEmpty())
        assertEquals("res${R.string.ui_prompt_window_stuck}", h.announcer.said.single().text)
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, h.presenter.status)

        // Still stuck: the next popup gets no prompt on top of the old window.
        h.emit(MockPopupFixtures.notice())
        runCurrent()
        assertEquals(1, h.presenter.shows)
        assertEquals("res${R.string.ui_prompt_show_failed}", h.announcer.said.last().text)
        assertTrue(h.handled.isEmpty())

        // Removal works again: the retry clears it and the next popup is asked normally.
        h.presenter.removeFails = false
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        assertTrue(h.presenter.isShowing)
        assertEquals(2, h.presenter.shows)
    }

    @Test fun lateTapAfterInvalidationIsDropped() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        val old = h.presenter.last
        h.source.invalidate(screenVersion = 99)
        runCurrent()
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, target = old)
        runCurrent()
        assertTrue(h.handled.isEmpty())
    }

    @Test fun tapOnOlderPromptAfterNewCandidateIsDropped() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        val old = h.presenter.last
        h.emit(MockPopupFixtures.notice())
        runCurrent()
        h.presenter.tap(PromptOptionKind.KEEP_OPEN, target = old)
        runCurrent()
        assertTrue(h.handled.isEmpty())

        h.presenter.tap(PromptOptionKind.KEEP_OPEN)
        runCurrent()
        assertEquals(MockPopupFixtures.notice().snapshot.token, h.handled.single().token)
    }

    @Test fun lateTapAfterFlowCancelledIsDropped() = runTest {
        // Service onDestroy cancels the flow; removal of the window fails at the same time.
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        val old = h.presenter.last
        h.presenter.removeFails = true
        h.job!!.cancel()
        runCurrent()
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, h.presenter.status)
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, target = old)
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, origin = ChoiceOrigin.VOICE, target = old)
        runCurrent()
        assertTrue(h.handled.isEmpty())
    }

    @Test fun doubleTapSendsOneChoice() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        val shown = h.presenter.last
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, target = shown)
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, target = shown)
        runCurrent()
        assertEquals(1, h.handled.size)
    }

    @Test fun voiceOriginReachesTheHandler() = runTest {
        val h = start()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, origin = ChoiceOrigin.VOICE)
        runCurrent()
        assertEquals(listOf(ChoiceOrigin.VOICE), h.origins)
        assertEquals(ChoiceKind.CLOSE_ONCE, h.handled.single().kind)
    }

    // --- REVALIDATING (issue #29, RevalidatingFeature off by default) ---

    @Test fun revalidationOffKeepsOldInvalidationPath() = runTest {
        val h = start(revalidating = false)
        h.presenter.revalidatingEnabled = true // even a capable presenter is not asked
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        assertEquals(OverlayWindowStatus.HIDDEN, h.presenter.status)
        assertNull(h.presenter.timeout)
        assertTrue(h.presenter.ended.isEmpty())
    }

    @Test fun invalidationStartsRevalidatingAndDropsOldTapsAndVoice() = runTest {
        val h = start(revalidating = true)
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        val old = h.presenter.last
        val stopsBefore = h.announcer.stops
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        assertEquals(OverlayWindowStatus.REVALIDATING, h.presenter.status)
        assertTrue("speech stops", h.announcer.stops > stopsBefore)
        assertTrue(h.presenter.windowState.generation != old!!.generation)
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, target = old)
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, ChoiceOrigin.VOICE, target = old)
        runCurrent()
        assertTrue("nothing from the old prompt reaches B/A", h.handled.isEmpty())
        assertEquals(OverlayWindowStatus.REVALIDATING, h.presenter.status)
    }

    @Test fun samePopupRefillsTheSameWindowWithNewGeneration() = runTest {
        val h = start(revalidating = true, isSamePopup = { true })
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        val old = h.presenter.last!!
        val window = h.presenter.windowState.windowNumber
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        assertEquals(OverlayWindowStatus.SHOWING, h.presenter.status)
        assertEquals("no second addView", 1, h.presenter.adds)
        assertEquals(1, h.presenter.refills)
        assertEquals("same window", window, h.presenter.windowState.windowNumber)
        assertTrue(h.presenter.last!!.generation > old.generation + 1)

        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE, target = old)
        runCurrent()
        assertTrue("the pre-revalidation prompt stays dead", h.handled.isEmpty())
        h.presenter.tap(PromptOptionKind.PRESS_CANDIDATE)
        runCurrent()
        assertEquals(ChoiceKind.CLOSE_ONCE, h.handled.single().kind)
    }

    @Test fun differentPopupClosesWithReasonAndShowsItsOwnPrompt() = runTest {
        val h = start(revalidating = true)
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        h.emit(MockPopupFixtures.notice())
        runCurrent()
        assertEquals(listOf(RevalidationEnd.DIFFERENT_POPUP), h.presenter.ended)
        assertEquals(0, h.presenter.refills)
        assertEquals("a new window for the new popup", 2, h.presenter.adds)
        assertEquals(OverlayWindowStatus.SHOWING, h.presenter.status)
        assertNull("timeout cancelled", h.presenter.timeout)
    }

    @Test fun timeoutClosesTheWindowAndTheNextCandidateGetsANewOne() = runTest {
        val h = start(revalidating = true, isSamePopup = { true })
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        requireNotNull(h.presenter.timeout).invoke()
        assertEquals(OverlayWindowStatus.HIDDEN, h.presenter.status)
        assertEquals(listOf(RevalidationEnd.TIMED_OUT), h.presenter.ended)

        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        assertEquals("not revalidating any more: a fresh window", 2, h.presenter.adds)
        assertEquals(0, h.presenter.refills)
    }

    @Test fun blockedWhileRevalidatingClosesSilently() = runTest {
        val h = start(revalidating = true)
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        h.source.emitBlocked(screenVersion = 2)
        runCurrent()
        assertEquals(OverlayWindowStatus.HIDDEN, h.presenter.status)
        assertTrue("no closing sentence on a sensitive screen", h.presenter.ended.isEmpty())
        assertNull(h.presenter.timeout)
    }

    @Test fun repeatedInvalidationDoesNotRestartTheTimeout() = runTest {
        val h = start(revalidating = true)
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        val first = h.presenter.timeout
        h.source.invalidate(screenVersion = 2)
        runCurrent()
        assertEquals(OverlayWindowStatus.REVALIDATING, h.presenter.status)
        assertTrue(first === h.presenter.timeout)
    }

    @Test fun stoppingTheFlowWhileRevalidatingClosesTheWindowAndCancelsTheTimeout() = runTest {
        val h = start(revalidating = true)
        h.emit(MockPopupFixtures.advertisement())
        runCurrent()
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        h.job!!.cancel()
        runCurrent()
        assertEquals(OverlayWindowStatus.HIDDEN, h.presenter.status)
        assertNull(h.presenter.timeout)
    }

    @Test fun invalidationWithNothingShownJustDismisses() = runTest {
        val h = start(revalidating = true)
        h.source.invalidate(screenVersion = 1)
        runCurrent()
        assertEquals(OverlayWindowStatus.HIDDEN, h.presenter.status)
        assertNull(h.presenter.timeout)
    }
}
