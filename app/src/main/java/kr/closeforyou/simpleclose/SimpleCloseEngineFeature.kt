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
 * - [helpAllAppsWhenUnset]: with no "도와줄 앱" choice yet, every non-system app is helped.
 *
 * What never relaxes (6-0 "끝까지 유지"): decision words are never pressed ([SimpleLabelRules.FORBIDDEN]),
 * a suspicious popup is only warned about, one click and no retry, no screen text in logs.
 */
object SimpleCloseEngineFeature {
    const val enabled: Boolean = true
    const val demoLabelRulesV2: Boolean = true
    const val helpAllAppsWhenUnset: Boolean = true

    /** Window events settle before the tree is read (Chrome paints web nodes a little late). */
    const val DEBOUNCE_MILLIS = 300L

    /**
     * After a read found no popup, the tree is read again after each of these waits (LG 10/31: the
     * 11번가 sheet was still animating at the first read and Chrome sent no further event for 40 s).
     * Any new window event restarts the chain.
     */
    val RECHECK_DELAYS_MILLIS = listOf(500L, 1_000L, 2_000L, 3_000L)

    /**
     * After a confirmed close, TalkBack's focus is moved to the first item of the page's web content
     * (demo mode 6-0): a closed web popup can stay in the DOM and TalkBack kept reading it (LG 10/31).
     */
    const val FOCUS_PAGE_AFTER_CLOSE = true

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

    /** The same popup (same fingerprint in the same window) is not handled again for this long. */
    const val SAME_POPUP_COOLDOWN_MILLIS = 10_000L

    /** Tree walk caps: a page like m.naver.com has ~1,500 nodes; the popup is found well before. */
    const val MAX_NODES = 2_500
    const val MAX_DEPTH = 64
}
