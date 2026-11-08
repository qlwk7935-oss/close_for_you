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
    /** F48: which part of the re-check failed (with RECHECK_TARGET_GONE; detail: a `RECHECK_MISS reason=` line). */
    RECHECK_GONE_PACKAGE,
    RECHECK_GONE_ROOT,
    RECHECK_GONE_TARGET,
    /** F48: an automatic press stopped because only the button did not match: said as a failed close, asked. */
    RECHECK_TARGET_FAILED_ASKED,
    /** F49: the popup root moved up or down only: read and judged again (once); the fresh button the same / another one. */
    RECHECK_SHIFTED_REREAD,
    RECHECK_SHIFTED_SAME_TARGET,
    RECHECK_SHIFTED_DIFFERENT,
    /** F49: no read used the re-read in time; the popup looked at once more (asked when still there). */
    RECHECK_SHIFTED_REREAD_UNUSED,
    /** F49: after a root miss the screen was read again: the popup still there / gone / no tree. */
    RECHECK_POPUP_STILL_SHOWN,
    RECHECK_POPUP_GONE,
    RECHECK_POPUP_UNKNOWN,
    /** F49: the popup root did not match but the popup is still on screen: said as a failed close, asked. */
    RECHECK_ROOT_FAILED_ASKED,
    /** F50: the popup root is read again before it is judged (it just appeared or moved) / stood still / still moving at the limit. */
    SETTLE_WAIT,
    SETTLE_STABLE,
    SETTLE_LIMIT,
    /** F60: a popup the tail read found: its own subtree read again for [SettleRules] / not read whole or gone: not judged from it. */
    TAIL_SETTLE_READ,
    TAIL_SETTLE_FAILED,
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

    /** F16: popups without a Dialog mark ([PopupRootKind]), and an app popup window read as its own tree. */
    DETECTED_FRONT_LAYER,
    DETECTED_BOTTOM_SHEET,
    DETECTED_APP_WINDOW,
    READ_APP_WINDOW,
    /** F16: a 결제·동의·구독·삭제·권한 button in the popup: asked in every preset. */
    BLOCKED_RISKY,
    /** F16 강력 모드: closed whatever its kind ([StrongCloseRules]). */
    DECIDED_AUTO_CLOSE_STRONG,
    /** F16: the prompt offers the popup's one unnamed X as "X 모양 닫기" (pressed only on the person's tap). */
    X_BUTTON_OFFERED,
    /** F17: the result card took TalkBack's focus / could not, so it spoke through its live region. */
    RESULT_BANNER_FOCUSED,
    RESULT_BANNER_FOCUS_FAILED,
    /** F17: the reclaim watch got its first target right when the page moved the focus (none was set yet). */
    FOCUS_RECLAIM_TARGET_TAKEN,
    /** F18: a video ad's named skip button ([VideoAdSkip]): found and pressed / waiting for it to appear. */
    VIDEO_SKIP_FOUND,
    VIDEO_SKIP_WAITING,
    VIDEO_SKIPPED,
    VIDEO_SKIP_NOT_IN_PRESET,

    /** F20: what the F15 probe did for a read that found a popup ([ProbeOutcome]). */
    PROBE_HIT_DIALOG,
    PROBE_HIT_DIM_PARENT,
    PROBE_HIT_LAYER,
    PROBE_MISS_CAPPED,
    PROBE_MISS,
    /** F20: after a close looked confirmed, the same popup was there again (strict check): not a close. */
    CLOSE_REAPPEARED,
    /** B_DEMO_RULES.md 10절: a popup stood where the closed one was, without any of its words: counted as closed, the other popup is judged next. */
    CLOSE_NEXT_POPUP_NOT_BACK,
    /** F20: an automatic close did not close; no result card, no focus move, the prompt asks at once. */
    AUTO_CLOSE_FAILED_ASKED,
    /** F21: the person's own press did not close it: asked again at once without that button. */
    USER_PRESS_FAILED_ASKED,
    /** F20: this popup failed an automatic close before: asked instead of pressing again (AGENTS 6-6). */
    AUTO_CLOSE_SKIPPED_AFTER_FAIL,
    /** F20: a decision popup is asked (no press option) instead of only explained. */
    DECISION_ASKED,
    /** F20: [버튼으로 이동]: TalkBack's focus put on the button / not taken / its place said by TTS (TalkBack off). */
    USER_CHOSE_MOVE,
    MOVED_TO_BUTTON,
    MOVE_TO_BUTTON_FAILED,
    MOVE_POSITION_SPOKEN,
    /** F20: the person chose to leave this popup: not asked or closed again while it stays. */
    SAME_POPUP_KEPT,
    /** F20 video diagnostics: YouTube app events arrive / the app is outside 도와줄 앱; ad mark seen or not. */
    VIDEO_EVENT_SEEN,
    VIDEO_APP_NOT_ALLOWED,
    VIDEO_AD_MARKER_FOUND,
    VIDEO_AD_MARKER_MISSING,
    /** F20: why no skip was pressed ([VideoAdDiag]). */
    VIDEO_SKIP_NO_CANDIDATE,
    VIDEO_SKIP_LABEL_UNNAMED,
    VIDEO_SKIP_NOT_CLICKABLE,
    VIDEO_SKIP_OFFSCREEN,
    VIDEO_SKIP_NO_AD_MARKER,
    VIDEO_SKIP_SEVERAL,

    /** F22: why an app event or read was skipped ([ScopeMiss]); replaces SKIPPED_NOT_ALLOWED_APP / TREE_UNAVAILABLE detail. */
    SKIPPED_SCOPE_NO_PACKAGE,
    SKIPPED_SCOPE_OWN_APP,
    SKIPPED_SCOPE_SYSTEM,
    SKIPPED_SCOPE_INPUT_METHOD,
    SKIPPED_SCOPE_USER_EXCLUDED,
    SKIPPED_SCOPE_SENSITIVE,
    SKIPPED_SCOPE_NOT_CHOSEN,
    SKIPPED_SCOPE_NO_MODE_NOT_ALLOWED,
    SKIPPED_SCOPE_LABEL_UNKNOWN,
    TREE_UNAVAILABLE_OTHER_APP,
    TREE_UNAVAILABLE_NO_ROOT,
    TREE_UNAVAILABLE_SENSITIVE,
    TREE_UNAVAILABLE_LABEL_UNKNOWN,
    TREE_UNAVAILABLE_NOT_ALLOWED,
    /** F22: a small app window above the main one with nothing to press was not taken for the popup. */
    APP_WINDOW_IGNORED,
    /** F22: a centred box over a screen-covering layer with an unnamed corner X ([PopupRootKind.MODAL_CARD]). */
    DETECTED_MODAL_CARD,
    /** F32: a screen-sized transparent window showing one centred card ([PopupRootKind.DIM_WINDOW]). */
    DETECTED_DIM_WINDOW,
    /** F34: a thin promotion strip at the bottom with a named close ([PopupRootKind.BOTTOM_BANNER]). */
    DETECTED_BOTTOM_BANNER,
    /** F34: a video ad's cover outside YouTube, its skip shown ([PopupRootKind.VIDEO_COVER]); asked, not skipped by itself. */
    DETECTED_VIDEO_COVER,
    /** F34: B's scam check: the prompt says so and offers [닫기 버튼으로 이동] only. */
    SUSPICIOUS_ASKED,
    /** F34: nothing to press yet, the preset would close it: waited for a late close ([LateCloseWait]). */
    LATE_CLOSE_WAITING,
    LATE_CLOSE_WAIT_TIMEOUT,
    LATE_CLOSE_APPEARED,
    /** F34: an install ad whose only button is the close: asked ([InstallAdLoneClose]). */
    INSTALL_AD_LONE_CLOSE_ASKED,
    /** F34: an automatic press left the app: no more automatic presses in that app this session. */
    AUTO_CLOSE_PAUSED_APP_LEFT,
    AUTO_CLOSE_SKIPPED_APP_PAUSED,
    /** F34: [WordKindHint] named the kind of an UNKNOWN popup, or a decision word kept it UNKNOWN. */
    WORD_HINT_COUPON,
    WORD_HINT_AD,
    WORD_HINT_NOTICE,
    WORD_HINT_DENIED,
    /**
     * F35 (B_DEMO_RULES.md 8-1): a modal card whose siblings were not in the read (the quick subtree read): the buttons
     * beside it could not be checked. With them read, the count is `NEIGHBORS_CHECKED` (a number line).
     */
    NEIGHBORS_UNREAD,
    /** F35: a waiting video ad's seconds said once ("광고를 n초 뒤에 건너뛸게요"). */
    VIDEO_SKIP_WAIT_ANNOUNCED,
    /** F35 (B_DEMO_RULES.md 8-5 B안): a promotion's login invitation no longer keeps the popup asked. */
    PROMO_LOGIN_NOT_ASKED,
    /** F36: a flat bottom banner found ([PopupRootFinder.flatBanner]), or why a bottom row with a close was not one. */
    BANNER_FLAT_FOUND,
    /** F37: an automatic close of a bottom banner did not close it: recorded, never pressed again, nobody asked. */
    BANNER_CLOSE_FAILED_QUIET,
    BANNER_MISS_ROW_ALONE,
    BANNER_MISS_TOO_NARROW,
    BANNER_MISS_TOO_TALL,
    BANNER_MISS_NOT_BOTTOM_MOST,
    BANNER_MISS_TOO_MANY_BUTTONS,
    BANNER_MISS_NO_PROMOTION,
    /** F36: a video ad cover outside YouTube: counting down, its skip shown, or why not ([VideoCoverMiss]). */
    VIDEO_COVER_WAITING,
    VIDEO_COVER_SHOWN,
    VIDEO_COVER_MISS_NO_AD_MARK,
    VIDEO_COVER_MISS_SEVERAL_SKIPS,
    VIDEO_COVER_MISS_NO_SKIP_OR_COUNTDOWN,
    VIDEO_COVER_MISS_NO_HOLDER,
    /** F36: a counting-down cover is read again in a second, not left to the page's repaints. */
    VIDEO_COVER_REREAD,
    /** F32 (e): the app opened an activity named like a popup (the name itself is never logged). */
    POPUP_ACTIVITY_HINT,
    /** F32: the unnamed X was offered as [버튼으로 이동] only (a decision, or a risky button beside it). */
    X_BUTTON_MOVE_OFFERED,
    /** F33: which kind of button was pressed, right after CLICKED ([PressKind]). */
    CLICKED_LABELED_CLOSE,
    CLICKED_STAY_ON_WEB,
    CLICKED_DIM_CLOSE,
    CLICKED_HIDE_FOR_A_WHILE,
    CLICKED_POLITE_NO,
    CLICKED_SKIP,
    CLICKED_UNLABELED_X,
    CLICKED_OTHER,
    /** F33: why a press counted as not closed, right after NOT_CLOSED. */
    NOT_CLOSED_STILL_THERE,
    NOT_CLOSED_NO_TREE,
    NOT_CLOSED_REAPPEARED,
    NOT_CLOSED_STILL_MOVING,
    /** F22: the popup the person remembered with "앞으로 항상 닫기", closed again by itself. */
    REMEMBERED_AUTO_CLOSE,
    REMEMBERED_SAVED,
    REMEMBER_OFFERED,
    /** F22: a video path read outside YouTube (app, m.youtube.com) was not run. */
    VIDEO_NOT_VIDEO_CONTEXT,
    /** F25: a whole read stopped at its node or time cap ([WholeReadCap]); the part read is used. */
    READ_WHOLE_ABORTED,
    /** F51: after a capped whole walk with no popup, the read from the end ([TailRead]) started, and how it ended. */
    READ_TAIL_STARTED,
    READ_TAIL_FOUND,
    READ_TAIL_NONE,
    READ_TAIL_ABORTED,
    READ_TAIL_SUBTREE_CUT,
    /** F53: what may lie over the button: stopped by it ([StackBlock]), asked; the look could not be made; another popup left after a close. */
    STACK_BLOCKED_WINDOW_ABOVE,
    STACK_BLOCKED_COVER_LATER,
    STACK_BLOCKED_POPUP_OVERLAP,
    STACK_ASKED,
    STACK_UNREAD,
    /** F57: no popup in front could be told: no move offered; the move went to its close or first node; asked nothing for now; that time ended. */
    STACK_NO_FRONT,
    OVERLAP_MOVED_TO_FRONT_CLOSE,
    OVERLAP_MOVED_TO_FRONT_FIRST,
    OVERLAP_MOVE_FAILED,
    MOVE_HOLD_QUIET,
    CLOSE_OTHER_POPUP_LEFT,
    /** F27 (self review): the same popup came back within a few seconds of a confirmed close: a failure after all. */
    CLOSE_REAPPEARED_LATE,
    /** F27: part of the popup is deeper than the walk goes: asked, never closed by itself. */
    DEPTH_CUT_ASKED,
    /** F27: a remembered "앞으로 항상 닫기" failed: the rule is forgotten. */
    REMEMBERED_RULE_DROPPED,
    /** F27: this skip button did not go away when pressed: never pressed again this session. */
    VIDEO_SKIP_FAILED_BEFORE,
    /** F28 (B_DEMO_RULES.md 2절): the screen was left alone right after the read ([SensitiveScreen]); nothing judged, pressed or spoken. */
    SKIPPED_SCOPE_SENSITIVE_PASSWORD_FIELD,
    SKIPPED_SCOPE_SENSITIVE_WEB_ADDRESS,
    SKIPPED_SCOPE_SENSITIVE_WEB_CREDENTIAL_FORM,
    SKIPPED_SCOPE_SENSITIVE_WEB_PAYMENT_PAGE,
    SKIPPED_SCOPE_SENSITIVE_WEB_PERSONAL_DATA,
    /** F28 (B_DEMO_RULES.md 1절): a checked promotion's purchase call to action no longer keeps it asked (never pressed). */
    PROMO_PURCHASE_NOT_ASKED,
    /** F28 (B_DEMO_RULES.md 4-1): outside YouTube, a skip with no ad mark in its own group or right next to it: not pressed. */
    VIDEO_SKIP_AD_MARK_NOT_NEAR,
    /** F28: outside YouTube, B's decision or scam check on the screen's words did not pass: not pressed. */
    VIDEO_SKIP_DENIED_BY_B,
    /** F28 (LG 11/1 14:29): the popup's app is no longer on screen (another app, ours too): our prompt closed, the analysis dropped. */
    CHOICE_DISMISSED_APP_LEFT,
    /** F29: the strict re-check found the popup elsewhere (leaving: sliding, shrinking): read again. */
    CLOSE_RECHECK_MOVING,
    /** F29: still moving when the re-check's time ran out: counted as not closed (asked). */
    CLOSE_RECHECK_STILL_MOVING,
    /** F30: the popup window reported the whole screen; its root view showed the card ([WindowBoundsRules]). */
    WINDOW_BOUNDS_FROM_ROOT,
    /** F31 (B_DEMO_RULES.md 6-1): the automatic press was stopped right before it; the reason follows as PRE_PRESS_STOPPED_*. */
    PRE_PRESS_RECHECK_STOPPED,
    PRE_PRESS_STOPPED_BECAME_DECISION_OR_SCAM,
    PRE_PRESS_STOPPED_RISKY_BUTTON_APPEARED,
    PRE_PRESS_STOPPED_KIND_CHANGED,
    /** F31: the popup could not be read again in time (gone, or past the cap): not pressed. */
    PRE_PRESS_STOPPED_UNREAD,
    /** 11/7 G마켓 ([BannerBounds]): a container banner's close no longer drawn inside it: not pressed, nothing asked. */
    PRE_PRESS_STOPPED_OUTSIDE_BANNER,
    PRE_PRESS_RECHECK_PASSED,
    /** F31 (B_DEMO_RULES.md 6-2): an automatic close asked instead, too many in this app within a minute. */
    CHAIN_GUARD_ASKED_RATE,
    /** F31: an automatic close asked instead, another popup appeared right after our own press. */
    CHAIN_GUARD_ASKED_NEW_POPUP_AFTER_PRESS,

    // F6: why the image-dialog ad hint did not apply ([AdHintMiss]).
    AD_HINT_MISS_SCAM,
    AD_HINT_MISS_NOT_UNKNOWN,
    AD_HINT_MISS_NOT_DIALOG_ROOT,
    AD_HINT_MISS_NO_VERIFIED_CLOSE,
    AD_HINT_MISS_IMAGE_TOO_SMALL,
    AD_HINT_MISS_TOO_MUCH_TEXT,
    /**
     * F39 (우진 삼성 SM-F966N, TalkBack 켬, [말하기] "닫아": closed, then TalkBack read the page's body instead of
     * "닫았어요"; FOCUS_RECLAIMED=0): BY_VOICE / BY_TAP: how the press was chosen (no words). During the watch, a
     * focus move we did not make: OTHER_INTO_POPUP (into the old popup or a gone node) / OTHER_ON_PAGE (elsewhere
     * on the page). RECLAIM_PAGE_TO_CARD: such a page move while the result card held the focus, put back on the
     * card; CARD_REFUSED: the card could not take it back.
     */
    USER_CHOSE_BY_VOICE,
    USER_CHOSE_BY_TAP,
    FOCUS_OTHER_INTO_POPUP,
    FOCUS_OTHER_ON_PAGE,
    FOCUS_RECLAIM_PAGE_TO_CARD,
    FOCUS_RECLAIM_CARD_REFUSED,
    /** F40: "광고만 닫기" and the popup's only close is "오늘 하루 보지 않기"-like ([HideTodaySafePreset]): asked, not pressed. */
    HIDE_TODAY_ASKED_SAFE_PRESET,
    /** F41: the result card offered [방금 닫은 내용 듣기] / it was pressed and read / pressed after it was forgotten. */
    RESULT_HEAR_CLOSED_OFFERED,
    RESULT_HEAR_CLOSED_READ,
    RESULT_HEAR_CLOSED_GONE,
    /**
     * F42 (a card with [방금 닫은 내용 듣기], [ResultCardClock]): HELD_FOCUS: past its timeout but TalkBack's focus is
     * inside, kept; FOCUS_LEFT: the focus left it, going in 2 s; HOLD_MAX: still focused at 15 s, gone; TIMED_OUT:
     * gone without the focus; REPLACED_BY_NEXT: the next popup took over. The first timeout is the debug line
     * `RESULT_CARD_TIMEOUT_MS=<ms>`.
     */
    RESULT_CARD_HELD_FOCUS,
    RESULT_CARD_FOCUS_LEFT,
    RESULT_CARD_HOLD_MAX,
    RESULT_CARD_TIMED_OUT,
    RESULT_CARD_REPLACED_BY_NEXT,
    /**
     * F43 ([WindowBoundsRules.bottomSheet]): the active app window, with nothing wider of its app listed, is sheet-shaped at
     * the bottom: ACCEPTED as the popup window / REJECTED_SPLIT (a split-screen divider or another app window above it).
     */
    BOTTOM_SHEET_WINDOW_ACCEPTED,
    BOTTOM_SHEET_REJECTED_SPLIT,
    /**
     * F44 ([TalkBackResultBanner.refocus], [CardFocusRules]): the focus put back on the result card through the
     * service's node (BY_NODE), the same after clearing its stale flag (AFTER_CLEAR), or our View (BY_VIEW, the card's
     * window not listed); refused = FOCUS_RECLAIM_CARD_REFUSED. RESULT_CARD_WINDOW_UNKNOWN: the card's overlay window
     * was not found among the windows (focus checks fell back to the View), once per card.
     */
    FOCUS_RECLAIM_CARD_BY_NODE,
    FOCUS_RECLAIM_CARD_AFTER_CLEAR,
    FOCUS_RECLAIM_CARD_BY_VIEW,
    RESULT_CARD_WINDOW_UNKNOWN,
    /**
     * F45: [방금 닫은 내용 듣기] pressed: the card's clock started again from the press, its new limit the debug line
     * `RESULT_CARD_READ_HOLD_MS=<ms>` ([ResultCardClock.readHoldMillis]).
     */
    RESULT_CARD_RESTARTED_ON_READ,
    /**
     * F47 ([ReclaimDeferral]): a focus move we did not make is taken back only after a short wait (DEFERRED);
     * the person's touch or gesture came with it, so it is theirs (SKIPPED_USER_MOVE, the watch ends).
     * Our prompt's first focus: looked after (HOLD_STARTED), moved away by TalkBack or the app (LOST), put back
     * (RECLAIMED / RECLAIM_FAILED), ended by the person's touch (HOLD_END_BY_USER) or after 2 times (HOLD_LIMIT).
     */
    FOCUS_RECLAIM_DEFERRED,
    FOCUS_RECLAIM_SKIPPED_USER_MOVE,
    PROMPT_FOCUS_HOLD_STARTED,
    PROMPT_FOCUS_LOST,
    PROMPT_FOCUS_RECLAIMED,
    PROMPT_FOCUS_RECLAIM_FAILED,
    PROMPT_FOCUS_HOLD_END_BY_USER,
    PROMPT_FOCUS_HOLD_LIMIT,
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
