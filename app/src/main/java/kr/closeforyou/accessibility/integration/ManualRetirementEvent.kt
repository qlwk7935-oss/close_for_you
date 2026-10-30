package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.coroutines.CancellationException

internal enum class ManualRetirementCheck { EVENT_TYPE, CHOICE, DISPLAY, PACKAGE, TIME, NATIVE, ABSENCE, READ_FAILED, COMPATIBLE, CLEANUP_WAIT }
internal data class ManualRetirementCode(val kind: ManualEventKind, val check: ManualRetirementCheck,
    val mask: ManualWindowMask = ManualWindowMask.NOT_APPLICABLE,
    val display: OwnershipRelation = OwnershipRelation.NOT_CHECKED,
    val pkg: OwnershipRelation = OwnershipRelation.NOT_CHECKED,
    val native: OwnershipRelation = OwnershipRelation.NOT_CHECKED)

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

/** Cleanup-only combination: known REMOVED + teardown metadata, never ADDED/unknown bits.
 * It grants no choice compatibility or receipt. Requires exact native removal completion. */
internal fun nativeCleanupRemovalCombination(type: Int, changes: Int): Boolean {
    val allowed = E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_LAYER or
        E.WINDOWS_CHANGE_TITLE or E.WINDOWS_CHANGE_ACTIVE or E.WINDOWS_CHANGE_FOCUSED or E.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED
    return type == E.TYPE_WINDOWS_CHANGED && changes != E.WINDOWS_CHANGE_REMOVED &&
        changes and E.WINDOWS_CHANGE_REMOVED != 0 && changes and allowed.inv() == 0
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
    var display = OwnershipRelation.NOT_CHECKED
    var pkg = OwnershipRelation.NOT_CHECKED
    var native = OwnershipRelation.NOT_CHECKED
    fun rejected(check: ManualRetirementCheck) = ManualRetirementCode(kind, check,
        if (type == E.TYPE_WINDOWS_CHANGED) manualWindowMask(changes) else ManualWindowMask.NOT_APPLICABLE,
        display, pkg, native)
    return try {
        if (!nativeRetirementTransientEvent(type, changes)) return rejected(ManualRetirementCheck.EVENT_TYPE)
        if (!choice()) return rejected(ManualRetirementCheck.CHOICE)
        if (!hidden().also { display = if (it) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH }) return rejected(ManualRetirementCheck.DISPLAY)
        if (!packageMatches().also { pkg = if (it) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH }) return rejected(ManualRetirementCheck.PACKAGE)
        if (!timeMatches()) return rejected(ManualRetirementCheck.TIME)
        if (!nativeRegistered().also { native = if (it) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH }) return rejected(ManualRetirementCheck.NATIVE)
        if (!absenceIfReleased()) return rejected(ManualRetirementCheck.ABSENCE)
        rejected(ManualRetirementCheck.COMPATIBLE)
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { rejected(ManualRetirementCheck.READ_FAILED) }
}

/** Only prevents a queued notification of THIS retiring C View from poisoning cleanup. Unlike
 * COMPATIBLE this NEVER preserves user intent, event handling, evidence or an execution token.
 * Callers must already be in pending cleanup with the exact event/native ID. No permit here. */
internal fun inspectNativeCleanupRetirement(type: Int, changes: Int, hidden: () -> Boolean,
    packageMatches: () -> Boolean, nativeRegistered: () -> Boolean,
    nativeRemovalComplete: () -> Boolean = { false }): ManualRetirementCode {
    val kind = when (type) {
        E.TYPE_VIEW_CLICKED -> ManualEventKind.CLICK
        E.TYPE_VIEW_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> ManualEventKind.FOCUS
        E.TYPE_WINDOW_CONTENT_CHANGED -> ManualEventKind.CONTENT
        E.TYPE_WINDOW_STATE_CHANGED -> ManualEventKind.WINDOW_STATE
        E.TYPE_WINDOWS_CHANGED -> ManualEventKind.WINDOW_SET
        else -> ManualEventKind.OTHER
    }
    var display = OwnershipRelation.NOT_CHECKED
    var pkg = OwnershipRelation.NOT_CHECKED
    var native = OwnershipRelation.NOT_CHECKED
    fun code(check: ManualRetirementCheck) = ManualRetirementCode(kind, check,
        if (type == E.TYPE_WINDOWS_CHANGED) manualWindowMask(changes) else ManualWindowMask.NOT_APPLICABLE,
        display, pkg, native)
    return try {
        when {
            !nativeRetirementTransientEvent(type, changes) &&
                !(nativeCleanupRemovalCombination(type, changes) && nativeRemovalComplete()) -> code(ManualRetirementCheck.EVENT_TYPE)
            !hidden().also { display = if (it) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH } -> code(ManualRetirementCheck.DISPLAY)
            !packageMatches().also { pkg = if (it) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH } -> code(ManualRetirementCheck.PACKAGE)
            !nativeRegistered().also { native = if (it) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH } -> code(ManualRetirementCheck.NATIVE)
            else -> code(ManualRetirementCheck.CLEANUP_WAIT)
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { code(ManualRetirementCheck.READ_FAILED) }
}
