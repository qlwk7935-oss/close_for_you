package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kr.closeforyou.R
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
import kr.closeforyou.ui.voice.overlay.PromptSpeech
import kr.closeforyou.ui.voice.settings.HandlingPreset

/** Hedged summary: a display classification can be wrong, so the sentence never asserts it. */
enum class PromptSummary { LIKELY_AD, LIKELY_VIDEO_AD, LIKELY_NOTICE, LIKELY_COUPON, NEEDS_REVIEW, UNKNOWN }

/**
 * MOVE_TO_BUTTON appears only while [MoveToButtonFeature.enabled]; it never presses anything.
 * REMEMBER_ALWAYS / STOP_ASKING appear only while [RememberChoiceFeature.enabled].
 */
enum class PromptOptionKind { PRESS_CANDIDATE, REMEMBER_ALWAYS, MOVE_TO_BUTTON, READ_CONTENT, KEEP_OPEN, STOP_ASKING }

/**
 * [앞으로 항상 닫기] / [다시 묻지 않기]. Off: D's rule storage is not connected (Mock only).
 * B's policy does not press for REMEMBER_EXACT_POPUP, so `PopupChoiceFlow` sends this popup's
 * one-time press first and saves the rule only after it succeeded (team decision 10/20 (가)).
 * Remembering is never authorization: every later auto close re-checks B's policy, A's gate and
 * the exact popup (AGENTS 6-4), and only in apps whose auto close setting is on.
 */
object RememberChoiceFeature {
    /**
     * AGENTS.md 6-0 demo mode: on until the final presentation (C 재호 decision 10/30). Until D's store
     * is connected the person hears that nothing was saved. The one constant to flip back for release.
     */
    const val DEMO_MODE_ON = true

    /** [앞으로 항상 닫기]; where it shows is [RememberChoiceRules]. */
    val enabled: Boolean get() = DEMO_MODE_ON

    /** [다시 묻지 않기] stays off: not part of the 10/30 decision. */
    val stopAskingEnabled: Boolean get() = false
}

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
    /** Set only while [VideoAdAnnouncementFeature] is on and B says video ad. */
    val videoAd: VideoAdNotice? = null,
    /**
     * The popup's extracted text in reading order, for the TalkBack takeover's verbatim reading.
     * Spoken only, never shown, logged or stored; null when there is none.
     */
    val popupText: String? = null,
) {
    override fun toString(): String =
        "ChoicePrompt(summary=$summary, options=${options.size}, failure=$pressFailure, videoAd=$videoAd, redacted)"

    /** B's countdown while no skip button can be pressed yet: "wait" replaces "cannot close". */
    val countdownSeconds: Int? get() = videoAd?.countdownSeconds
}

/**
 * C: decides what the "닫을까요?" prompt offers and turns a tap into the contract's [UserChoice].
 *
 * Offering a button is not an authorization. B's ActionPolicy and A's gate re-check everything.
 * REMEMBER_EXACT_POPUP is behind [RememberChoiceFeature] (demo mode on) and [RememberChoiceRules];
 * STOP_ASKING stays off.
 * [버튼으로 이동] is prepared behind [MoveToButtonFeature] (off: no contract value yet).
 */
object ChoicePromptPlanner {
    private val pressableRoles = setOf(ButtonRole.CLOSE, ButtonRole.SKIP)
    private val pressableEvidence = setOf(EvidenceLevel.E1_DISMISS, EvidenceLevel.E2_LABELED_NODE)

    /**
     * [labelPolicy]: only labels the shared contract accepts for the candidate's role get a press
     * button; others fall back to "이 팝업은 대신 닫을 수 없어요". Default is [SharedLabelPolicy].
     * [videoAd]: B's F5 facts (A passes `VideoAdInfo.from(DecisionVideoAd.inspect(analysis))`).
     * Used only while [videoAdEnabled] and only for an ADVERTISEMENT. It changes wording only:
     * the press buttons are the same as without it.
     */
    fun plan(
        analysis: PopupAnalysis,
        classification: PopupClassification,
        labelPolicy: PressableLabelPolicy = SharedLabelPolicy,
        moveToButtonEnabled: Boolean = MoveToButtonFeature.enabled,
        rememberChoicesEnabled: Boolean = RememberChoiceFeature.enabled,
        videoAd: VideoAdInfo? = null,
        videoAdEnabled: Boolean = VideoAdAnnouncementFeature.enabled,
        /** The person's preset; null = unknown (then only the other rules hide [앞으로 항상 닫기]). */
        preset: HandlingPreset? = null,
        /** B's scam check; by default B's own rule on this analysis. */
        suspicious: Boolean = RememberChoiceRules.suspiciousByB(analysis),
        stopAskingEnabled: Boolean = RememberChoiceFeature.stopAskingEnabled,
    ): ChoicePrompt {
        val popupText = PromptSpeech.contentOrNull(analysis)
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
        // "Always" only for the exact buttons offered for a one-time press, and it names the button.
        val remember = if (rememberChoicesEnabled && RememberChoiceRules.offers(classification, popupText, preset, suspicious)) {
            pressable.zip(options).filter { (candidate, _) -> RememberChoiceRules.keyable(candidate) }
                .map { (_, option) -> option.copy(kind = PromptOptionKind.REMEMBER_ALWAYS) }
        } else {
            emptyList()
        }
        val stopAsking = if (rememberChoicesEnabled && stopAskingEnabled && classification != PopupClassification.USER_DECISION) {
            listOf(PromptOption(PromptOptionKind.STOP_ASKING))
        } else {
            emptyList()
        }
        val all = options + remember + movable.map(::moveOption) +
            PromptOption(PromptOptionKind.READ_CONTENT) + PromptOption(PromptOptionKind.KEEP_OPEN) + stopAsking
        val notice = if (videoAdEnabled && videoAd != null && classification == PopupClassification.ADVERTISEMENT) {
            videoAdNotice(videoAd, options)
        } else {
            null
        }
        return ChoicePrompt(
            token = analysis.token,
            summary = if (notice != null) PromptSummary.LIKELY_VIDEO_AD else summaryOf(classification),
            cannotPressForUser = pressable.isEmpty() && classification != PopupClassification.USER_DECISION,
            options = all.toImmutableList(),
            videoAd = notice,
            popupText = popupText,
        )
    }

    /**
     * A skip button offered now (C's rule and in B's skip list) ends the wait, so the countdown is
     * dropped. Otherwise only B's read value is kept, as is; a non-positive value is not spoken.
     */
    private fun videoAdNotice(info: VideoAdInfo, offered: List<PromptOption>): VideoAdNotice {
        val skipNow = offered.any { it.candidateRole == ButtonRole.SKIP && it.candidateId in info.skipCandidateIds }
        return VideoAdNotice(countdownSeconds = info.countdownSeconds?.takeIf { !skipNow && it > 0 })
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
                it.kind == PromptOptionKind.REMEMBER_ALWAYS -> null // also a press: no retry
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
            // The tap names the button (confirmed meaning) and says "always" (explicit repeat permission).
            PromptOptionKind.REMEMBER_ALWAYS -> UserChoice(
                token = prompt.token,
                kind = ChoiceKind.REMEMBER_EXACT_POPUP,
                candidateId = requireNotNull(option.candidateId),
                buttonMeaningConfirmed = true,
                repeatAutomationExplicitlyAllowed = true,
            )
            PromptOptionKind.STOP_ASKING -> UserChoice(prompt.token, ChoiceKind.STOP_ASKING)
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

    /** B's role must agree with the label's role (`ButtonLabelRules.roleOf(label) == candidate.role`). */
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

    internal fun summaryOf(classification: PopupClassification) = when (classification) {
        PopupClassification.ADVERTISEMENT -> PromptSummary.LIKELY_AD
        PopupClassification.NOTICE -> PromptSummary.LIKELY_NOTICE
        PopupClassification.USER_DECISION -> PromptSummary.NEEDS_REVIEW
        PopupClassification.UNKNOWN -> PromptSummary.UNKNOWN
        PopupClassification.COUPON -> PromptSummary.LIKELY_COUPON
    }
}

/** C: what to say right after [앞으로 항상 닫기]. Saving is D's (Mock today); this is wording only. */
object RememberChoiceSpeech {
    /** [autoCloseEnabledForApp]: C's per-app setting (default off). Off means "we will ask again". */
    fun afterRememberRes(autoCloseEnabledForApp: Boolean): Int =
        if (autoCloseEnabledForApp) R.string.ui_remember_auto_close_on else R.string.ui_remember_auto_close_off
}
