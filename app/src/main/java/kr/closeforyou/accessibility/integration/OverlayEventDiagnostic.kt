package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.accessibility.diagnosticWrite
import kr.closeforyou.accessibility.EventCapturePhase
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
internal enum class OverlayWindowChangeKind { LAYER_ONLY, BOUNDS_INCLUDED, ADDED_ONLY, MIXED, UNKNOWN }
/** QUERY_FAILED includes read/release exceptions. UNCERTAIN covers an empty/oversized list or
 * previously unhealthy reader. New-window counts refer only to validated candidates minus before;
 * NO_NEW_WINDOW is not proof that no native C window exists. UNKNOWN means no applicable detail.
 */
internal enum class OverlayOwnedWindowDetail { QUERY_FAILED, NO_NEW_WINDOW, MULTIPLE_NEW_WINDOWS, UNCERTAIN, UNKNOWN }
/** Results of the ORIGINAL ownership predicates, never raw window/node/title values. Mixed
 * eligible candidates cannot identify which one is C. NO_ELIGIBLE_OVERLAY combines type/display. */
internal enum class OverlayOwnedScanDetail {
    NO_ELIGIBLE_OVERLAY, ROOT_UNAVAILABLE, ROOT_RELATION_UNCONFIRMED, TITLE_UNCONFIRMED,
    OWNED_CANDIDATE, MIXED, UNAVAILABLE, UNKNOWN,
}
/** Claimed event package relationship only; NOT authenticated window identity or event cause. */
internal enum class OverlayEventPackageRelation { SERVICE_PACKAGE, TARGET_PACKAGE, OTHER_PACKAGE, MISSING, AMBIGUOUS, UNKNOWN }

/** Fixed codes only. UNKNOWN also covers zero, future bits and other single change types.
 * BOUNDS_INCLUDED says nothing about safety, including when REMOVED is also present.
 */
internal fun overlayWindowChangeKind(mask: Int): OverlayWindowChangeKind {
    val known = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED or
        AccessibilityEvent.WINDOWS_CHANGE_TITLE or AccessibilityEvent.WINDOWS_CHANGE_BOUNDS or
        AccessibilityEvent.WINDOWS_CHANGE_LAYER or AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or
        AccessibilityEvent.WINDOWS_CHANGE_FOCUSED or AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED or
        AccessibilityEvent.WINDOWS_CHANGE_PARENT or AccessibilityEvent.WINDOWS_CHANGE_CHILDREN or
        AccessibilityEvent.WINDOWS_CHANGE_PIP
    return when {
        mask == 0 || mask and known.inv() != 0 -> OverlayWindowChangeKind.UNKNOWN
        mask and AccessibilityEvent.WINDOWS_CHANGE_BOUNDS != 0 -> OverlayWindowChangeKind.BOUNDS_INCLUDED
        mask == AccessibilityEvent.WINDOWS_CHANGE_LAYER -> OverlayWindowChangeKind.LAYER_ONLY
        mask == AccessibilityEvent.WINDOWS_CHANGE_ADDED -> OverlayWindowChangeKind.ADDED_ONLY
        mask and (mask - 1) != 0 -> OverlayWindowChangeKind.MIXED
        else -> OverlayWindowChangeKind.UNKNOWN
    }
}

/** The V5 owned-window read/release order, with synchronous generic handles for JVM fault tests.
 * The result still belongs only to the original binding policy. Diagnostic detail adds no reads.
 */
internal fun <W : Any, N : Any> readOwnedOverlayIds(
    readWindows: () -> List<W>,
    isOverlayOnDefaultDisplay: (W) -> Boolean,
    readRoot: (W) -> N?,
    isOwnedRoot: (N, W) -> Boolean,
    hasOwnedTitle: (W) -> Boolean,
    windowId: (W) -> Int,
    releaseRoot: (N) -> Unit,
    releaseWindow: (W) -> Unit,
    onUnhealthy: () -> Unit,
    diagnostic: OverlayEventDiagnostic?,
    probe: ManualOwnedReadProbe? = null,
): Set<Int>? {
    val roots = mutableListOf<N>()
    val windows = mutableListOf<W>()
    val result = try {
        CollectionResourceSafety.run(body = {
            try {
                diagnostic?.ownedScanStarted()
                windows += readWindows()
                if (windows.isEmpty() || windows.size > 32) {
                    probe?.unavailable(if (windows.isEmpty()) ManualOwnedQuery.EMPTY_WINDOW_LIST else ManualOwnedQuery.WINDOW_LIMIT)
                    diagnostic?.ownedQueryUncertain(OverlayOwnedWindowDetail.UNCERTAIN)
                    return@run null
                }
                val found = mutableSetOf<Int>()
                for (window in windows) {
                    if (!isOverlayOnDefaultDisplay(window)) continue
                    val root = readRoot(window)
                    if (root == null) {
                        probe?.observed(OverlayOwnedScanDetail.ROOT_UNAVAILABLE)
                        diagnostic?.ownedScanObserved(OverlayOwnedScanDetail.ROOT_UNAVAILABLE)
                        continue
                    }
                    if (roots.none { it === root }) roots += root
                    if (!isOwnedRoot(root, window)) {
                        probe?.observed(OverlayOwnedScanDetail.ROOT_RELATION_UNCONFIRMED)
                        diagnostic?.ownedScanObserved(OverlayOwnedScanDetail.ROOT_RELATION_UNCONFIRMED)
                        continue
                    }
                    if (hasOwnedTitle(window)) {
                        val id = windowId(window)
                        val duplicate = !found.add(id)
                        diagnostic?.ownedIdObserved(id, duplicate)
                        probe?.observed(OverlayOwnedScanDetail.OWNED_CANDIDATE)
                        diagnostic?.ownedScanObserved(OverlayOwnedScanDetail.OWNED_CANDIDATE)
                    } else {
                        probe?.observed(OverlayOwnedScanDetail.TITLE_UNCONFIRMED)
                        diagnostic?.ownedScanObserved(OverlayOwnedScanDetail.TITLE_UNCONFIRMED)
                    }
                }
                found
            } catch (failure: Exception) { probe?.readFailed(); throw failure }
        }, cleanup = {
            val batch = CollectionReleaseBatch()
            roots.forEach { root -> batch.attempt {
                try { releaseRoot(root) } catch (failure: Exception) { probe?.releaseFailed(); throw failure }
            } }
            val seen = java.util.IdentityHashMap<W, Boolean>()
            windows.forEach { window -> if (seen.put(window, true) == null) batch.attempt {
                try { releaseWindow(window) } catch (failure: Exception) { probe?.releaseFailed(); throw failure }
            } }
            batch.finish()
        }, cleanupFailure = {
            onUnhealthy(); diagnostic?.ownedQueryUncertain(OverlayOwnedWindowDetail.QUERY_FAILED); null
        })
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        // All acquired handles were already independently released by the inner scope. Refuse
        // future ownership on uncertainty, but never turn cancellation into an ordinary null or
        // let a secondary health/diagnostic callback replace the original cancellation.
        try { onUnhealthy() } catch (_: Exception) { /* Original cancellation has precedence. */ }
        diagnosticWrite { diagnostic?.ownedQueryUncertain(OverlayOwnedWindowDetail.QUERY_FAILED) }
        probe?.completed(null)
        throw cancelled
    } catch (_: Exception) {
        onUnhealthy(); diagnostic?.ownedQueryUncertain(OverlayOwnedWindowDetail.QUERY_FAILED); null
    } finally { roots.clear(); windows.clear() }
    probe?.completed(result)
    return result
}

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
 * Per-event fields retain only enums. The separate optional origin observer owns bounded Debug
 * identity history; no event, token, Android handle, text, exception or policy authority is retained.
 */
internal class OverlayEventDiagnostic(
    private val origin: OverlayEventOriginDiagnostic? = null,
    private val capturePhase: ((Long?) -> EventCapturePhase)? = null,
    private val write: (OverlayEventCategory, OverlayEventRejection, OverlayBindingDetail, OverlayEventWindowClass,
        OverlayWindowChangeKind, OverlayOwnedWindowDetail, OverlayEventIdRelation, OverlayEventTimeRelation,
        OverlayOwnedScanDetail, OverlayEventPackageRelation, EventCapturePhase) -> Unit,
) {
    private var armed = false
    private var reported = false
    private var category = OverlayEventCategory.OTHER
    private var reason = OverlayEventRejection.UNKNOWN
    private var bindingFailure = OverlayBindingRejection.NONE
    private var bindingDetail = OverlayBindingDetail.UNKNOWN
    private var windowChangeKind = OverlayWindowChangeKind.UNKNOWN
    private var ownedWindowDetail = OverlayOwnedWindowDetail.UNKNOWN
    private var idRelation = OverlayEventIdRelation.UNKNOWN
    private var ownedScan = OverlayOwnedScanDetail.UNKNOWN
    private var packageRelation = OverlayEventPackageRelation.UNKNOWN

    fun arm() { armed = true; reported = false }
    fun disarm() { armed = false }
    fun begin(event: OverlayEventCategory) {
        category = event; reason = OverlayEventRejection.UNKNOWN; bindingFailure = OverlayBindingRejection.NONE
        bindingDetail = OverlayBindingDetail.UNKNOWN
        windowChangeKind = OverlayWindowChangeKind.UNKNOWN; ownedWindowDetail = OverlayOwnedWindowDetail.UNKNOWN
        idRelation = OverlayEventIdRelation.UNKNOWN
        ownedScan = OverlayOwnedScanDetail.UNKNOWN; packageRelation = OverlayEventPackageRelation.UNKNOWN
    }
    fun ownedScanStarted() { ownedScan = OverlayOwnedScanDetail.NO_ELIGIBLE_OVERLAY }
    fun ownedScanObserved(value: OverlayOwnedScanDetail) {
        ownedScan = when {
            ownedScan == OverlayOwnedScanDetail.UNAVAILABLE -> ownedScan
            ownedScan == OverlayOwnedScanDetail.NO_ELIGIBLE_OVERLAY || ownedScan == value -> value
            else -> OverlayOwnedScanDetail.MIXED
        }
    }
    fun eventPackageObserved(pkg: String?, target: String?, own: String) {
        packageRelation = when {
            pkg == null -> OverlayEventPackageRelation.MISSING
            pkg.isBlank() || own.isBlank() -> OverlayEventPackageRelation.UNKNOWN
            pkg == own && pkg == target -> OverlayEventPackageRelation.AMBIGUOUS
            pkg == own -> OverlayEventPackageRelation.SERVICE_PACKAGE
            target.isNullOrBlank() -> OverlayEventPackageRelation.UNKNOWN
            pkg == target -> OverlayEventPackageRelation.TARGET_PACKAGE
            else -> OverlayEventPackageRelation.OTHER_PACKAGE
        }
    }
    fun decisionContext(eventWindow: Int, targetWindow: Int?, generation: Long) {
        idRelation = origin?.relation(eventWindow, targetWindow, generation) ?: OverlayEventIdRelation.UNKNOWN
    }
    fun boundConfirmed(window: Int, generation: Long, targetWindow: Int?) {
        origin?.bound(window, generation)
        idRelation = origin?.relation(window, targetWindow, generation) ?: OverlayEventIdRelation.UNKNOWN
    }
    fun ownedIdObserved(window: Int, duplicate: Boolean) { origin?.ownedId(window, duplicate) }
    fun windowChangesObserved(mask: Int) { windowChangeKind = overlayWindowChangeKind(mask) }
    fun ownedQueryUncertain(value: OverlayOwnedWindowDetail) {
        ownedWindowDetail = value
        ownedScan = OverlayOwnedScanDetail.UNAVAILABLE
        if (value == OverlayOwnedWindowDetail.QUERY_FAILED) origin?.uncertain()
    }
    fun ownedWindowUnconfirmed(value: OverlayOwnedWindowDetail) {
        bindingRejected(OverlayBindingRejection.OWNED_WINDOW_UNCONFIRMED)
        ownedWindowDetail = value
    }
    fun bindingRejected(value: OverlayBindingRejection, detail: OverlayBindingDetail = OverlayBindingDetail.UNKNOWN) {
        bindingFailure = value; bindingDetail = detail
        if (value == OverlayBindingRejection.OWNED_WINDOW_UNCONFIRMED && ownedWindowDetail == OverlayOwnedWindowDetail.UNKNOWN)
            ownedWindowDetail = OverlayOwnedWindowDetail.UNCERTAIN
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
    fun completed(ignored: Boolean, eventTime: () -> Long? = { null },
        windowClass: () -> OverlayEventWindowClass = { OverlayEventWindowClass.UNKNOWN }) {
        if (!armed || reported || ignored) return
        reported = true // A failed sink is never retried on later events.
        // Freeze fixed codes before framework reads can reenter event inspection.
        val event = category
        val rejection = reason
        val detail = if (rejection == OverlayEventRejection.BINDING_MISMATCH) bindingDetail else OverlayBindingDetail.UNKNOWN
        val change = if (event == OverlayEventCategory.WINDOWS_CHANGED) windowChangeKind else OverlayWindowChangeKind.UNKNOWN
        val ownedDetail = if (rejection == OverlayEventRejection.OWNED_WINDOW_UNCONFIRMED)
            ownedWindowDetail else OverlayOwnedWindowDetail.UNKNOWN
        val relation = idRelation
        val scan = ownedScan
        val packageCode = packageRelation
        val eventAt = if (origin != null || capturePhase != null) try { eventTime() } catch (_: Exception) { null } else null
        val time = try { origin?.timeRelation(eventAt) ?: OverlayEventTimeRelation.UNKNOWN }
            catch (_: Exception) { OverlayEventTimeRelation.UNKNOWN }
        val phase = try { capturePhase?.invoke(eventAt) ?: EventCapturePhase.UNKNOWN }
            catch (_: Exception) { EventCapturePhase.UNKNOWN }
        val classified = try { windowClass() } catch (_: Exception) { OverlayEventWindowClass.UNKNOWN }
        // A predecessor reported live again after ACK is reuse/identity uncertainty, not proof of
        // a late event. Reuse the existing diagnostic query; never query or suppress an event here.
        val conservativeRelation = if (relation == OverlayEventIdRelation.PREVIOUS_C_ACKED_ID_MATCH) when (classified) {
            OverlayEventWindowClass.NOT_IN_LIST -> relation
            OverlayEventWindowClass.UNKNOWN -> OverlayEventIdRelation.UNKNOWN
            else -> OverlayEventIdRelation.AMBIGUOUS_OR_REUSED
        } else relation
        diagnosticWrite { write(event, rejection, detail, classified, change, ownedDetail, conservativeRelation, time, scan, packageCode, phase) }
    }
    override fun toString() = "OverlayEventDiagnostic(redacted)"
}
