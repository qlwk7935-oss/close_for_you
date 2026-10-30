package kr.closeforyou.accessibility

import kr.closeforyou.accessibility.testing.DetectionTestSession
import kr.closeforyou.core.model.PrivacyBlockReason

internal class DetectionAttempt(val owner: DetectionTestSession, val ticket: DetectionTestSession.Attempt) {
    override fun toString() = "DetectionAttempt(redacted)"
}

/** Observation only. Cannot set settings/tokens, return evidence, or authorize capture. */
internal object DetectionObservationHook {
    @Volatile var session: DetectionTestSession? = null
    fun policyChanged() { session?.close(); session = null }
    fun event(target: String?, windowId: Int, input: DetectionInput) { session?.event(target, windowId, input) }
    fun beginAttempt(): DetectionAttempt? = session?.let { owner -> owner.beginAttempt()?.let { DetectionAttempt(owner, it) } }
    fun assessed(target: String, windowId: Int, result: DetectionAssessment, attempt: DetectionAttempt?) {
        attempt?.owner?.assessed(target, windowId, result, attempt.ticket)
    }
    fun blocked(target: String?, windowId: Int, reason: PrivacyBlockReason, diagnostic: CollectionProtectionDiagnostic?, attempt: DetectionAttempt?) {
        attempt?.owner?.blocked(target, windowId, reason, diagnostic, attempt.ticket)
    }
}
