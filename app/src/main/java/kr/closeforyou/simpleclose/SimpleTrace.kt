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
    /** F9: the strict first try found nothing; tried again with fallbacks. */
    FOCUS_RETRY,
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
