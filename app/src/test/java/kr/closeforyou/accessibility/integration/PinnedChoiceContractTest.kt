package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.*
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

/** Actual pinned C keeper + A boundary, with fake framework operations only. */
class PinnedChoiceContractTest {
    private class Port : ChoicePromptPresenter {
        var failCreate = false; var failAdd = false; var failRemove = false
        var destroys = 0; var stops = 0; var creates = 0
        var answer: ((UserChoice, ChoiceOrigin) -> Unit)? = null
        var rawAnswer: ((PromptAnswer) -> Unit)? = null
        var reported: OverlayWindowState? = null
        val keeper = OverlayWindowKeeper(object : OverlayWindowOps<Any> {
            override fun add(window: Any) { if (failAdd) error("PRIVATE_ADD") }
            override fun announceShown(window: Any) = Unit
            override fun announceDismissed(window: Any) = Unit
            override fun stop(window: Any) { stops++ }
            override fun remove(window: Any, immediate: Boolean) = if (failRemove) RemoveOutcome.FAILED else RemoveOutcome.REMOVED
            override fun blank(window: Any) = Unit
            override fun destroy(window: Any) { destroys++ }
        })
        override val isShowing get() = keeper.status == OverlayWindowStatus.SHOWING
        override val windowState get() = reported ?: OverlayWindowState(keeper.status, keeper.windowGeneration)
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult = keeper.show { generation ->
            creates++
            if (failCreate) error("PRIVATE_CREATE")
            rawAnswer = onAnswer
            answer = { choice, origin -> keeper.deliver(PromptAnswer.Picked(choice, origin, generation),
                choice.kind != ChoiceKind.READ_CONTENT, onAnswer) }
            Any()
        }
        override fun dismiss() = keeper.dismiss()
    }
    private class Fixture {
        val f = MockPopupFixtures.notice()
        val prompt = ChoicePromptPlanner.plan(f.analysis, PopupClassification.NOTICE)
        val port = Port(); val health = ChoicePresentationHealth()
        var current: SnapshotToken? = prompt.token; var failed = 0
        val picked = mutableListOf<PromptAnswer>()
        val guard = GuardedChoicePresenter(port, { current }, { false }, health) { failed++ }
        fun present() = guard.present(prompt) { picked += it }
        fun choice(kind: PromptOptionKind = PromptOptionKind.PRESS_CANDIDATE) =
            ChoicePromptPlanner.toUserChoice(prompt, prompt.options.first { it.kind == kind })
    }
    @Test fun shownRequiresCGenerationAndClosedWindowForATerminalChoice() {
        val h = Fixture(); assertEquals(PromptShowResult.SHOWN, h.present())
        val generation = h.guard.windowState.generation
        assertTrue(generation > 0); assertTrue(h.guard.isShowing)
        h.port.answer!!(h.choice(), ChoiceOrigin.TAP)
        val answer = h.picked.single() as PromptAnswer.Picked
        assertEquals(generation, answer.generation); assertEquals(ChoiceOrigin.TAP, answer.origin)
        assertEquals(OverlayWindowState.HIDDEN, h.guard.windowState); assertFalse(h.health.failed)
    }
    @Test fun creationOrAddFailedResultNeverBecomesAShownAChoice() {
        for (add in listOf(false, true)) {
            val h = Fixture(); h.port.failCreate = !add; h.port.failAdd = add
            assertEquals(PromptShowResult.FAILED, h.present())
            assertTrue(h.health.failed); assertTrue(h.picked.isEmpty()); assertFalse(h.guard.isShowing)
            assertEquals(PromptShowResult.BLOCKED, h.present()); assertEquals(1, h.port.creates)
        }
    }
    @Test fun blockedStuckCWindowPreventsNewAPromptAndCallbacks() {
        val h = Fixture()
        assertEquals(PromptShowResult.SHOWN, h.port.present(h.prompt) {})
        h.port.failRemove = true
        assertEquals(PromptShowResult.BLOCKED, h.present())
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, h.guard.windowState.status)
        assertTrue(h.health.failed); assertEquals(1, h.port.creates); assertTrue(h.picked.isEmpty())
    }
    @Test fun actualCLostDropsTheChoiceAndDoesNotRecoverAHealthAfterCRemovalSucceeds() {
        val h = Fixture(); h.present(); h.port.failRemove = true
        h.port.answer!!(h.choice(), ChoiceOrigin.TAP)
        assertTrue(h.picked.isEmpty()); assertTrue(h.health.failed); assertEquals(1, h.failed)
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, h.guard.windowState.status)
        h.port.failRemove = false; h.guard.dismiss()
        assertEquals(OverlayWindowState.HIDDEN, h.guard.windowState)
        assertEquals(PromptShowResult.BLOCKED, h.present())
        assertEquals(1, h.port.stops); assertEquals(1, h.port.destroys)
    }
    @Test fun readKeepsItsCGenerationWhileTerminalDuplicateCannotBeDelivered() {
        val h = Fixture(); h.present(); val read = h.choice(PromptOptionKind.READ_CONTENT)
        h.port.answer!!(read, ChoiceOrigin.TAP); h.port.answer!!(read, ChoiceOrigin.TAP)
        assertEquals(2, h.picked.size); assertTrue(h.guard.isShowing)
        h.port.answer!!(h.choice(), ChoiceOrigin.TAP); h.port.answer!!(h.choice(), ChoiceOrigin.TAP)
        assertEquals(3, h.picked.size); assertEquals(OverlayWindowState.HIDDEN, h.guard.windowState)
    }
    @Test fun staleCGenerationAndRevokedAPromptCannotReplaceCurrentChoice() {
        val h = Fixture(); h.present(); val old = h.port.answer!!
        h.guard.dismiss(); h.present()
        old(h.choice(), ChoiceOrigin.TAP); assertTrue(h.picked.isEmpty())
        val generation = h.guard.windowState.generation
        h.port.rawAnswer!!(PromptAnswer.Picked(h.choice(), ChoiceOrigin.TAP, generation - 1))
        assertTrue(h.picked.isEmpty()); assertFalse(h.health.failed)
        h.guard.revoke(); h.port.answer!!(h.choice(), ChoiceOrigin.TAP)
        assertTrue(h.picked.isEmpty())
    }
    @Test fun moveCannotCreateFocusOrActionPermission() {
        val h = Fixture(); h.present()
        val generation = h.guard.windowState.generation
        h.port.rawAnswer!!(PromptAnswer.Move(MoveToButtonRequest(h.prompt.token, "SYNTHETIC_TARGET"), ChoiceOrigin.TAP, generation))
        assertTrue(h.picked.isEmpty()); assertTrue(h.health.failed)
    }
    @Test fun shownWithMissingOrMalformedWindowStateIsRejected() {
        for (state in listOf(OverlayWindowState.HIDDEN, OverlayWindowState(OverlayWindowStatus.SHOWING, 0),
            OverlayWindowState(OverlayWindowStatus.SHOWING, -1), OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, 1))) {
            val h = Fixture(); h.port.reported = state
            assertEquals(PromptShowResult.FAILED, h.present())
            assertTrue(h.health.failed); assertTrue(h.picked.isEmpty())
        }
    }
    @Test fun dismissThatReturnsNormallyButLeavesRemoveFailedIsStillAFailure() {
        val h = Fixture(); h.present(); h.port.failRemove = true
        val error = assertThrows(IllegalStateException::class.java) { h.guard.dismiss() }
        assertEquals("CHOICE_DISMISS_FAILED", error.message)
        assertTrue(h.health.failed); assertEquals(OverlayWindowStatus.REMOVE_FAILED, h.guard.windowState.status)
    }
    @Test fun candidateEventWindowMustMatchBothCMetadataAndAShowGeneration() {
        val h = Fixture(); h.present()
        val state = h.guard.windowState
        val facts = WindowFacts(20, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY, "SYNTHETIC_TITLE", "SYNTHETIC_PACKAGE")
        assertTrue(ChoiceOverlayIdentity.isOwnOverlayWindow(facts, "SYNTHETIC_PACKAGE", "SYNTHETIC_TITLE", state))
        val binding = OwnedOverlayBinding(); binding.expect(emptySet(), h.prompt.token, state.generation)
        assertTrue(binding.matches(20, setOf(20), h.prompt.token, state.generation))
        assertFalse(binding.matches(20, setOf(20), h.prompt.token, state.generation + 1))
        assertFalse(binding.matches(30, setOf(20), h.prompt.token, state.generation))
        val stuck = OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, state.generation)
        assertTrue(ChoiceOverlayIdentity.isOwnOverlayWindow(facts, "SYNTHETIC_PACKAGE", "SYNTHETIC_TITLE", stuck))
        assertFalse(OwnedOverlayEventPolicy.mayTrack(stuck)) // A deliberately keeps the stricter invalidation policy.
        assertFalse(OwnedOverlayEventPolicy.mayTrack(OverlayWindowState.HIDDEN))
        assertFalse(ChoiceOverlayIdentity.isOwnOverlayWindow(facts.copy(title = "OTHER"), "SYNTHETIC_PACKAGE", "SYNTHETIC_TITLE", state))
    }
}
