package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kr.closeforyou.accessibility.action.F9AutoCloser
import kr.closeforyou.accessibility.action.F9ContentSafety
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.*

/** A display request, never execution evidence. Only the actual displayed B analysis is used. */
internal fun manualDemoBoundsCandidates(enabled: Boolean, type: Int, changes: Int, eventWindow: Int,
    original: PopupSnapshot, analysis: PopupAnalysis?, detection: PopupDetection?): List<CloseCandidate>? {
    if (!enabled || type != E.TYPE_WINDOWS_CHANGED || changes != E.WINDOWS_CHANGE_BOUNDS ||
        eventWindow < 0 || eventWindow != original.token.windowId || analysis?.token != original.token ||
        detection?.token != original.token || detection.signals != setOf(PopupSignal.NEW_WINDOW)) return null
    return analysis.closeCandidates.takeIf { it.size == 1 }
}

/** Demo-only observable continuity, not native-generation authentication or F0/F9 approval.
 * Bounds remain exact: either the WHOLE popup translates, or only the nonclickable root/window
 * resizes while every descendant (including the close button) stays exactly where it was.
 * No coordinate conversion, rebase, retry, token/time mutation, or framework call exists here. */
internal object ManualDemoBoundsPolicy {
    fun retains(original: PopupSnapshot, fresh: F9Scene, nativeId: Int, listing: ManualDisplayListing,
        candidates: List<CloseCandidate>): Boolean {
        if (original.completeness != SnapshotCompleteness.COMPLETE || !fresh.complete ||
            original.token.packageName != fresh.packageName || original.token.windowId != fresh.windowId ||
            original.screenBounds != fresh.screen || original.nodes.size !in 2..1_200 ||
            original.nodes.size != fresh.nodes.size || original.windows.size !in 1..32 || fresh.windows.size !in 1..32 ||
            original.windows.map { it.id }.toSet().size != original.windows.size || candidates.size != 1) return false
        val current = ManualDisplayContinuity.target(fresh.windows, original.token.windowId, nativeId, listing = listing) ?: return false
        val previous = original.windows.singleOrNull { it.id == original.token.windowId && it.type == WindowType.APPLICATION } ?: return false
        if (previous.bounds == current.bounds || original.windows.any { it.id == nativeId }) return false
        val withoutC = if (listing == ManualDisplayListing.LISTED) fresh.windows.filter { it.id != nativeId } else fresh.windows
        if (withoutC.size != original.windows.size || (listing == ManualDisplayListing.NOT_ENUMERATED &&
                withoutC.map { it.id } != original.windows.map { it.id })) return false
        val byId = withoutC.associateBy { it.id }
        for (old in original.windows) {
            val new = byId[old.id] ?: return false
            if (old.id != current.id) { if (new != old) return false }
            else if (new.type != old.type || new.layer != old.layer || (listing == ManualDisplayListing.NOT_ENUMERATED &&
                    (new.active != old.active || new.focused != old.focused))) return false
        }
        fun contains(a: ScreenBounds, b: ScreenBounds) = a.right.toLong() > a.left && a.bottom.toLong() > a.top &&
            b.right.toLong() > b.left && b.bottom.toLong() > b.top && F9AutoCloser.contains(a, b)
        if (!contains(original.screenBounds, previous.bounds) || previous.bounds == original.screenBounds ||
            !contains(fresh.screen, current.bounds) || current.bounds == fresh.screen) return false
        val oldRoot = original.nodes.first(); val newRoot = fresh.nodes.first()
        if (oldRoot.path != "0" || newRoot.path != "0" || oldRoot.clickable || newRoot.clickable ||
            oldRoot.bounds != previous.bounds || newRoot.bounds != current.bounds ||
            original.nodes.map { it.path }.toSet().size != original.nodes.size) return false
        for (i in original.nodes.indices) {
            val a = original.nodes[i]; val b = fresh.nodes[i]
            if (a.path != b.path || a.viewId != b.viewId || a.className != b.className || a.text != b.text ||
                a.contentDescription != b.contentDescription || a.clickable != b.clickable || a.enabled != b.enabled ||
                a.actions != b.actions || !contains(previous.bounds, a.bounds) || !contains(current.bounds, b.bounds)) return false
        }
        val candidate = candidates.single()
        val oldButton = original.nodes.singleOrNull { it.path == candidate.nodePath } ?: return false
        val newButton = fresh.nodes.single { it.path == oldButton.path }
        if (candidate.nodePath == "0" || candidate.role != ButtonRole.CLOSE || candidate.evidence != EvidenceLevel.E2_LABELED_NODE ||
            F9AutoCloser.role(candidate.label) != ButtonRole.CLOSE || candidate.viewId != oldButton.viewId ||
            candidate.bounds != oldButton.bounds || !oldButton.clickable || !oldButton.enabled ||
            NodeAction.CLICK !in oldButton.actions || !F9AutoCloser.supportedCloseWidget(oldButton.className, true) ||
            newButton.path !in fresh.visiblePaths) return false
        val labels = listOfNotNull(oldButton.text, oldButton.contentDescription).filter { it.isNotBlank() }
        if (labels.isEmpty() || candidate.label !in labels || labels.any { F9AutoCloser.role(it) != ButtonRole.CLOSE } ||
            original.nodes.count { n -> n.clickable && listOfNotNull(n.text, n.contentDescription).any { F9AutoCloser.role(it) != null } } != 1) return false
        if (F9ContentSafety.protectedPackage(fresh.packageName) ||
            F9ContentSafety.blocked(fresh.nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }.joinToString("\n"))) return false
        val dx = current.bounds.left.toLong() - previous.bounds.left
        val dy = current.bounds.top.toLong() - previous.bounds.top
        fun shifted(a: ScreenBounds, b: ScreenBounds) = b.left.toLong() - a.left == dx && b.right.toLong() - a.right == dx &&
            b.top.toLong() - a.top == dy && b.bottom.toLong() - a.bottom == dy
        val translation = shifted(previous.bounds, current.bounds) && original.nodes.indices.all {
            shifted(original.nodes[it].bounds, fresh.nodes[it].bounds)
        }
        val rootOnlyResize = !shifted(previous.bounds, current.bounds) &&
            original.nodes.indices.drop(1).all { original.nodes[it].bounds == fresh.nodes[it].bounds }
        return translation || rootOnlyResize
    }
}
