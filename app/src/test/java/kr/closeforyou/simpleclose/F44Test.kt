package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.ResultCardClock.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F44 (우진 SM-F966N, c279ee3, P04 [닫기] by tap: FOCUS_OTHER_ON_PAGE → FOCUS_RECLAIM_CARD_REFUSED →
 * RESULT_CARD_HELD_FOCUS → RESULT_CARD_HOLD_MAX while the page was read): the card's focus is the system's.
 */
class F44Test {
    private val card = ScreenBounds(140, 252, 940, 520)
    private val prompt = ScreenBounds(42, 700, 1038, 1800)

    @Test fun theCardWindowIsTheOverlayWithTheCardsBounds() {
        assertEquals(7, CardFocusRules.cardWindow(card, listOf(3 to prompt, 7 to card)))
        // A pixel or two off (rounding, shadow) still matches.
        assertEquals(7, CardFocusRules.cardWindow(card, listOf(7 to ScreenBounds(138, 250, 942, 522))))
        // Our prompt window, or a full-screen overlay, is not the card.
        assertNull(CardFocusRules.cardWindow(card, listOf(3 to prompt)))
        assertNull(CardFocusRules.cardWindow(card, listOf(4 to ScreenBounds(0, 0, 1080, 2408))))
        assertNull(CardFocusRules.cardWindow(card, emptyList()))
        assertNull(CardFocusRules.cardWindow(ScreenBounds(0, 0, 0, 0), listOf(7 to card)))
    }

    @Test fun placeComparesWindowsOnly() {
        assertEquals(CardFocusPlace.INSIDE, CardFocusRules.place(7, 7))
        assertEquals(CardFocusPlace.OUTSIDE, CardFocusRules.place(7, 12))
        assertEquals(CardFocusPlace.NONE, CardFocusRules.place(7, null))
        assertEquals(CardFocusPlace.CARD_WINDOW_UNKNOWN, CardFocusRules.place(null, 7))
    }

    @Test fun theViewsStaleFlagNoLongerHoldsTheCard() {
        // The galaxy: the page has the focus, the View still says "focused".
        val staleView = { true }
        assertFalse(CardFocusRules.inside(CardFocusPlace.OUTSIDE, staleView))
        assertFalse(CardFocusRules.inside(CardFocusPlace.NONE, staleView))
        assertTrue(CardFocusRules.inside(CardFocusPlace.INSIDE) { false })
        // Card window not listed: the View is all there is.
        assertTrue(CardFocusRules.inside(CardFocusPlace.CARD_WINDOW_UNKNOWN, staleView))
        assertFalse(CardFocusRules.inside(CardFocusPlace.CARD_WINDOW_UNKNOWN) { false })
    }

    @Test fun theGalaxyRunNowGoesTwoSecondsAfterThePageTookTheFocus() {
        // Focus on the card, page at 0.9 s (the reclaim refused): FOCUS_LEFT, gone at 3 s, not held to 15 s.
        val clock = ResultCardClock(10_000)
        val steps = mutableListOf<Step>()
        var t = 0L
        while (true) {
            t += ResultCardClock.CHECK_MILLIS
            val place = if (t < 900) CardFocusPlace.INSIDE else CardFocusPlace.OUTSIDE
            val step = clock.tick(t, CardFocusRules.inside(place) { true })
            if (step != Step.KEEP) steps += step
            if (step == Step.DISMISS || step == Step.DISMISS_HOLD_MAX) break
        }
        assertEquals(2_500L, t)
        assertEquals(listOf(Step.FOCUS_LEFT, Step.DISMISS), steps)
    }

    @Test fun refocusWaysHaveTheirCodes() {
        assertEquals(SimpleTrace.FOCUS_RECLAIM_CARD_BY_NODE, CardFocusRules.trace(CardRefocus.BY_NODE))
        assertEquals(SimpleTrace.FOCUS_RECLAIM_CARD_AFTER_CLEAR, CardFocusRules.trace(CardRefocus.BY_NODE_AFTER_CLEAR))
        assertEquals(SimpleTrace.FOCUS_RECLAIM_CARD_BY_VIEW, CardFocusRules.trace(CardRefocus.BY_VIEW))
        assertEquals(SimpleTrace.FOCUS_RECLAIM_CARD_REFUSED, CardFocusRules.trace(CardRefocus.REFUSED))
    }
}
