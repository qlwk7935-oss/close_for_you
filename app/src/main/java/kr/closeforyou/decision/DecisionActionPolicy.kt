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
 * Today an action is requested only when the person picks a verified E1/E2 button on this screen
 * with CLOSE_ONCE / SKIP_ONCE and confirms its meaning (design 3-5 "닫아"), the same conditions A's
 * gate checks (PR #10). An exact remembered rule is still checked, but automatic repeats wait for the
 * contract fields agreed in the PR #8 review. A display classification such as ADVERTISEMENT never
 * requests an action (design 1-2), and INCOMPLETE_EVIDENCE never overrides A's privacy blocking:
 * a blocked screen reaches B only as PopupEvent.Blocked, without an analysis to decide on.
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
            choice != null -> userRequestedTarget(analysis, choice, deny, confirm)
            rule != null -> rememberedRuleTarget(analysis, rule, context.announcementAvailable, deny, confirm)
            else -> {
                // First seen: read and ask, even when it looks like an advertisement.
                confirm += SafetyReason.FIRST_SEEN
                val verified = analysis.closeCandidates.filter(::isVerifiedTarget)
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

    /**
     * The person's current choice. Only CLOSE_ONCE / SKIP_ONCE ask for a press, matching A's gate
     * (PR #10): remembering a popup is D's rule, not a press request on this screen.
     */
    private fun userRequestedTarget(
        analysis: PopupAnalysis,
        choice: UserChoice,
        deny: MutableSet<SafetyReason>,
        confirm: MutableSet<SafetyReason>,
    ): CloseCandidate? {
        val role = when (choice.kind) {
            ChoiceKind.CLOSE_ONCE -> ButtonRole.CLOSE
            ChoiceKind.SKIP_ONCE -> ButtonRole.SKIP
            ChoiceKind.REMEMBER_EXACT_POPUP, ChoiceKind.READ_CONTENT, ChoiceKind.KEEP_OPEN,
            ChoiceKind.STOP_ASKING, ChoiceKind.REVOKE_RULE -> {
                deny += SafetyReason.EXPLICIT_PERMISSION_REQUIRED
                return null
            }
        }
        val candidate = analysis.closeCandidates.singleOrNull { it.id == choice.candidateId }
        if (candidate == null || candidate.role != role || !isVerifiedTarget(candidate)) {
            deny += SafetyReason.UNVERIFIED_TARGET
            return null
        }
        // A tap is a press request only once the person confirmed the named button (A's gate and
        // PR #8 review #4); otherwise ask again about this same button.
        if (!choice.buttonMeaningConfirmed) confirm += SafetyReason.EXPLICIT_PERMISSION_REQUIRED
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
        // Automatic repeats stay off until DecisionContext carries the per-app automation setting
        // (design 3-3 condition 1) and this popup's attempt history (condition 7), as agreed in the
        // PR #8 review. The checks below still report every other unmet condition.
        confirm += SafetyReason.EXPLICIT_PERMISSION_REQUIRED
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
        if (candidate == null || !isVerifiedTarget(candidate)) {
            deny += SafetyReason.UNVERIFIED_TARGET
            return null
        }
        return candidate
    }

    /**
     * Labeled close or skip buttons with tree evidence (E1/E2), as A's gate requires. Icon (E3) and
     * pixel-only (E4) targets and an unlabeled DISMISS are not pressed. A supplied role is not
     * trusted on its own: the label must itself say that role, so `CLOSE` + "닫기 전 저장", a bare
     * "X", or a grade D/K/P/O label is refused whoever built the candidate.
     */
    private fun isVerifiedTarget(candidate: CloseCandidate): Boolean {
        val label = candidate.label?.takeIf { it.isNotBlank() } ?: return false
        val treeEvidence = candidate.evidence == EvidenceLevel.E1_DISMISS ||
            candidate.evidence == EvidenceLevel.E2_LABELED_NODE
        return treeEvidence &&
            (candidate.role == ButtonRole.CLOSE || candidate.role == ButtonRole.SKIP) &&
            DecisionKeywords.buttonRoleOf(label) == candidate.role
    }
}
