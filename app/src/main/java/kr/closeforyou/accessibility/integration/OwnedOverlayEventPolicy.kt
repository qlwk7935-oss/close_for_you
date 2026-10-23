package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus

/** An event exclusion is UI bookkeeping, never evidence that the underlying screen stayed still. */
internal enum class OverlayEventKind { CONTENT, WINDOW_STATE, OWN_WINDOW_LAYOUT, FOCUS, INTERACTION, UNKNOWN }
internal object OwnedOverlayEventPolicy {
    // C may identify a stuck window; A still does not exempt its events or permit choices.
    fun mayTrack(state: OverlayWindowState): Boolean =
        state.status == OverlayWindowStatus.SHOWING && state.generation > 0L
    fun mayIgnore(kind: OverlayEventKind, boundWindow: Boolean, liveOwnership: Boolean,
        packageMatches: Boolean, packageMissing: Boolean, currentToken: Boolean,
        diagnostic: OverlayEventDiagnostic? = null): Boolean {
        if (!boundWindow || !liveOwnership) {
            diagnostic?.bindingRefused(currentToken)
            return false
        }
        if (!currentToken) { diagnostic?.rejected(OverlayEventRejection.CURRENT_UNAVAILABLE); return false }
        if (!(packageMatches || (packageMissing && kind == OverlayEventKind.OWN_WINDOW_LAYOUT))) {
            diagnostic?.rejected(OverlayEventRejection.PACKAGE_UNCONFIRMED)
            return false
        }
        if (kind !in setOf(OverlayEventKind.CONTENT, OverlayEventKind.WINDOW_STATE,
                OverlayEventKind.OWN_WINDOW_LAYOUT, OverlayEventKind.FOCUS)) {
            diagnostic?.rejected(OverlayEventRejection.UNKNOWN)
            return false
        }
        return true
    }
}

/** Same lazy read order as the Android tracker. Inline callbacks retain no framework objects.
 * JVM regressions use this exact driver boundary, including both original windowChanges reads.
 */
internal inline fun inspectOwnedOverlayEvent(
    state: OverlayWindowState,
    eventType: () -> Int,
    windowChanges: () -> Int,
    packageName: () -> String?,
    currentToken: () -> SnapshotToken?,
    liveBinding: (SnapshotToken?, OverlayEventDiagnostic?) -> Boolean,
    ownPackageName: () -> String,
    diagnostic: OverlayEventDiagnostic?,
    crossinline rejectedWindowClass: () -> OverlayEventWindowClass = { OverlayEventWindowClass.UNKNOWN },
    crossinline rejectedEventTime: () -> Long? = { null },
): Boolean {
    if (!OwnedOverlayEventPolicy.mayTrack(state)) return false
    val category = when (eventType()) {
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> OverlayEventCategory.CONTENT_CHANGED
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> OverlayEventCategory.WINDOW_STATE_CHANGED
        AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> OverlayEventCategory.FOCUS
        AccessibilityEvent.TYPE_WINDOWS_CHANGED -> OverlayEventCategory.WINDOWS_CHANGED
        else -> OverlayEventCategory.OTHER
    }
    diagnostic?.begin(category)
    val kind = when (category) {
        OverlayEventCategory.CONTENT_CHANGED -> OverlayEventKind.CONTENT
        OverlayEventCategory.WINDOW_STATE_CHANGED -> OverlayEventKind.WINDOW_STATE
        OverlayEventCategory.FOCUS -> OverlayEventKind.FOCUS
        OverlayEventCategory.WINDOWS_CHANGED -> {
            val harmless = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_BOUNDS or
                AccessibilityEvent.WINDOWS_CHANGE_LAYER or AccessibilityEvent.WINDOWS_CHANGE_TITLE
            if (windowChanges().also { diagnostic?.windowChangesObserved(it) } != 0 &&
                windowChanges().also { diagnostic?.windowChangesObserved(it) } and harmless.inv() == 0)
                OverlayEventKind.OWN_WINDOW_LAYOUT else OverlayEventKind.UNKNOWN
        }
        OverlayEventCategory.OTHER -> OverlayEventKind.INTERACTION
    }
    if (kind == OverlayEventKind.INTERACTION || kind == OverlayEventKind.UNKNOWN) {
        diagnostic?.rejected(if (kind == OverlayEventKind.UNKNOWN) OverlayEventRejection.MASK_UNSUPPORTED
            else OverlayEventRejection.OWNERSHIP_NOT_CHECKED)
        diagnostic?.completed(false, { rejectedEventTime() }) { rejectedWindowClass() }
        return false
    }
    val pkg = packageName()
    val current = currentToken()
    val owns = liveBinding(current, diagnostic)
    val ignored = OwnedOverlayEventPolicy.mayIgnore(kind, owns, owns,
        pkg == ownPackageName(), pkg == null, current != null, diagnostic)
    diagnostic?.completed(ignored, { rejectedEventTime() }) { rejectedWindowClass() }
    return ignored
}

/** Window identity is bound only from a new, uniquely owned window, including delayed add events. */
internal class OwnedOverlayBinding {
    private var before: Set<Int>? = null
    private var token: SnapshotToken? = null
    private var bound: Int? = null
    private var generation = 0L
    fun expect(ownedBeforeShow: Set<Int>?, current: SnapshotToken, currentGeneration: Long) {
        clear()
        if (ownedBeforeShow != null && ownedBeforeShow.size <= 32 && currentGeneration > 0L) {
            before = ownedBeforeShow.toSet(); token = current; generation = currentGeneration
        }
    }
    fun matches(eventWindow: Int, liveOwned: Set<Int>?, current: SnapshotToken?, currentGeneration: Long,
        diagnostic: OverlayEventDiagnostic? = null): Boolean {
        diagnostic?.decisionContext(eventWindow, current?.windowId, currentGeneration)
        val previous = before ?: run {
            diagnostic?.bindingRejected(OverlayBindingRejection.BEFORE_SET_UNAVAILABLE)
            return false
        }
        if (generation <= 0L || generation != currentGeneration || token == null) {
            diagnostic?.bindingRejected(OverlayBindingRejection.BINDING_MISMATCH,
                OverlayBindingDetail.CONTROL_STATE_MISMATCH); return false
        }
        if (token != current) {
            diagnostic?.bindingRejected(OverlayBindingRejection.TOKEN_MISMATCH); return false
        }
        if (liveOwned == null || liveOwned.size > 32) {
            diagnostic?.bindingRejected(OverlayBindingRejection.OWNED_WINDOW_UNCONFIRMED); return false
        }
        val known = bound
        if (known != null) {
            if (eventWindow != known) {
                diagnostic?.bindingRejected(OverlayBindingRejection.BINDING_MISMATCH,
                    OverlayBindingDetail.BOUND_EVENT_WINDOW_MISMATCH)
                return false
            }
            val matches = known in liveOwned
            if (!matches) diagnostic?.bindingRejected(OverlayBindingRejection.BINDING_MISMATCH,
                OverlayBindingDetail.BOUND_WINDOW_NOT_LIVE)
            return matches
        }
        val createdWindows = liveOwned.filter { it !in previous }
        val created = createdWindows.singleOrNull() ?: run {
            diagnostic?.ownedWindowUnconfirmed(if (createdWindows.isEmpty()) OverlayOwnedWindowDetail.NO_NEW_WINDOW
                else OverlayOwnedWindowDetail.MULTIPLE_NEW_WINDOWS)
            return false
        }
        if (eventWindow != created) {
            diagnostic?.bindingRejected(OverlayBindingRejection.BINDING_MISMATCH,
                OverlayBindingDetail.NEW_EVENT_WINDOW_MISMATCH); return false
        }
        bound = created
        diagnostic?.boundConfirmed(created, currentGeneration, current?.windowId)
        return true
    }
    fun clear() { before = null; token = null; bound = null; generation = 0L }
    fun boundWindowFor(currentGeneration: Long): Int? = if (generation == currentGeneration && generation > 0) bound else null
    override fun toString() = "OwnedOverlayBinding(redacted)"
}
