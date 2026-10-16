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
enum class PopupClassification { ADVERTISEMENT, NOTICE, USER_DECISION, UNKNOWN }
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

data class PopupAnalysis(
    val token: SnapshotToken,
    val classification: PopupClassification = PopupClassification.UNKNOWN,
    val texts: ImmutableList<ExtractedText> = persistentListOf(),
    val closeCandidates: ImmutableList<CloseCandidate> = persistentListOf(),
    val limitations: ImmutableSet<AnalysisLimit> = persistentSetOf(),
    /** F5: video evidence alone must not imply an advertisement. F6 is excluded. */
    val videoSurfaceObserved: Boolean = false,
) {
    override fun toString(): String = "PopupAnalysis(classification=$classification, redacted)"
}

enum class ActionAuthorization { DENY, NEEDS_CONFIRMATION, ALLOW_ONCE }
enum class SafetyReason {
    NOT_IMPLEMENTED, FIRST_SEEN, PRIVACY_BLOCKED, IMPORTANT_DECISION, STALE_SCREEN,
    USER_INTERACTION, UNVERIFIED_TARGET, RULE_MISMATCH, EXPLICIT_PERMISSION_REQUIRED,
    INCOMPLETE_EVIDENCE, ALREADY_ATTEMPTED, FEEDBACK_UNAVAILABLE,
}

data class ActionDecision(
    val token: SnapshotToken,
    val authorization: ActionAuthorization,
    val reasons: ImmutableSet<SafetyReason>,
    val candidateId: String? = null,
) {
    init { require(authorization != ActionAuthorization.ALLOW_ONCE || candidateId != null) }
}

enum class ActionStatus { NOT_EXECUTED, BLOCKED, STALE, ATTEMPT_FAILED, MECHANICAL_SUCCESS, OUTCOME_UNKNOWN }

/** Mechanical success never establishes semantic safety or that the user heard feedback. */
data class ActionResult(
    val token: SnapshotToken,
    val status: ActionStatus,
    val reason: SafetyReason?,
    val attempted: Boolean = false,
    val semanticSafetyConfirmedByUser: Boolean = false,
)

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
