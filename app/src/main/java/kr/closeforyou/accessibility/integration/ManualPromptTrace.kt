package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.diagnosticWrite

internal enum class ManualPromptPoint {
    OFFERED, OFFER_REJECTED, FOCUS_REVIEW, REVIEW_REJECTED, ADMISSION_REQUESTED, ADMISSION_ACCEPTED, ADMISSION_REJECTED,
    PRESENT_REQUESTED, SHOWN, PRESENT_REJECTED, INVALIDATED, DISPLAY_RETAINED,
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
    UNKNOWN,
}

/** One service-local attempt ordinal, not a screen identifier. Only fixed codes reach the sink.
 * De-duplicate boundary/cause pairs per offer; no events, tokens, timestamps or exceptions retained. */
internal class ManualPromptTrace(private val sink: (Long, ManualPromptPoint, ManualPromptCause) -> Unit) {
    private var attempt = 0L
    private val recorded = mutableSetOf<Pair<ManualPromptPoint, ManualPromptCause>>()
    fun begin() { attempt = if (attempt == Long.MAX_VALUE) 1 else attempt + 1; recorded.clear(); record(ManualPromptPoint.OFFERED) }
    fun record(point: ManualPromptPoint, cause: ManualPromptCause = ManualPromptCause.NONE) {
        if (recorded.add(point to cause)) diagnosticWrite { sink(attempt, point, cause) }
    }
    override fun toString() = "ManualPromptTrace(redacted)"
}

/** Same side-effect ordering in the real manual driver and Flow regression. This is ONLY a
 * presentation lifetime boundary. Runtime/automatic evidence are invalidated independently. */
internal inline fun applyManualInvalidation(cause: ManualPromptCause, displayCurrent: Boolean,
    healthy: Boolean, pendingAction: Boolean, ownedRetirement: Boolean,
    revoke: () -> Unit, remove: () -> Unit): Boolean {
    if (healthy && displayCurrent && cause in setOf(ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED,
            ManualPromptCause.UNATTRIBUTED_TOUCH)) return true
    if (ownedRetirement && pendingAction) return true
    revoke()
    if (!pendingAction) remove()
    return false
}
