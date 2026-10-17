package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.UserChoice

/** Hedged summary: a display classification can be wrong, so the sentence never asserts it. */
enum class PromptSummary { LIKELY_AD, LIKELY_NOTICE, NEEDS_REVIEW, UNKNOWN }

enum class PromptOptionKind { PRESS_CANDIDATE, READ_CONTENT, KEEP_OPEN }

data class PromptOption(
    val kind: PromptOptionKind,
    val candidateId: String? = null,
    /** Screen text: shown and spoken, never logged. */
    val buttonLabel: String? = null,
) {
    override fun toString(): String = "PromptOption(kind=$kind, redacted)"
}

data class ChoicePrompt(
    val token: SnapshotToken,
    val summary: PromptSummary,
    /** True when no verified close/skip button can be offered for this popup. */
    val cannotPressForUser: Boolean,
    val options: ImmutableList<PromptOption>,
) {
    override fun toString(): String = "ChoicePrompt(summary=$summary, options=${options.size}, redacted)"
}

/**
 * C: decides what the "닫을까요?" prompt offers and turns a tap into the contract's [UserChoice].
 *
 * Offering a button is not an authorization. B's ActionPolicy and A's gate re-check everything.
 * Not offered yet: REMEMBER_EXACT_POPUP / STOP_ASKING (no contract for repeat automation) and
 * "move to button" (no ChoiceKind exists).
 */
object ChoicePromptPlanner {
    private val pressableRoles = setOf(ButtonRole.CLOSE, ButtonRole.SKIP)
    private val pressableEvidence = setOf(EvidenceLevel.E1_DISMISS, EvidenceLevel.E2_LABELED_NODE)

    fun plan(analysis: PopupAnalysis, classification: PopupClassification): ChoicePrompt {
        val pressable = if (classification == PopupClassification.USER_DECISION) {
            emptyList()
        } else {
            analysis.closeCandidates.filter(::isPressable).distinctBy { it.id }
        }
        val options = pressable.map {
            PromptOption(PromptOptionKind.PRESS_CANDIDATE, candidateId = it.id, buttonLabel = it.label?.trim())
        } + PromptOption(PromptOptionKind.READ_CONTENT) + PromptOption(PromptOptionKind.KEEP_OPEN)
        return ChoicePrompt(
            token = analysis.token,
            summary = summaryOf(classification),
            cannotPressForUser = pressable.isEmpty() && classification != PopupClassification.USER_DECISION,
            options = options.toImmutableList(),
        )
    }

    /** The tap itself confirms the meaning, because the button names the exact target label. */
    fun toUserChoice(prompt: ChoicePrompt, option: PromptOption): UserChoice {
        require(option in prompt.options) { "Option does not belong to this prompt" }
        return when (option.kind) {
            PromptOptionKind.PRESS_CANDIDATE -> UserChoice(
                token = prompt.token,
                kind = ChoiceKind.CLOSE_ONCE,
                candidateId = requireNotNull(option.candidateId),
                buttonMeaningConfirmed = true,
                repeatAutomationExplicitlyAllowed = false,
            )
            PromptOptionKind.READ_CONTENT -> UserChoice(prompt.token, ChoiceKind.READ_CONTENT)
            PromptOptionKind.KEEP_OPEN -> UserChoice(prompt.token, ChoiceKind.KEEP_OPEN)
        }
    }

    private fun isPressable(candidate: CloseCandidate): Boolean =
        candidate.role in pressableRoles &&
            candidate.evidence in pressableEvidence &&
            !candidate.label.isNullOrBlank()

    private fun summaryOf(classification: PopupClassification) = when (classification) {
        PopupClassification.ADVERTISEMENT -> PromptSummary.LIKELY_AD
        PopupClassification.NOTICE -> PromptSummary.LIKELY_NOTICE
        PopupClassification.USER_DECISION -> PromptSummary.NEEDS_REVIEW
        PopupClassification.UNKNOWN -> PromptSummary.UNKNOWN
    }
}
