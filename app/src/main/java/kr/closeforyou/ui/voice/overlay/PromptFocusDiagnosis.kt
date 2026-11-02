package kr.closeforyou.ui.voice.overlay

/**
 * F46 (우진 SM-F966N, ac66631, 11번가 앱 동의 팝업 ×2: DECISION_ASKED → ASKED, the prompt shown but TalkBack's focus
 * never came; LG P04 fine): why [PromptFocus.moveTo] did or did not move the focus, as codes only.
 * - OK / AFTER_CLEAR (refused first, accepted after clearing the node's stale focus, as F44's result card) /
 *   ALREADY_FOCUSED (the system's focus is on the node already): moved.
 * - NOT_SHOWING (the window is gone, replaced or revalidating), NO_OWN_WINDOW (no listed window is our prompt),
 *   MANY_OWN_WINDOWS (two or more match), NO_TARGET_NODE (the tagged node is not in it), NOT_VISIBLE, REFUSED, ERROR.
 */
enum class PromptFocusOutcome {
    OK, AFTER_CLEAR, ALREADY_FOCUSED, NOT_SHOWING, NO_OWN_WINDOW, MANY_OWN_WINDOWS, NO_TARGET_NODE, NOT_VISIBLE, REFUSED, ERROR;

    val moved: Boolean get() = this == OK || this == AFTER_CLEAR || this == ALREADY_FOCUSED
}

/**
 * F46: what the window list looked like when our prompt was not found (NO_OWN_WINDOW, MANY_OWN_WINDOWS), to tell
 * "not listed yet" from "listed, but its title does not match" (a vendor build giving another window title):
 * [ownPackageOverlays] accessibility overlay windows whose root is our package (the result card is one too),
 * [titleNull] of them with no title, [titleOther] with a title that is not our prompt's. Counts only, never a title.
 */
data class OwnWindowCounts(val ownPackageOverlays: Int, val titleNull: Int, val titleOther: Int)

/** F46: pure parts of the prompt focus diagnosis, JVM tested. */
object PromptFocusRules {
    fun windowOutcome(matches: Int): PromptFocusOutcome? = when {
        matches <= 0 -> PromptFocusOutcome.NO_OWN_WINDOW
        matches == 1 -> null
        else -> PromptFocusOutcome.MANY_OWN_WINDOWS
    }

    /**
     * The node's own answer: [visible], then the focus action ([focus]); refused, its focus is cleared ([clear]) and
     * asked once more. [alreadyFocused]: the system's accessibility focus is this node now (not the node's own flag,
     * which on the Galaxy stays set after the focus left, F44); then nothing is cleared or asked again.
     */
    fun nodeOutcome(visible: Boolean, alreadyFocused: () -> Boolean, focus: () -> Boolean, clear: () -> Unit): PromptFocusOutcome {
        if (!visible) return PromptFocusOutcome.NOT_VISIBLE
        if (focus()) return PromptFocusOutcome.OK
        if (alreadyFocused()) return PromptFocusOutcome.ALREADY_FOCUSED
        clear()
        return if (focus()) PromptFocusOutcome.AFTER_CLEAR else PromptFocusOutcome.REFUSED
    }

    /**
     * One debug line for one focus move ([attempts] tries of [target]): the last outcome, how many times each outcome
     * came (in enum order), and the window counts of the last try that did not find our prompt. Codes and numbers only.
     */
    fun line(target: TakeoverFocusTarget, attempts: Int, outcomes: Map<PromptFocusOutcome, Int>, last: PromptFocusOutcome?, windows: OwnWindowCounts?): String {
        val counts = PromptFocusOutcome.entries.mapNotNull { o -> outcomes[o]?.takeIf { it > 0 }?.let { "${o.name}:$it" } }.joinToString(",")
        val w = windows?.let { " own=${it.ownPackageOverlays} titleNull=${it.titleNull} titleOther=${it.titleOther}" } ?: ""
        return "PROMPT_FOCUS target=${target.name} result=${last?.name ?: "NONE"} attempts=$attempts tries=[$counts]$w"
    }
}

/** F46: one focus move's tries, summed up for [PromptFocusRules.line]; reset by [PromptFocus.finished]. */
class PromptFocusTally {
    private val outcomes = mutableMapOf<PromptFocusOutcome, Int>()
    private var last: PromptFocusOutcome? = null
    private var windows: OwnWindowCounts? = null

    fun add(outcome: PromptFocusOutcome, counts: OwnWindowCounts?) {
        outcomes[outcome] = (outcomes[outcome] ?: 0) + 1
        last = outcome
        if (counts != null) windows = counts
    }

    fun line(target: TakeoverFocusTarget, attempts: Int): String = PromptFocusRules.line(target, attempts, outcomes, last, windows)

    fun clear() {
        outcomes.clear()
        last = null
        windows = null
    }
}
