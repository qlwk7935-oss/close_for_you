package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds

/**
 * F44 (우진 SM-F966N, c279ee3, P04 [닫기] by tap, twice: RESULT_BANNER_FOCUSED → FOCUS_OTHER_ON_PAGE →
 * FOCUS_RECLAIM_CARD_REFUSED → RESULT_CARD_HELD_FOCUS → RESULT_CARD_HOLD_MAX while the person heard the page):
 * whether TalkBack's focus is on the result card is asked of the system (the service's
 * `findFocus(FOCUS_ACCESSIBILITY)`, which follows the window the system marks accessibility-focused), not of our
 * own View's flag, which on that phone still said "focused" after the focus went to the page.
 * The card's window is the accessibility overlay window whose bounds are the card's. Pure, unit tested; ids are
 * compared only (same / different / unknown), never logged.
 */
object CardFocusRules {
    /** Both areas must be covered this much: our prompt overlay (another size) is never taken for the card. */
    const val MATCH_RATIO = 0.8

    /** The id of the overlay window that is the card ([card] its bounds on screen); null = not listed. */
    fun cardWindow(card: ScreenBounds, overlays: List<Pair<Int, ScreenBounds>>): Int? {
        val cardArea = area(card)
        if (cardArea <= 0) return null
        return overlays
            .map { (id, b) -> Triple(id, overlap(card, b), area(b)) }
            .filter { (_, o, a) -> a > 0 && o >= cardArea * MATCH_RATIO && o >= a * MATCH_RATIO }
            .maxByOrNull { it.second }
            ?.first
    }

    /** Where the focus is: the card's window [cardWindowId] (null = not found) and the focused node's window. */
    fun place(cardWindowId: Int?, focusedWindowId: Int?): CardFocusPlace = when {
        cardWindowId == null -> CardFocusPlace.CARD_WINDOW_UNKNOWN
        focusedWindowId == null -> CardFocusPlace.NONE
        focusedWindowId == cardWindowId -> CardFocusPlace.INSIDE
        else -> CardFocusPlace.OUTSIDE
    }

    /**
     * For [ResultCardClock]: inside only when the system says so. When the card's window could not be found, the
     * View's own answer ([viewSaysInside], the F42 way) is all there is (still cut at 15 s).
     */
    fun inside(place: CardFocusPlace, viewSaysInside: () -> Boolean): Boolean = when (place) {
        CardFocusPlace.INSIDE -> true
        CardFocusPlace.OUTSIDE, CardFocusPlace.NONE -> false
        CardFocusPlace.CARD_WINDOW_UNKNOWN -> viewSaysInside()
    }

    /** The debug code for how the focus was put back on the card. */
    fun trace(refocus: CardRefocus): SimpleTrace = when (refocus) {
        CardRefocus.BY_NODE -> SimpleTrace.FOCUS_RECLAIM_CARD_BY_NODE
        CardRefocus.BY_NODE_AFTER_CLEAR -> SimpleTrace.FOCUS_RECLAIM_CARD_AFTER_CLEAR
        CardRefocus.BY_VIEW -> SimpleTrace.FOCUS_RECLAIM_CARD_BY_VIEW
        CardRefocus.REFUSED -> SimpleTrace.FOCUS_RECLAIM_CARD_REFUSED
    }

    private fun area(b: ScreenBounds): Long = maxOf(0, b.width).toLong() * maxOf(0, b.height)

    private fun overlap(a: ScreenBounds, b: ScreenBounds): Long {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w <= 0 || h <= 0) 0 else w.toLong() * h
    }
}

enum class CardFocusPlace { INSIDE, OUTSIDE, NONE, CARD_WINDOW_UNKNOWN }

/**
 * F44: how [TalkBackResultBanner.refocus] put the focus back: the card's node through the service (BY_NODE); the
 * same after clearing its stale "focused" flag (BY_NODE_AFTER_CLEAR); our View the old way (BY_VIEW); not at all.
 */
enum class CardRefocus { BY_NODE, BY_NODE_AFTER_CLEAR, BY_VIEW, REFUSED }
