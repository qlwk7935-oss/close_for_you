package kr.closeforyou.ui.voice

import kr.closeforyou.R
import kr.closeforyou.core.model.ActionDecision
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.NextStep
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.ui.voice.choice.CannotPressReasonFeature
import kr.closeforyou.ui.voice.choice.CannotPressReasonSpeech
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.choice.VideoAdAnnouncementFeature
import kr.closeforyou.ui.voice.choice.VideoAdInfo

/** One sentence C may say: a string resource and its arguments (a resource id argument is resolved first). */
data class SpokenLine(val resId: Int, val args: List<Arg> = emptyList()) {
    sealed interface Arg {
        data class Res(val resId: Int) : Arg
        data class Number(val value: Int) : Arg
    }
}

/** What C does for one contract v2 decision or result. Carries no screen text except [Batch.title]. */
sealed interface AutoSpeech {
    /** Nothing to say (still waiting, already said, or nothing was pressed). */
    data object Quiet : AutoSpeech

    /** Counted by [AnnouncementBatcher] (2 s) and said with [AutoDecisionSpeech.batchSentence]. */
    data class Batch(val outcome: HandledOutcome, val kind: ClosedKind?, val title: String? = null) : AutoSpeech {
        override fun toString(): String = "Batch(outcome=$outcome, kind=$kind, redacted)"
    }

    /**
     * Said at once, with no prompt: a warning or an explanation never comes with a press or
     * move-to-button option (the person handles the popup themselves).
     */
    data class Say(val lines: List<SpokenLine>) : AutoSpeech

    /** ANNOUNCE: the existing summary + content reading path; nothing is pressed. */
    data class Announce(val summary: PromptSummary) : AutoSpeech

    /** ASK: the existing "닫을까요?" prompt (ChoicePromptPlanner / PopupChoiceFlow). */
    data object Ask : AutoSpeech
}

/** Resolves a string resource with arguments (`context::getString` on Android). */
fun interface UiFormat {
    fun get(resId: Int, vararg args: Any): String
}

/**
 * C/F10, contract v2 (#38): what to say about a decision B made without asking on this screen
 * (DecisionSource.PRESET / REMEMBERED_RULE) and A's result for it. Pure; not connected yet,
 * because nothing delivers these decisions and results to C (see docs/c-notes/contract38-c-connect.md).
 * The person's own taps keep [PressResultSpeech].
 *
 * - "닫았어요" only for [ClickOutcome.CLOSED_CONFIRMED]. A delivered click alone is "확인하지 못했어요".
 * - WAIT is said once per popup ([waitAlreadySaid], see [WaitSaidOnce]), and only for
 *   [SafetyReason.WAITING_FOR_TARGET]; waiting for A's result or for the person is silent.
 * - SUSPICIOUS_CONTENT: a warning with nothing to press. The contract already allows it only with
 *   DENY (#38 review); the check here stays as a second guard.
 */
object AutoDecisionSpeech {
    /** How much of a popup title is said ("'제목' 광고를 닫았어요"). */
    const val TITLE_MAX_CHARS = 20

    fun speechFor(
        decision: ActionDecision,
        classification: PopupClassification,
        result: ActionResult? = null,
        title: String? = null,
        waitAlreadySaid: Boolean = false,
        videoAd: VideoAdInfo? = null,
        videoAdEnabled: Boolean = VideoAdAnnouncementFeature.enabled,
        reasonEnabled: Boolean = CannotPressReasonFeature.enabled,
    ): AutoSpeech {
        if (decision.next != NextStep.EXECUTE && SafetyReason.SUSPICIOUS_CONTENT in decision.reasons) {
            return AutoSpeech.Say(listOf(SpokenLine(R.string.ui_auto_suspicious)))
        }
        val kind = kindOf(classification)
        return when (decision.next) {
            NextStep.EXECUTE -> afterExecute(result, kind, title)
            NextStep.WAIT -> waitSpeech(decision, kind, waitAlreadySaid, videoAd.takeIf { videoAdEnabled })
            NextStep.ANNOUNCE -> AutoSpeech.Announce(ChoicePromptPlanner.summaryOf(classification))
            NextStep.ASK -> AutoSpeech.Ask
            NextStep.DENY -> AutoSpeech.Say(
                listOfNotNull(
                    SpokenLine(R.string.ui_choice_summary_review),
                    CannotPressReasonSpeech.explain(decision.reasons, emptyList(), reasonEnabled)
                        ?.let { SpokenLine(CannotPressReasonSpeech.sentenceRes(it)) },
                ),
            )
        }
    }

    /** No result yet means A has not answered: nothing to say. Never "closed" without A's check. */
    private fun afterExecute(result: ActionResult?, kind: ClosedKind, title: String?): AutoSpeech {
        result ?: return AutoSpeech.Quiet
        return when (result.outcome) {
            // Gone after a failed or unknown press: not claimed as ours ([PressSuccess]).
            ClickOutcome.CLOSED_CONFIRMED ->
                if (PressSuccess.isConfirmed(result)) AutoSpeech.Batch(HandledOutcome.CLOSED, kind, shortTitle(title))
                else AutoSpeech.Batch(HandledOutcome.NOT_CONFIRMED, kind)
            ClickOutcome.LEFT_APP -> AutoSpeech.Say(
                listOf(SpokenLine(R.string.ui_auto_left_app, listOf(SpokenLine.Arg.Res(objectRes(kind))))),
            )
            ClickOutcome.STILL_PRESENT, ClickOutcome.UNKNOWN -> AutoSpeech.Batch(HandledOutcome.NOT_CONFIRMED, kind)
            null -> when (result.status) {
                // A pressed (or tried) but did not observe the screen afterwards.
                ActionStatus.MECHANICAL_SUCCESS, ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN ->
                    AutoSpeech.Batch(HandledOutcome.NOT_CONFIRMED, kind)
                // Nothing was pressed; A's next detection asks B again.
                ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE -> AutoSpeech.Quiet
            }
        }
    }

    private fun waitSpeech(
        decision: ActionDecision,
        kind: ClosedKind,
        alreadySaid: Boolean,
        videoAd: VideoAdInfo?,
    ): AutoSpeech {
        if (alreadySaid || SafetyReason.WAITING_FOR_TARGET !in decision.reasons) return AutoSpeech.Quiet
        if (kind != ClosedKind.AD) {
            return AutoSpeech.Say(listOf(SpokenLine(R.string.ui_auto_wait_other, listOf(SpokenLine.Arg.Res(objectRes(kind))))))
        }
        videoAd ?: return AutoSpeech.Say(listOf(SpokenLine(R.string.ui_auto_wait_ad)))
        // The existing video ad countdown sentence, only with B's read value (never guessed).
        val seconds = videoAd.countdownSeconds?.takeIf { it > 0 }
        return AutoSpeech.Say(
            listOf(
                SpokenLine(R.string.ui_choice_summary_video_ad),
                seconds?.let { SpokenLine(R.string.ui_choice_video_ad_wait, listOf(SpokenLine.Arg.Number(it))) }
                    ?: SpokenLine(R.string.ui_auto_wait_ad),
            ),
        )
    }

    /**
     * The batch sentence with kinds: "광고 2개, 쿠폰 1개를 닫았어요", or "'제목' 광고를 닫았어요" for one.
     * Falls back to [BatchSentence]'s "팝업 N개" when some closes in the window have no kind
     * (the person's own taps).
     */
    fun batchSentence(summary: BatchSummary, detailed: Boolean, format: UiFormat): String {
        val closedLine = if (summary.closed > 0 && summary.closedKinds.values.sum() == summary.closed) {
            closedLine(summary, format)
        } else {
            null
        }
        return BatchSentence.build(summary, detailed, closedLine) { res, count -> format.get(res, count) }
    }

    private fun closedLine(summary: BatchSummary, format: UiFormat): String {
        if (summary.closed == 1) {
            val kind = summary.closedKinds.keys.single()
            val obj = format.get(objectRes(kind))
            return summary.onlyTitle?.let { format.get(R.string.ui_auto_closed_one_titled, it, obj) }
                ?: format.get(R.string.ui_auto_closed_one, obj)
        }
        val parts = ClosedKind.entries.mapNotNull { kind ->
            summary.closedKinds[kind]?.let { format.get(R.string.ui_auto_kind_count, format.get(nameRes(kind)), it) }
        }
        return format.get(R.string.ui_auto_closed_counts, parts.joinToString(", "))
    }

    /** Renders [AutoSpeech.Say] lines. */
    fun render(lines: List<SpokenLine>, format: UiFormat): String = lines.joinToString(" ") { line ->
        val args = line.args.map {
            when (it) {
                is SpokenLine.Arg.Res -> format.get(it.resId)
                is SpokenLine.Arg.Number -> it.value
            }
        }
        format.get(line.resId, *args.toTypedArray())
    }

    /** First [TITLE_MAX_CHARS] characters, spaces collapsed and quotes removed; null when blank. */
    fun shortTitle(raw: String?): String? {
        val clean = raw?.filterNot { it == '\'' || it == '"' }?.trim()?.replace(Regex("\\s+"), " ")
        if (clean.isNullOrEmpty()) return null
        if (clean.codePointCount(0, clean.length) <= TITLE_MAX_CHARS) return clean
        return clean.substring(0, clean.offsetByCodePoints(0, TITLE_MAX_CHARS)).trimEnd()
    }

    fun kindOf(classification: PopupClassification): ClosedKind = when (classification) {
        PopupClassification.ADVERTISEMENT -> ClosedKind.AD
        PopupClassification.COUPON -> ClosedKind.COUPON
        PopupClassification.NOTICE -> ClosedKind.NOTICE
        PopupClassification.USER_DECISION, PopupClassification.UNKNOWN -> ClosedKind.OTHER
    }

    fun nameRes(kind: ClosedKind): Int = when (kind) {
        ClosedKind.AD -> R.string.ui_auto_kind_ad
        ClosedKind.COUPON -> R.string.ui_auto_kind_coupon
        ClosedKind.NOTICE -> R.string.ui_auto_kind_notice
        ClosedKind.OTHER -> R.string.ui_auto_kind_other
    }

    fun objectRes(kind: ClosedKind): Int = when (kind) {
        ClosedKind.AD -> R.string.ui_auto_obj_ad
        ClosedKind.COUPON -> R.string.ui_auto_obj_coupon
        ClosedKind.NOTICE -> R.string.ui_auto_obj_notice
        ClosedKind.OTHER -> R.string.ui_auto_obj_other
    }
}

/**
 * Remembers which popups' WAIT was already said, so "광고가 끝나면 닫을게요" is said once per popup.
 * [key] identifies the same popup (the caller's choice, e.g. A's popup window or D's fingerprint);
 * opaque, memory only, never logged. Bounded; [clear] when the service stops.
 */
class WaitSaidOnce(private val capacity: Int = 32) {
    private val keys = LinkedHashSet<String>()

    /** True the first time for [key] (say it), false afterwards (stay quiet). */
    @Synchronized
    fun firstTime(key: String): Boolean {
        if (!keys.add(key)) return false
        if (keys.size > capacity) keys.remove(keys.first())
        return true
    }

    /** The popup is gone (closed or replaced): a later WAIT for it may be said again. */
    @Synchronized
    fun forget(key: String) {
        keys.remove(key)
    }

    @Synchronized
    fun clear() = keys.clear()
}
