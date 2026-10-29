package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.diagnosticWrite
import kr.closeforyou.accessibility.action.F9ReadCode

internal enum class ManualReviewPoint { ADMITTED, PRESENT_REQUESTED, SHOWN }
internal data class ManualReviewCode(val point: ManualReviewPoint, val admission: ManualReviewAdmission?,
    val change: ManualReviewChange, val summary: kr.closeforyou.ui.voice.choice.PromptSummary? = null, val pressOffered: Boolean? = null)

/** Uses THIS existing immutable prompt only; no classifier rerun, token logging or authority. */
internal fun manualReviewPresentationCode(point: ManualReviewPoint, original: kr.closeforyou.core.model.SnapshotToken?,
    prompt: kr.closeforyou.ui.voice.choice.ChoicePrompt, budget: ChoiceReviewBudget): ManualReviewCode {
    val sameOffer = original == prompt.token
    return ManualReviewCode(point, if (sameOffer) budget.lastAdmission else null,
        if (sameOffer) budget.lastChange else ManualReviewChange.UNKNOWN, prompt.summary,
        prompt.options.any { it.kind == kr.closeforyou.ui.voice.choice.PromptOptionKind.PRESS_CANDIDATE })
}

internal enum class ManualPromptPoint {
    OFFERED, OFFER_REJECTED, FOCUS_REVIEW, REVIEW_REJECTED, REVIEW_REQUESTED, REVIEW_ADMISSION, ADMISSION_REQUESTED, ADMISSION_ACCEPTED, ADMISSION_REJECTED,
    PRESENT_REQUESTED, SHOWN, PRESENT_REJECTED, INVALIDATED, DISPLAY_RETAINED, DISPLAY_REVIEW_STARTED, DISPLAY_REVIEW_FINISHED,
    DISMISS_REQUESTED, OVERLAY_DISMISS_STARTED, OVERLAY_REMOVED, RESULT_RETAINED,
    CLEANUP_FAILED, FLOW_CANCELLED, FLOW_FAILED, SERVICE_CLOSED,
}
internal enum class ManualPromptCause {
    NONE, FOCUS_INVALIDATED_REVIEW_REQUESTED, FOCUS_INVALIDATED_NO_REVIEW,
    PRESENTATION_GATE_REJECTED, ORIGINAL_EVIDENCE_EXPIRED, DISPLAY_LEASE_EXPIRED,
    UNATTRIBUTED_TOUCH, INTERACTION_INVALIDATED, SCREEN_EVENT_INVALIDATED, POLICY_CHANGED,
    SERVICE_STOPPED, CONFIGURATION_CHANGED, OWNED_RETIREMENT, FLOW_FINISHED, FLOW_CANCELLED,
    HEALTH_FAILED, CLEANUP_FAILED, OFFER_UNAVAILABLE, NO_POPUP_SCOPE, REVIEW_BUDGET_EXHAUSTED,
    COLLECTION_BLOCKED, F1_NOT_CANDIDATE, EVIDENCE_BLOCKED, EVIDENCE_CANCELLED, EVIDENCE_FAILED,
    POLICY_BLOCKED, PACKAGE_BLOCKED, TOUCH_STARTED, TOUCH_ENDED, SCROLL_INVALIDATED,
    CONTENT_INVALIDATED, WINDOW_STATE_INVALIDATED, WINDOW_SET_INVALIDATED, CLICK_INVALIDATED,
    OFFER_CLOSED, OFFER_HEALTH_FAILED, OFFER_COMPLETED_KEY, OFFER_CLEANUP_NOT_READY,
    OFFER_OVERLAY_SHOWING, OFFER_SELECTION_PENDING, OWNED_CONTENT_CONFIRMED, OWNED_WINDOW_CONFIRMED,
    CLEANUP_REVIEW, REVIEW_FIRST, REVIEW_UNCHANGED_RECOVERY, REVIEW_IDENTITY_CHANGED,
    DISPLAY_REVIEW_PENDING, TARGET_RECONFIRMED, TARGET_CHANGED, TARGET_REVIEW_UNAVAILABLE, UNKNOWN,
}

/** One service-local attempt ordinal, not a screen identifier. Only fixed codes reach the sink.
 * De-duplicate boundary/cause pairs per offer; no events, tokens, timestamps or exceptions retained. */
internal class ManualPromptTrace(private val actionSink: (Long, ManualActionCode) -> Unit = { _, _ -> },
    private val ownershipSink: (Long, ManualOwnershipCode) -> Unit = { _, _ -> },
    private val eventSink: (Long, ManualEventCode) -> Unit = { _, _ -> },
    private val speechSink: (Long, ManualSpeechState) -> Unit = { _, _ -> },
    private val retirementSink: (Long, ManualRetirementCode) -> Unit = { _, _ -> },
    private val displayReviewSink: (Long, ManualDisplayReviewCode) -> Unit = { _, _ -> },
    private val cleanupEventSink: (Long, ManualCleanupEventCode) -> Unit = { _, _ -> },
    private val reviewSink: (Long, ManualReviewCode) -> Unit = { _, _ -> },
    private val readSink: (Long, F9ReadCode) -> Unit = { _, _ -> },
    cleanupSink: (Long, ManualCleanupCode) -> Unit = { _, _ -> },
    private val sink: (Long, ManualPromptPoint, ManualPromptCause) -> Unit) {
    private var attempt = 0L
    private val recorded = mutableSetOf<Pair<ManualPromptPoint, ManualPromptCause>>()
    private val ownership = mutableSetOf<ManualOwnershipCode>()
    private val eventCodes = mutableSetOf<ManualEventCode>()
    private var speechCodes = mutableSetOf<ManualSpeechState>()
    private val retirementCodes = mutableSetOf<ManualRetirementCode>()
    private val displayReviewCodes = mutableSetOf<ManualDisplayReviewCode>()
    private var cleanupEventFailureReported = false
    private val cleanupEventCodes = mutableSetOf<ManualCleanupEventCode>()
    private val reviewCodes = mutableSetOf<ManualReviewCode>()
    private val readCodes = mutableSetOf<F9ReadCode>()
    val cleanup = ManualCleanupTrace { code -> cleanupSink(attempt, code) }
    fun begin() { attempt = if (attempt == Long.MAX_VALUE) 1 else attempt + 1; recorded.clear(); ownership.clear(); eventCodes.clear(); retirementCodes.clear(); displayReviewCodes.clear(); cleanupEventCodes.clear(); cleanupEventFailureReported = false; reviewCodes.clear(); readCodes.clear(); speechCodes = mutableSetOf(); record(ManualPromptPoint.OFFERED) }
    fun record(point: ManualPromptPoint, cause: ManualPromptCause = ManualPromptCause.NONE) {
        if (recorded.add(point to cause)) diagnosticWrite { sink(attempt, point, cause) }
    }
    fun action(): ManualActionTrace {
        val acceptedAttempt = attempt
        return ManualActionTrace { code -> actionSink(acceptedAttempt, code) }
    }
    fun ownership(code: ManualOwnershipCode) {
        if (ownership.size < 8 && ownership.add(code)) diagnosticWrite { ownershipSink(attempt, code) }
    }
    fun windowEvent(code: ManualEventCode) {
        if (eventCodes.size < 16 && eventCodes.add(code)) diagnosticWrite { eventSink(attempt, code) }
    }
    fun speech(code: ManualSpeechState) {
        if (speechCodes.add(code)) diagnosticWrite { speechSink(attempt, code) }
    }
    fun retirement(code: ManualRetirementCode) {
        if (retirementCodes.size < 16 && retirementCodes.add(code)) diagnosticWrite { retirementSink(attempt, code) }
    }
    fun displayReview(code: ManualDisplayReviewCode) {
        if (displayReviewCodes.size < 16 && displayReviewCodes.add(code)) diagnosticWrite { displayReviewSink(attempt, code) }
    }
    fun cleanupEvent(code: ManualCleanupEventCode) {
        // Reserve one report for the first actually rejected barrier event even after routine
        // diagnostics reached their cap. Mark before writing: sink failure is never retried.
        if (code.result == ManualCleanupEventResult.REJECTED) {
            if (cleanupEventFailureReported) return
            cleanupEventFailureReported = true
            diagnosticWrite { cleanupEventSink(attempt, code) }
        } else if (cleanupEventCodes.size < 16 && cleanupEventCodes.add(code)) diagnosticWrite { cleanupEventSink(attempt, code) }
    }
    fun review(code: ManualReviewCode) {
        if (reviewCodes.size < 16 && reviewCodes.add(code)) diagnosticWrite { reviewSink(attempt, code) }
    }
    fun f9Read(code: F9ReadCode) {
        if (readCodes.size < 24 && readCodes.add(code)) diagnosticWrite { readSink(attempt, code) }
    }
    fun speechReporter(): (ManualSpeechState) -> Unit {
        val acceptedAttempt = attempt; val codes = speechCodes
        return { code -> if (codes.add(code)) diagnosticWrite { speechSink(acceptedAttempt, code) } }
    }
    override fun toString() = "ManualPromptTrace(redacted)"
}

/** Same side-effect ordering in the real manual driver and Flow regression. This is ONLY a
 * presentation lifetime boundary. Runtime/automatic evidence are invalidated independently. */
internal inline fun applyManualInvalidation(cause: ManualPromptCause, displayCurrent: Boolean,
    healthy: Boolean, pendingAction: Boolean, ownedRetirement: Boolean,
    revoke: () -> Unit, remove: () -> Unit): Boolean {
    if (healthy && displayCurrent && cause in setOf(ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED,
            ManualPromptCause.UNATTRIBUTED_TOUCH, ManualPromptCause.DISPLAY_REVIEW_PENDING)) return true
    if (ownedRetirement && pendingAction) return true
    revoke()
    if (!pendingAction) remove()
    return false
}
