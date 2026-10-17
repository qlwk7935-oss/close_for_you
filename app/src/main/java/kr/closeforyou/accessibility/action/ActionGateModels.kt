package kr.closeforyou.accessibility.action

import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.ImmutableSet

/** A-private, transient values. No Android object, durable permission, logging or persistence. */
internal enum class GateEvidenceOrigin { UNAVAILABLE, SYNTHETIC_TEST }
internal enum class GateCheck { MISSING, PASSED, BLOCKED, UNCERTAIN }
internal enum class GateCollection { MISSING, COMPLETE, INCOMPLETE, MALFORMED_NODE_BOUNDS, WINDOW_OUTSIDE_FRAME, TIME_LIMIT, TARGET_MISSING }
internal enum class GateChoiceOrigin { UNKNOWN, SYNTHETIC_EXPLICIT_SELECTION }
internal enum class GateDenial {
    ANDROID_REVALIDATION_NOT_IMPLEMENTED, UNREGISTERED_REQUEST, EVIDENCE_UNAVAILABLE,
    NOT_ALLOW_ONCE, DECISION_HAS_REASONS, CANDIDATE_MISMATCH, SESSION_CHANGED,
    SERVICE_STOPPED, STALE_TOKEN, TTL_EXPIRED, INVALID_TTL, USER_INTERACTION,
    NO_CONSENT, PAUSED, APP_NOT_ALLOWED, COLLECTION_INCOMPLETE, F0_A_UNVERIFIED,
    F0_B_UNVERIFIED, F1_UNVERIFIED, GEOMETRY_UNVERIFIED, OCCLUSION_UNVERIFIED,
    POPUP_MEMBERSHIP_UNVERIFIED, IMPORTANT_DECISION, CHOICE_UNVERIFIED,
    TARGET_UNVERIFIED, UNSUPPORTED_EVIDENCE, BUTTON_MEANING_UNVERIFIED,
    POPUP_IDENTITY_UNPROVEN, REVIEW_ALREADY_RESERVED, REVIEW_ALREADY_FINISHED,
    INVALIDATED_DURING_CHECK, INSPECTION_FAILED,
}

/** Default means no evidence, never a completed protection check. PASSED is synthetic here. */
internal data class GateChecks(
    val collection: GateCollection = GateCollection.MISSING,
    val f0A: GateCheck = GateCheck.MISSING,
    val f0B: GateCheck = GateCheck.MISSING,
    val f1: GateCheck = GateCheck.MISSING,
    val geometry: GateCheck = GateCheck.MISSING,
    val occlusion: GateCheck = GateCheck.MISSING,
    val popupMembership: GateCheck = GateCheck.MISSING,
    val noImportantDecision: GateCheck = GateCheck.MISSING,
    val buttonMeaning: GateCheck = GateCheck.MISSING,
)

/** All node attributes must come from A's future fresh reader, never from ActionRequest. */
internal data class GateNode(
    val path: String,
    val viewId: String?,
    val className: String?,
    val packageName: String,
    val windowId: Int,
    val bounds: ScreenBounds,
    val text: String?,
    val description: String?,
    val actions: ImmutableSet<NodeAction>,
    val clickable: Boolean,
    val enabled: Boolean,
    val visible: Boolean,
    val password: Boolean = false,
    val editable: Boolean = false,
    val inputType: Int = 0,
) {
    override fun toString(): String = "GateNode(redacted)"
}

internal data class GateGeometry(
    val screen: ScreenBounds,
    val window: ScreenBounds,
    val popup: ScreenBounds,
    val popupRootPath: String,
) {
    override fun toString(): String = "GateGeometry(redacted)"
}

/** Not a public token. Only the owning ledger recognizes this object by reference. */
internal class PopupEpisode {
    override fun toString(): String = "PopupEpisode(redacted)"
}

internal data class GateRegistration(
    val sessionGeneration: Long,
    val token: SnapshotToken,
    val issuedAtMillis: Long,
    val expiresAtMillis: Long,
    val episode: PopupEpisode,
    val candidate: CloseCandidate,
    val node: GateNode,
    val geometry: GateGeometry,
    val choice: UserChoice,
    val choiceOrigin: GateChoiceOrigin,
) {
    override fun toString(): String = "GateRegistration(redacted)"
}

/** There is intentionally no live/Android-verified origin in this first implementation. */
internal data class ActionGateContext(
    val registration: GateRegistration,
    val origin: GateEvidenceOrigin = GateEvidenceOrigin.UNAVAILABLE,
    val currentSessionGeneration: Long,
    val currentToken: SnapshotToken?,
    val connected: Boolean = false,
    val userInteracting: Boolean = true,
    val consentGranted: Boolean = false,
    val paused: Boolean = true,
    val appAllowed: Boolean = false,
    val popupContinuity: GateCheck = GateCheck.MISSING,
    val checks: GateChecks = GateChecks(),
    val currentNode: GateNode? = null,
    val currentGeometry: GateGeometry? = null,
) {
    override fun toString(): String = "ActionGateContext(redacted)"
}

internal data class GateEvaluation(val denials: Set<GateDenial>) {
    /** Synthetic check coverage only; never action authorization or actual-screen validation. */
    val syntheticChecksSatisfied: Boolean
        get() = denials == setOf(GateDenial.ANDROID_REVALIDATION_NOT_IMPLEMENTED)
    val productionReady: Boolean get() = false
}

/** No request argument: providers must not manufacture registration evidence from supplied DTOs. */
internal fun interface ActionGateContextSource {
    suspend fun read(): ActionGateContext?
}

internal fun interface GateClock { fun nowMillis(): Long }
