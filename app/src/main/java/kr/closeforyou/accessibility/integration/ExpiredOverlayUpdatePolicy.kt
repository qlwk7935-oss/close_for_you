package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent
import kr.closeforyou.ui.voice.overlay.OverlayWindowState

/** Display-only housekeeping after TTL. Never creates a binding or supplies an evidence token.
 * Only the already-bound, uniquely live C window's content/bounds update is excluded. Focus,
 * touch, scroll, removal, target/system events and uncertain ownership keep normal invalidation.
 */
internal inline fun inspectExpiredOverlayUpdate(
    state: OverlayWindowState,
    retainedDisplay: Boolean,
    eventType: Int,
    windowChanges: () -> Int,
    eventPackage: () -> String?,
    ownPackage: String,
    eventWindow: () -> Int,
    boundWindow: () -> Int?,
    liveOwnedWindows: () -> Set<Int>?,
): Boolean {
    if (!retainedDisplay || !OwnedOverlayEventPolicy.mayTrack(state)) return false
    val layout = eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && !layout) return false
    if (layout && windowChanges() != AccessibilityEvent.WINDOWS_CHANGE_BOUNDS) return false
    val pkg = eventPackage()
    if (pkg != ownPackage && !(layout && pkg == null)) return false
    val bound = boundWindow() ?: return false
    if (bound < 0 || eventWindow() != bound) return false
    // A second candidate or a failed query is uncertainty, not proof of the same display window.
    return liveOwnedWindows()?.singleOrNull() == bound
}
