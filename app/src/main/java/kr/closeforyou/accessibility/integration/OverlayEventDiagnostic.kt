package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.accessibility.diagnosticWrite
import kr.closeforyou.core.model.SnapshotToken

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
internal enum class OverlayEventWindowClass { TARGET_APP, SYSTEM, INPUT_METHOD, OTHER_APP, NOT_IN_LIST, UNKNOWN }

/** Synchronous, diagnostic-only lease. Only the result enum leaves this call; no text/title reads.
 * A different window of the target package is UNKNOWN, never TARGET_APP or OTHER_APP.
 * An empty/unavailable/oversized list is insufficient evidence, rather than NOT_IN_LIST.
 * Generic handles let JVM tests exercise the production read/release boundary without Android mocks.
 */
internal fun <W : Any, N : Any> classifyOverlayEventWindow(
    eventWindowId: Int,
    current: SnapshotToken?,
    readWindows: () -> List<W>?,
    windowId: (W) -> Int,
    windowType: (W) -> Int,
    readRoot: (W) -> N?,
    rootWindowId: (N) -> Int,
    rootPackage: (N) -> String?,
    releaseRoot: (N) -> Unit,
    releaseWindow: (W) -> Unit,
): OverlayEventWindowClass {
    if (eventWindowId < 0 || current == null || current.windowId < 0 || current.packageName.isBlank())
        return OverlayEventWindowClass.UNKNOWN
    if (eventWindowId == current.windowId) return OverlayEventWindowClass.TARGET_APP
    var windows: List<W>? = null
    var root: N? = null
    return try {
        CollectionResourceSafety.run(body = {
            val live = readWindows() ?: return@run OverlayEventWindowClass.UNKNOWN
            windows = live
            if (live.isEmpty() || live.size > 32) return@run OverlayEventWindowClass.UNKNOWN
            var matched: W? = null
            for (window in live) {
                if (windowId(window) != eventWindowId) continue
                if (matched != null) return@run OverlayEventWindowClass.UNKNOWN
                matched = window
            }
            val eventWindow = matched ?: return@run OverlayEventWindowClass.NOT_IN_LIST
            when (windowType(eventWindow)) {
                AccessibilityWindowInfo.TYPE_SYSTEM -> OverlayEventWindowClass.SYSTEM
                AccessibilityWindowInfo.TYPE_INPUT_METHOD -> OverlayEventWindowClass.INPUT_METHOD
                AccessibilityWindowInfo.TYPE_APPLICATION, AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> {
                    val node = readRoot(eventWindow) ?: return@run OverlayEventWindowClass.UNKNOWN
                    root = node
                    if (rootWindowId(node) != eventWindowId) return@run OverlayEventWindowClass.UNKNOWN
                    val pkg = rootPackage(node)
                    if (pkg.isNullOrBlank() || pkg == current.packageName) OverlayEventWindowClass.UNKNOWN
                    else OverlayEventWindowClass.OTHER_APP
                }
                else -> OverlayEventWindowClass.UNKNOWN
            }
        }, cleanup = {
            val batch = CollectionReleaseBatch()
            root?.let { node -> batch.attempt { releaseRoot(node) } }
            val seen = java.util.IdentityHashMap<W, Boolean>()
            windows?.forEach { window -> if (seen.put(window, true) == null) batch.attempt { releaseWindow(window) } }
            batch.finish()
        }, cleanupFailure = { OverlayEventWindowClass.UNKNOWN })
    } catch (_: Exception) { OverlayEventWindowClass.UNKNOWN }
    finally { root = null; windows = null }
}

/** Debug observation only: at most one rejected event per actual successful presentation.
 * No IDs, tokens, windows, text, clock, exceptions, collection or policy authority are retained.
 */
internal class OverlayEventDiagnostic(
    private val write: (OverlayEventCategory, OverlayEventRejection, OverlayBindingDetail, OverlayEventWindowClass) -> Unit,
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
    fun completed(ignored: Boolean, windowClass: () -> OverlayEventWindowClass = { OverlayEventWindowClass.UNKNOWN }) {
        if (!armed || reported || ignored) return
        reported = true // A failed sink is never retried on later events.
        // Freeze fixed codes before framework reads can reenter event inspection.
        val event = category
        val rejection = reason
        val detail = if (rejection == OverlayEventRejection.BINDING_MISMATCH) bindingDetail else OverlayBindingDetail.UNKNOWN
        val classified = try { windowClass() } catch (_: Exception) { OverlayEventWindowClass.UNKNOWN }
        diagnosticWrite { write(event, rejection, detail, classified) }
    }
    override fun toString() = "OverlayEventDiagnostic(redacted)"
}
