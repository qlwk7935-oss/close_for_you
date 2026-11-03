package kr.closeforyou.simpleclose

import android.view.accessibility.AccessibilityEvent
import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F49: Chromium's scroll events are dropped at once; a popup that only moved is judged again once; a popup still there is asked about. */
class F49Test {
    private val screenHeight = 2400
    private val layer = ScreenBounds(0, 63, 1080, 2400)

    private fun moved(dl: Int, dt: Int, dr: Int, db: Int) =
        ScreenBounds(layer.left + dl, layer.top + dt, layer.right + dr, layer.bottom + db)

    @Test fun scrollEventsAreDroppedFirstThing() {
        assertTrue(ServiceEventRules.dropAtOnce(AccessibilityEvent.TYPE_VIEW_SCROLLED))
        for (kept in listOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_GESTURE_DETECTION_START,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START)) {
            assertFalse(ServiceEventRules.dropAtOnce(kept))
        }
    }

    @Test fun theTwoMeasuredMovesArePureVerticalShifts() {
        // 동준 7a3912c: APP-16 dt=db=-53, APP-17 dt=db=16, dl=dr=0.
        assertTrue(ClickRecheck.verticalShift(RecheckMiss.ROOT_MOVED, layer, moved(0, -53, 0, -53), screenHeight))
        assertTrue(ClickRecheck.verticalShift(RecheckMiss.ROOT_MOVED, layer, moved(0, 16, 0, 16), screenHeight))
    }

    @Test fun anythingElseIsNotAShift() {
        // Resized, moved sideways, not moved, too far, another reason, no live bounds.
        assertFalse(ClickRecheck.verticalShift(RecheckMiss.ROOT_MOVED, layer, moved(0, 16, 0, 0), screenHeight))
        assertFalse(ClickRecheck.verticalShift(RecheckMiss.ROOT_MOVED, layer, moved(10, 0, 10, 0), screenHeight))
        assertFalse(ClickRecheck.verticalShift(RecheckMiss.ROOT_MOVED, layer, moved(0, 0, 0, 0), screenHeight))
        assertTrue(ClickRecheck.verticalShift(RecheckMiss.ROOT_MOVED, layer, moved(0, 600, 0, 600), screenHeight))
        assertFalse(ClickRecheck.verticalShift(RecheckMiss.ROOT_MOVED, layer, moved(0, 601, 0, 601), screenHeight))
        assertFalse(ClickRecheck.verticalShift(RecheckMiss.ROOT_MISSING, layer, moved(0, 16, 0, 16), screenHeight))
        assertFalse(ClickRecheck.verticalShift(RecheckMiss.ROOT_MOVED, layer, null, screenHeight))
    }

    @Test fun aShiftIsReadAgainOnceThenAskedWhileStillShown() {
        assertEquals(MissStep.REREAD, ClickRecheck.afterMiss(RecheckMiss.ROOT_MOVED, shift = true, rereadDone = false, stillShown = null, bottomBanner = false))
        // The fresh judgement failed again (APP-16 and APP-17 alike now): still there = asked, gone = quiet.
        assertEquals(MissStep.ASK, ClickRecheck.afterMiss(RecheckMiss.ROOT_MOVED, shift = true, rereadDone = true, stillShown = true, bottomBanner = false))
        assertEquals(MissStep.QUIET, ClickRecheck.afterMiss(RecheckMiss.ROOT_MOVED, shift = true, rereadDone = true, stillShown = false, bottomBanner = false))
        assertEquals(MissStep.QUIET, ClickRecheck.afterMiss(RecheckMiss.ROOT_MOVED, shift = true, rereadDone = true, stillShown = null, bottomBanner = false))
    }

    @Test fun stillOnScreenIsAlwaysAskedGoneOrAnotherAppIsQuiet() {
        for (miss in RecheckMiss.entries) {
            val shown = ClickRecheck.afterMiss(miss, shift = false, rereadDone = false, stillShown = true, bottomBanner = false)
            val gone = ClickRecheck.afterMiss(miss, shift = false, rereadDone = false, stillShown = false, bottomBanner = false)
            if (miss.group == RecheckGroup.PACKAGE) {
                assertEquals(miss.name, MissStep.QUIET, shown)
                assertEquals(miss.name, MissStep.QUIET, gone)
            } else {
                assertEquals(miss.name, MissStep.ASK, shown)
                // A button mismatch: its root was just found, so the popup is there.
                assertEquals(miss.name, if (miss.group == RecheckGroup.TARGET) MissStep.ASK else MissStep.QUIET, gone)
            }
            // F37: a bottom banner is never asked about, never re-read.
            assertEquals(miss.name, MissStep.QUIET, ClickRecheck.afterMiss(miss, false, false, true, bottomBanner = true))
        }
    }

    @Test fun theFreshButtonMustBeTheSameOneInItsRoot() {
        val button = ScreenBounds(900, 87, 1056, 231)
        val shiftedRoot = moved(0, 16, 0, 16)
        val shiftedButton = ScreenBounds(900, 103, 1056, 247)
        assertTrue(ClickRecheck.sameTarget("닫기", button, layer, "닫기", shiftedButton, shiftedRoot))
        // Another name, another place in the root, another size, no name.
        assertFalse(ClickRecheck.sameTarget("닫기", button, layer, "자세히 보기", shiftedButton, shiftedRoot))
        assertFalse(ClickRecheck.sameTarget("닫기", button, layer, "닫기", ScreenBounds(900, 400, 1056, 544), shiftedRoot))
        assertFalse(ClickRecheck.sameTarget("닫기", button, layer, "닫기", ScreenBounds(900, 103, 1000, 247), shiftedRoot))
        assertFalse(ClickRecheck.sameTarget(null, button, layer, null, shiftedButton, shiftedRoot))
    }
}
