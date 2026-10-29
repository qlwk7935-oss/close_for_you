package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.accessibility.CollectionCleanupFailure
import kr.closeforyou.accessibility.action.F9ContentSafety
import kr.closeforyou.accessibility.action.F9AutoCloser
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.*

internal enum class ManualDisplayVerdict { SAME, CHANGED, UNAVAILABLE }
internal data class ManualDisplayReviewCode(val kind: ManualEventKind, val mask: ManualWindowMask,
    val verdict: ManualDisplayVerdict)

/** A target BOUNDS notification is a request to re-read, not proof that C or the target caused it.
 * Mixed/structural/unknown masks and unrelated windows keep ordinary invalidation. */
internal fun manualDisplayReviewEvent(type: Int, changes: Int = 0, eventWindow: Int = -1,
    targetWindow: Int? = null) = type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
    type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
    (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED && changes == AccessibilityEvent.WINDOWS_CHANGE_BOUNDS &&
        eventWindow >= 0 && eventWindow == targetWindow)

/** The real service's synchronous retire -> read -> retain/remove boundary. A matching display
 * never restores Runtime/F9 evidence. Exceptions/cancellation remove the presentation too, while
 * resource safety preserves the primary failure. No retries, grace period or event-time exemption. */
internal fun reviewManualDisplay(retire: () -> Unit, verify: () -> ManualDisplayVerdict,
    remove: () -> Unit, onChecked: (ManualDisplayVerdict) -> Unit = {}): ManualDisplayVerdict {
    var retain = false
    return CollectionResourceSafety.run(body = {
        val verdict = try { retire(); verify() }
        catch (failure: Exception) {
            kr.closeforyou.accessibility.diagnosticWrite { onChecked(ManualDisplayVerdict.UNAVAILABLE) }
            throw failure
        }
        retain = verdict == ManualDisplayVerdict.SAME
        // Record the verified result BEFORE remove can throw; the sink has no policy authority.
        kr.closeforyou.accessibility.diagnosticWrite { onChecked(verdict) }
        verdict
    }, cleanup = { if (!retain) remove() }, cleanupFailure = { throw CollectionCleanupFailure() })
}

/** Presentation-only comparison. Used inside Android's read-only scan, never by read()/press(),
 * never returned as a Ready, token or eligibility certificate. Original age is not renewed. */
internal object ManualDisplayContinuity {
    fun target(windows: List<WindowSnapshot>, targetId: Int, nativeOverlayId: Int): WindowSnapshot? {
        if (targetId == nativeOverlayId || windows.size !in 2..32 || windows.map { it.id }.toSet().size != windows.size) return null
        val overlay = windows.singleOrNull { it.id == nativeOverlayId && it.type == WindowType.ACCESSIBILITY_OVERLAY } ?: return null
        val target = windows.singleOrNull { it.id == targetId && it.type == WindowType.APPLICATION } ?: return null
        if (overlay.layer <= target.layer || windows.any { it.type == WindowType.INPUT_METHOD ||
                (it.type == WindowType.ACCESSIBILITY_OVERLAY && it.id != nativeOverlayId) ||
                ((it.active || it.focused) && it.id != targetId && it.id != nativeOverlayId) }) return null
        if (!(target.active || overlay.active) || !(target.focused || overlay.focused)) return null
        return target
    }
    fun assess(original: PopupSnapshot, fresh: F9Scene, nativeOverlayId: Int): ManualDisplayVerdict {
        if (original.completeness != SnapshotCompleteness.COMPLETE || !fresh.complete) return ManualDisplayVerdict.UNAVAILABLE
        val current = target(fresh.windows, original.token.windowId, nativeOverlayId) ?: return ManualDisplayVerdict.UNAVAILABLE
        if (!F9AutoCloser.contains(fresh.screen, current.bounds) || current.bounds == fresh.screen ||
            fresh.nodes.isEmpty() || fresh.nodes.any { !F9AutoCloser.contains(fresh.screen, it.bounds) })
            return ManualDisplayVerdict.UNAVAILABLE
        if (original.windows.any { it.id == nativeOverlayId } || fresh.packageName != original.token.packageName ||
            fresh.windowId != original.token.windowId || fresh.screen != original.screenBounds || fresh.nodes != original.nodes)
            return ManualDisplayVerdict.CHANGED
        val expected = original.windows.singleOrNull { it.id == original.token.windowId } ?: return ManualDisplayVerdict.UNAVAILABLE
        // C alone may own active/input focus. Only those flags on the SAME application window
        // may differ; bounds/type/layer/other windows and the entire target tree must agree.
        val withoutC = fresh.windows.filter { it.id != nativeOverlayId }.map {
            if (it.id == current.id) it.copy(active = expected.active, focused = expected.focused) else it
        }
        if (withoutC.toSet() != original.windows.toSet()) return ManualDisplayVerdict.CHANGED
        val content = fresh.nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }.joinToString("\n")
        if (F9ContentSafety.protectedPackage(fresh.packageName) || F9ContentSafety.blocked(content)) return ManualDisplayVerdict.UNAVAILABLE
        return ManualDisplayVerdict.SAME
    }
}
