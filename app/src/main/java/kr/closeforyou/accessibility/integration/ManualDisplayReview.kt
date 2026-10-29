package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.accessibility.CollectionCleanupFailure
import kr.closeforyou.accessibility.action.F9ContentSafety
import kr.closeforyou.accessibility.action.F9AutoCloser
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.*

internal enum class ManualDisplayVerdict { SAME, CHANGED, UNAVAILABLE }
internal enum class ManualDisplayPoint { NOT_CHECKED, CHECKPOINT, NATIVE_BEFORE, SCREEN, NATIVE_AFTER, COMPARISON }
internal enum class ManualDisplayDetail {
    NONE, CURRENTNESS_FAILED, TIME_LIMIT, NATIVE_ID_UNAVAILABLE, NATIVE_ID_CHANGED, BEFORE_SET_UNCONFIRMED, LIVE_SET_UNCONFIRMED,
    SCREEN_READ_UNAVAILABLE, SCREEN_RESOURCE_FAILURE, WINDOW_SCOPE, TARGET_EQUALS_NATIVE, WINDOW_LIST_UNCONFIRMED,
    OVERLAY_WINDOW_UNAVAILABLE, TARGET_WINDOW_UNAVAILABLE, OVERLAY_LAYER, COMPETING_WINDOW, ACTIVE_OR_FOCUS_UNCONFIRMED,
    INCOMPLETE, INVALID_GEOMETRY,
    PACKAGE_CHANGED, WINDOW_CHANGED, FRAME_CHANGED, NODES_CHANGED, WINDOW_SET_CHANGED,
    ORIGINAL_WINDOW_UNAVAILABLE, PROTECTED_CONTENT, CANCELLED, EXCEPTION, UNKNOWN,
}
/** Comparison/ownership diagnostics only. First failure survives later exceptions and cleanup. */
internal class ManualDisplayProbe {
    var ownedRead = ManualOwnedReadCode()
    var point = ManualDisplayPoint.NOT_CHECKED
    private var firstPoint: ManualDisplayPoint? = null
    private var firstOwned: ManualOwnedReadCode? = null
    val failureOwned get() = firstOwned ?: ownedRead
    var detail = ManualDisplayDetail.NONE; private set
    val failurePoint get() = firstPoint ?: point
    fun reject(value: ManualDisplayDetail) {
        if (detail == ManualDisplayDetail.NONE) { detail = value; firstPoint = point; firstOwned = ownedRead }
    }
    fun require(condition: Boolean, value: ManualDisplayDetail) {
        if (!condition) { reject(value); check(false) { "MANUAL_DISPLAY_UNAVAILABLE" } }
    }
    fun screenUnavailable(resourceFailure: Boolean) {
        point = ManualDisplayPoint.SCREEN
        reject(if (resourceFailure) ManualDisplayDetail.SCREEN_RESOURCE_FAILURE else ManualDisplayDetail.SCREEN_READ_UNAVAILABLE)
    }
    override fun toString() = "ManualDisplayProbe(redacted)"
}
/** Shared real checkpoint: preserve original currentness-first order and single clock read. */
internal fun manualDisplayCheckpoint(current: () -> Boolean, now: () -> Long, started: Long, probe: ManualDisplayProbe) {
    val previous = probe.point
    probe.point = ManualDisplayPoint.CHECKPOINT
    probe.require(current(), ManualDisplayDetail.CURRENTNESS_FAILED)
    val at = now()
    probe.require(at >= started && at - started < 100L, ManualDisplayDetail.TIME_LIMIT)
    probe.point = previous
}
/** Same native -> saved-before -> owned-live short circuit as the Android driver. No new reads. */
internal fun manualDisplayOwnedId(native: () -> Int?, before: (Int) -> Boolean?, live: () -> Set<Int>?,
    probe: ManualDisplayProbe): Int? {
    val id = native()
    if (id == null || id < 0) { probe.reject(ManualDisplayDetail.NATIVE_ID_UNAVAILABLE); return null }
    if (before(id) != false) { probe.reject(ManualDisplayDetail.BEFORE_SET_UNCONFIRMED); return null }
    val ids = live()
    val only = ids?.singleOrNull()
    val relation = if (ids?.size == 1) {
        if (only == id) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH
    } else OwnershipRelation.UNAVAILABLE
    probe.ownedRead = probe.ownedRead.copy(count = when (ids?.size) {
        null -> ManualOwnedCount.UNAVAILABLE; 0 -> ManualOwnedCount.NONE; 1 -> ManualOwnedCount.ONE; else -> ManualOwnedCount.MULTIPLE
    }, native = relation)
    if (only != id) { probe.reject(ManualDisplayDetail.LIVE_SET_UNCONFIRMED); return null }
    return id
}
internal inline fun checkedManualDisplay(probe: ManualDisplayProbe, verify: () -> ManualDisplayVerdict): ManualDisplayVerdict =
    try { verify() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { probe.reject(ManualDisplayDetail.CANCELLED); throw cancelled }
    catch (_: Exception) { probe.reject(ManualDisplayDetail.EXCEPTION); ManualDisplayVerdict.UNAVAILABLE }
internal data class ManualDisplayReviewCode(val kind: ManualEventKind, val mask: ManualWindowMask,
    val verdict: ManualDisplayVerdict, val point: ManualDisplayPoint = ManualDisplayPoint.NOT_CHECKED,
    val detail: ManualDisplayDetail = ManualDisplayDetail.NONE, val owned: ManualOwnedReadCode = ManualOwnedReadCode())

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
    fun target(windows: List<WindowSnapshot>, targetId: Int, nativeOverlayId: Int, probe: ManualDisplayProbe? = null): WindowSnapshot? {
        fun unavailable(detail: ManualDisplayDetail): WindowSnapshot? { probe?.reject(detail); return null }
        if (targetId == nativeOverlayId) return unavailable(ManualDisplayDetail.TARGET_EQUALS_NATIVE)
        if (windows.size !in 2..32 || windows.map { it.id }.toSet().size != windows.size) return unavailable(ManualDisplayDetail.WINDOW_LIST_UNCONFIRMED)
        val overlay = windows.singleOrNull { it.id == nativeOverlayId && it.type == WindowType.ACCESSIBILITY_OVERLAY } ?: return unavailable(ManualDisplayDetail.OVERLAY_WINDOW_UNAVAILABLE)
        val target = windows.singleOrNull { it.id == targetId && it.type == WindowType.APPLICATION } ?: return unavailable(ManualDisplayDetail.TARGET_WINDOW_UNAVAILABLE)
        if (overlay.layer <= target.layer) return unavailable(ManualDisplayDetail.OVERLAY_LAYER)
        if (windows.any { it.type == WindowType.INPUT_METHOD ||
                (it.type == WindowType.ACCESSIBILITY_OVERLAY && it.id != nativeOverlayId) ||
                ((it.active || it.focused) && it.id != targetId && it.id != nativeOverlayId) }) return unavailable(ManualDisplayDetail.COMPETING_WINDOW)
        if (!(target.active || overlay.active) || !(target.focused || overlay.focused)) return unavailable(ManualDisplayDetail.ACTIVE_OR_FOCUS_UNCONFIRMED)
        return target
    }
    fun assess(original: PopupSnapshot, fresh: F9Scene, nativeOverlayId: Int, probe: ManualDisplayProbe? = null): ManualDisplayVerdict {
        probe?.point = ManualDisplayPoint.COMPARISON
        fun reject(detail: ManualDisplayDetail, verdict: ManualDisplayVerdict = ManualDisplayVerdict.UNAVAILABLE): ManualDisplayVerdict {
            probe?.reject(detail); return verdict
        }
        if (original.completeness != SnapshotCompleteness.COMPLETE || !fresh.complete) return reject(ManualDisplayDetail.INCOMPLETE)
        val current = target(fresh.windows, original.token.windowId, nativeOverlayId, probe) ?: return ManualDisplayVerdict.UNAVAILABLE
        if (!F9AutoCloser.contains(fresh.screen, current.bounds) || current.bounds == fresh.screen ||
            fresh.nodes.isEmpty() || fresh.nodes.any { !F9AutoCloser.contains(fresh.screen, it.bounds) })
            return reject(ManualDisplayDetail.INVALID_GEOMETRY)
        if (original.windows.any { it.id == nativeOverlayId }) return reject(ManualDisplayDetail.WINDOW_SET_CHANGED, ManualDisplayVerdict.CHANGED)
        if (fresh.packageName != original.token.packageName) return reject(ManualDisplayDetail.PACKAGE_CHANGED, ManualDisplayVerdict.CHANGED)
        if (fresh.windowId != original.token.windowId) return reject(ManualDisplayDetail.WINDOW_CHANGED, ManualDisplayVerdict.CHANGED)
        if (fresh.screen != original.screenBounds) return reject(ManualDisplayDetail.FRAME_CHANGED, ManualDisplayVerdict.CHANGED)
        if (fresh.nodes != original.nodes) return reject(ManualDisplayDetail.NODES_CHANGED, ManualDisplayVerdict.CHANGED)
        val expected = original.windows.singleOrNull { it.id == original.token.windowId } ?: return reject(ManualDisplayDetail.ORIGINAL_WINDOW_UNAVAILABLE)
        // C alone may own active/input focus. Only those flags on the SAME application window
        // may differ; bounds/type/layer/other windows and the entire target tree must agree.
        val withoutC = fresh.windows.filter { it.id != nativeOverlayId }.map {
            if (it.id == current.id) it.copy(active = expected.active, focused = expected.focused) else it
        }
        if (withoutC.toSet() != original.windows.toSet()) return reject(ManualDisplayDetail.WINDOW_SET_CHANGED, ManualDisplayVerdict.CHANGED)
        val content = fresh.nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }.joinToString("\n")
        if (F9ContentSafety.protectedPackage(fresh.packageName) || F9ContentSafety.blocked(content)) return reject(ManualDisplayDetail.PROTECTED_CONTENT)
        return ManualDisplayVerdict.SAME
    }
}
