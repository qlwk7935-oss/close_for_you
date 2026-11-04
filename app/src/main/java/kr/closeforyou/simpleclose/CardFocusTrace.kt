package kr.closeforyou.simpleclose

/**
 * F54 (우진 SM-F966N df726f4, P13: RESULT_BANNER_FOCUSED and no sentence heard; no FOCUS_OTHER_*): "the focus action said yes" is
 * not "TalkBack read it". A focus event from a window not in the accessibility window list yet is not dispatched to any service
 * (AOSP 14 `AccessibilitySecurityPolicy.canDispatchAccessibilityEventLocked`: only window-state and touch kinds always go), and the
 * card's focus is asked for about 30 ms after its window was added. Whether that is what happened on the device is not known yet.
 * Told apart here, codes and numbers only:
 * - was the card's window listed when its focus was asked ([startLine]), and how long it was waited for;
 * - did a focus event from the card's window reach this service, as it would TalkBack ([eventLine]; our own events were dropped
 *   before any look until now), and did others come after it;
 * - where the system's focus is a moment later ([placeLine]).
 * Pure; JVM tested.
 */
class CardFocusTrace {
    var onCard = 0
        private set
    var others = 0
        private set
    private var retried = false

    fun event(fromCard: Boolean) {
        if (fromCard) onCard += 1 else others += 1
    }

    /** No event from the card came by [RETRY_AFTER_MILLIS] and none was asked again yet: its focus is asked once more. */
    fun retry(): Boolean {
        if (onCard > 0 || retried) return false
        retried = true
        return true
    }

    companion object {
        /** The card's window is looked for this often, for at most this long; then its focus is asked anyway, as before. */
        const val LISTED_CHECK_MILLIS = 30L
        const val LISTED_MAX_MILLIS = 300L

        /** An event from the card would have come by now (`notificationTimeout` is 100 ms). */
        const val RETRY_AFTER_MILLIS = 400L

        /** After the focus was asked, where it is looked up. */
        val PLACE_CHECKS_MILLIS = listOf(300L, 1_000L, 2_500L)

        fun startLine(listed: Boolean, waitMillis: Long, viewFocused: Boolean): String =
            "CARD_FOCUS listed=$listed waitMs=$waitMillis view=$viewFocused"

        fun eventLine(atMillis: Long, fromCard: Boolean): String = "CARD_FOCUS_EVENT dt=$atMillis onCard=$fromCard"

        fun placeLine(atMillis: Long, place: CardFocusPlace, trace: CardFocusTrace): String =
            "CARD_FOCUS_AT dt=$atMillis place=${place.name} events=${trace.onCard} others=${trace.others}"

        fun retryLine(outcome: CardRefocus): String = "CARD_FOCUS_RETRY outcome=${outcome.name}"
    }
}
