package kr.closeforyou.ui.voice.choice

import kr.closeforyou.R
import kr.closeforyou.core.model.LabelRejectReason
import kr.closeforyou.core.model.SafetyReason

/**
 * Off until the team decides to say reasons and the prompt is wired (the contract is on main).
 * Off = prompt keeps "이 팝업은 대신 닫을 수 없어요".
 */
object CannotPressReasonFeature {
    val enabled: Boolean get() = false
}

/** Why C does not offer to press for the person. Two separate sources; never an execution result. */
sealed interface CannotPressExplanation {
    /** B's ActionPolicy reason for the whole popup. */
    data class Policy(val reason: SafetyReason) : CannotPressExplanation
    /** Why a button's label was rejected (no label text is kept). */
    data class Label(val reason: LabelRejectReason) : CannotPressExplanation
}

/**
 * C: picks one sentence for "왜 대신 누르지 않나요".
 *
 * Order (contract v3 4-1, A 10/19): a B policy reason first, then a label reject reason.
 * A's gate results (`ui_result_*`, [kr.closeforyou.ui.voice.PressResultSpeech]) are a third,
 * separate thing said only after a press, and are not chosen here.
 * Label accepted ≠ pressable: nothing here says a button "can be pressed".
 */
object CannotPressReasonSpeech {
    /** B reasons that explain a refusal. Others (first seen, needs confirmation, …) fall through. */
    private val policyOrder = listOf(
        SafetyReason.IMPORTANT_DECISION,
        SafetyReason.PRIVACY_BLOCKED,
        SafetyReason.UNVERIFIED_TARGET,
        SafetyReason.INCOMPLETE_EVIDENCE,
    )

    /** With several rejected buttons, the reason most worth knowing for safety is said first. */
    private val labelOrder = listOf(
        LabelRejectReason.DECISION_WORD,
        LabelRejectReason.CONDITIONAL_WORD,
        LabelRejectReason.NAVIGATION_WORD,
        LabelRejectReason.AMBIGUOUS_WORD,
        LabelRejectReason.LABEL_CONFLICT,
        LabelRejectReason.NOT_IN_LIST,
        LabelRejectReason.SYMBOL_ONLY,
        LabelRejectReason.NO_LABEL,
    )

    /** Null when the feature is off or nothing explains the refusal (keep the generic sentence). */
    fun explain(
        policyReasons: Set<SafetyReason>,
        labelReasons: Collection<LabelRejectReason>,
        enabled: Boolean = CannotPressReasonFeature.enabled,
    ): CannotPressExplanation? {
        if (!enabled) return null
        policyOrder.firstOrNull { it in policyReasons }?.let { return CannotPressExplanation.Policy(it) }
        return labelOrder.firstOrNull { it in labelReasons }?.let { CannotPressExplanation.Label(it) }
    }

    fun sentenceRes(explanation: CannotPressExplanation): Int = when (explanation) {
        is CannotPressExplanation.Policy -> policyRes(explanation.reason)
        is CannotPressExplanation.Label -> labelRes(explanation.reason)
    }

    fun labelRes(reason: LabelRejectReason): Int = when (reason) {
        LabelRejectReason.NO_LABEL -> R.string.ui_label_reject_no_label
        LabelRejectReason.SYMBOL_ONLY -> R.string.ui_label_reject_symbol_only
        LabelRejectReason.DECISION_WORD -> R.string.ui_label_reject_decision_word
        LabelRejectReason.CONDITIONAL_WORD -> R.string.ui_label_reject_conditional_word
        LabelRejectReason.NAVIGATION_WORD -> R.string.ui_label_reject_navigation_word
        LabelRejectReason.AMBIGUOUS_WORD -> R.string.ui_label_reject_ambiguous_word
        LabelRejectReason.NOT_IN_LIST -> R.string.ui_label_reject_not_in_list
        LabelRejectReason.LABEL_CONFLICT -> R.string.ui_label_reject_label_conflict
    }

    /** Only reasons in [policyOrder] reach here through [explain]. */
    fun policyRes(reason: SafetyReason): Int = when (reason) {
        SafetyReason.IMPORTANT_DECISION -> R.string.ui_policy_block_important_decision
        SafetyReason.PRIVACY_BLOCKED -> R.string.ui_policy_block_privacy
        SafetyReason.UNVERIFIED_TARGET -> R.string.ui_policy_block_unverified_target
        SafetyReason.INCOMPLETE_EVIDENCE -> R.string.ui_policy_block_incomplete_evidence
        else -> R.string.ui_choice_cannot_press
    }
}
