package kr.closeforyou.ui.voice.overlay

import androidx.annotation.StringRes
import kr.closeforyou.R

/**
 * REVALIDATING (issue #29, Woojin's proposal 10/24, C agreed): when A invalidates the token while
 * C's prompt is up, C keeps the same window, clears and disables it, and waits for A's new F0
 * check instead of closing at once. A has not answered yet, so it stays OFF: every call then does
 * what it did before (invalidation → dismiss). (REPLACE-WITH-CONTRACT)
 */
object RevalidatingFeature {
    /** Off until A sends the three signals (docs/c-notes/overlay-identity-contract.md 6장). */
    val enabled: Boolean get() = false

    /** No confirmation within this time → the window closes (value to agree with A). */
    const val TIMEOUT_MILLIS = 3_000L
}

/** Why A started a re-check. C does the same thing for each; kept so diagnostics can tell them apart. */
enum class RevalidationCause {
    /** A screen event invalidated the token (issue #29: target window settling, status bar, focus). */
    SCREEN_EVENT,

    /** The evidence TTL ran out while the screen looked unchanged. */
    EVIDENCE_EXPIRED,
    OTHER,
}

/** Why a re-check ended without the same popup. A decides which; C only picks the sentence. */
enum class RevalidationEnd {
    /** A found a different popup (its own prompt follows). */
    DIFFERENT_POPUP,

    /** The screen changed and the popup is not confirmed. */
    SCREEN_CHANGED,

    /** Sensitive screen: closed silently, like `PopupEvent.Blocked`. */
    BLOCKED,

    /** C's own timeout ([RevalidatingFeature.TIMEOUT_MILLIS]); A does not send it. */
    TIMED_OUT,
}

/** The one sentence after a re-check closed the window; null = say nothing. No command words. */
object RevalidationSpeech {
    @StringRes
    fun closedRes(end: RevalidationEnd): Int? = when (end) {
        RevalidationEnd.DIFFERENT_POPUP, RevalidationEnd.SCREEN_CHANGED -> R.string.ui_revalidate_closed_changed
        RevalidationEnd.TIMED_OUT -> R.string.ui_revalidate_closed_timeout
        RevalidationEnd.BLOCKED -> null
    }
}
