package kr.closeforyou.ui.voice.debug

import kr.closeforyou.ui.voice.debug.FocusWatchSchedule.Verdict
import kr.closeforyou.ui.voice.overlay.OverlayWindowKeeper
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR #34 review: the debug focus watch is read-only by default, ends on time and never acts on a stale window. */
class FocusWatchScheduleTest {
    private val schedule = FocusWatchSchedule()
    private fun showing(generation: Long) = OverlayWindowState(OverlayWindowStatus.SHOWING, generation)

    @Test fun readOnlyUnlessTheMoveWasAskedFor() {
        schedule.start(now = 0, millis = 10_000, moveFocus = false, serviceSession = SESSION)
        val followUp = schedule.onPromptShown(showing(3))
        assertNotNull(followUp)
        assertFalse(followUp!!.moveFocus)
        assertEquals(Verdict.RUN, schedule.check(followUp, SESSION, showing(3)))

        schedule.start(now = 0, millis = 10_000, moveFocus = true, serviceSession = SESSION)
        assertTrue(schedule.onPromptShown(showing(4))!!.moveFocus)
    }

    @Test fun deadlineEndsTheWatchAndForgetsItsFollowUp() {
        schedule.start(now = 1_000, millis = 5_000, moveFocus = true, serviceSession = SESSION)
        val followUp = schedule.onPromptShown(showing(1))!!
        assertFalse(schedule.isExpired(5_999))
        assertTrue(schedule.isExpired(6_000))

        assertTrue(schedule.stop())
        assertNull(schedule.watch)
        assertNull(schedule.followUp)
        assertFalse(schedule.isExpired(10_000))
        assertEquals(Verdict.NO_WATCH, schedule.check(followUp, SESSION, showing(1)))
        // Stopping twice is harmless and says nothing was running.
        assertFalse(schedule.stop())
    }

    @Test fun newRequestReplacesTheOldWatchAndItsFollowUp() {
        schedule.start(now = 0, millis = 10_000, moveFocus = true, serviceSession = SESSION)
        val old = schedule.onPromptShown(showing(1))!!
        val newer = schedule.start(now = 100, millis = 10_000, moveFocus = false, serviceSession = SESSION)

        assertNull(schedule.followUp)
        assertEquals(newer, schedule.watch)
        assertEquals(Verdict.REPLACED, schedule.check(old, SESSION, showing(1)))
    }

    @Test fun newerShowReplacesTheEarlierFollowUp() {
        schedule.start(now = 0, millis = 10_000, moveFocus = true, serviceSession = SESSION)
        val first = schedule.onPromptShown(showing(1))!!
        val second = schedule.onPromptShown(showing(2))!!

        assertEquals(Verdict.REPLACED, schedule.check(first, SESSION, showing(2)))
        assertEquals(Verdict.RUN, schedule.check(second, SESSION, showing(2)))
    }

    @Test fun differentServiceSessionNeverRuns() {
        schedule.start(now = 0, millis = 10_000, moveFocus = true, serviceSession = SESSION)
        val followUp = schedule.onPromptShown(showing(1))!!

        assertEquals(Verdict.SERVICE_CHANGED, schedule.check(followUp, SESSION + 1, showing(1)))
        assertEquals(Verdict.SERVICE_CHANGED, schedule.check(followUp, FocusWatchSchedule.NO_SESSION, showing(1)))
    }

    @Test fun watchStartedWithoutServiceNeverRuns() {
        schedule.start(now = 0, millis = 10_000, moveFocus = true, serviceSession = FocusWatchSchedule.NO_SESSION)
        val followUp = schedule.onPromptShown(showing(1))!!

        assertEquals(Verdict.SERVICE_CHANGED, schedule.check(followUp, FocusWatchSchedule.NO_SESSION, showing(1)))
    }

    @Test fun closedOrReplacedWindowNeverRuns() {
        schedule.start(now = 0, millis = 10_000, moveFocus = true, serviceSession = SESSION)
        val followUp = schedule.onPromptShown(showing(5))!!

        assertEquals(Verdict.WINDOW_NOT_SHOWING, schedule.check(followUp, SESSION, OverlayWindowState.HIDDEN))
        assertEquals(
            Verdict.WINDOW_NOT_SHOWING,
            schedule.check(followUp, SESSION, OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, 5)),
        )
        assertEquals(Verdict.WINDOW_CHANGED, schedule.check(followUp, SESSION, showing(6)))
    }

    @Test fun nothingIsScheduledWithoutWatchOrShownWindow() {
        assertNull(schedule.onPromptShown(showing(1)))

        schedule.start(now = 0, millis = 10_000, moveFocus = true, serviceSession = SESSION)
        assertNull(schedule.onPromptShown(OverlayWindowState.HIDDEN))
        assertNull(schedule.onPromptShown(showing(OverlayWindowKeeper.NO_GENERATION)))
        assertNull(schedule.followUp)
    }

    @Test fun finishedFollowUpCannotRunAgain() {
        schedule.start(now = 0, millis = 10_000, moveFocus = true, serviceSession = SESSION)
        val followUp = schedule.onPromptShown(showing(1))!!
        schedule.finish(followUp)

        assertNull(schedule.followUp)
        assertEquals(Verdict.REPLACED, schedule.check(followUp, SESSION, showing(1)))
    }

    private companion object {
        const val SESSION = 7
    }
}
