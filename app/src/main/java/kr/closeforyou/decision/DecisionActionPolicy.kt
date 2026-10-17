package kr.closeforyou.decision

import kr.closeforyou.core.contract.ActionPolicy
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.ActionAuthorization
import kr.closeforyou.core.model.ActionDecision
import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.core.model.UserChoice
import kotlinx.collections.immutable.toPersistentSet

/**
 * B: deny-first safety decision (design 3-3). DENY reasons win over NEEDS_CONFIRMATION reasons,
 * and ALLOW_ONCE needs none of either. The result is only a request: A's ActionExecutor rereads the
 * screen and performs at most one action. No clicks, gestures, focus changes or logging of screen text.
 *
 * An action is requested in exactly two ways: the person picks a verified button on this screen
 * (design 3-5 "닫아"), or an exact remembered rule explicitly allows repeating it. A display
 * classification such as ADVERTISEMENT never does (design 1-2).
 */
class DecisionActionPolicy : ActionPolicy {
    override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext): ActionDecision {
        val deny = mutableSetOf<SafetyReason>()
        val confirm = mutableSetOf<SafetyReason>()

        // 1. Deny first: an old screen or choice, the person acting, or an important decision.
        val choice = context.userChoice
        if (context.currentToken != analysis.token || (choice != null && choice.token != analysis.token)) {
            deny += SafetyReason.STALE_SCREEN
        }
        if (context.userInteracting) deny += SafetyReason.USER_INTERACTION
        if (analysis.classification == PopupClassification.USER_DECISION || analysis.hasUserDecisionSignal()) {
            deny += SafetyReason.IMPORTANT_DECISION
        }

        // 2. Who asked for an action, and on which verified button.
        val rule = context.matchingRule
        val target = when {
            choice != null -> userRequestedTarget(analysis, choice, deny)
            rule != null -> rememberedRuleTarget(analysis, rule, context.announcementAvailable, deny, confirm)
            else -> {
                // First seen: read and ask, even when it looks like an advertisement.
                confirm += SafetyReason.FIRST_SEEN
                val verified = analysis.closeCandidates.filter { isVerifiedTarget(it, allowBoundIcon = false) }
                if (verified.isEmpty()) deny += SafetyReason.UNVERIFIED_TARGET
                verified.singleOrNull()
            }
        }
        // Defensive: ALLOW_ONCE must never be reached without a target.
        if (target == null && deny.isEmpty() && confirm.isEmpty()) deny += SafetyReason.UNVERIFIED_TARGET

        val authorization = when {
            deny.isNotEmpty() -> ActionAuthorization.DENY
            confirm.isNotEmpty() -> ActionAuthorization.NEEDS_CONFIRMATION
            else -> ActionAuthorization.ALLOW_ONCE
        }
        return ActionDecision(
            token = analysis.token,
            authorization = authorization,
            reasons = (deny + confirm).toPersistentSet(),
            candidateId = if (authorization == ActionAuthorization.DENY) null else target?.id,
        )
    }

    /** The person's current choice. Only close or skip choices ask for a press. */
    private fun userRequestedTarget(
        analysis: PopupAnalysis,
        choice: UserChoice,
        deny: MutableSet<SafetyReason>,
    ): CloseCandidate? {
        val allowedRoles = when (choice.kind) {
            ChoiceKind.CLOSE_ONCE -> setOf(ButtonRole.CLOSE)
            ChoiceKind.SKIP_ONCE -> setOf(ButtonRole.SKIP)
            ChoiceKind.REMEMBER_EXACT_POPUP -> setOf(ButtonRole.CLOSE, ButtonRole.SKIP)
            ChoiceKind.READ_CONTENT, ChoiceKind.KEEP_OPEN, ChoiceKind.STOP_ASKING, ChoiceKind.REVOKE_RULE -> {
                deny += SafetyReason.EXPLICIT_PERMISSION_REQUIRED
                return null
            }
        }
        val candidate = analysis.closeCandidates.singleOrNull { it.id == choice.candidateId }
        if (candidate == null || candidate.role !in allowedRoles ||
            !isVerifiedTarget(candidate, allowBoundIcon = choice.buttonMeaningConfirmed)
        ) {
            deny += SafetyReason.UNVERIFIED_TARGET
            return null
        }
        return candidate
    }

    /** Automatic repeat from D's exact rule. Every automation condition must hold (design 3-3). */
    private fun rememberedRuleTarget(
        analysis: PopupAnalysis,
        rule: PopupRule,
        announcementAvailable: Boolean,
        deny: MutableSet<SafetyReason>,
        confirm: MutableSet<SafetyReason>,
    ): CloseCandidate? {
        // "Stop asking" is neither an automatic close nor a reason to ask again (design F12).
        if (rule.enabled && rule.choice == ChoiceKind.STOP_ASKING) {
            deny += SafetyReason.EXPLICIT_PERMISSION_REQUIRED
            return null
        }
        if (!rule.enabled || rule.choice != ChoiceKind.REMEMBER_EXACT_POPUP ||
            !rule.repeatAutomationExplicitlyAllowed || !rule.buttonMeaningConfirmed
        ) confirm += SafetyReason.RULE_MISMATCH
        if (rule.failureCount > 0) confirm += SafetyReason.ALREADY_ATTEMPTED
        // A marks every analysis incomplete today, so automatic repeats stay off until that changes.
        if (AnalysisLimit.INCOMPLETE_EVIDENCE in analysis.limitations) confirm += SafetyReason.INCOMPLETE_EVIDENCE
        // Closing silently would leave the person unaware of what happened (design F10, B04).
        if (!announcementAvailable) confirm += SafetyReason.FEEDBACK_UNAVAILABLE

        // The rule binds one exact target; anything but a single verified candidate is ambiguous.
        val candidate = analysis.closeCandidates.singleOrNull()
        if (candidate == null || !isVerifiedTarget(candidate, allowBoundIcon = rule.buttonMeaningConfirmed)) {
            deny += SafetyReason.UNVERIFIED_TARGET
            return null
        }
        return candidate
    }

    /**
     * Close or skip buttons with tree evidence (E1/E2), or an icon button whose meaning the person
     * confirmed (E3). Pixel-only targets (E4) and labels of grades D/K/P/O are never pressed.
     */
    private fun isVerifiedTarget(candidate: CloseCandidate, allowBoundIcon: Boolean): Boolean {
        val evidenceVerified = when (candidate.evidence) {
            EvidenceLevel.E1_DISMISS -> true
            EvidenceLevel.E2_LABELED_NODE -> !candidate.label.isNullOrBlank()
            EvidenceLevel.E3_USER_BOUND_NODE -> allowBoundIcon
            EvidenceLevel.E4_VISUAL_ONLY -> false
        }
        return evidenceVerified &&
            (candidate.role == ButtonRole.CLOSE || candidate.role == ButtonRole.SKIP) &&
            candidate.label?.let(DecisionKeywords::isRiskyButtonLabel) != true
    }
}
