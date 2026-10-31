package kr.closeforyou.simpleclose

/**
 * One code per step of the engine, for the debug log tag `CfySimple`. Names only: no text, no ids,
 * no package names (AGENTS 6-9, C rule 3-3). The device check reads this sequence.
 */
enum class SimpleTrace {
    SERVICE_CONNECTED,
    SERVICE_DISABLED_BY_FEATURE,
    EVENT_OWN_WINDOW_IGNORED,
    SKIPPED_NOT_ALLOWED_APP,
    TREE_UNAVAILABLE,
    NO_POPUP_ROOT,
    DETECTED_ALERTDIALOG,
    DETECTED_DIALOG_WINDOW,
    DETECTED_DIM_OVERLAY,
    SAME_POPUP_COOLDOWN,
    CLASSIFIED_AD,
    CLASSIFIED_COUPON,
    CLASSIFIED_NOTICE,
    CLASSIFIED_DECISION,
    CLASSIFIED_UNKNOWN,
    CLASSIFIED_SUSPICIOUS,
    CANDIDATE_FOUND,
    CANDIDATE_NONE,
    BLOCKED_FORBIDDEN,
    UNLABELED_SKIPPED,
    DECIDED_AUTO_CLOSE,
    /** F7: an UNKNOWN popup closed under [DemoUnknownAutoClose] ("전부 자동 닫기" only). */
    DECIDED_AUTO_CLOSE_UNKNOWN,
    DECIDED_ASK,
    DECIDED_DENY_DECISION,
    DECIDED_DENY_SUSPICIOUS,
    ASKED,
    PROMPT_NOT_SHOWN,
    USER_CHOSE_PRESS,
    USER_CHOSE_KEEP,
    USER_CHOSE_READ,
    RECHECK_TARGET_GONE,
    CLICK_TARGET_NOT_CLICKABLE,
    CLICKED,
    CLICK_REJECTED,
    CLOSED_CONFIRMED,
    NOT_CLOSED,
    LEFT_APP,
    POPUP_GONE_PROMPT_DISMISSED,
    RECHECK_SCHEDULED,
    CONTENT_READ_THROTTLED,
    AD_HINT_IMAGE_DIALOG,
    FOCUS_MOVED_TO_PAGE,
    FOCUS_MOVE_FAILED,

    // F4 (LG 10/31 21:00): what the focus was put on after a close ([FocusTargetKind]).
    FOCUS_TARGET_OVERLAPS_OLD_POPUP,
    FOCUS_TARGET_WEBVIEW_ROOT,
    FOCUS_TARGET_PAGE_HEADING,
    FOCUS_TARGET_PAGE_ITEM,
    FOCUS_TARGET_NONE,
    FOCUS_TARGET_ABOVE_OLD_POPUP,
    FOCUS_TARGET_TOOLBAR_ITEM,
    /** F12: TalkBack's focus was already outside the old popup after the close (put there before the press): not moved again. */
    FOCUS_KEPT_ON_PAGE,
    /** F13: after the close nothing held TalkBack's focus (the node it was on went away) / it was still in the old popup; moved once. */
    FOCUS_AFTER_CLOSE_LOST,
    FOCUS_AFTER_CLOSE_IN_POPUP,
    /** F13: an event while a first read for the same app was already waiting; that read covers it. */
    EVENT_COALESCED,
    /** F12: the focus target picked right before the press ([FocusTargetKind]). */
    FOCUS_PRE_CLICK_ABOVE_OLD_POPUP,
    /** F13: a heading wholly above the popup, before the press. */
    FOCUS_PRE_CLICK_PAGE_HEADING,
    FOCUS_PRE_CLICK_WEBVIEW_ROOT,
    FOCUS_PRE_CLICK_TOOLBAR_ITEM,
    FOCUS_PRE_CLICK_NONE,
    /** F12: the result card was really on screen after it was added (or not). */
    RESULT_BANNER_SHOWN,
    RESULT_BANNER_NOT_SHOWN,
    /** F11: where TalkBack's focus was before an automatic close (moved after it only when in the popup). */
    FOCUS_WAS_IN_POPUP,
    FOCUS_WAS_OUTSIDE,
    FOCUS_WAS_UNKNOWN,
    /** F11: the result sentence went through TalkBack's queue (live region) or our TTS. */
    RESULT_VIA_TALKBACK,
    RESULT_VIA_TTS,
    // F10: which read found the popup.
    DETECTED_BY_WINDOW_EVENT,
    DETECTED_BY_CONTENT_EVENT,
    DETECTED_BY_RECHECK,
    DETECTED_BY_TALKBACK_FOCUS,
    /** Focus put on a page item right before the press ([SimpleCloseEngineFeature.FOCUS_PAGE_BEFORE_CLICK]). */
    FOCUS_PRE_CLICK_MOVED,
    FOCUS_PRE_CLICK_FAILED,
    /** F15: the read that found the popup: only the popup's subtree / the whole tree / the whole tree after a probe miss. */
    READ_POPUP_SUBTREE,
    READ_FULL,
    READ_FULL_AFTER_PROBE,
    /**
     * F15 (demo mode): after the press, the page moved TalkBack's focus into the old popup or onto a node
     * that went with it, and we put it back on the page at once ([FocusReclaim]). STOPPED_BY_USER: a touch
     * or gesture ended the watch; LIMIT: [SimpleCloseEngineFeature.FOCUS_RECLAIM_MAX] reached; NO_TARGET:
     * no page item took the focus.
     */
    FOCUS_RECLAIM_STOPPED_BY_USER,
    FOCUS_RECLAIM_LIMIT,
    FOCUS_RECLAIM_NO_TARGET,

    // F6: why the image-dialog ad hint did not apply ([AdHintMiss]).
    AD_HINT_MISS_SCAM,
    AD_HINT_MISS_NOT_UNKNOWN,
    AD_HINT_MISS_NOT_DIALOG_ROOT,
    AD_HINT_MISS_NO_VERIFIED_CLOSE,
    AD_HINT_MISS_IMAGE_TOO_SMALL,
    AD_HINT_MISS_TOO_MUCH_TEXT,
    ;

    companion object {
        fun focusTarget(kind: FocusTargetKind): SimpleTrace = valueOf("FOCUS_TARGET_${kind.name}")

        /** Before the press only TOOLBAR_ITEM / PAGE_HEADING / ABOVE_OLD_POPUP are picked; anything else is NONE. */
        fun preClickTarget(kind: FocusTargetKind): SimpleTrace =
            entries.firstOrNull { it.name == "FOCUS_PRE_CLICK_${kind.name}" } ?: FOCUS_PRE_CLICK_NONE
        fun adHintMiss(miss: AdHintMiss): SimpleTrace = valueOf("AD_HINT_MISS_${miss.name}")
    }
}

/** Debug-only sink for [SimpleTrace]; the Android service installs Log.d, tests a list. */
fun interface SimpleTraceSink {
    fun trace(code: SimpleTrace)

    companion object {
        val NONE = SimpleTraceSink { }
    }
}
