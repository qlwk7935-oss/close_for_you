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
     * F17 (demo mode 6-0): after a close with the focus to look after, the result card takes TalkBack's
     * focus ([TalkBackResultBanner]) instead of a page item: one move, one sentence. False = F15 (page move,
     * reclaim, then the card in TalkBack's queue).
     */
    const val RESULT_BANNER_TAKES_FOCUS: Boolean = true

    /**
     * F22-2 D (browser speech, "올리브영 수준": found, closed, one sentence): with the result card taking the focus, an
     * automatic close no longer first moves TalkBack's focus to the toolbar (it said the toolbar's name before the
     * result). The reclaim watch still runs from the click and takes a page place only if the page pulls the focus
     * into the old popup. The person's own press keeps its move (our window goes away under the focus).
     */
    const val AUTO_PRE_CLICK_MOVE_WITH_BANNER: Boolean = false

    /** F17: touch events this soon after the reclaim watch began are the press's own double tap, not a new touch. */
    const val FOCUS_RECLAIM_TOUCH_GRACE_MILLIS = 800L

    /**
     * F18 (F5 video ads): a video ad's named skip button is pressed in a preset that closes ads ([VideoAdSkip]).
     * Device unverified (YouTube app and web).
     */
    const val VIDEO_AD_SKIP: Boolean = true

    /**
     * F20 (LG 11/1 11번가: "closed" 0.27 s after the click, then the same sheet again): a close counts only when,
     * [CLOSE_RECHECK_MILLIS] after the root went away, a fresh probe-only read (F21: 600 → 350 ms, F22-2: 220 ms for
     * CLOSE_RECHECK_MS ≤ 500 (LG 옥션 625); no whole-page
     * walk, so the result follows within about 0.7 s) does not show the same popup again
     * ([PopupMemory.reappeared]). Before that no result card, focus move or reclaim end happens.
     */
    const val STRICT_CLOSE_CHECK: Boolean = true

    /**
     * F22 (AGENTS 6-3·6-4): the unnamed X offered in the prompt also comes as "앞으로 항상 닫기": pressed once on the
     * person's tap, remembered only when the close is confirmed ([RememberedPopups]), then closed by itself next
     * time when the same popup is in the same place with the same button.
     */
    const val REMEMBER_X_ALWAYS: Boolean = true

    /**
     * F24 (재호 to decide): true = a "구매·주문" button beside the close keeps the popup asked, as in F16~F22 (the
     * safe default). False = F23: only 결제·동의·구독·삭제·권한·허용·로그인… keep it asked; 구매·주문 are still never
     * pressed (D's answer key L04~L06, L13 CLOSE_OK).
     */
    const val ASK_WHEN_PURCHASE_BUTTON: Boolean = true
    const val CLOSE_RECHECK_MILLIS = 220L

    /**
     * F29 (LG 11/1 11번가 웹: a closing sheet still sliding out 220 ms after the click counted as back): a popup found
     * again at another place is read again every [CLOSE_RECHECK_MILLIS] until it is gone or still; past this from the
     * first re-check it counts as not closed. A popup back in its place is a failure at the first read, as before.
     */
    const val CLOSE_RECHECK_SETTLE_MILLIS = 1_200L

    /**
     * F31 (B_DEMO_RULES.md 6-1): right before an automatic press the popup's own subtree is read again and B judges it
     * again ([PrePressRecheck]); a decision, a scam, a new risky button or a kind the preset does not close stops the
     * press and the person is asked. The read stops at [PRE_PRESS_READ_CAP_MILLIS] (an unfinished read also stops).
     */
    const val PRE_PRESS_RECHECK: Boolean = true
    const val PRE_PRESS_READ_CAP_MILLIS = 250L

    /**
     * F50 (에뮬레이터 P01: a Dialog judged while its window was still coming in): a popup root is judged only once two
     * reads in a row found it standing still ([SettleRules]), at most a second after it was first seen.
     */
    const val SETTLE_BEFORE_JUDGING: Boolean = true

    /**
     * F31 (B_DEMO_RULES.md 6-2, numbers to be checked on the LG with 11번가-like chains): more than [CHAIN_MAX_AUTO]
     * automatic closes in one app within [CHAIN_WINDOW_MILLIS] are asked (`CHAIN_GUARD_ASKED_RATE`), and so is another
     * popup that appears within [CHAIN_NEW_POPUP_AFTER_PRESS_MILLIS] of our own press (`CHAIN_GUARD_ASKED_NEW_POPUP_AFTER_PRESS`).
     */
    const val CHAIN_GUARD: Boolean = true

    /** F34: wait for a close that comes late ([LateCloseWait]) instead of asking at once. */
    const val LATE_CLOSE_WAIT: Boolean = true

    /** F34: an install ad whose only button is "닫기" is asked ([InstallAdLoneClose]). */
    const val INSTALL_AD_LONE_CLOSE_ASK: Boolean = true

    /** F34: B's word lists name an UNKNOWN popup's kind ([WordKindHint]). */
    const val WORD_KIND_HINT: Boolean = true
    const val CHAIN_MAX_AUTO = 3
    const val CHAIN_WINDOW_MILLIS = 60_000L
    const val CHAIN_NEW_POPUP_AFTER_PRESS_MILLIS = 1_500L

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
    val RECHECK_DELAYS_MILLIS = listOf(200L, 400L, 600L, 800L, 1_000L, 1_500L, 2_000L, 3_000L)

    /**
     * F22-2 (LG 11/1 옥션 웹: DETECT_LATENCY_MS=4461, caught by the 5th re-check): re-checks and repaint reads that
     * find nothing cost only the probe (no whole-page walk, about 0.3 s instead of 0.7~2 s), so they can come
     * more often ([RECHECK_DELAYS_MILLIS], [ReadBudget]). The window event's read, a TalkBack
     * focus read and every [WHOLE_READ_EVERY]-th read of a search still read the whole tree when the probe
     * misses (a popup beyond its cap).
     */
    const val WHOLE_READ_EVERY = 3

    /**
     * F13 (LG 11/1 11번가: 6.4 s from the first event to the read that found the sheet, no read in
     * between): a first read already waiting is never pushed back past this by further events.
     */
    const val FIRST_READ_MAX_WAIT_MILLIS = 400L

    /*
     * F24: repaint reads follow [ReadBudget.contentGap] (0.2 s, doubling to 3 s while nothing is found; was a fixed
     * 1.5 s, then 0.7 s in F22-2, which read without rest on carousel pages).
     */

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

    /**
     * F39 (삼성, TalkBack, voice "닫아"): while the focused result card is up, a page move is taken back to the card
     * ([ReclaimVerdict.RECLAIM_TO_CARD]) for this long after it took the focus; still at most [FOCUS_RECLAIM_MAX]
     * and ended by the person's touch or gesture.
     */
    const val FOCUS_RECLAIM_CARD_WINDOW_MILLIS = 3_000L

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
