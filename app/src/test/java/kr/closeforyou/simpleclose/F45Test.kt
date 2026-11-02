package kr.closeforyou.simpleclose

import kr.closeforyou.simpleclose.ResultCardClock.Step
import org.junit.Assert.assertEquals
import org.junit.Test

/** F45: a press of [방금 닫은 내용 듣기] starts the card's clock again, for as long as the words take. */
class F45Test {
    /** Ticks every [ResultCardClock.CHECK_MILLIS]; [pressAt] restarts with [hold]. Returns when it went and the steps. */
    private fun run(clock: ResultCardClock, pressAt: Long, hold: Long, focus: (Long) -> Boolean): Pair<Long, List<Step>> {
        val steps = mutableListOf<Step>()
        var t = 0L
        while (true) {
            t += ResultCardClock.CHECK_MILLIS
            if (t == pressAt) clock.restartOnRead(t, hold)
            val step = clock.tick(t, focus(t))
            if (step != Step.KEEP) steps += step
            if (step == Step.DISMISS || step == Step.DISMISS_HOLD_MAX) return t to steps
        }
    }

    @Test fun holdFollowsTheLength() {
        assertEquals(15_000L, ResultCardClock.readHoldMillis(0, null, 6_000))
        // 100 characters at 1x: 15 s + 20 s.
        assertEquals(35_000L, ResultCardClock.readHoldMillis(100, null, 6_000))
        assertEquals(35_000L, ResultCardClock.readHoldMillis(100, 1f, 6_000))
        // Faster speech, less time; slower, more.
        assertEquals(25_000L, ResultCardClock.readHoldMillis(100, 2f, 6_000))
        assertEquals(40_000L, ResultCardClock.readHoldMillis(100, 0.8f, 6_000))
        // Capped at 60 s; a rate under 0.5 counts as 0.5.
        assertEquals(60_000L, ResultCardClock.readHoldMillis(1_000, null, 6_000))
        assertEquals(35_000L, ResultCardClock.readHoldMillis(50, 0.1f, 6_000))
        // Never shorter than the person's accessibility timeout, even past the cap.
        assertEquals(90_000L, ResultCardClock.readHoldMillis(10, null, 90_000))
        assertEquals(15_000L, ResultCardClock.readHoldMillis(-5, null, 6_000))
    }

    @Test fun aLatePressIsNotCutAtFifteenSeconds() {
        // 우진: pressed at 10 s with the focus on the button; before F45 the card went at 15 s.
        val (goneAt, steps) = run(ResultCardClock(10_000), pressAt = 10_000, hold = 35_000) { true }
        assertEquals(45_000L, goneAt)
        assertEquals(listOf(Step.DISMISS_HOLD_MAX), steps)
    }

    @Test fun leavingAfterThePressStillTakesItTwoSecondsLater() {
        val (goneAt, steps) = run(ResultCardClock(10_000), pressAt = 10_000, hold = 35_000) { it < 20_000 }
        assertEquals(21_500L, goneAt)
        assertEquals(listOf(Step.FOCUS_LEFT, Step.DISMISS), steps)
    }

    @Test fun withoutTalkBackTheCardStaysForTheReading() {
        // TalkBack off: no focus ever. Pressed by touch at 2 s; before F45 it went at its 4 s timeout.
        val (goneAt, steps) = run(ResultCardClock(4_000), pressAt = 2_000, hold = 20_000) { false }
        assertEquals(22_000L, goneAt)
        assertEquals(listOf(Step.DISMISS), steps)
    }

    @Test fun aSecondPressStartsAgain() {
        val clock = ResultCardClock(10_000)
        clock.restartOnRead(5_000, 15_000)
        assertEquals(Step.KEEP, clock.tick(19_500, true))
        clock.restartOnRead(19_500, 15_000)
        assertEquals(Step.KEEP, clock.tick(34_000, true))
        assertEquals(Step.DISMISS_HOLD_MAX, clock.tick(34_500, true))
    }
}
