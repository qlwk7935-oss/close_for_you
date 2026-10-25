package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as Event
import kotlinx.coroutines.test.runTest
import kr.closeforyou.accessibility.resumePendingObservationAfterFocus
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

class ManualChoiceLifetimeTest {
    @Test fun cleanupStillReadsTheNextPopupButCannotLoopPresentationOfTheSameInterruptedPopup() {
        val token = MockPopupFixtures.advertisement().snapshot.token
        val budget = ChoiceReviewBudget()
        assertTrue(budget.admit(token))
        assertTrue(budget.admit(token.copy(screenVersion = 99, captureEpoch = 99)))
        assertFalse(budget.admit(token.copy(popupInstanceId = "new-random-id")))
        assertTrue(budget.admit(token.copy(windowId = token.windowId + 1)))
        assertTrue(budget.admit(token.copy(windowId = token.windowId + 1, contentDigest = "changed")))
    }
    @Test fun onlyAPendingScreenReadSurvivesFocusAndOldEvidenceIsNeverExempted() {
        for (type in listOf(Event.TYPE_VIEW_FOCUSED, Event.TYPE_VIEW_ACCESSIBILITY_FOCUSED, Event.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)) {
            assertTrue(resumePendingObservationAfterFocus(true, type))
            assertFalse(resumePendingObservationAfterFocus(false, type))
        }
        for (type in listOf(Event.TYPE_VIEW_CLICKED, Event.TYPE_VIEW_SCROLLED, Event.TYPE_TOUCH_INTERACTION_START,
            Event.TYPE_TOUCH_EXPLORATION_GESTURE_START)) assertFalse(resumePendingObservationAfterFocus(true, type))
    }
    @Test fun displayOnlyLeaseIsBoundToNativeGenerationAndThirtySecondsFromOriginalShow() {
        var now = 100L
        val token = MockPopupFixtures.advertisement().snapshot.token
        val lease = ChoiceDisplayLease { now }
        val showing = OverlayWindowState(OverlayWindowStatus.SHOWING, 3)
        lease.shown(token, showing)
        assertFalse(lease.detach(showing, false))
        lease.shown(token, showing); now = 5_100
        assertTrue(lease.detach(showing, true)); assertTrue(lease.retains(showing))
        now = 30_099
        assertEquals(1L, lease.remainingMillis(showing))
        now = 30_100
        assertFalse(lease.retains(showing)); assertEquals(0L, lease.remainingMillis(showing))
        lease.shown(token, showing); assertTrue(lease.detach(showing, true))
        assertFalse(lease.retains(showing.copy(generation = 4)))
        assertFalse(lease.retains(showing)) // uncertainty cannot become valid again
    }
}
