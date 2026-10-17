package kr.closeforyou.accessibility

import kr.closeforyou.accessibility.testing.DetectionTestSession
import kr.closeforyou.core.model.PrivacyBlockReason

/** Observation only. Cannot set settings/tokens, return evidence, or authorize capture. */
internal object DetectionObservationHook {
    @Volatile var session: DetectionTestSession? = null
    fun policyChanged() { session?.close(); session = null }
    fun event(target: String?, windowId: Int, input: DetectionInput) { session?.event(target, windowId, input) }
    fun assessed(target: String, windowId: Int, result: DetectionAssessment) { session?.assessed(target, windowId, result) }
    fun blocked(target: String?, windowId: Int, reason: PrivacyBlockReason, diagnostic: CollectionProtectionDiagnostic? = null) {
        session?.blocked(target, windowId, reason, diagnostic)
    }
}
