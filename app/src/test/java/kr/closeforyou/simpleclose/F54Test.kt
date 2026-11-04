package kr.closeforyou.simpleclose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F54: whether the card's focus event came is told apart; asked once more only when none did; codes and numbers only. */
class F54Test {
    @Test fun noEventFromTheCardIsAskedOnceMore() {
        val trace = CardFocusTrace()
        assertTrue(trace.retry())
        assertFalse(trace.retry())
    }

    @Test fun anEventFromTheCardNeedsNoSecondAsk() {
        val trace = CardFocusTrace()
        trace.event(fromCard = true)
        assertFalse(trace.retry())
    }

    @Test fun eventsFromElsewhereDoNotCountAsTheCards() {
        val trace = CardFocusTrace()
        trace.event(fromCard = false)
        assertEquals(0, trace.onCard)
        assertEquals(1, trace.others)
        assertTrue(trace.retry())
    }

    @Test fun theRetryComesAfterTheServiceWouldHaveGotTheEvent() {
        // notificationTimeout 100 ms in ui_simple_close_service.xml.
        assertTrue(CardFocusTrace.RETRY_AFTER_MILLIS > 100)
        assertTrue(CardFocusTrace.LISTED_MAX_MILLIS >= 10 * CardFocusTrace.LISTED_CHECK_MILLIS)
    }

    @Test fun theLinesHoldCodesAndNumbersOnly() {
        val trace = CardFocusTrace().apply { event(true); event(false) }
        assertEquals("CARD_FOCUS listed=true waitMs=45 view=true", CardFocusTrace.startLine(true, 45, true))
        assertEquals("CARD_FOCUS_EVENT dt=38 onCard=true", CardFocusTrace.eventLine(38, true))
        assertEquals("CARD_FOCUS_AT dt=300 place=OUTSIDE events=1 others=1", CardFocusTrace.placeLine(300, CardFocusPlace.OUTSIDE, trace))
        assertEquals("CARD_FOCUS_RETRY outcome=BY_NODE_AFTER_CLEAR", CardFocusTrace.retryLine(CardRefocus.BY_NODE_AFTER_CLEAR))
    }
}
