package kr.closeforyou.simpleclose

/**
 * F42 (준영 제안, 팀 결정 11/2): how long a result card with [방금 닫은 내용 듣기] stays. Hearing "닫았어요", swiping to
 * the button and double tapping did not fit in 4 s / 6 s, and the words go with the card.
 * - While TalkBack's focus is inside the card (its sentence or its button) it stays, up to [HOLD_MAX_MILLIS]
 *   (or the person's longer [timeoutMillis] setting).
 * - Once the focus was inside and left, the card goes [LEAVE_MILLIS] later.
 * - Never focused (TalkBack off, or the focus went elsewhere): the old time, as long as the person's
 *   accessibility timeout setting asks ([timeoutMillis], `getRecommendedTimeoutMillis`).
 * A card without the button keeps its old time ([baseMillis]); the next popup still replaces it at once.
 * Pure (elapsed times in, a step out) so it is unit tested; [TalkBackResultBanner] checks every [CHECK_MILLIS].
 *
 * F45 (우진 SM-F966N, ac66631: RESULT_HEAR_CLOSED_READ, then RESULT_CARD_HOLD_MAX at 15 s from the card): a press of
 * [방금 닫은 내용 듣기] starts the clock again from the press ([restartOnRead]), with a limit that follows the length of
 * what is read ([readHoldMillis]); leaving the card still takes it away 2 s later. Time only: whether TalkBack or our TTS
 * is still speaking is not asked.
 */
class ResultCardClock(private val timeoutMillis: Long) {
    enum class Step { KEEP, HELD_FOCUS, FOCUS_LEFT, DISMISS, DISMISS_HOLD_MAX }

    private var timeout = timeoutMillis
    private var holdMax = maxOf(HOLD_MAX_MILLIS, timeoutMillis)
    /** F45: elapsed time at the last restart; limits count from here. */
    private var startAt = 0L
    private var lastInsideAt = -1L
    private var heldLogged = false
    private var leftLogged = false

    /** [elapsed] since the card was shown; [focusInside]: TalkBack's focus is on the card or its button now. */
    fun tick(elapsed: Long, focusInside: Boolean): Step {
        val since = elapsed - startAt
        if (since >= holdMax) return if (focusInside) Step.DISMISS_HOLD_MAX else Step.DISMISS
        if (focusInside) {
            lastInsideAt = elapsed
            leftLogged = false
            if (since >= timeout && !heldLogged) {
                heldLogged = true
                return Step.HELD_FOCUS
            }
            return Step.KEEP
        }
        if (lastInsideAt >= 0) {
            if (elapsed - lastInsideAt >= LEAVE_MILLIS) return Step.DISMISS
            if (!leftLogged) {
                leftLogged = true
                return Step.FOCUS_LEFT
            }
            return Step.KEEP
        }
        return if (since >= timeout) Step.DISMISS else Step.KEEP
    }

    /**
     * F45: [방금 닫은 내용 듣기] was pressed at [elapsed]: from now the card stays [readHoldMillis] (focused or not, so a
     * card read with TalkBack off stays too), and goes [LEAVE_MILLIS] after the focus leaves it.
     */
    fun restartOnRead(elapsed: Long, readHoldMillis: Long) {
        startAt = elapsed
        timeout = readHoldMillis
        holdMax = readHoldMillis
        lastInsideAt = -1L
        heldLogged = false
        leftLogged = false
    }

    companion object {
        const val SHOW_MILLIS = 4_000L
        /** F17: a card holding the focus stays a little longer (the person may read it again by touch). */
        const val FOCUSED_SHOW_MILLIS = 6_000L
        const val HOLD_MAX_MILLIS = 15_000L
        const val LEAVE_MILLIS = 2_000L
        const val CHECK_MILLIS = 500L

        fun baseMillis(takeFocus: Boolean): Long = if (takeFocus) FOCUSED_SHOW_MILLIS else SHOW_MILLIS

        /** F45: the time after a press of [방금 닫은 내용 듣기] before the words' own share ([readHoldMillis]). */
        const val READ_BASE_MILLIS = 15_000L
        /**
         * F45: per character at 1x speech. Korean TTS at 1x says about 5 characters a second with spaces (an estimate,
         * not measured on our devices): 200 ms each, so a 100-character popup gets 35 s.
         */
        const val READ_PER_CHAR_MILLIS = 200L
        const val READ_MAX_MILLIS = 60_000L
        /** F45: a rate below this is counted as this (a very slow setting is still capped by [READ_MAX_MILLIS]). */
        const val READ_MIN_RATE = 0.5f

        /**
         * F45: how long a card stays after [방금 닫은 내용 듣기]: [READ_BASE_MILLIS] + [chars] × [READ_PER_CHAR_MILLIS] at
         * the speech [rate] (null = 1x), at most [READ_MAX_MILLIS], never shorter than the card's own [timeoutMillis]
         * (the person's accessibility timeout, F42). Only the count of characters comes in, never the words.
         */
        fun readHoldMillis(chars: Int, rate: Float?, timeoutMillis: Long): Long {
            val r = (rate ?: 1f).coerceAtLeast(READ_MIN_RATE)
            val words = (chars.coerceAtLeast(0) * READ_PER_CHAR_MILLIS / r).toLong()
            return (READ_BASE_MILLIS + words).coerceAtMost(READ_MAX_MILLIS).coerceAtLeast(timeoutMillis)
        }

        /**
         * The first timeout. Without the button: [baseMillis] as before. With it: [recommended] (the person's
         * accessibility timeout, `AccessibilityManager.getRecommendedTimeoutMillis`) of that, never shorter.
         */
        fun timeoutMillis(takeFocus: Boolean, hasButton: Boolean, recommended: (Int) -> Int): Long {
            val base = baseMillis(takeFocus)
            if (!hasButton) return base
            return recommended(base.toInt()).toLong().coerceAtLeast(base)
        }
    }
}
