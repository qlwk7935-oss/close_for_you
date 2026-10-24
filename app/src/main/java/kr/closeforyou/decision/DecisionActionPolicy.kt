package kr.closeforyou.decision

import kr.closeforyou.core.contract.ActionPolicy
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.ActionAuthorization
import kr.closeforyou.core.model.ActionDecision
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.DecisionSource
import kr.closeforyou.core.model.NextStep
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
 * An action is requested when the person picks a verified E1/E2 button on this screen with
 * CLOSE_ONCE / SKIP_ONCE and confirms its meaning (design 3-5 "닫아"), the same conditions A's gate
 * checks (PR #10), or (contract v2) when the person's preset closes this kind of first-seen popup and
 * every condition of [DecisionAutoClose] holds, A's AutoEligibility included. An exact remembered
 * rule is still checked, but automatic repeats wait for their own agreement (#38). A display
 * classification such as ADVERTISEMENT never requests an action by itself (design 1-2), and
 * INCOMPLETE_EVIDENCE never overrides A's privacy blocking: a blocked screen reaches B only as
 * PopupEvent.Blocked, without an analysis to decide on.
 *
 * Every decision says who asked ([ActionDecision.source]) and what C does next ([ActionDecision.next]):
 * DENY for a decision or a scam-like popup, WAIT when B waits for a screen, ANNOUNCE when the preset
 * only tells this kind, otherwise ASK. Pass every ActionResult A returns to [onActionResult].
 */
class DecisionActionPolicy internal constructor(private val autoClose: DecisionAutoClose) : ActionPolicy {
    /** With the JVM monotonic clock: for tests and the non-executing build. */
    constructor() : this(DecisionAutoClose())

    /** [nowUptimeMillis] must be the clock A stamps AutoEligibility with (SystemClock.uptimeMillis). */
    constructor(nowUptimeMillis: () -> Long) : this(DecisionAutoClose(nowUptimeMillis))

    override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext): ActionDecision {
        val deny = mutableSetOf<SafetyReason>()
        val confirm = mutableSetOf<SafetyReason>()
        val kind = DecisionKinds.of(analysis)

        // 1. Deny first: an old screen or choice, the person acting, an important decision or a scam.
        val choice = context.userChoice
        if (context.currentToken != analysis.token || (choice != null && choice.token != analysis.token)) {
            deny += SafetyReason.STALE_SCREEN
        }
        if (context.userInteracting) deny += SafetyReason.USER_INTERACTION
        if (analysis.classification == PopupClassification.USER_DECISION || analysis.hasUserDecisionSignal() ||
            kind == DecisionKind.USER_DECISION
        ) {
            deny += SafetyReason.IMPORTANT_DECISION
        }
        if (kind == DecisionKind.SUSPICIOUS) deny += SafetyReason.SUSPICIOUS_CONTENT

        // 2. Who asked for an action, and on which verified button.
        val rule = context.matchingRule
        val presetKinds = context.autoCloseKinds.mapNotNull(::kindOf).toSet()
        var announceOnly = false
        val (source, target) = when {
            choice != null -> DecisionSource.USER_CHOICE to userRequestedTarget(analysis, choice, deny, confirm)
            rule != null -> DecisionSource.REMEMBERED_RULE to
                rememberedRuleTarget(analysis, rule, context.announcementAvailable, deny, confirm)
            else -> {
                val preset = if (presetKinds.isEmpty()) null else autoClose.evaluateScoped(analysis, context, presetKinds)
                if (preset != null && preset.approved && deny.isEmpty()) {
                    // The person's preset closes this first-seen popup (contract v2, AUTO_CLOSE.md 4절).
                    DecisionSource.PRESET to analysis.closeCandidates.single { it.id == preset.candidateId }
                } else {
                    // First seen: read and ask, even when it looks like an advertisement.
                    confirm += SafetyReason.FIRST_SEEN
                    preset?.blocks?.mapNotNullTo(confirm, ::reasonOf)
                    announceOnly = preset != null && preset.blocks.all { it in ANNOUNCE_BLOCKS }
                    val verified = analysis.closeCandidates.filter(::isVerifiedTarget)
                    if (verified.isEmpty()) deny += SafetyReason.UNVERIFIED_TARGET
                    (if (preset != null) DecisionSource.PRESET else null) to verified.singleOrNull()
                }
            }
        }
        // Defensive: ALLOW_ONCE must never be reached without a target.
        if (target == null && deny.isEmpty() && confirm.isEmpty()) deny += SafetyReason.UNVERIFIED_TARGET

        val authorization = when {
            deny.isNotEmpty() -> ActionAuthorization.DENY
            confirm.isNotEmpty() -> ActionAuthorization.NEEDS_CONFIRMATION
            else -> ActionAuthorization.ALLOW_ONCE
        }
        val reasons = (deny + confirm).toPersistentSet()
        return ActionDecision(
            token = analysis.token,
            authorization = authorization,
            reasons = reasons,
            candidateId = if (authorization == ActionAuthorization.DENY) null else target?.id,
            source = source,
            next = nextStep(authorization, reasons, announceOnly),
        )
    }

    /**
     * Every result A returns, whoever asked for the press: B's session record for automatic closing
     * (a press that left the app stops automation there; a preset close is done once per popup).
     */
    fun onActionResult(result: ActionResult) = autoClose.onResult(result)

    private fun nextStep(authorization: ActionAuthorization, reasons: Set<SafetyReason>, announceOnly: Boolean): NextStep = when {
        authorization == ActionAuthorization.ALLOW_ONCE -> NextStep.EXECUTE
        reasons.any { it in ActionDecision.DENY_ONLY_REASONS } -> NextStep.DENY
        reasons.any { it in ActionDecision.WAIT_REASONS } -> NextStep.WAIT
        announceOnly -> NextStep.ANNOUNCE
        else -> NextStep.ASK
    }

    private fun kindOf(classification: PopupClassification): DecisionKind? = when (classification) {
        PopupClassification.COUPON -> DecisionKind.COUPON
        PopupClassification.ADVERTISEMENT -> DecisionKind.ADVERTISEMENT
        PopupClassification.NOTICE -> DecisionKind.NOTICE
        // Never closable, whoever puts them in the preset.
        PopupClassification.USER_DECISION, PopupClassification.UNKNOWN -> null
    }

    /** The shared reason code C can say for each automatic-close block (contract v2). */
    private fun reasonOf(block: AutoCloseBlock): SafetyReason? = when (block) {
        AutoCloseBlock.PRESET_OFF, AutoCloseBlock.OTHER_PATH, AutoCloseBlock.KIND_NEVER_CLOSED -> null
        AutoCloseBlock.KIND_NOT_IN_PRESET -> SafetyReason.PRESET_NOT_ALLOWED
        AutoCloseBlock.NOT_TREE_EVIDENCE, AutoCloseBlock.MOCK_DATA -> SafetyReason.INCOMPLETE_EVIDENCE
        AutoCloseBlock.STALE_SCREEN -> SafetyReason.STALE_SCREEN
        AutoCloseBlock.USER_INTERACTION -> SafetyReason.USER_INTERACTION
        AutoCloseBlock.FEEDBACK_UNAVAILABLE -> SafetyReason.FEEDBACK_UNAVAILABLE
        AutoCloseBlock.WAITING_FOR_TARGET -> SafetyReason.WAITING_FOR_TARGET
        AutoCloseBlock.NO_TARGET, AutoCloseBlock.AMBIGUOUS_TARGET, AutoCloseBlock.SKIP_NOT_READY -> SafetyReason.UNVERIFIED_TARGET
        AutoCloseBlock.AWAITING_RESULT -> SafetyReason.AWAITING_RESULT
        AutoCloseBlock.FAILED_BEFORE -> SafetyReason.ALREADY_ATTEMPTED
        AutoCloseBlock.CLOSED_BEFORE -> SafetyReason.REPEAT_NOT_ALLOWED
        AutoCloseBlock.APP_PAUSED -> SafetyReason.LEFT_APP_BEFORE
        AutoCloseBlock.RATE_LIMIT -> SafetyReason.AUTO_LIMIT_REACHED
        AutoCloseBlock.NOT_AUTO_ELIGIBLE -> SafetyReason.NOT_AUTO_ELIGIBLE
    }

    private companion object {
        /** Blocks after which C only tells the content: the preset does not close this kind, or cannot here. */
        val ANNOUNCE_BLOCKS = setOf(AutoCloseBlock.KIND_NOT_IN_PRESET, AutoCloseBlock.NOT_TREE_EVIDENCE, AutoCloseBlock.NO_TARGET)
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
    private fun isVerifiedTarget(candidate: CloseCandidate): Boolean = candidate.isVerifiedTarget()
}

/** See [DecisionActionPolicy.isVerifiedTarget]; shared with [DecisionAutoClose]. */
internal fun CloseCandidate.isVerifiedTarget(): Boolean {
    val label = label?.takeIf { it.isNotBlank() } ?: return false
    val treeEvidence = evidence == EvidenceLevel.E1_DISMISS || evidence == EvidenceLevel.E2_LABELED_NODE
    return treeEvidence && (role == ButtonRole.CLOSE || role == ButtonRole.SKIP) && DecisionKeywords.buttonRoleOf(label) == role
}
