package kr.closeforyou.simpleclose

/**
 * C's "단순 닫기 엔진" (plan B, AGENTS.md 6-0 demo mode, 9장 11/1 first integration): a second
 * accessibility service inside the real app that finds a popup root in the active app's tree,
 * classifies it with B's classifier, and closes it with one ACTION_CLICK or asks through C's
 * prompt. A's service and files are not touched; both services are never meant to run together.
 *
 * Every demo relaxation lives behind this one object so a release build can turn it off:
 * - [enabled]: the whole engine (the service does nothing when false).
 * - [demoLabelRulesV2]: "○○ 닫기", "웹으로 볼래요", "배경을 눌러 닫기" count as close labels
 *   ([SimpleLabelRules]); the shared contract's allowlist is still tried first.
 *
 * What never relaxes (6-0 "끝까지 유지"): decision words are never pressed ([SimpleLabelRules.FORBIDDEN]),
 * a suspicious popup is only warned about, one click and no retry, no screen text in logs.
 */
object SimpleCloseEngineFeature {
    const val enabled: Boolean = true
    const val demoLabelRulesV2: Boolean = true

    /** F16: polite dismiss labels ("오늘 하루 보지 않기", "괜찮아요", "No thanks"…), whole label only ([SimpleLabelRules]). */
    const val demoLabelRulesV3: Boolean = true

    /**
     * F16 강력 모드 (demo mode 6-0, F7 extended): with "전부 닫기" and its "자동 닫기" switch on, a popup is
     * closed whatever its kind (광고·쿠폰·공지·판단 불가) when it has no risky button, at least one named
     * close button (the plainest is pressed) and is neither a decision nor scam-like ([StrongCloseRules]).
     * False = F7 (an unknown popup only with exactly one button).
     */
    const val STRONG_CLOSE_ALL: Boolean = true

    /**
     * F16: popups without a Dialog mark are found too: a large front layer over the page, a bottom sheet
     * ([PopupRootFinder]), and an app's own popup window above its main window ([SimpleTreeCollector]).
     */
    const val FIND_FRONT_LAYERS: Boolean = true
    const val FIND_APP_POPUP_WINDOWS: Boolean = true

    /**
     * F16: a popup with nothing named to press but one X-shaped button ([ButtonScan.xButton]) offers
     * "'X 모양 닫기' 버튼 눌러 주기"; pressed only on the person's tap (AGENTS 6-3), never automatically.
     */
    const val OFFER_X_BUTTON: Boolean = true

    /**
     * Window events settle before the tree is read (Chrome paints web nodes a little late). 300 → 200 ms
     * (F5, LG 10/31 21:00: TalkBack read the 11번가 sheet twice before our window came); a read that comes
     * too early is caught by the first re-check.
     */
    const val DEBOUNCE_MILLIS = 200L

    /**
     * After a read found no popup, the tree is read again after each of these waits (LG 10/31: the
     * 11번가 sheet was still animating at the first read and Chrome sent no further event for 40 s).
     * Any new window event restarts the chain.
     */
    val RECHECK_DELAYS_MILLIS = listOf(200L, 500L, 1_000L, 2_000L, 3_000L)

    /**
     * F13 (LG 11/1 11번가: 6.4 s from the first event to the read that found the sheet, no read in
     * between): a first read already waiting is never pushed back past this by further events.
     */
    const val FIRST_READ_MAX_WAIT_MILLIS = 400L

    /**
     * A content change (Chrome repaints constantly) gets one read, at most this often, and never
     * restarts the re-check chain; only a window-state or windows change does (LG 10/31: the chain
     * restarted on every repaint and read the tree every 1~2 s for minutes).
     */
    const val CONTENT_CHANGE_READ_MILLIS = 1_500L

    /**
     * Demo hint for an image-only dialog (옥션: one big clickable image with little text, a 닫기
     * button): when B says UNKNOWN and nothing is suspicious, the engine treats it as an advertisement.
     * The engine's own rule, not B's classifier; a notice drawn as an image would be closed too.
     */
    const val IMAGE_DIALOG_AD_HINT = true

    /**
     * After a confirmed close, TalkBack's focus is moved to the first item of the page's web content
     * (demo mode 6-0): a closed web popup can stay in the DOM and TalkBack kept reading it (LG 10/31).
     */
    const val FOCUS_PAGE_AFTER_CLOSE = true

    /**
     * Also right before the press (F4): TalkBack's focus is put on a page item outside the popup first,
     * so when our window (or the popup) goes away it is not left on the popup's DOM leftovers. Done
     * before the target re-check, which still runs right before the click. F13: for the person's own
     * press it is done before our window is removed, so TalkBack never falls back into the popup in
     * between. Device check needed.
     */
    const val FOCUS_PAGE_BEFORE_CLICK = true

    /**
     * F12: the result card waits this long after a focus move made after the close, so TalkBack has
     * read the newly focused item and then says "닫았어요" (LG 10/31: it was not heard). F13: no wait
     * when the focus was kept.
     */
    const val RESULT_AFTER_FOCUS_MILLIS = 600L

    /**
     * F14: after a confirmed close with the focus to look after, wait this long before checking where
     * TalkBack's focus is, so a focus the page restores on its own comes first and ours is the last move.
     */
    const val FOCUS_SETTLE_MILLIS = 300L

    /**
     * F15, demo mode (AGENTS 6-0; off again for any release): after a press, take TalkBack's focus back
     * from the page when it moves it into the closed popup or onto a node that went with it ([FocusReclaimRules]).
     * Watched from the click until [FOCUS_RECLAIM_WINDOW_MILLIS] after the confirmed close, at most
     * [FOCUS_RECLAIM_MAX] times, ended by the person's touch or gesture. False = F14's single move after
     * [FOCUS_SETTLE_MILLIS].
     */
    const val FOCUS_RECLAIM_AFTER_CLOSE = true
    const val FOCUS_RECLAIM_WINDOW_MILLIS = 1_500L
    const val FOCUS_RECLAIM_MAX = 3

    /** F15: the result sentence waits until no move for this long (TalkBack reads the focused item first). */
    const val FOCUS_RECLAIM_QUIET_MILLIS = 600L
    const val FOCUS_RECLAIM_POLL_MILLIS = 50L

    /** F10: TalkBack focusing something in a helped app starts a read at once, at most this often. */
    const val FOCUS_EVENT_READ_MILLIS = 300L

    /** After the click: the popup root must be gone within this to count as closed, checked every [CONFIRM_POLL_MILLIS]. */
    const val CONFIRM_DELAY_MILLIS = 1_500L
    const val CONFIRM_POLL_MILLIS = 250L

    /** While C's prompt is up the tree is re-read at most this often (only to notice the popup went away). */
    const val PROMPT_RECHECK_MILLIS = 2_000L

    /**
     * Whether the prompt's own TTS reads the popup text in full (TalkBack takeover). LG 10/31: too much
     * talking; false = the short kind sentence only, and [내용 다시 읽기] reads the full text on request.
     */
    const val READ_POPUP_TEXT_ON_PROMPT = false

    /**
     * F13 (one voice): a notice's first words are shown in the prompt's first item, so TalkBack reads
     * them with the summary and the question; at most this many characters, cut at a space.
     */
    const val PROMPT_EXCERPT_CHARS = 50

    /** The same popup (same fingerprint in the same window) is not handled again for this long. */
    const val SAME_POPUP_COOLDOWN_MILLIS = 10_000L

    /** Tree walk caps: a page like m.naver.com has ~1,500 nodes; the popup is found well before. */
    /**
     * F15: find the popup root with a small probe ([PopupProbe], last child first) and read only its
     * subtree; the whole tree only when that gives no popup root. False = every read is a full read.
     */
    const val READ_POPUP_SUBTREE_FIRST = true

    /** F15: nodes the probe may fetch (about 1.4 ms each on the LG Velvet in Chrome). */
    const val PROBE_MAX_VISITED = 200

    const val MAX_NODES = 2_500
    const val MAX_DEPTH = 64
}
