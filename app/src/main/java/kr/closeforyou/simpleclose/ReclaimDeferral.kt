package kr.closeforyou.simpleclose

/**
 * F47 (우진 SM-F966N, 76245ff, TalkBack: the result card took the person's own swipe back 3 ms after it,
 * `FOCUS_OTHER_ON_PAGE` 25.131 → `FOCUS_RECLAIM_CARD_AFTER_CLEAR` 25.134 → `FOCUS_RECLAIM_STOPPED_BY_USER` 25.348):
 * the touch or gesture event of a swipe can reach us after the focus move it made, so a focus move we did not make
 * is not taken back at once. It waits [DEFER_MILLIS]; a touch or gesture of the person in that time, or within
 * [USER_BEFORE_MILLIS] before the move, makes it the person's own move ([DeferVerdict.SKIPPED_USER_MOVE]).
 * The same rule holds our prompt's first focus ([PROMPT_HOLD_MILLIS], at most [PROMPT_HOLD_MAX] times).
 * Pure: times in, a verdict out (uptime ms; a negative time = none).
 */
object ReclaimDeferral {
    /**
     * The touch came 214 ms after the move on that phone (one sample, the swipe's own events; our service's
     * notificationTimeout is 100 ms): about twice that. Short enough that a page pulling the focus away is still
     * taken back before TalkBack has read much of it.
     */
    const val DEFER_MILLIS = 400L

    /** A touch this soon before the move is the swipe that made it (a TalkBack swipe takes about this long). */
    const val USER_BEFORE_MILLIS = 500L

    /** F47: how long our prompt's first focus is looked after once it came. */
    const val PROMPT_HOLD_MILLIS = 2_000L
    const val PROMPT_HOLD_MAX = 2

    /** The person touched or gestured from [USER_BEFORE_MILLIS] before the move ([moveAt]) on. */
    fun userMove(moveAt: Long, lastUserAt: Long): Boolean =
        lastUserAt >= 0 && lastUserAt >= moveAt - USER_BEFORE_MILLIS

    /**
     * After [DEFER_MILLIS]: [active] the watch (or hold) is still on and the same; [lastUserAt] the person's last
     * touch or gesture; [newerMove] another focus move came meanwhile (its own wait decides); [alreadyBack] the focus
     * is where we would put it; [reclaimed] of [max] already done.
     */
    fun afterDefer(active: Boolean, moveAt: Long, lastUserAt: Long, newerMove: Boolean, alreadyBack: Boolean, reclaimed: Int, max: Int): DeferVerdict = when {
        // The person's move first: the touch that ended the watch tells why it ended.
        userMove(moveAt, lastUserAt) -> DeferVerdict.SKIPPED_USER_MOVE
        !active -> DeferVerdict.ENDED
        newerMove -> DeferVerdict.SUPERSEDED
        alreadyBack -> DeferVerdict.ALREADY_BACK
        reclaimed >= max -> DeferVerdict.LIMIT
        else -> DeferVerdict.RECLAIM
    }

    /** F47: the prompt's first focus is still looked after at [now] (it came at [startedAt]). */
    fun promptHoldOn(startedAt: Long, now: Long): Boolean = now - startedAt in 0..PROMPT_HOLD_MILLIS

    /**
     * The person's touch for the card's reclaim watch: one within [graceMillis] after the watch was armed
     * ([armedAt]) is the press's own double tap (F17), not a new touch. Negative = none.
     */
    fun userTouchAfterGrace(lastTouchAt: Long, armedAt: Long, graceMillis: Long): Long =
        if (lastTouchAt >= 0 && lastTouchAt - armedAt > graceMillis) lastTouchAt else -1L
}

enum class DeferVerdict { RECLAIM, SKIPPED_USER_MOVE, ENDED, SUPERSEDED, ALREADY_BACK, LIMIT }
