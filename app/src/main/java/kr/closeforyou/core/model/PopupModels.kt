package kr.closeforyou.core.model

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

/** Contract v1. Screen text is ephemeral private data: never log or persist this object. */
data class ScreenBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    init { require(right >= left && bottom >= top) }
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

enum class NodeAction { CLICK, DISMISS }
enum class WindowType { APPLICATION, INPUT_METHOD, ACCESSIBILITY_OVERLAY, SYSTEM, OTHER }
enum class PopupTrigger { WINDOW_CHANGED, CONTENT_CHANGED, FULL_SCREEN_TRANSITION }
enum class SnapshotCompleteness { COMPLETE, TRUNCATED }
enum class PopupSignal { NEW_WINDOW, SAME_WINDOW_OVERLAY, FULL_SCREEN_TRANSITION, INSUFFICIENT_EVIDENCE }

/** A descriptor, never an AccessibilityNodeInfo or a durable executable handle. */
data class NodeSnapshot(
    val path: String,
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val bounds: ScreenBounds,
    val actions: ImmutableSet<NodeAction> = persistentSetOf(),
) {
    override fun toString(): String = "NodeSnapshot(redacted)"
}

data class WindowSnapshot(
    val id: Int,
    val type: WindowType,
    val bounds: ScreenBounds,
    val active: Boolean,
    val focused: Boolean,
    val layer: Int,
)

/** Every field must be revalidated immediately before any future external action. */
data class SnapshotToken(
    val screenVersion: Long,
    val windowId: Int,
    val packageName: String,
    val popupInstanceId: String,
    val structureDigest: String,
    val contentDigest: String,
    val captureEpoch: Long,
) {
    override fun toString(): String = "SnapshotToken(version=$screenVersion, redacted)"
}

data class PopupSnapshot(
    val token: SnapshotToken,
    val capturedAtUptimeMillis: Long,
    val nodes: ImmutableList<NodeSnapshot>,
    val windows: ImmutableList<WindowSnapshot>,
    val screenBounds: ScreenBounds,
    val trigger: PopupTrigger,
    val completeness: SnapshotCompleteness,
) {
    override fun toString(): String = "PopupSnapshot(version=${token.screenVersion}, nodes=${nodes.size}, redacted)"
}

data class PopupDetection(
    val token: SnapshotToken,
    val signals: ImmutableSet<PopupSignal>,
    val requiresUserReview: Boolean = true,
)

enum class PrivacyBlockReason {
    NO_CONSENT, APP_NOT_ALLOWED, PROTECTED_APP, PASSWORD, EDITABLE_FIELD,
    SENSITIVE_CONTENT, SYSTEM_WINDOW, UNREADABLE_TREE, TRUNCATED_TREE, PAUSED,
}

/** Display classification is never an action authorization. B owns its implementation. */
/**
 * COUPON (contract v2): a benefit the person can claim or already holds (쿠폰·적립금·상품권…).
 * Appended last so existing ordinals stay. A scam-like popup is not a class: it is UNKNOWN with
 * [SafetyReason.SUSPICIOUS_CONTENT].
 */
enum class PopupClassification { ADVERTISEMENT, NOTICE, USER_DECISION, UNKNOWN, COUPON }
enum class EvidenceLevel { E1_DISMISS, E2_LABELED_NODE, E3_USER_BOUND_NODE, E4_VISUAL_ONLY }
enum class ButtonRole { CLOSE, SKIP, ACKNOWLEDGE, USER_DECISION, NAVIGATE, UNKNOWN }
enum class TextSource { ACCESSIBILITY_TREE, OCR }

data class ExtractedText(
    val text: String,
    val bounds: ScreenBounds,
    val source: TextSource,
    val confidence: Float?,
) {
    init { require(confidence == null || confidence in 0f..1f) }
    override fun toString(): String = "ExtractedText(redacted)"
}

data class CloseCandidate(
    val id: String,
    val nodePath: String?,
    val viewId: String?,
    val label: String?,
    val bounds: ScreenBounds,
    val evidence: EvidenceLevel,
    val role: ButtonRole = ButtonRole.UNKNOWN,
) {
    override fun toString(): String = "CloseCandidate(evidence=$evidence, role=$role, redacted)"
}

enum class AnalysisLimit { MOCK_ONLY, OCR_NOT_IMPLEMENTED, IMAGE_MODEL_NOT_IMPLEMENTED, INCOMPLETE_EVIDENCE }

/** Contract v2: one thing A verified on this exact screen. All are needed before any automatic close. */
enum class EligibilityCheck {
    /** F0 protection ran on this screen and blocked nothing. */
    F0_PASSED,
    /** The popup's own nodes are known (its window, or one unambiguous Dialog subtree). */
    POPUP_SCOPE_CONFIRMED,
    /** The popup's tree was read completely (not truncated, not partial). */
    TREE_COMPLETE,
    /** Decision text was judged within the popup, never dropped from it (decision summary scope). */
    DECISION_TEXT_SCOPED,
    /** Every close candidate lies inside the popup's own nodes. */
    TARGETS_IN_POPUP,
}

/**
 * Contract v2, from A: the evidence that makes this analysis eligible for an automatic close. It is
 * not an authorization and does not override [AnalysisLimit.INCOMPLETE_EVIDENCE] for anything
 * else. Absent (the default) means no automatic close at all, as today. Valid only for [token] and
 * for [TTL_MILLIS] after [issuedAtUptimeMillis], the same 5 s as A's evidence.
 */
data class AutoEligibility(
    val token: SnapshotToken,
    val checks: ImmutableSet<EligibilityCheck>,
    val issuedAtUptimeMillis: Long,
) {
    val complete: Boolean get() = checks.containsAll(EligibilityCheck.entries)

    fun validFor(analysisToken: SnapshotToken, nowUptimeMillis: Long): Boolean =
        complete && token == analysisToken && nowUptimeMillis >= issuedAtUptimeMillis &&
            nowUptimeMillis - issuedAtUptimeMillis < TTL_MILLIS

    override fun toString(): String = "AutoEligibility(checks=$checks, redacted)"

    companion object {
        const val TTL_MILLIS = 5_000L
    }
}

data class PopupAnalysis(
    val token: SnapshotToken,
    val classification: PopupClassification = PopupClassification.UNKNOWN,
    val texts: ImmutableList<ExtractedText> = persistentListOf(),
    val closeCandidates: ImmutableList<CloseCandidate> = persistentListOf(),
    val limitations: ImmutableSet<AnalysisLimit> = persistentSetOf(),
    /** F5: video evidence alone must not imply an advertisement. F6 is excluded. */
    val videoSurfaceObserved: Boolean = false,
    /** Contract v2: set by A only when every [EligibilityCheck] holds; null keeps automation off. */
    val autoEligibility: AutoEligibility? = null,
) {
    override fun toString(): String = "PopupAnalysis(classification=$classification, redacted)"
}

enum class ActionAuthorization { DENY, NEEDS_CONFIRMATION, ALLOW_ONCE }
enum class SafetyReason {
    NOT_IMPLEMENTED, FIRST_SEEN, PRIVACY_BLOCKED, IMPORTANT_DECISION, STALE_SCREEN,
    USER_INTERACTION, UNVERIFIED_TARGET, RULE_MISMATCH, EXPLICIT_PERMISSION_REQUIRED,
    INCOMPLETE_EVIDENCE, ALREADY_ATTEMPTED, FEEDBACK_UNAVAILABLE,

    // Contract v2 (automatic close). Appended so existing ordinals stay.
    /** Prize-win, infection or urgent-install wording: warn, and never close for the person. */
    SUSPICIOUS_CONTENT,
    /** The person's preset does not close this kind ([DecisionContext.autoCloseKinds]). */
    PRESET_NOT_ALLOWED,
    /** A close button may still appear (delayed ad, skip countdown): observe again, do not prompt yet. */
    WAITING_FOR_TARGET,
    /** This popup was already sent to A and its result has not come back. */
    AWAITING_RESULT,
    /** A did not supply a complete, current [AutoEligibility]. */
    NOT_AUTO_ELIGIBLE,
    /** Too many automatic closes in this app in a short time. */
    AUTO_LIMIT_REACHED,
    /**
     * An earlier press in this app left the app ([ClickOutcome.LEFT_APP]): automatic closing stops for
     * that app until the accessibility service session ends (B keeps it in memory only). Asking the
     * person still works.
     */
    LEFT_APP_BEFORE,
    /** Closed automatically once already; repeating needs the person's F12 permission. */
    REPEAT_NOT_ALLOWED,
}

/** Contract v2: who asked for the action a decision answers. A source is never an authorization. */
enum class DecisionSource {
    /** The person chose on this screen (UserChoice). */
    USER_CHOICE,
    /** The person's F12 rule for this exact popup (PopupRule with repeat automation allowed). */
    REMEMBERED_RULE,
    /** The person's preset for first-seen popups of a kind ([DecisionContext.autoCloseKinds]). */
    PRESET,
}

/**
 * Contract v2: what happens next. EXECUTE is B's approval only, never a completed click: A's gate
 * still rereads F0, the screen and the target before at most one press.
 */
enum class NextStep {
    EXECUTE,
    /** Observe again without prompting (WAITING_FOR_TARGET, AWAITING_RESULT, the person acting). */
    WAIT,
    /** Tell the content; nothing is pressed unless the person then chooses. */
    ANNOUNCE,
    /** Ask the person, as for a first-seen popup today. */
    ASK,
    /** Explain only (decision, suspicious): no option to press for the person. */
    DENY,
}

/** The [NextStep] an older caller means by [authorization] alone. */
fun defaultNextStep(authorization: ActionAuthorization): NextStep = when (authorization) {
    ActionAuthorization.ALLOW_ONCE -> NextStep.EXECUTE
    ActionAuthorization.NEEDS_CONFIRMATION -> NextStep.ASK
    ActionAuthorization.DENY -> NextStep.DENY
}

data class ActionDecision(
    val token: SnapshotToken,
    val authorization: ActionAuthorization,
    val reasons: ImmutableSet<SafetyReason>,
    val candidateId: String? = null,
    /** Contract v2. Null where the caller has not said yet (decisions made before v2). */
    val source: DecisionSource? = null,
    /** Contract v2. Defaults to what [authorization] meant before v2. */
    val next: NextStep = defaultNextStep(authorization),
) {
    init {
        require(authorization != ActionAuthorization.ALLOW_ONCE || candidateId != null)
        // Only an approval executes, and an approval always executes.
        require((next == NextStep.EXECUTE) == (authorization == ActionAuthorization.ALLOW_ONCE))
        // "No option to press" cannot sit on a decision that still offers one.
        require(next != NextStep.DENY || authorization == ActionAuthorization.DENY)
        // An important decision or a scam-like popup is only ever explained (#38 review, C).
        require(reasons.none { it in DENY_ONLY_REASONS } || next == NextStep.DENY)
        // Waiting always says what it waits for, so C can say it once and stay quiet (#38 review, C).
        require(next != NextStep.WAIT || reasons.any { it in WAIT_REASONS })
    }

    companion object {
        val DENY_ONLY_REASONS: Set<SafetyReason> = setOf(SafetyReason.IMPORTANT_DECISION, SafetyReason.SUSPICIOUS_CONTENT)
        val WAIT_REASONS: Set<SafetyReason> = setOf(
            SafetyReason.WAITING_FOR_TARGET, SafetyReason.AWAITING_RESULT, SafetyReason.USER_INTERACTION, SafetyReason.STALE_SCREEN,
        )
    }
}

enum class ActionStatus { NOT_EXECUTED, BLOCKED, STALE, ATTEMPT_FAILED, MECHANICAL_SUCCESS, OUTCOME_UNKNOWN }

/**
 * Contract v2, from A: what the screen showed after a press. A delivered click
 * ([ActionStatus.MECHANICAL_SUCCESS]) is never by itself a closed popup.
 */
enum class ClickOutcome {
    /** The popup's window or nodes are gone, the same app is in front and no new window opened. */
    CLOSED_CONFIRMED,
    /** Another app or page came to the front: treat the button as a fake close. */
    LEFT_APP,
    /** The popup is still there. */
    STILL_PRESENT,
    UNKNOWN,
}

/** Mechanical success never establishes semantic safety or that the user heard feedback. */
data class ActionResult(
    val token: SnapshotToken,
    val status: ActionStatus,
    val reason: SafetyReason?,
    val attempted: Boolean = false,
    val semanticSafetyConfirmedByUser: Boolean = false,
    /**
     * Contract v2. Observed only after an attempted press, whoever asked for it (the person, an F12
     * rule or a preset); null when A did not observe. C says "닫았어요" only on CLOSED_CONFIRMED.
     */
    val outcome: ClickOutcome? = null,
) {
    init { require(outcome == null || attempted) }
}

enum class ChoiceKind { CLOSE_ONCE, SKIP_ONCE, READ_CONTENT, KEEP_OPEN, REMEMBER_EXACT_POPUP, STOP_ASKING, REVOKE_RULE }

data class UserChoice(
    val token: SnapshotToken,
    val kind: ChoiceKind,
    val candidateId: String? = null,
    val buttonMeaningConfirmed: Boolean = false,
    val repeatAutomationExplicitlyAllowed: Boolean = false,
) {
    init {
        require(!repeatAutomationExplicitlyAllowed ||
            (kind == ChoiceKind.REMEMBER_EXACT_POPUP && buttonMeaningConfirmed && candidateId != null))
    }
}

/** D supplies device-local keyed fingerprints, separate from A's session-scoped tokens. */
data class RuleKey(
    val packageName: String,
    val appVersionCode: Long,
    val structureFingerprint: String,
    val exactContentFingerprint: String,
    val targetFingerprint: String,
) {
    override fun toString(): String = "RuleKey(redacted)"
}

data class PopupRule(
    val id: String,
    val key: RuleKey,
    val choice: ChoiceKind,
    val buttonMeaningConfirmed: Boolean,
    val repeatAutomationExplicitlyAllowed: Boolean,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val schemaVersion: Int = 1,
    val failureCount: Int = 0,
    val enabled: Boolean = true,
) {
    init {
        require(failureCount >= 0 && schemaVersion > 0)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > createdAtEpochMillis)
        require(!repeatAutomationExplicitlyAllowed ||
            (choice == ChoiceKind.REMEMBER_EXACT_POPUP && buttonMeaningConfirmed))
    }
    override fun toString(): String = "PopupRule(enabled=$enabled, schemaVersion=$schemaVersion, redacted)"
}
