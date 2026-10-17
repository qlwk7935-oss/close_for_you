package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason

/** No synthetic session, registration, IPC, text or image inspection in release. */
@Suppress("UNUSED_PARAMETER")
internal object DetectionObservationHook {
    fun policyChanged() = Unit
    fun event(target: String?, windowId: Int, input: DetectionInput) = Unit
    fun assessed(target: String, windowId: Int, result: DetectionAssessment) = Unit
    fun blocked(target: String?, windowId: Int, reason: PrivacyBlockReason) = Unit
}
