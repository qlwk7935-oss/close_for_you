package kr.closeforyou.core.contract

import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.Flow

/** Ephemeral stream: no replay, logging, persistence, or retaining a snapshot across invalidation. */
sealed interface PopupEvent {
    data class Candidate(val snapshot: PopupSnapshot, val detection: PopupDetection) : PopupEvent
    data class Blocked(val reason: PrivacyBlockReason, val screenVersion: Long) : PopupEvent
    data class Invalidated(val screenVersion: Long) : PopupEvent
}

interface SnapshotSource {
    val events: Flow<PopupEvent>
    fun currentToken(): SnapshotToken?
}

/** A: asynchronous tree/OCR evidence. No Android node or bitmap crosses this boundary. */
interface EvidenceExtractor {
    suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis
}

/** A: availability is checked per token, consent, app and service capability; never authorizes an action. */
enum class CaptureAvailability { NOT_IMPLEMENTED, PRIVACY_BLOCKED, UNSUPPORTED_API, AVAILABLE }
interface CaptureCapability {
    fun availability(token: SnapshotToken): CaptureAvailability
}

/** B: no clicking, gestures, Back, or focus changes from a classifier or policy implementation. */
interface PopupClassifier {
    suspend fun classify(analysis: PopupAnalysis): PopupClassification
}

/**
 * The person's consent comes in two separate scopes:
 * - [autoCloseKinds] (contract v2, preset): kinds the person lets B close when first seen, once per
 *   popup per session. Empty by default = no automatic close, even when C shows a preset as chosen.
 *   USER_DECISION and UNKNOWN are ignored whoever puts them in. A policy input, not an authorization.
 * - [matchingRule] (F12): repeating for one exact popup, only when the rule allows repeat automation.
 */
data class DecisionContext(
    val currentToken: SnapshotToken?,
    val userChoice: UserChoice?,
    val matchingRule: PopupRule?,
    val userInteracting: Boolean,
    val announcementAvailable: Boolean,
    val autoCloseKinds: ImmutableSet<PopupClassification> = persistentSetOf(),
)

interface ActionPolicy {
    suspend fun decide(analysis: PopupAnalysis, context: DecisionContext): ActionDecision

    /**
     * Contract v3: A passes every ActionResult it returns here, whoever asked for the press, so the
     * policy can keep its session record (a preset close is done once; a press that left the app
     * stops automation there). Does nothing by default.
     */
    fun onActionResult(result: ActionResult) {}
}

data class ActionRequest(val decision: ActionDecision, val candidate: CloseCandidate)

/** A owns the future single action gate. Implementations must re-read the actual screen, not
 * just compare the supplied DTO. One authorized attempt; no automatic coordinate fallback. */
interface ActionExecutor {
    suspend fun execute(request: ActionRequest): ActionResult
}

/** C: these states do not mean the person heard or understood an announcement. */
enum class AnnouncementState { NOT_IMPLEMENTED, UI_UPDATED, FOCUS_OBSERVED, TTS_COMPLETED, USER_ACKNOWLEDGED }
enum class AnnouncementKind { POPUP_FOUND, CONTENT, ACTION_RESULT, PROTECTED_SCREEN, PARTIAL_INFORMATION }
data class AnnouncementRequest(val token: SnapshotToken?, val kind: AnnouncementKind, val text: String) {
    override fun toString(): String = "AnnouncementRequest(kind=$kind, redacted)"
}
data class AnnouncementReceipt(val state: AnnouncementState)

interface PopupAnnouncer {
    suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt
    suspend fun repeatLast(): AnnouncementReceipt
    fun stop()
}

/**
 * Contract v3, C: receives what happened to popups handled without asking (HandlingReport). C says
 * "닫았어요" only for [HandlingReport.closedConfirmed], says a WAIT once, warns on a scam DENY, and may
 * batch reports from about 2 s into one sentence. Never logs or stores a report or its title.
 */
interface PopupHandlingListener {
    suspend fun onHandled(report: HandlingReport)
}

enum class VoiceCommand { CLOSE, SKIP, READ_CONTENT, KEEP_OPEN, GO_BACK, MOVE_TO_BUTTON, DESCRIBE_IMAGE }
enum class VoiceAvailability { NOT_IMPLEMENTED, MICROPHONE_PERMISSION_REQUIRED, ON_DEVICE_UNAVAILABLE, READY }
sealed interface VoiceEvent {
    data class Recognized(val command: VoiceCommand, val token: SnapshotToken) : VoiceEvent
    data object Cancelled : VoiceEvent
    data object Unavailable : VoiceEvent
}

/** F13 required. Called only by an explicit speak-button press. No always-on listener and no
 * default remote recognizer. Recognized commands must still pass B's policy and A's gate. */
interface VoiceCommandInput {
    val events: Flow<VoiceEvent>
    fun availability(): VoiceAvailability
    fun startFromSpeakButton(token: SnapshotToken): VoiceAvailability
    fun cancel()
}

/** D: exact content, app version and target form the lookup key. A match is not authorization. */
interface PopupRuleRepository {
    suspend fun findExact(key: RuleKey, nowEpochMillis: Long): PopupRule?
    suspend fun save(rule: PopupRule)
    suspend fun revoke(id: String)
    suspend fun deleteAll()
    fun observeRules(): Flow<ImmutableList<PopupRule>>
}

interface PopupFingerprintProvider {
    suspend fun createKey(snapshot: PopupSnapshot, target: CloseCandidate, appVersionCode: Long): RuleKey
}

/** D: reason codes only; no screen text, screenshot, node, package or fingerprint parameters. */
enum class DiagnosticCode { CANDIDATE_FOUND, PRIVACY_BLOCKED, SNAPSHOT_INVALIDATED, ACTION_NOT_IMPLEMENTED }
interface SafeDiagnostics {
    fun record(code: DiagnosticCode, reasons: ImmutableSet<SafetyReason>)
}
