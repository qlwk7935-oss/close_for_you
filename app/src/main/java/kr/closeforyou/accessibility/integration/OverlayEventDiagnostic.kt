package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.diagnosticWrite

internal enum class OverlayEventCategory { WINDOWS_CHANGED, FOCUS, CONTENT_CHANGED, WINDOW_STATE_CHANGED, OTHER }
internal enum class OverlayEventRejection {
    MASK_UNSUPPORTED, OWNERSHIP_NOT_CHECKED, BEFORE_SET_UNAVAILABLE, OWNED_WINDOW_UNCONFIRMED,
    BINDING_MISMATCH, PACKAGE_UNCONFIRMED, CURRENT_UNAVAILABLE, UNKNOWN,
}
internal enum class OverlayBindingRejection {
    NONE, BEFORE_SET_UNAVAILABLE, OWNED_WINDOW_UNCONFIRMED, BINDING_MISMATCH, TOKEN_MISMATCH,
}
internal enum class OverlayBindingDetail {
    CONTROL_STATE_MISMATCH, NONNULL_TOKEN_MISMATCH, BOUND_EVENT_WINDOW_MISMATCH,
    BOUND_WINDOW_NOT_LIVE, NEW_EVENT_WINDOW_MISMATCH, UNKNOWN,
}

/** Debug observation only: at most one rejected event per actual successful presentation.
 * No IDs, tokens, windows, text, clock, exceptions, collection or policy authority are retained.
 */
internal class OverlayEventDiagnostic(
    private val write: (OverlayEventCategory, OverlayEventRejection, OverlayBindingDetail) -> Unit,
) {
    private var armed = false
    private var reported = false
    private var category = OverlayEventCategory.OTHER
    private var reason = OverlayEventRejection.UNKNOWN
    private var bindingFailure = OverlayBindingRejection.NONE
    private var bindingDetail = OverlayBindingDetail.UNKNOWN

    fun arm() { armed = true; reported = false }
    fun disarm() { armed = false }
    fun begin(event: OverlayEventCategory) {
        category = event; reason = OverlayEventRejection.UNKNOWN; bindingFailure = OverlayBindingRejection.NONE
        bindingDetail = OverlayBindingDetail.UNKNOWN
    }
    fun bindingRejected(value: OverlayBindingRejection, detail: OverlayBindingDetail = OverlayBindingDetail.UNKNOWN) {
        bindingFailure = value; bindingDetail = detail
    }
    fun rejected(value: OverlayEventRejection) { reason = value }
    fun bindingRefused(currentAvailable: Boolean) {
        reason = when (bindingFailure) {
            OverlayBindingRejection.BEFORE_SET_UNAVAILABLE -> OverlayEventRejection.BEFORE_SET_UNAVAILABLE
            OverlayBindingRejection.OWNED_WINDOW_UNCONFIRMED -> OverlayEventRejection.OWNED_WINDOW_UNCONFIRMED
            OverlayBindingRejection.BINDING_MISMATCH -> OverlayEventRejection.BINDING_MISMATCH
            OverlayBindingRejection.TOKEN_MISMATCH -> if (currentAvailable)
                OverlayEventRejection.BINDING_MISMATCH else OverlayEventRejection.CURRENT_UNAVAILABLE
            OverlayBindingRejection.NONE -> OverlayEventRejection.UNKNOWN
        }
        if (bindingFailure == OverlayBindingRejection.TOKEN_MISMATCH && currentAvailable)
            bindingDetail = OverlayBindingDetail.NONNULL_TOKEN_MISMATCH
    }
    fun completed(ignored: Boolean) {
        if (!armed || reported || ignored) return
        reported = true // A failed sink is never retried on later events.
        diagnosticWrite { write(category, reason,
            if (reason == OverlayEventRejection.BINDING_MISMATCH) bindingDetail else OverlayBindingDetail.UNKNOWN) }
    }
    override fun toString() = "OverlayEventDiagnostic(redacted)"
}
