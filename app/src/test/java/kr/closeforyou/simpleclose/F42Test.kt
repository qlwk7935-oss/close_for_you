package kr.closeforyou.simpleclose

import kr.closeforyou.simpleclose.ResultCardClock.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** F42: a result card with [방금 닫은 내용 듣기] stays while TalkBack's focus is in it, up to 15 s. */
class F42Test {
    private fun run(clock: ResultCardClock, focus: (Long) -> Boolean): Pair<Long, List<Step>> {
        val steps = mutableListOf<Step>()
        var t = 0L
        while (true) {
            t += ResultCardClock.CHECK_MILLIS
            val step = clock.tick(t, focus(t))
            if (step != Step.KEEP) steps += step
            if (step == Step.DISMISS || step == Step.DISMISS_HOLD_MAX) return t to steps
        }
    }

    @Test fun focusedCardStaysUntilFifteenSeconds() {
        val (goneAt, steps) = run(ResultCardClock(6_000)) { true }
        assertEquals(15_000L, goneAt)
        assertEquals(listOf(Step.HELD_FOCUS, Step.DISMISS_HOLD_MAX), steps)
    }

    @Test fun focusLeavingTakesTheCardTwoSecondsLater() {
        // Heard the sentence, swiped to the button, pressed it, swiped off the card at 9 s.
        val (goneAt, steps) = run(ResultCardClock(6_000)) { it < 9_000 }
        assertEquals(10_500L, goneAt)
        assertEquals(listOf(Step.HELD_FOCUS, Step.FOCUS_LEFT, Step.DISMISS), steps)
    }

    @Test fun focusComingBackKeepsItAgain() {
        val (goneAt, _) = run(ResultCardClock(6_000)) { it < 3_000 || it in 4_000..8_000 }
        assertEquals(10_000L, goneAt)
    }

    @Test fun neverFocusedGoesAtItsTimeout() {
        assertEquals(4_000L, run(ResultCardClock(4_000)) { false }.first)
        assertEquals(listOf(Step.DISMISS), run(ResultCardClock(4_000)) { false }.second)
    }

    @Test fun theTimeoutFollowsThePersonsSettingOnlyWithTheButton() {
        val longer: (Int) -> Int = { it * 5 }
        assertEquals(4_000L, ResultCardClock.timeoutMillis(takeFocus = false, hasButton = false, recommended = longer))
        assertEquals(6_000L, ResultCardClock.timeoutMillis(takeFocus = true, hasButton = false, recommended = longer))
        assertEquals(20_000L, ResultCardClock.timeoutMillis(takeFocus = false, hasButton = true, recommended = longer))
        // Never shorter than before.
        assertEquals(6_000L, ResultCardClock.timeoutMillis(takeFocus = true, hasButton = true) { 1_000 })
        assertEquals(4_000L, ResultCardClock.timeoutMillis(takeFocus = false, hasButton = true) { it })
    }

    @Test fun aLongSettingAlsoRaisesTheFocusedLimit() {
        // A 30 s setting: a focused card is not cut at 15 s.
        assertEquals(30_000L, run(ResultCardClock(30_000)) { true }.first)
    }
}
