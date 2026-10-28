package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.action.F9ReadStage
import kr.closeforyou.core.model.*

/** Private run/capture-local diagnostics, never an execution receipt. No Android objects or
 * screen identifiers retained. Only Debug constructs a trace; Release factory returns null. */
internal enum class DelayedTracePoint {
    EVENT_RECEIVED, EVENT_IGNORED, EVENT_REJECTED, CAPTURE_SCHEDULED, CAPTURE_CANCELLED, CAPTURE_REJECTED,
    CAPTURE_BLOCKED, F0_READY, PAGE_READ_STARTED, PAGE_READ_REJECTED, SCREEN_READ_REJECTED, PAGE_VERIFIED,
    F1_NOT_CANDIDATE, POPUP_DETECTED, EVIDENCE_READY, EVIDENCE_BLOCKED, EVIDENCE_CANCELLED,
    EVIDENCE_FAILED, CANDIDATE, PUBLICATION_REJECTED, CANDIDATE_REJECTED,
    B_DECISION, WAITING_FOR_TARGET, REOBSERVED, F9_RESULT,
}
internal enum class DelayedEventKind { WINDOW, CONTENT, FOCUS, TOUCH, SCROLL, CLICK, OTHER }
internal class DelayedAutoTrace(private val sink: (DelayedTracePoint, DelayedEventKind?, F9ReadStage?,
    F9PageObservation?, PrivacyBlockReason?, NextStep?, SafetyReason?) -> Unit) {
    private val emitted = mutableSetOf<DelayedTracePoint>()
    fun record(point: DelayedTracePoint, event: DelayedEventKind? = null, read: F9ReadStage? = null,
        page: F9PageObservation? = null, block: PrivacyBlockReason? = null,
        next: NextStep? = null, reason: SafetyReason? = null) {
        if (emitted.add(point)) kr.closeforyou.accessibility.diagnosticWrite {
            sink(point, event, read, page, block, next, reason)
        }
    }
    override fun toString() = "DelayedAutoTrace(redacted)"
}
