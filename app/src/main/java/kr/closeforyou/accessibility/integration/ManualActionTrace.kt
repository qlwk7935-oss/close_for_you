package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.EvidenceMismatch
import kr.closeforyou.accessibility.action.F9Block
import kr.closeforyou.accessibility.diagnosticWrite
import kr.closeforyou.core.model.*

internal enum class ManualActionStep {
    CHOICE_ACCEPTED, OVERLAY_REMOVAL, INTERACTION_END, MANUAL_REFRESH, SNAPSHOT_MATCH,
    TARGET_MATCH, SESSION_ENTRY, B_DECISION, F9_VALIDATION, CLICK_ATTEMPT, CLICK_RESULT, MANUAL_END,
}
internal enum class ManualActionState {
    STARTED, WAITING, PASSED, APPROVED, REJECTED, ATTEMPTED, NOT_ATTEMPTED,
    COMPLETED, CANCELLED, FAILED, TIMED_OUT,
}
internal enum class ManualActionReason {
    NONE, ORIGINAL_UNAVAILABLE, DETECTION_UNAVAILABLE, INVALID_CHOICE, SELECTION_STALE,
    COLLECTION_UNAVAILABLE, FRESHNESS_OR_COMPLETENESS, NODES_CHANGED, PROTECTED_CONTENT,
    TARGET_UNAVAILABLE, TARGET_CHANGED, SESSION_UNAVAILABLE, ALREADY_ISSUED,
    ELIGIBILITY_UNAVAILABLE, DELIVERY_TRUE, DELIVERY_FALSE, DELIVERY_UNKNOWN,
}

/** Fixed codes only. No snapshot, token, app, window, time, node or Throwable can be retained. */
internal data class ManualActionCode(val step: ManualActionStep, val state: ManualActionState,
    val reason: ManualActionReason = ManualActionReason.NONE, val safety: SafetyReason? = null,
    val block: F9Block? = null, val mismatch: EvidenceMismatch? = null,
    val outcome: ClickOutcome? = null, val next: NextStep? = null)

/** Immutable attempt binding: a later offer cannot relabel an earlier suspended action.
 * The bounded code set de-duplicates repeated boundaries; diagnostics never authorize actions. */
internal class ManualActionTrace(private val sink: (ManualActionCode) -> Unit) {
    private val recorded = mutableSetOf<ManualActionCode>()
    fun record(step: ManualActionStep, state: ManualActionState,
        reason: ManualActionReason = ManualActionReason.NONE, safety: SafetyReason? = null,
        block: F9Block? = null, mismatch: EvidenceMismatch? = null,
        outcome: ClickOutcome? = null, next: NextStep? = null) {
        val code = ManualActionCode(step, state, reason, safety, block, mismatch, outcome, next)
        if (recorded.add(code)) diagnosticWrite { sink(code) }
    }
    override fun toString() = "ManualActionTrace(redacted)"
}

/** Exact short-circuit order of the real offer guard. First matching reason wins; later getters
 * are not evaluated. This guard concerns a NEW prompt, never the result of an accepted click. */
internal inline fun manualOfferRejection(closed: Boolean, unhealthy: () -> Boolean,
    completed: () -> Boolean, cleanupReady: () -> Boolean, showing: () -> Boolean,
    selected: () -> Boolean): ManualPromptCause? = when {
    closed -> ManualPromptCause.OFFER_CLOSED
    unhealthy() -> ManualPromptCause.OFFER_HEALTH_FAILED
    completed() -> ManualPromptCause.OFFER_COMPLETED_KEY
    !cleanupReady() -> ManualPromptCause.OFFER_CLEANUP_NOT_READY
    showing() -> ManualPromptCause.OFFER_OVERLAY_SHOWING
    selected() -> ManualPromptCause.OFFER_SELECTION_PENDING
    else -> null
}
