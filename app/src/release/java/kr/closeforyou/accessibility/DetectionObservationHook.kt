package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason

internal class DetectionAttempt private constructor()

/** No synthetic session, registration, IPC, text or image inspection in release. */
@Suppress("UNUSED_PARAMETER")
internal object DetectionObservationHook {
    fun policyChanged() = Unit
    fun event(target: String?, windowId: Int, input: DetectionInput) = Unit
    fun beginAttempt(): DetectionAttempt? = null
    fun assessed(target: String, windowId: Int, result: DetectionAssessment, attempt: DetectionAttempt?) = Unit
    fun blocked(target: String?, windowId: Int, reason: PrivacyBlockReason, diagnostic: CollectionProtectionDiagnostic?, attempt: DetectionAttempt?) = Unit
}
