package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.coroutines.CancellationException

internal enum class ManualRetirementCheck { EVENT_TYPE, CHOICE, DISPLAY, PACKAGE, TIME, NATIVE, ABSENCE, READ_FAILED, COMPATIBLE }
internal data class ManualRetirementCode(val kind: ManualEventKind, val check: ManualRetirementCheck)

/** Framework window-change notifications may omit package metadata. This exception requires
 * THIS View's completed native removal AND independently queried full-list absence; it never
 * applies to clicks/content, or a non-null foreign package. Still not a cleanup acknowledgement. */
internal fun retirementPackageConfirmed(type: Int, own: Boolean, missing: Boolean,
    nativeGoneAndAbsent: () -> Boolean): Boolean = own ||
        (type == E.TYPE_WINDOWS_CHANGED && missing && nativeGoneAndAbsent())

/** Transient notifications of a selected C window being removed. No ADDED, REMOVED/mixed
 * structural masks, touches, scrolls or unknown event types are treated as compatible. */
internal fun nativeRetirementTransientEvent(type: Int, changes: Int): Boolean = when (type) {
    E.TYPE_VIEW_CLICKED, E.TYPE_VIEW_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
    E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, E.TYPE_WINDOW_CONTENT_CHANGED, E.TYPE_WINDOW_STATE_CHANGED -> true
    E.TYPE_WINDOWS_CHANGED -> {
        val transient = E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_LAYER or E.WINDOWS_CHANGE_TITLE or
            E.WINDOWS_CHANGE_ACTIVE or E.WINDOWS_CHANGE_FOCUSED or E.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED
        changes != 0 && changes and transient.inv() == 0
    }
    else -> false
}

/** Shared with the real event driver. COMPATIBLE preserves a pending explicit intent only;
 * it is not removal evidence or permission to collect/press. The barrier separately requires
 * actual native removal completion plus full window absence and the unchanged deadline. */
internal fun inspectManualRetirementEvent(type: Int, changes: Int, choice: () -> Boolean,
    hidden: () -> Boolean, packageMatches: () -> Boolean, timeMatches: () -> Boolean,
    nativeRegistered: () -> Boolean, absenceIfReleased: () -> Boolean): ManualRetirementCode {
    val kind = when (type) {
        E.TYPE_VIEW_CLICKED -> ManualEventKind.CLICK
        E.TYPE_VIEW_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> ManualEventKind.FOCUS
        E.TYPE_WINDOW_CONTENT_CHANGED -> ManualEventKind.CONTENT
        E.TYPE_WINDOW_STATE_CHANGED -> ManualEventKind.WINDOW_STATE
        E.TYPE_WINDOWS_CHANGED -> ManualEventKind.WINDOW_SET
        else -> ManualEventKind.OTHER
    }
    fun rejected(check: ManualRetirementCheck) = ManualRetirementCode(kind, check)
    return try {
        if (!nativeRetirementTransientEvent(type, changes)) return rejected(ManualRetirementCheck.EVENT_TYPE)
        if (!choice()) return rejected(ManualRetirementCheck.CHOICE)
        if (!hidden()) return rejected(ManualRetirementCheck.DISPLAY)
        if (!packageMatches()) return rejected(ManualRetirementCheck.PACKAGE)
        if (!timeMatches()) return rejected(ManualRetirementCheck.TIME)
        if (!nativeRegistered()) return rejected(ManualRetirementCheck.NATIVE)
        if (!absenceIfReleased()) return rejected(ManualRetirementCheck.ABSENCE)
        ManualRetirementCode(kind, ManualRetirementCheck.COMPATIBLE)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { rejected(ManualRetirementCheck.READ_FAILED) }
}
