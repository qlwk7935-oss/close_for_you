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
import kr.closeforyou.ui.voice.PressFailure
import kr.closeforyou.ui.voice.PressResultSpeech

/** Hedged summary: a display classification can be wrong, so the sentence never asserts it. */
enum class PromptSummary { LIKELY_AD, LIKELY_NOTICE, NEEDS_REVIEW, UNKNOWN }

/** MOVE_TO_BUTTON appears only while [MoveToButtonFeature.enabled]; it never presses anything. */
enum class PromptOptionKind { PRESS_CANDIDATE, MOVE_TO_BUTTON, READ_CONTENT, KEEP_OPEN }

data class PromptOption(
    val kind: PromptOptionKind,
    val candidateId: String? = null,
    /** B's role for the candidate; decides CLOSE_ONCE vs SKIP_ONCE. Null for read/keep. */
    val candidateRole: ButtonRole? = null,
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
    /** Set on the follow-up prompt shown after the person's own press did not succeed. */
    val pressFailure: PressFailure? = null,
) {
    override fun toString(): String =
        "ChoicePrompt(summary=$summary, options=${options.size}, failure=$pressFailure, redacted)"
}

/**
 * C: decides what the "닫을까요?" prompt offers and turns a tap into the contract's [UserChoice].
 *
 * Offering a button is not an authorization. B's ActionPolicy and A's gate re-check everything.
 * Not offered yet: REMEMBER_EXACT_POPUP / STOP_ASKING (no contract for repeat automation).
 * [버튼으로 이동] is prepared behind [MoveToButtonFeature] (off: no contract value yet).
 */
object ChoicePromptPlanner {
    private val pressableRoles = setOf(ButtonRole.CLOSE, ButtonRole.SKIP)
    private val pressableEvidence = setOf(EvidenceLevel.E1_DISMISS, EvidenceLevel.E2_LABELED_NODE)

    /**
     * [labelPolicy]: only labels A's gate accepts get a press button; others fall back to
     * "이 팝업은 대신 닫을 수 없어요". Default is C's temporary copy of A's list (REPLACE-WITH-CONTRACT).
     */
    fun plan(
        analysis: PopupAnalysis,
        classification: PopupClassification,
        labelPolicy: PressableLabelPolicy = AGateLabelPolicy,
        moveToButtonEnabled: Boolean = MoveToButtonFeature.enabled,
    ): ChoicePrompt {
        val pressable = if (classification == PopupClassification.USER_DECISION) {
            emptyList()
        } else {
            analysis.closeCandidates.filter { isPressable(it, labelPolicy) }.distinctBy { it.id }
        }
        val options = pressable.map {
            PromptOption(
                PromptOptionKind.PRESS_CANDIDATE,
                candidateId = it.id,
                candidateRole = it.role,
                buttonLabel = it.label?.trim(),
            )
        }
        // Buttons C may not press: offered only as "move focus there" so the person decides.
        val movable = if (moveToButtonEnabled) {
            analysis.closeCandidates.filter { c -> pressable.none { it.id == c.id } && isMovable(c) }.distinctBy { it.id }
        } else {
            emptyList()
        }
        val all = options + movable.map(::moveOption) +
            PromptOption(PromptOptionKind.READ_CONTENT) + PromptOption(PromptOptionKind.KEEP_OPEN)
        return ChoicePrompt(
            token = analysis.token,
            summary = summaryOf(classification),
            cannotPressForUser = pressable.isEmpty() && classification != PopupClassification.USER_DECISION,
            options = all.toImmutableList(),
        )
    }

    /**
     * Follow-up after a press the person chose did not succeed: same token, press buttons removed
     * (no retry, AGENTS 6-6), the remaining choices kept. Null when the screen changed, because the
     * old analysis must not be offered again (AGENTS 6-7).
     */
    fun afterFailedPress(
        prompt: ChoicePrompt,
        failure: PressFailure,
        moveToButtonEnabled: Boolean = MoveToButtonFeature.enabled,
    ): ChoicePrompt? {
        if (!PressResultSpeech.canOfferFollowUp(failure)) return null
        // A pressed button becomes "move there" when that is available, so the person can try it.
        val options = prompt.options.mapNotNull {
            when {
                it.kind != PromptOptionKind.PRESS_CANDIDATE -> it
                moveToButtonEnabled -> it.copy(kind = PromptOptionKind.MOVE_TO_BUTTON)
                else -> null
            }
        }
        return prompt.copy(cannotPressForUser = false, options = options.toImmutableList(), pressFailure = failure)
    }

    /** One entry point for taps and voice: a [UserChoice] for B/A, or a focus move for A. */
    fun select(prompt: ChoicePrompt, option: PromptOption): PromptSelection =
        if (option.kind == PromptOptionKind.MOVE_TO_BUTTON) {
            require(option in prompt.options) { "Option does not belong to this prompt" }
            PromptSelection.MoveToButton(MoveToButtonRequest(prompt.token, requireNotNull(option.candidateId)))
        } else {
            PromptSelection.Choice(toUserChoice(prompt, option))
        }

    /** The tap itself confirms the meaning, because the button names the exact target label. */
    fun toUserChoice(prompt: ChoicePrompt, option: PromptOption): UserChoice {
        require(option in prompt.options) { "Option does not belong to this prompt" }
        return when (option.kind) {
            PromptOptionKind.PRESS_CANDIDATE -> UserChoice(
                token = prompt.token,
                kind = pressChoiceKind(requireNotNull(option.candidateRole)),
                candidateId = requireNotNull(option.candidateId),
                buttonMeaningConfirmed = true,
                repeatAutomationExplicitlyAllowed = false,
            )
            PromptOptionKind.READ_CONTENT -> UserChoice(prompt.token, ChoiceKind.READ_CONTENT)
            PromptOptionKind.KEEP_OPEN -> UserChoice(prompt.token, ChoiceKind.KEEP_OPEN)
            PromptOptionKind.MOVE_TO_BUTTON ->
                throw IllegalArgumentException("MOVE_TO_BUTTON is not a UserChoice; use select()")
        }
    }

    /** Only CLOSE/SKIP are ever offered (see isPressable); anything else is a programming error. */
    private fun pressChoiceKind(role: ButtonRole): ChoiceKind = when (role) {
        ButtonRole.CLOSE -> ChoiceKind.CLOSE_ONCE
        ButtonRole.SKIP -> ChoiceKind.SKIP_ONCE
        else -> throw IllegalArgumentException("Role $role cannot be pressed for the user")
    }

    /** B's role must agree with the label's role (B draft v1 §11: `roleOf(label) == candidate.role`). */
    private fun isPressable(candidate: CloseCandidate, labelPolicy: PressableLabelPolicy): Boolean =
        candidate.role in pressableRoles &&
            candidate.evidence in pressableEvidence &&
            !candidate.label.isNullOrBlank() &&
            labelPolicy.roleOf(candidate.label) == candidate.role

    /** Needs a node to move to and a name to read; no role or evidence check (nothing is pressed). */
    private fun isMovable(candidate: CloseCandidate): Boolean =
        candidate.nodePath != null && !candidate.label.isNullOrBlank()

    private fun moveOption(candidate: CloseCandidate) = PromptOption(
        PromptOptionKind.MOVE_TO_BUTTON,
        candidateId = candidate.id,
        candidateRole = candidate.role,
        buttonLabel = candidate.label?.trim(),
    )

    private fun summaryOf(classification: PopupClassification) = when (classification) {
        PopupClassification.ADVERTISEMENT -> PromptSummary.LIKELY_AD
        PopupClassification.NOTICE -> PromptSummary.LIKELY_NOTICE
        PopupClassification.USER_DECISION -> PromptSummary.NEEDS_REVIEW
        PopupClassification.UNKNOWN -> PromptSummary.UNKNOWN
    }
}
