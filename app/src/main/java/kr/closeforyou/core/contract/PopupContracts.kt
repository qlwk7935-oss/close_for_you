package kr.closeforyou.core.contract

import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
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

/** A: capture/OCR remain explicit, denied capabilities until F0-B is implemented and verified. */
enum class CaptureAvailability { NOT_IMPLEMENTED, PRIVACY_BLOCKED, UNSUPPORTED_API, AVAILABLE }
interface CaptureCapability {
    fun availability(token: SnapshotToken): CaptureAvailability
}

/** B: no clicking, gestures, Back, or focus changes from a classifier or policy implementation. */
interface PopupClassifier {
    suspend fun classify(analysis: PopupAnalysis): PopupClassification
}

data class DecisionContext(
    val currentToken: SnapshotToken?,
    val userChoice: UserChoice?,
    val matchingRule: PopupRule?,
    val userInteracting: Boolean,
    val announcementAvailable: Boolean,
)

interface ActionPolicy {
    suspend fun decide(analysis: PopupAnalysis, context: DecisionContext): ActionDecision
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
