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
 */
class ResultCardClock(private val timeoutMillis: Long) {
    enum class Step { KEEP, HELD_FOCUS, FOCUS_LEFT, DISMISS, DISMISS_HOLD_MAX }

    private val holdMax = maxOf(HOLD_MAX_MILLIS, timeoutMillis)
    private var lastInsideAt = -1L
    private var heldLogged = false
    private var leftLogged = false

    /** [elapsed] since the card was shown; [focusInside]: TalkBack's focus is on the card or its button now. */
    fun tick(elapsed: Long, focusInside: Boolean): Step {
        if (elapsed >= holdMax) return if (focusInside) Step.DISMISS_HOLD_MAX else Step.DISMISS
        if (focusInside) {
            lastInsideAt = elapsed
            leftLogged = false
            if (elapsed >= timeoutMillis && !heldLogged) {
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
        return if (elapsed >= timeoutMillis) Step.DISMISS else Step.KEEP
    }

    companion object {
        const val SHOW_MILLIS = 4_000L
        /** F17: a card holding the focus stays a little longer (the person may read it again by touch). */
        const val FOCUSED_SHOW_MILLIS = 6_000L
        const val HOLD_MAX_MILLIS = 15_000L
        const val LEAVE_MILLIS = 2_000L
        const val CHECK_MILLIS = 500L

        fun baseMillis(takeFocus: Boolean): Long = if (takeFocus) FOCUSED_SHOW_MILLIS else SHOW_MILLIS

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
