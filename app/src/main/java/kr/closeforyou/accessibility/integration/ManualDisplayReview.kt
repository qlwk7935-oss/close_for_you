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
    NONE, CURRENTNESS_FAILED, TIME_LIMIT, NATIVE_ID_UNAVAILABLE, NATIVE_ID_CHANGED, BEFORE_SET_UNCONFIRMED, LIVE_SET_UNCONFIRMED, REGISTRATION_CHANGED,
    SCREEN_READ_UNAVAILABLE, SCREEN_RESOURCE_FAILURE, WINDOW_SCOPE, TARGET_EQUALS_NATIVE, WINDOW_LIST_UNCONFIRMED,
    OVERLAY_WINDOW_UNAVAILABLE, TARGET_WINDOW_UNAVAILABLE, OVERLAY_LAYER, COMPETING_WINDOW, ACTIVE_OR_FOCUS_UNCONFIRMED,
    INCOMPLETE, INVALID_GEOMETRY,
    PACKAGE_CHANGED, WINDOW_CHANGED, FRAME_CHANGED, NODES_CHANGED, WINDOW_SET_CHANGED,
    ORIGINAL_WINDOW_UNAVAILABLE, PROTECTED_CONTENT, CANCELLED, EXCEPTION, UNKNOWN,
}
/** Field type only; no node index/path/value/identifier is retained or printed. */
internal enum class ManualNodeMismatch {
    NOT_CHECKED, NONE, NODE_COUNT, PATH_OR_ORDER, VIEW_ID, CLASS_NAME, TEXT, CONTENT_DESCRIPTION,
    CLICKABLE, ENABLED, BOUNDS, ACTIONS, LIMIT_EXCEEDED, UNKNOWN,
}
/** Pure diagnostic on already frozen DTOs. Counts first, then positional node order and DTO
 * declaration field order. Never used for equality/authorization; bounded to the existing 1200. */
internal fun firstManualNodeMismatch(original: List<NodeSnapshot>, fresh: List<NodeSnapshot>): ManualNodeMismatch {
    if (original.size != fresh.size) return ManualNodeMismatch.NODE_COUNT
    if (original.size > 1_200) return ManualNodeMismatch.LIMIT_EXCEEDED
    for (i in original.indices) {
        val a = original[i]; val b = fresh[i]
        when {
            a.path != b.path -> return ManualNodeMismatch.PATH_OR_ORDER
            a.viewId != b.viewId -> return ManualNodeMismatch.VIEW_ID
            a.className != b.className -> return ManualNodeMismatch.CLASS_NAME
            a.text != b.text -> return ManualNodeMismatch.TEXT
            a.contentDescription != b.contentDescription -> return ManualNodeMismatch.CONTENT_DESCRIPTION
            a.clickable != b.clickable -> return ManualNodeMismatch.CLICKABLE
            a.enabled != b.enabled -> return ManualNodeMismatch.ENABLED
            a.bounds != b.bounds -> return ManualNodeMismatch.BOUNDS
            a.actions != b.actions -> return ManualNodeMismatch.ACTIONS
        }
    }
    return ManualNodeMismatch.NONE
}

/** Comparison/ownership diagnostics only. First failure survives later exceptions and cleanup. */
internal class ManualDisplayProbe(private val nodeDiagnostics: Boolean = false) {
    var listing = ManualDisplayListing.NOT_CHECKED
    var ownedRead = ManualOwnedReadCode()
    var point = ManualDisplayPoint.NOT_CHECKED
    private var firstPoint: ManualDisplayPoint? = null
    private var firstOwned: ManualOwnedReadCode? = null
    private var firstListing: ManualDisplayListing? = null
    private var firstNodes: ManualNodeMismatch? = null
    private var nodes = ManualNodeMismatch.NOT_CHECKED
    val failureNodes get() = firstNodes ?: nodes
    fun nodesChanged(original: List<NodeSnapshot>, fresh: List<NodeSnapshot>) {
        if (!nodeDiagnostics || detail != ManualDisplayDetail.NONE || nodes != ManualNodeMismatch.NOT_CHECKED) return
        nodes = ManualNodeMismatch.UNKNOWN
        kr.closeforyou.accessibility.diagnosticWrite {
            val first = firstManualNodeMismatch(original, fresh)
            nodes = if (first == ManualNodeMismatch.NONE) ManualNodeMismatch.UNKNOWN else first
        }
    }
    val failureListing get() = firstListing ?: listing
    val failureOwned get() = firstOwned ?: ownedRead
    var detail = ManualDisplayDetail.NONE; private set
    val failurePoint get() = firstPoint ?: point
    fun reject(value: ManualDisplayDetail) {
        if (detail == ManualDisplayDetail.NONE) { detail = value; firstPoint = point; firstOwned = ownedRead; firstListing = listing; firstNodes = nodes }
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
internal enum class ManualDisplayListing { NOT_CHECKED, LISTED, NOT_ENUMERATED }
/** Native identity plus an observed enumeration state. NOT_ENUMERATED is NOT remote ownership. */
internal data class ManualDisplayIdentity(val window: Int, val listing: ManualDisplayListing) {
    override fun toString() = "ManualDisplayIdentity(redacted)"
}

/** Strict legacy ownership helper. Empty/malformed/uncertain live reads still fail here. */
internal fun manualDisplayOwnedId(native: () -> Int?, before: (Int) -> Boolean?, live: () -> Set<Int>?,
    probe: ManualDisplayProbe): Int? = readDisplayIdentity(native, before, live, probe, false)?.window

/** One native generation's initial registration only. Once remotely listed, absence is never
 * reinterpreted as creation. This latch is not an event exemption or an execution capability. */
internal class ManualDisplayRegistration {
    private var generation = 0L
    private var listed = false
    private var invalid = false
    fun created(value: Long) {
        if (invalid || value <= generation) { invalid = true; return }
        generation = value; listed = false
    }
    fun confirmed(value: Long) { if (!invalid && value == generation) listed = true }
    fun read(value: Long, native: () -> Int?, before: (Int) -> Boolean?, live: () -> Set<Int>?,
        probe: ManualDisplayProbe): ManualDisplayIdentity? {
        if (invalid || value <= 0 || value != generation) { probe.reject(ManualDisplayDetail.NATIVE_ID_UNAVAILABLE); return null }
        return readDisplayIdentity(native, before, live, probe, !listed).also {
            if (it?.listing == ManualDisplayListing.LISTED) listed = true
        }
    }
}
private fun readDisplayIdentity(native: () -> Int?, before: (Int) -> Boolean?, live: () -> Set<Int>?,
    probe: ManualDisplayProbe, initial: Boolean): ManualDisplayIdentity? {
    probe.listing = ManualDisplayListing.NOT_CHECKED
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
    if (only == id) {
        probe.listing = ManualDisplayListing.LISTED
        return ManualDisplayIdentity(id, ManualDisplayListing.LISTED)
    }
    // This is a request for an independent, STRICT target read with the native id ABSENT.
    // Root/title/owner/release failure cannot use this route, nor can an already-listed View.
    if (initial && ids?.isEmpty() == true && probe.ownedRead.query == ManualOwnedQuery.COMPLETE &&
        probe.ownedRead.scan == OverlayOwnedScanDetail.NO_ELIGIBLE_OVERLAY) {
        probe.listing = ManualDisplayListing.NOT_ENUMERATED
        return ManualDisplayIdentity(id, ManualDisplayListing.NOT_ENUMERATED)
    }
    probe.reject(ManualDisplayDetail.LIVE_SET_UNCONFIRMED)
    return null
}
/** Actual Android checkpoint/native-before/whole-screen/native-after order shared with JVM.
 * Registration changes during this single bounded read fail; no retry or deadline renewal. */
internal fun verifyRegisteredManualDisplay(probe: ManualDisplayProbe, checkpoint: () -> Unit,
    identity: () -> ManualDisplayIdentity?, verify: (ManualDisplayIdentity) -> ManualDisplayVerdict): ManualDisplayVerdict =
    checkedManualDisplay(probe) {
        checkpoint()
        probe.point = ManualDisplayPoint.NATIVE_BEFORE
        val first = identity() ?: return@checkedManualDisplay ManualDisplayVerdict.UNAVAILABLE
        checkpoint()
        probe.point = ManualDisplayPoint.SCREEN
        val verdict = verify(first)
        checkpoint()
        probe.point = ManualDisplayPoint.NATIVE_AFTER
        val after = identity()
        if (after != first) {
            probe.reject(if (after?.window != first.window) ManualDisplayDetail.NATIVE_ID_CHANGED else ManualDisplayDetail.REGISTRATION_CHANGED)
            return@checkedManualDisplay ManualDisplayVerdict.UNAVAILABLE
        }
        checkpoint()
        verdict
    }
internal inline fun checkedManualDisplay(probe: ManualDisplayProbe, verify: () -> ManualDisplayVerdict): ManualDisplayVerdict =
    try { verify() }
    catch (cancelled: kotlinx.coroutines.CancellationException) { probe.reject(ManualDisplayDetail.CANCELLED); throw cancelled }
    catch (_: Exception) { probe.reject(ManualDisplayDetail.EXCEPTION); ManualDisplayVerdict.UNAVAILABLE }
internal data class ManualDisplayReviewCode(val kind: ManualEventKind, val mask: ManualWindowMask,
    val verdict: ManualDisplayVerdict, val point: ManualDisplayPoint = ManualDisplayPoint.NOT_CHECKED,
    val detail: ManualDisplayDetail = ManualDisplayDetail.NONE, val owned: ManualOwnedReadCode = ManualOwnedReadCode(),
    val listing: ManualDisplayListing = ManualDisplayListing.NOT_CHECKED,
    val nodes: ManualNodeMismatch = ManualNodeMismatch.NOT_CHECKED)

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
    fun target(windows: List<WindowSnapshot>, targetId: Int, nativeOverlayId: Int, probe: ManualDisplayProbe? = null,
        listing: ManualDisplayListing = ManualDisplayListing.LISTED): WindowSnapshot? {
        fun unavailable(detail: ManualDisplayDetail): WindowSnapshot? { probe?.reject(detail); return null }
        if (targetId == nativeOverlayId) return unavailable(ManualDisplayDetail.TARGET_EQUALS_NATIVE)
        if (windows.size !in (if (listing == ManualDisplayListing.NOT_ENUMERATED) 1..32 else 2..32) || windows.map { it.id }.toSet().size != windows.size) return unavailable(ManualDisplayDetail.WINDOW_LIST_UNCONFIRMED)
        if (listing == ManualDisplayListing.NOT_ENUMERATED) {
            if (windows.any { it.id == nativeOverlayId }) return unavailable(ManualDisplayDetail.REGISTRATION_CHANGED)
            val target = windows.singleOrNull { it.id == targetId && it.type == WindowType.APPLICATION }
                ?: return unavailable(ManualDisplayDetail.TARGET_WINDOW_UNAVAILABLE)
            if (windows.any { it.type == WindowType.INPUT_METHOD || it.type == WindowType.ACCESSIBILITY_OVERLAY ||
                    ((it.active || it.focused) && it.id != targetId) }) return unavailable(ManualDisplayDetail.COMPETING_WINDOW)
            if (!target.active || !target.focused) return unavailable(ManualDisplayDetail.ACTIVE_OR_FOCUS_UNCONFIRMED)
            return target // Must still pass full F0-like scan, endpoint and EXACT original comparison.
        }
        val overlay = windows.singleOrNull { it.id == nativeOverlayId && it.type == WindowType.ACCESSIBILITY_OVERLAY } ?: return unavailable(ManualDisplayDetail.OVERLAY_WINDOW_UNAVAILABLE)
        val target = windows.singleOrNull { it.id == targetId && it.type == WindowType.APPLICATION } ?: return unavailable(ManualDisplayDetail.TARGET_WINDOW_UNAVAILABLE)
        if (overlay.layer <= target.layer) return unavailable(ManualDisplayDetail.OVERLAY_LAYER)
        if (windows.any { it.type == WindowType.INPUT_METHOD ||
                (it.type == WindowType.ACCESSIBILITY_OVERLAY && it.id != nativeOverlayId) ||
                ((it.active || it.focused) && it.id != targetId && it.id != nativeOverlayId) }) return unavailable(ManualDisplayDetail.COMPETING_WINDOW)
        if (!(target.active || overlay.active) || !(target.focused || overlay.focused)) return unavailable(ManualDisplayDetail.ACTIVE_OR_FOCUS_UNCONFIRMED)
        return target
    }
    fun assess(original: PopupSnapshot, fresh: F9Scene, nativeOverlayId: Int, probe: ManualDisplayProbe? = null,
        listing: ManualDisplayListing = ManualDisplayListing.LISTED): ManualDisplayVerdict {
        probe?.point = ManualDisplayPoint.COMPARISON
        fun reject(detail: ManualDisplayDetail, verdict: ManualDisplayVerdict = ManualDisplayVerdict.UNAVAILABLE): ManualDisplayVerdict {
            probe?.reject(detail); return verdict
        }
        if (original.completeness != SnapshotCompleteness.COMPLETE || !fresh.complete) return reject(ManualDisplayDetail.INCOMPLETE)
        val current = target(fresh.windows, original.token.windowId, nativeOverlayId, probe, listing) ?: return ManualDisplayVerdict.UNAVAILABLE
        if (!F9AutoCloser.contains(fresh.screen, current.bounds) || current.bounds == fresh.screen ||
            fresh.nodes.isEmpty() || fresh.nodes.any { !F9AutoCloser.contains(fresh.screen, it.bounds) })
            return reject(ManualDisplayDetail.INVALID_GEOMETRY)
        if (original.windows.any { it.id == nativeOverlayId }) return reject(ManualDisplayDetail.WINDOW_SET_CHANGED, ManualDisplayVerdict.CHANGED)
        if (fresh.packageName != original.token.packageName) return reject(ManualDisplayDetail.PACKAGE_CHANGED, ManualDisplayVerdict.CHANGED)
        if (fresh.windowId != original.token.windowId) return reject(ManualDisplayDetail.WINDOW_CHANGED, ManualDisplayVerdict.CHANGED)
        if (fresh.screen != original.screenBounds) return reject(ManualDisplayDetail.FRAME_CHANGED, ManualDisplayVerdict.CHANGED)
        if (fresh.nodes != original.nodes) {
            probe?.nodesChanged(original.nodes, fresh.nodes)
            return reject(ManualDisplayDetail.NODES_CHANGED, ManualDisplayVerdict.CHANGED)
        }
        val expected = original.windows.singleOrNull { it.id == original.token.windowId } ?: return reject(ManualDisplayDetail.ORIGINAL_WINDOW_UNAVAILABLE)
        // C alone may own active/input focus. Only those flags on the SAME application window
        // may differ; bounds/type/layer/other windows and the entire target tree must agree.
        val withoutC = if (listing == ManualDisplayListing.NOT_ENUMERATED) fresh.windows else fresh.windows.filter { it.id != nativeOverlayId }.map {
            if (it.id == current.id) it.copy(active = expected.active, focused = expected.focused) else it
        }
        val sameWindows = if (listing == ManualDisplayListing.NOT_ENUMERATED) withoutC == original.windows
            else withoutC.toSet() == original.windows.toSet()
        if (!sameWindows) return reject(ManualDisplayDetail.WINDOW_SET_CHANGED, ManualDisplayVerdict.CHANGED)
        val content = fresh.nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }.joinToString("\n")
        if (F9ContentSafety.protectedPackage(fresh.packageName) || F9ContentSafety.blocked(content)) return reject(ManualDisplayDetail.PROTECTED_CONTENT)
        return ManualDisplayVerdict.SAME
    }
}
