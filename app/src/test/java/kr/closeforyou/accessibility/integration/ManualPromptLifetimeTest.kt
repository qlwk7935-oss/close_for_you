package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.*
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

class ManualPromptLifetimeTest {
    private var now = 1_000L
    private val token = F9AutoCloserTest.Fixture().token
    private val state = OverlayWindowState(OverlayWindowStatus.SHOWING, 2)
    private val lifetime = ManualPromptLifetime { now }.also { it.shown(token, state) }
    private val owner = Any()
    private fun native(generation: Long = 2) = OverlayNativeWindowLifetime(generation, owner).also {
        it.attached(99); it.addReturned(true)
    }
    private fun owned(type: Int, eventTime: Long = now, eventId: Int = 99, pkg: String? = "synthetic.own",
        n: OverlayNativeWindowLifetime = native(), live: Set<Int>? = setOf(99), before: Set<Int>? = emptySet(),
        windowState: OverlayWindowState = state) = inspectManualOwnedInteraction(type, windowState, lifetime,
        { eventTime }, { pkg }, "synthetic.own", { eventId }, { n.liveWindow(owner, windowState.generation) }, before, { live })

    @Test fun lgStyleInternalFocusAndClickUseNativeGenerationAndLiveWindow() {
        for (type in listOf(E.TYPE_VIEW_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, E.TYPE_VIEW_CLICKED)) assertTrue(owned(type))
    }
    @Test fun originalEvidenceExpiryDoesNotExpireExplicitIntentAtSixSeconds() {
        now += 6_000; assertEquals(token, lifetime.current()); assertTrue(owned(E.TYPE_VIEW_CLICKED))
        lifetime.clear(); assertNull(lifetime.current())
    }
    @Test fun displayDeadlineAndClockReversalRemainTerminal() {
        now += 29_999; assertEquals(token, lifetime.current())
        now++; assertNull(lifetime.current()); now = 1_001; assertNull(lifetime.current())
    }
    @Test fun eventBeforeCurrentShowIsNotExemptEvenWithReusedNativeId() {
        assertFalse(owned(E.TYPE_VIEW_ACCESSIBILITY_FOCUSED, eventTime = 999))
        assertFalse(owned(E.TYPE_VIEW_CLICKED, n = native(1)))
        assertFalse(owned(E.TYPE_VIEW_CLICKED, before = setOf(99)))
    }
    @Test fun rejectedNativeControlCannotFallBackToLessStrictFocusPolicy() {
        var fallbacks = 0
        for (type in listOf(E.TYPE_VIEW_CLICKED, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED)) {
            assertFalse(inspectManualEvent(type, { owned(type, eventTime = 999) }, { fallbacks++; true }))
        }
        assertEquals(0, fallbacks)
        assertTrue(inspectManualEvent(E.TYPE_WINDOW_CONTENT_CHANGED, { error("UNEXPECTED_CONTROL") }, { fallbacks++; true }))
        assertEquals(1, fallbacks)
    }
    @Test fun wrongOwnershipUnknownIdentityAndUncertainLiveSetRemainExternal() {
        assertFalse(owned(E.TYPE_VIEW_CLICKED, pkg = "synthetic.external"))
        assertFalse(owned(E.TYPE_VIEW_CLICKED, pkg = null))
        assertFalse(owned(E.TYPE_VIEW_CLICKED, eventId = 20))
        assertFalse(owned(E.TYPE_VIEW_CLICKED, live = null))
        assertFalse(owned(E.TYPE_VIEW_CLICKED, live = setOf(98, 99)))
        assertFalse(owned(E.TYPE_VIEW_CLICKED, before = null))
        assertFalse(owned(E.TYPE_VIEW_CLICKED, eventTime = now + 1))
    }
    @Test fun retiredDetachedForeignOrFailedNativeLifetimeGrantsNothing() {
        for (n in listOf(native().also { it.removalRequested() },
            native().also { it.removalRequested(); it.detached() },
            OverlayNativeWindowLifetime(2, Any()).also { it.attached(99); it.addReturned(true) },
            native().also { it.addReturned(false) })) assertFalse(owned(E.TYPE_VIEW_CLICKED, n = n))
    }
    @Test fun touchAndScrollNeverUseOwnedFocusExemption() {
        for (type in listOf(E.TYPE_TOUCH_INTERACTION_START, E.TYPE_TOUCH_INTERACTION_END,
            E.TYPE_VIEW_SCROLLED, E.TYPE_WINDOW_STATE_CHANGED)) assertFalse(owned(type))
    }
    @Test fun onlyIdentityFreeGlobalTouchMayPreserveDisplayNotEvidence() {
        for (type in listOf(E.TYPE_TOUCH_INTERACTION_START, E.TYPE_TOUCH_INTERACTION_END,
            E.TYPE_TOUCH_EXPLORATION_GESTURE_START, E.TYPE_TOUCH_EXPLORATION_GESTURE_END)) {
            assertTrue(unattributedManualTouch(type, -1, null))
            assertFalse(unattributedManualTouch(type, 20, null))
            assertFalse(unattributedManualTouch(type, -1, "synthetic.external"))
        }
        assertFalse(unattributedManualTouch(E.TYPE_VIEW_SCROLLED, -1, null))
    }
    @Test fun presentationTokenDoesNotMoveToNextPopupOrNewGeneration() {
        lifetime.clear(); assertFalse(owned(E.TYPE_VIEW_CLICKED))
        val next = token.copy(popupInstanceId = "next", screenVersion = 3, captureEpoch = 3)
        val nextState = state.copy(generation = 3); lifetime.shown(next, nextState)
        assertEquals(next, lifetime.current()); assertFalse(owned(E.TYPE_VIEW_CLICKED))
    }
    @Test fun diagnosticRepresentationDoesNotRetainSensitivePayload() {
        assertEquals("ManualPromptLifetime(redacted)", lifetime.toString())
    }
    @Test fun actualGuardAcceptsOneSixSecondIntentAfterCHidesButDoesNotExecuteIt() {
        val prompt = ChoicePromptPlanner.plan(MockPopupFixtures.notice().analysis, PopupClassification.NOTICE)
        lifetime.clear()
        var rawCurrent: SnapshotToken? = prompt.token
        var callback: ((PromptAnswer) -> Unit)? = null
        var window = OverlayWindowState.HIDDEN
        val port = object : ChoicePromptPresenter {
            override val windowState get() = window
            override val isShowing get() = window.status == OverlayWindowStatus.SHOWING
            override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
                window = OverlayWindowState(OverlayWindowStatus.SHOWING, 1)
                lifetime.shown(prompt.token, window); callback = onAnswer
                return PromptShowResult.SHOWN
            }
            override fun dismiss() { window = OverlayWindowState.HIDDEN }
        }
        val accepted = mutableListOf<UserChoice>()
        val guard = GuardedChoicePresenter(port, { lifetime.current() ?: rawCurrent }, { false }, ChoicePresentationHealth()) {}
        assertEquals(PromptShowResult.SHOWN, guard.present(prompt) { if (it is PromptAnswer.Picked) accepted += it.choice })
        now += 6_000
        rawCurrent = null // Runtime's original execution evidence really expires.
        val choice = ChoicePromptPlanner.toUserChoice(prompt, prompt.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        window = OverlayWindowState.HIDDEN // C removes before callback; cleanup proof is still required later.
        repeat(2) { callback?.invoke(PromptAnswer.Picked(choice, ChoiceOrigin.TAP, 1)) }
        assertEquals(listOf(choice), accepted) // Intent only: this port has no action executor.
    }
    @Test fun nestedTalkBackExplorationAndTouchRequireBothEndsBeforeExecution() {
        val touches = ManualTouchState()
        touches.observe(E.TYPE_TOUCH_EXPLORATION_GESTURE_START, true)
        touches.observe(E.TYPE_TOUCH_INTERACTION_START, true)
        assertTrue(touches.active); assertFalse(touches.intentBlocked)
        touches.observe(E.TYPE_TOUCH_INTERACTION_END, true)
        assertTrue(touches.active) // display may remain, but F9 userInteracting stays true
        touches.observe(E.TYPE_TOUCH_EXPLORATION_GESTURE_END, true)
        assertFalse(touches.active)
    }
    @Test fun attributedExternalTouchBlocksIntentEvenWithUnattributedExploration() {
        val touches = ManualTouchState()
        touches.observe(E.TYPE_TOUCH_EXPLORATION_GESTURE_START, true)
        touches.observe(E.TYPE_TOUCH_INTERACTION_START, false)
        assertTrue(touches.active); assertTrue(touches.intentBlocked)
        touches.clear(); assertFalse(touches.active); assertFalse(touches.intentBlocked)
    }
}
