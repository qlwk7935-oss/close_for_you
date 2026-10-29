package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import kotlinx.collections.immutable.persistentSetOf
import kr.closeforyou.accessibility.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateWiring
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.choice.*
import kr.closeforyou.ui.voice.integration.*
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

/** Real C collectLatest/awaitAnswer + B wiring + A guard and invalidation operation.
 * Only Android windows, TTS and F0 are fake; no LG/TalkBack event origin is fabricated. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualDisplayFlowTest {
    private class Rig(val scope: TestScope) {
        val input = MockPopupFixtures.notice()
        var now = input.snapshot.capturedAtUptimeMillis
        var raw: SnapshotToken? = input.snapshot.token
        val lifetime = ManualPromptLifetime { now }
        val stream = MutableSharedFlow<PopupEvent>(extraBufferCapacity = 1)
        var state = OverlayWindowState.HIDDEN
        var shows = 0; var dismisses = 0; var stops = 0; var handled = 0; var verifies = 0
        var callback: ((PromptAnswer) -> Unit)? = null
        var prompt: ChoicePrompt? = null
        val traceRows = mutableListOf<Triple<Long, ManualPromptPoint, ManualPromptCause>>()
        val trace = ManualPromptTrace { a, p, c -> traceRows += Triple(a, p, c) }
        val source = object : SnapshotSource {
            override val events = stream
            override fun currentToken() = lifetime.current() ?: raw
        }
        val gate = ChoicePresentationGate({ now }, { source.currentToken() == it }, { true }, { true },
            { _, _ -> verifies++; true }, {})
        val wiring = DecisionCandidateWiring(source, object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot) = input.analysis
        })
        val delegate = object : ChoicePromptPresenter {
            override val windowState get() = state
            override val isShowing get() = state.status == OverlayWindowStatus.SHOWING
            override fun present(value: ChoicePrompt, answer: (PromptAnswer) -> Unit): PromptShowResult {
                shows++; state = OverlayWindowState(OverlayWindowStatus.SHOWING, shows.toLong())
                lifetime.shown(value.token, state); prompt = value; callback = answer
                trace.record(ManualPromptPoint.SHOWN)
                return PromptShowResult.SHOWN
            }
            override fun dismiss() { dismisses++; state = OverlayWindowState.HIDDEN }
        }
        val guarded = GuardedChoicePresenter(delegate, source::currentToken, { false }, ChoicePresentationHealth()) {}
        val announcer = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest) = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override fun stop() { stops++ }
        }
        val flow = PopupChoiceFlow(wiring.source, object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
                val a = wiring.extractor.extract(snapshot)
                check(gate.awaitReady(snapshot.token)); return a
            }
        }, DecisionPopupClassifier(), guarded, announcer, AnnouncementBatcher(scope.backgroundScope, onFlush = {}),
            ChoiceHandler { _, _ -> handled++; null }, UiText { "SYNTHETIC" }, { "SYNTHETIC" },
            rememberChoicesEnabled = false, revalidatingEnabled = false)
        val job = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { flow.run() }
        suspend fun show() {
            trace.begin(); gate.prepare(input.snapshot, Any())
            stream.emit(PopupEvent.Candidate(input.snapshot, PopupDetection(input.snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW))))
            scope.runCurrent(); assertEquals(1, shows); assertEquals(1, verifies)
        }
        fun invalidate(cause: ManualPromptCause) {
            raw = null // Runtime/automatic evidence is ALWAYS invalidated, independently of C.
            gate.clear()
            val retained = applyManualInvalidation(cause, lifetime.current() != null, true, false, false,
                revoke = { guarded.revoke(); lifetime.clear() }, remove = {
                    stream.tryEmit(PopupEvent.Invalidated(input.snapshot.token.screenVersion + 1))
                    delegate.dismiss(); announcer.stop()
                })
            trace.record(if (retained) ManualPromptPoint.DISPLAY_RETAINED else ManualPromptPoint.INVALIDATED, cause)
        }
        fun reviewBounds(changed: Boolean): ManualDisplayVerdict {
            val s = input.snapshot
            check(manualDisplayReviewEvent(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, s.token.windowId, s.token.windowId))
            val windows = s.windows.map {
                it.copy(active = false, focused = false, bounds = if (changed) it.bounds.copy(right = it.bounds.right + 1) else it.bounds)
            } + WindowSnapshot(99, WindowType.ACCESSIBILITY_OVERLAY, s.screenBounds, true, true, 5)
            val scene = kr.closeforyou.accessibility.action.F9Scene(s.token.packageName, s.token.windowId, s.token.windowId,
                s.screenBounds, windows, s.nodes, s.nodes.mapTo(mutableSetOf()) { it.path }, true)
            return reviewManualDisplay(retire = { invalidate(ManualPromptCause.DISPLAY_REVIEW_PENDING) },
                verify = { ManualDisplayContinuity.assess(s, scene, 99) },
                remove = { invalidate(ManualPromptCause.WINDOW_SET_INVALIDATED) },
                onChecked = { trace.displayReview(ManualDisplayReviewCode(ManualEventKind.WINDOW_SET, ManualWindowMask.BOUNDS, it)) })
        }
    }
    @Test fun targetBoundsNotificationWithUnchangedWholePopupKeepsRealCFlowAndSpeechBeyondEvidenceTtl() = runTest {
        val r = Rig(this); r.show(); r.now += 6_001
        assertEquals(ManualDisplayVerdict.SAME, r.reviewBounds(changed = false)); runCurrent()
        assertTrue(r.guarded.isShowing); assertEquals(1, r.shows); assertEquals(0, r.dismisses); assertEquals(0, r.stops)
        assertNull(r.raw); assertFalse(r.gate.hasCurrentObservation()); assertEquals(0, r.handled)
    }
    @Test fun targetBoundsActuallyChangedClosesRealCFlowAndRejectsItsOldAnswer() = runTest {
        val r = Rig(this); r.show(); val prompt = requireNotNull(r.prompt); val answer = requireNotNull(r.callback)
        assertEquals(ManualDisplayVerdict.CHANGED, r.reviewBounds(changed = true)); runCurrent()
        assertFalse(r.guarded.isShowing); assertTrue(r.stops > 0); assertNull(r.lifetime.current())
        val choice = ChoicePromptPlanner.toUserChoice(prompt, prompt.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        answer(PromptAnswer.Picked(choice, ChoiceOrigin.TAP, 1)); runCurrent(); assertEquals(0, r.handled)
    }
    @Test fun originalFiveSecondExpiryCannotCancelRealFlowOrSpeechOrNativeDisplay() = runTest {
        val r = Rig(this); r.show(); r.now += 5_000
        r.invalidate(ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED); runCurrent()
        r.now += 1_001; runCurrent()
        assertNull(r.raw); assertFalse(r.gate.hasCurrentObservation())
        assertTrue(r.guarded.isShowing); assertEquals(1, r.shows); assertEquals(0, r.dismisses); assertEquals(0, r.stops)
        assertEquals(r.input.snapshot.token, r.source.currentToken()) // Presentation intent only.
        val p = requireNotNull(r.prompt)
        val choice = ChoicePromptPlanner.toUserChoice(p, p.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        r.state = OverlayWindowState.HIDDEN
        repeat(2) { r.callback?.invoke(PromptAnswer.Picked(choice, ChoiceOrigin.TAP, 1)) }
        runCurrent(); assertEquals(1, r.handled) // Actual new-F0/B/F9 behavior has its own shared regression.
    }
    @Test fun everyExternalPolicyScreenAndHealthBoundaryStillClosesRealFlow() = runTest {
        for (cause in listOf(ManualPromptCause.INTERACTION_INVALIDATED, ManualPromptCause.SCREEN_EVENT_INVALIDATED,
            ManualPromptCause.POLICY_CHANGED, ManualPromptCause.SERVICE_STOPPED, ManualPromptCause.HEALTH_FAILED)) {
            val r = Rig(this); r.show(); r.invalidate(cause); runCurrent()
            assertEquals(OverlayWindowStatus.HIDDEN, r.state.status); assertTrue(r.stops > 0)
            assertEquals(0, r.handled); r.job.cancelAndJoin()
        }
    }
    @Test fun displayDeadlineAndCancellationDoNotLeaveOldCallbacksOrSpeechLive() = runTest {
        val r = Rig(this); r.show(); r.now += 30_000
        r.invalidate(ManualPromptCause.DISPLAY_LEASE_EXPIRED); runCurrent()
        assertNull(r.lifetime.current()); assertEquals(OverlayWindowStatus.HIDDEN, r.state.status)
        assertEquals(0, r.handled)
        val other = Rig(this); other.show(); other.job.cancelAndJoin()
        assertEquals(OverlayWindowStatus.HIDDEN, other.state.status); assertTrue(other.stops > 0)
    }
    @Test fun unattributedTouchInvalidatesEvidenceButNotThePresentationFlow() = runTest {
        val r = Rig(this); r.show(); r.invalidate(ManualPromptCause.UNATTRIBUTED_TOUCH); runCurrent()
        assertNull(r.raw); assertTrue(r.guarded.isShowing); assertEquals(0, r.handled); assertEquals(0, r.stops)
    }
    @Test fun focusAfterCollectionHasFinishedButEvidenceOrAdmissionIsPendingRequiresFreshReview() {
        for (type in listOf(E.TYPE_VIEW_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)) {
            assertTrue(pendingFocusReview(type, false, true, false))
            assertTrue(pendingFocusReview(type, false, false, true))
            assertFalse(pendingFocusReview(type, false, false, false))
            // Automatic first focus and user focus have no distinguishing input evidence.
            // Both invalidate; only an existing pending review can be carried forward.
            assertTrue(pendingFocusReview(type, true, false, false))
        }
        for (type in listOf(E.TYPE_VIEW_CLICKED, E.TYPE_VIEW_SCROLLED, E.TYPE_TOUCH_INTERACTION_START,
            E.TYPE_WINDOW_CONTENT_CHANGED)) assertFalse(pendingFocusReview(type, true, true, true))
    }
    @Test fun freshReviewMustRediscoverBoundedPopupInsteadOfReusingOldF1Signals() {
        val detector = PopupCandidateDetector()
        val frame = DetectionFrame("app", 20, setOf(10, 20), "structure", ScreenBounds(0, 0, 400, 800),
            emptyList(), PopupTrigger.WINDOW_CHANGED, ScreenBounds(50, 100, 350, 600))
        assertTrue(PopupSignal.NEW_WINDOW in detector.detect(frame)); assertTrue(detector.detect(frame).isEmpty())
        assertTrue(pendingFocusReview(E.TYPE_VIEW_ACCESSIBILITY_FOCUSED, false, true, false))
        detector.clear() // Actual service does this only while transferring an interrupted review.
        assertTrue(PopupSignal.NEW_WINDOW in detector.detect(frame))
        detector.clear()
        assertFalse(PopupSignal.NEW_WINDOW in detector.detect(frame.copy(windowBounds = frame.screenBounds)))
    }
    @Test fun traceContainsOnlyCodesIsBoundedAndThrowingSinkHasNoPolicyAuthority() {
        var calls = 0
        val trace = ManualPromptTrace { _, _, _ -> calls++; throw CancellationException("SYNTHETIC") }
        trace.begin(); repeat(100) { trace.record(ManualPromptPoint.INVALIDATED, ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED) }
        assertEquals(2, calls); assertEquals("ManualPromptTrace(redacted)", trace.toString())
        var revoked = false
        assertTrue(applyManualInvalidation(ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED, true, true, false, false,
            { revoked = true }, { error("MUST_RETAIN") }))
        assertFalse(revoked)
        assertFalse(applyManualInvalidation(ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED, true, false, false, false,
            { revoked = true }, {})); assertTrue(revoked)
    }
}
