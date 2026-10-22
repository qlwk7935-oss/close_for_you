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
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.core.contract.PopupRuleRepository
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.core.model.UserChoice
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
import kr.closeforyou.ui.voice.overlay.ChoiceOrigin
import kr.closeforyou.ui.voice.overlay.ChoicePromptPresenter
import kr.closeforyou.ui.voice.overlay.OverlayWindowKeeper
import kr.closeforyou.ui.voice.overlay.OverlayWindowOps
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import kr.closeforyou.ui.voice.overlay.PromptAnswer
import kr.closeforyou.ui.voice.overlay.PromptShowResult
import kr.closeforyou.ui.voice.overlay.RemoveOutcome
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
        private val keeper = OverlayWindowKeeper(object : OverlayWindowOps<Long> {
            override fun add(window: Long) {
                if (addFails) throw IllegalStateException("BadToken")
            }
            override fun announceShown(window: Long) = Unit
            override fun announceDismissed(window: Long) = Unit
            override fun stop(window: Long) = Unit
            override fun remove(window: Long, immediate: Boolean) =
                if (removeFails) RemoveOutcome.FAILED else RemoveOutcome.REMOVED
            override fun blank(window: Long) = Unit
            override fun destroy(window: Long) = Unit
        })

        val prompt: ChoicePrompt? get() = if (isShowing) last?.prompt else null
        val status: OverlayWindowStatus get() = keeper.status
        override val isShowing: Boolean get() = keeper.status == OverlayWindowStatus.SHOWING
        override val windowState get() = OverlayWindowState(keeper.status, keeper.windowGeneration)

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
    ) {
        val source = MockSnapshotSource()
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
    ): Harness {
        val h = Harness(this, status, remember, store, autoClose)
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
}
