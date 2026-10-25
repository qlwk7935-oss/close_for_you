package kr.closeforyou.ui.voice.overlay

import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review #18-1·2·3: window add/remove failures, exactly-once cleanup, stale callbacks. */
class OverlayWindowKeeperTest {
    /** Records every step; windows are named after their generation ("w1", "w2", ...). */
    private class FakeOps : OverlayWindowOps<String> {
        val log = mutableListOf<String>()
        var addThrows = false
        var announceThrows = false
        var cleanupThrows = false
        /** Next removal results; empty = REMOVED. */
        val removeResults = ArrayDeque<RemoveOutcome>()

        override fun add(window: String) {
            log += "add $window"
            if (addThrows) throw IllegalStateException("BadToken")
        }
        override fun announceShown(window: String) {
            log += "shown $window"
            if (announceThrows) throw IllegalStateException("listener")
        }
        override fun announceDismissed(window: String) {
            log += "dismissed $window"
            if (cleanupThrows) throw IllegalStateException("listener")
        }
        override fun stop(window: String) {
            log += "stop $window"
            if (cleanupThrows) throw IllegalStateException("stop")
        }
        override fun remove(window: String, immediate: Boolean): RemoveOutcome {
            log += if (immediate) "removeNow $window" else "remove $window"
            return removeResults.removeFirstOrNull() ?: RemoveOutcome.REMOVED
        }
        override fun blank(window: String) {
            log += "blank $window"
        }
        override fun destroy(window: String) {
            log += "destroy $window"
            if (cleanupThrows) throw IllegalStateException("destroy")
        }
        override fun enterRevalidating(window: String) {
            log += "revalidate $window"
        }

        fun count(step: String) = log.count { it == step }
    }

    private val ops = FakeOps()
    private val keeper = OverlayWindowKeeper(ops)
    private val created = mutableListOf<Long>()
    private fun show() = keeper.show { created += it; "w$it" }

    private val choice = UserChoice(MockPopupFixtures.advertisement().snapshot.token, ChoiceKind.CLOSE_ONCE, "b")
    private val answers = mutableListOf<PromptAnswer>()
    private fun tap(generation: Long, closes: Boolean = true, origin: ChoiceOrigin = ChoiceOrigin.TAP) =
        keeper.deliver(PromptAnswer.Picked(choice, origin, generation), closes) { answers += it }

    // --- normal path ---

    @Test fun showThenDismissCleansUpOnce() {
        assertEquals(PromptShowResult.SHOWN, show())
        assertEquals(OverlayWindowStatus.SHOWING, keeper.status)
        assertTrue(keeper.isCurrent(1))
        keeper.dismiss()
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(
            listOf("add w1", "shown w1", "dismissed w1", "stop w1", "remove w1", "destroy w1"),
            ops.log,
        )
    }

    @Test fun dismissTwiceAndDismissWhileHiddenDoNothingMore() {
        keeper.dismiss()
        show()
        keeper.dismiss()
        val after = ops.log.size
        keeper.dismiss()
        assertEquals(after, ops.log.size)
    }

    @Test fun showRightAfterShowClosesTheFirst() {
        show()
        show()
        assertFalse(keeper.isCurrent(1))
        assertTrue(keeper.isCurrent(2))
        assertEquals(1, ops.count("destroy w1"))
        assertEquals(0, ops.count("destroy w2"))
    }

    @Test fun showThenImmediateDismissLeavesNothingCurrent() {
        show()
        keeper.dismiss()
        tap(1)
        assertTrue(answers.isEmpty())
        assertEquals(OverlayWindowKeeper.NO_GENERATION, keeper.currentGeneration)
    }

    // --- review 1: removal failure ---

    @Test fun failedRemovalIsNotHiddenAndKillsCallbacksAtOnce() {
        show()
        ops.removeResults += RemoveOutcome.FAILED
        keeper.dismiss()
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, keeper.status)
        assertFalse(keeper.isCurrent(1))
        assertEquals("the stuck window is still C's", 1L, keeper.windowGeneration)
        assertEquals(1, ops.count("blank w1"))
        assertEquals("stop once", 1, ops.count("stop w1"))
        assertEquals("destroy once even though removal failed", 1, ops.count("destroy w1"))
        tap(1)
        assertTrue("a late tap of the stuck window goes nowhere", answers.isEmpty())
    }

    @Test fun nextDismissRetriesWithRemoveImmediateWithoutSecondDestroy() {
        show()
        ops.removeResults += RemoveOutcome.FAILED
        keeper.dismiss()
        ops.removeResults += RemoveOutcome.FAILED
        keeper.dismiss()
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, keeper.status)
        keeper.dismiss()
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(2, ops.count("removeNow w1"))
        assertEquals(1, ops.count("destroy w1"))
        assertEquals(1, ops.count("stop w1"))
    }

    @Test fun stuckWindowBlocksNewPromptUntilRemoved() {
        show()
        ops.removeResults += RemoveOutcome.FAILED
        keeper.dismiss()
        ops.removeResults += RemoveOutcome.FAILED
        assertEquals(PromptShowResult.BLOCKED, show())
        assertEquals("nothing new is created", listOf(1L), created)
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, keeper.status)

        assertEquals(PromptShowResult.SHOWN, show())
        assertEquals(listOf(1L, 2L), created)
        assertTrue(keeper.isCurrent(2))
    }

    @Test fun notAttachedOnRetryCountsAsGone() {
        show()
        ops.removeResults += RemoveOutcome.FAILED
        keeper.dismiss()
        ops.removeResults += RemoveOutcome.NOT_ATTACHED
        keeper.dismiss()
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
    }

    @Test fun choiceWhoseWindowCannotCloseBecomesLost() {
        show()
        ops.removeResults += RemoveOutcome.FAILED
        tap(1)
        assertEquals(listOf<PromptAnswer>(PromptAnswer.Lost(1)), answers)
        tap(1)
        assertEquals("second tap dropped", 1, answers.size)
    }

    @Test fun cleanupThatThrowsStillRunsEveryStepOnce() {
        show()
        ops.cleanupThrows = true
        keeper.dismiss()
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(listOf("dismissed w1", "stop w1", "remove w1", "destroy w1"), ops.log.drop(2))
    }

    // --- review 2: show failure ---

    @Test fun createFailureShowsNothing() {
        assertEquals(PromptShowResult.FAILED, keeper.show { throw IllegalStateException("compose") })
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertTrue(ops.log.isEmpty())
        assertEquals(PromptShowResult.SHOWN, show())
    }

    @Test fun addFailureReleasesEverythingAndStaysHidden() {
        ops.addThrows = true
        ops.removeResults += RemoveOutcome.NOT_ATTACHED
        assertEquals(PromptShowResult.FAILED, show())
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(listOf("add w1", "stop w1", "removeNow w1", "destroy w1"), ops.log)
        assertFalse(keeper.isCurrent(1))
        tap(1)
        assertTrue(answers.isEmpty())
    }

    @Test fun addFailureWithWindowLeftIsStuck() {
        ops.addThrows = true
        ops.removeResults += RemoveOutcome.FAILED
        assertEquals(PromptShowResult.FAILED, show())
        assertEquals(OverlayWindowStatus.REMOVE_FAILED, keeper.status)
        assertEquals(1, ops.count("destroy w1"))
        ops.addThrows = false
        assertEquals("retried and cleared before the next prompt", PromptShowResult.SHOWN, show())
        assertEquals(2, ops.count("removeNow w1"))
    }

    @Test fun announceFailureRemovesTheWindow() {
        ops.announceThrows = true
        assertEquals(PromptShowResult.FAILED, show())
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
        assertEquals(1, ops.count("remove w1"))
        assertEquals(1, ops.count("destroy w1"))
        assertEquals(1, ops.count("stop w1"))
        tap(1)
        assertTrue(answers.isEmpty())
    }

    // --- review 3 / regression: stale generations ---

    @Test fun readContentKeepsWindowAndLaterChoiceStillArrives() {
        show()
        tap(1, closes = false)
        assertTrue(keeper.isCurrent(1))
        tap(1, origin = ChoiceOrigin.VOICE)
        assertEquals(2, answers.size)
        assertEquals(ChoiceOrigin.VOICE, (answers.last() as PromptAnswer.Picked).origin)
        assertEquals(OverlayWindowStatus.HIDDEN, keeper.status)
    }

    @Test fun oldGenerationAfterNewShowIsDropped() {
        show()
        show()
        tap(1)
        assertTrue(answers.isEmpty())
        assertTrue(keeper.isCurrent(2))
        tap(2)
        assertEquals(1, answers.size)
    }

    @Test fun reconnectedOverlayDoesNotTakeOldInstanceCallbacks() {
        // Service ends while removal fails; a new overlay instance starts again at generation 1.
        show()
        ops.removeResults += RemoveOutcome.FAILED
        keeper.dismiss()
        val fresh = OverlayWindowKeeper(FakeOps())
        fresh.show { "new$it" }
        assertTrue(fresh.isCurrent(1))
        // The old window's callback is bound to the old keeper, which no longer has anything current.
        tap(1)
        assertTrue(answers.isEmpty())
    }

    @Test fun noGenerationIsNeverCurrent() {
        assertFalse(keeper.isCurrent(OverlayWindowKeeper.NO_GENERATION))
        show()
        assertFalse(keeper.isCurrent(OverlayWindowKeeper.NO_GENERATION))
    }
}
