package kr.closeforyou.simpleclose

import kr.closeforyou.simpleclose.ReclaimDeferral.DEFER_MILLIS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F47: a focus move we did not make waits a little, and the person's touch in that time makes it theirs. */
class F47Test {
    private fun after(moveAt: Long, lastUserAt: Long, active: Boolean = true, newer: Boolean = false, back: Boolean = false, done: Int = 0, max: Int = 3) =
        ReclaimDeferral.afterDefer(active, moveAt, lastUserAt, newer, back, done, max)

    @Test fun aSwipeWhoseTouchComesLateIsThePersons() {
        // 우진 SM-F966N: move at 25 131, the touch event at 25 348 (217 ms later), inside the 400 ms wait.
        assertEquals(DeferVerdict.SKIPPED_USER_MOVE, after(moveAt = 25_131, lastUserAt = 25_348))
        // Also when the touch already ended the watch.
        assertEquals(DeferVerdict.SKIPPED_USER_MOVE, after(moveAt = 25_131, lastUserAt = 25_348, active = false))
        assertTrue(25_348 - 25_131 < DEFER_MILLIS)
    }

    @Test fun aTouchJustBeforeTheMoveIsTheSwipeThatMadeIt() {
        assertEquals(DeferVerdict.SKIPPED_USER_MOVE, after(moveAt = 10_000, lastUserAt = 9_600))
        assertEquals(DeferVerdict.SKIPPED_USER_MOVE, after(moveAt = 10_000, lastUserAt = 9_500))
        // Longer ago: not this move's.
        assertEquals(DeferVerdict.RECLAIM, after(moveAt = 10_000, lastUserAt = 9_400))
    }

    @Test fun noTouchTakesItBack() {
        // The page pulled the focus away by itself (P04 direct close): still taken back, 400 ms later.
        assertEquals(DeferVerdict.RECLAIM, after(moveAt = 10_000, lastUserAt = -1))
        assertEquals(DeferVerdict.ENDED, after(moveAt = 10_000, lastUserAt = -1, active = false))
        assertEquals(DeferVerdict.SUPERSEDED, after(moveAt = 10_000, lastUserAt = -1, newer = true))
        assertEquals(DeferVerdict.ALREADY_BACK, after(moveAt = 10_000, lastUserAt = -1, back = true))
    }

    @Test fun theLimitsStay() {
        assertEquals(DeferVerdict.LIMIT, after(moveAt = 10_000, lastUserAt = -1, done = 3, max = 3))
        assertEquals(DeferVerdict.RECLAIM, after(moveAt = 10_000, lastUserAt = -1, done = 1, max = ReclaimDeferral.PROMPT_HOLD_MAX))
        assertEquals(DeferVerdict.LIMIT, after(moveAt = 10_000, lastUserAt = -1, done = 2, max = ReclaimDeferral.PROMPT_HOLD_MAX))
    }

    @Test fun thePressesOwnDoubleTapIsNotATouch() {
        // F17: a touch within 800 ms after the watch was armed is the [닫기] double tap.
        assertEquals(-1L, ReclaimDeferral.userTouchAfterGrace(lastTouchAt = 1_500, armedAt = 1_000, graceMillis = 800))
        assertEquals(1_900L, ReclaimDeferral.userTouchAfterGrace(lastTouchAt = 1_900, armedAt = 1_000, graceMillis = 800))
        assertEquals(-1L, ReclaimDeferral.userTouchAfterGrace(lastTouchAt = -1, armedAt = 1_000, graceMillis = 800))
    }

    @Test fun thePromptHoldLastsTwoSeconds() {
        assertTrue(ReclaimDeferral.promptHoldOn(startedAt = 1_000, now = 1_000))
        assertTrue(ReclaimDeferral.promptHoldOn(startedAt = 1_000, now = 3_000))
        assertFalse(ReclaimDeferral.promptHoldOn(startedAt = 1_000, now = 3_001))
        assertFalse(ReclaimDeferral.promptHoldOn(startedAt = 1_000, now = 900))
    }
}
