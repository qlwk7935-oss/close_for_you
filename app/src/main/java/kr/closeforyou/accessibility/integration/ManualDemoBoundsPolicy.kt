package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kr.closeforyou.accessibility.action.F9AutoCloser
import kr.closeforyou.accessibility.action.F9ContentSafety
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.*

/** One Debug report field. Selection, first policy rejection, and policy-only acceptance are
 * distinct; none of these codes grants display/currentness/cleanup/execution permission. */
internal enum class ManualDemoBoundsReason {
    NOT_CHECKED, SELECT_DISABLED, SELECT_EVENT, SELECT_TARGET, SELECT_ANALYSIS_MISSING,
    SELECT_ANALYSIS_TOKEN, SELECT_DETECTION_MISSING, SELECT_DETECTION_TOKEN, SELECT_SCOPE,
    SELECT_NO_CANDIDATE, SELECT_MULTIPLE_CANDIDATES, SELECTED,
    POLICY_INCOMPLETE, POLICY_BINDING, POLICY_NODE_COUNT, POLICY_WINDOW_SCOPE, POLICY_CANDIDATE_COUNT,
    POLICY_TARGET_UNCONFIRMED, POLICY_ORIGINAL_TARGET, POLICY_WINDOW_UNCHANGED, POLICY_NATIVE_COLLISION,
    POLICY_WINDOW_SET, POLICY_OTHER_WINDOW, POLICY_TARGET_METADATA, POLICY_WINDOW_GEOMETRY,
    POLICY_ROOT_PATH, POLICY_ROOT_INTERACTIVE, POLICY_ROOT_WINDOW, POLICY_NODE_FIELDS,
    POLICY_ORIGINAL_NODE_GEOMETRY, POLICY_CURRENT_NODE_GEOMETRY,
    POLICY_CANDIDATE_NODE, POLICY_CANDIDATE_ROLE_EVIDENCE, POLICY_CANDIDATE_LABEL,
    POLICY_CANDIDATE_BINDING, POLICY_CANDIDATE_ACTION, POLICY_CANDIDATE_NOT_VISIBLE,
    POLICY_AMBIGUOUS_LABELS, POLICY_MULTIPLE_ACTION_LABELS, POLICY_PROTECTED_CONTENT,
    POLICY_NONCOHERENT_TRANSLATION, POLICY_DESCENDANT_BOUNDS,
    POLICY_RETAIN_TRANSLATION, POLICY_RETAIN_ROOT_RESIZE,
}
private fun reportDemoBounds(report: ((ManualDemoBoundsReason) -> Unit)?, value: ManualDemoBoundsReason) {
    if (report != null) kr.closeforyou.accessibility.diagnosticWrite { report(value) }
}

/** A display request, never execution evidence. Only the actual displayed B analysis is used. */
internal fun manualDemoBoundsCandidates(enabled: Boolean, type: Int, changes: Int, eventWindow: Int,
    original: PopupSnapshot, analysis: PopupAnalysis?, detection: PopupDetection?,
    report: ((ManualDemoBoundsReason) -> Unit)? = null): List<CloseCandidate>? {
    fun reject(reason: ManualDemoBoundsReason): List<CloseCandidate>? { reportDemoBounds(report, reason); return null }
    if (!enabled) return reject(ManualDemoBoundsReason.SELECT_DISABLED)
    if (type != E.TYPE_WINDOWS_CHANGED || changes != E.WINDOWS_CHANGE_BOUNDS) return reject(ManualDemoBoundsReason.SELECT_EVENT)
    if (eventWindow < 0 || eventWindow != original.token.windowId) return reject(ManualDemoBoundsReason.SELECT_TARGET)
    if (analysis == null) return reject(ManualDemoBoundsReason.SELECT_ANALYSIS_MISSING)
    if (analysis.token != original.token) return reject(ManualDemoBoundsReason.SELECT_ANALYSIS_TOKEN)
    if (detection == null) return reject(ManualDemoBoundsReason.SELECT_DETECTION_MISSING)
    if (detection.token != original.token) return reject(ManualDemoBoundsReason.SELECT_DETECTION_TOKEN)
    if (detection.signals != setOf(PopupSignal.NEW_WINDOW)) return reject(ManualDemoBoundsReason.SELECT_SCOPE)
    if (analysis.closeCandidates.isEmpty()) return reject(ManualDemoBoundsReason.SELECT_NO_CANDIDATE)
    if (analysis.closeCandidates.size != 1) return reject(ManualDemoBoundsReason.SELECT_MULTIPLE_CANDIDATES)
    reportDemoBounds(report, ManualDemoBoundsReason.SELECTED)
    return analysis.closeCandidates
}

/** Demo-only observable continuity, not native-generation authentication or F0/F9 approval.
 * Bounds remain exact: either the WHOLE popup translates, or only the nonclickable root/window
 * resizes while every descendant (including the close button) stays exactly where it was.
 * No coordinate conversion, rebase, retry, token/time mutation, or framework call exists here. */
internal object ManualDemoBoundsPolicy {
    fun retains(original: PopupSnapshot, fresh: F9Scene, nativeId: Int, listing: ManualDisplayListing,
        candidates: List<CloseCandidate>, report: ((ManualDemoBoundsReason) -> Unit)? = null): Boolean {
        fun reject(reason: ManualDemoBoundsReason): Boolean { reportDemoBounds(report, reason); return false }
        if (original.completeness != SnapshotCompleteness.COMPLETE || !fresh.complete) return reject(ManualDemoBoundsReason.POLICY_INCOMPLETE)
        if (original.token.packageName != fresh.packageName || original.token.windowId != fresh.windowId ||
            original.screenBounds != fresh.screen) return reject(ManualDemoBoundsReason.POLICY_BINDING)
        if (original.nodes.size !in 2..1_200 || original.nodes.size != fresh.nodes.size) return reject(ManualDemoBoundsReason.POLICY_NODE_COUNT)
        if (original.windows.size !in 1..32 || fresh.windows.size !in 1..32 ||
            original.windows.map { it.id }.toSet().size != original.windows.size) return reject(ManualDemoBoundsReason.POLICY_WINDOW_SCOPE)
        if (candidates.size != 1) return reject(ManualDemoBoundsReason.POLICY_CANDIDATE_COUNT)
        val current = ManualDisplayContinuity.target(fresh.windows, original.token.windowId, nativeId, listing = listing)
            ?: return reject(ManualDemoBoundsReason.POLICY_TARGET_UNCONFIRMED)
        val previous = original.windows.singleOrNull { it.id == original.token.windowId && it.type == WindowType.APPLICATION }
            ?: return reject(ManualDemoBoundsReason.POLICY_ORIGINAL_TARGET)
        if (previous.bounds == current.bounds) return reject(ManualDemoBoundsReason.POLICY_WINDOW_UNCHANGED)
        if (original.windows.any { it.id == nativeId }) return reject(ManualDemoBoundsReason.POLICY_NATIVE_COLLISION)
        val withoutC = if (listing == ManualDisplayListing.LISTED) fresh.windows.filter { it.id != nativeId } else fresh.windows
        if (withoutC.size != original.windows.size || (listing == ManualDisplayListing.NOT_ENUMERATED &&
                withoutC.map { it.id } != original.windows.map { it.id })) return reject(ManualDemoBoundsReason.POLICY_WINDOW_SET)
        val byId = withoutC.associateBy { it.id }
        for (old in original.windows) {
            val new = byId[old.id] ?: return reject(ManualDemoBoundsReason.POLICY_WINDOW_SET)
            if (old.id != current.id) { if (new != old) return reject(ManualDemoBoundsReason.POLICY_OTHER_WINDOW) }
            else if (new.type != old.type || new.layer != old.layer || (listing == ManualDisplayListing.NOT_ENUMERATED &&
                    (new.active != old.active || new.focused != old.focused))) return reject(ManualDemoBoundsReason.POLICY_TARGET_METADATA)
        }
        fun contains(a: ScreenBounds, b: ScreenBounds) = a.right.toLong() > a.left && a.bottom.toLong() > a.top &&
            b.right.toLong() > b.left && b.bottom.toLong() > b.top && F9AutoCloser.contains(a, b)
        if (!contains(original.screenBounds, previous.bounds) || previous.bounds == original.screenBounds ||
            !contains(fresh.screen, current.bounds) || current.bounds == fresh.screen) return reject(ManualDemoBoundsReason.POLICY_WINDOW_GEOMETRY)
        val oldRoot = original.nodes.first(); val newRoot = fresh.nodes.first()
        if (oldRoot.path != "0" || newRoot.path != "0") return reject(ManualDemoBoundsReason.POLICY_ROOT_PATH)
        if (oldRoot.clickable || newRoot.clickable) return reject(ManualDemoBoundsReason.POLICY_ROOT_INTERACTIVE)
        if (oldRoot.bounds != previous.bounds || newRoot.bounds != current.bounds) return reject(ManualDemoBoundsReason.POLICY_ROOT_WINDOW)
        if (original.nodes.map { it.path }.toSet().size != original.nodes.size) return reject(ManualDemoBoundsReason.POLICY_ROOT_PATH)
        for (i in original.nodes.indices) {
            val a = original.nodes[i]; val b = fresh.nodes[i]
            if (a.path != b.path || a.viewId != b.viewId || a.className != b.className || a.text != b.text ||
                a.contentDescription != b.contentDescription || a.clickable != b.clickable || a.enabled != b.enabled ||
                a.actions != b.actions) return reject(ManualDemoBoundsReason.POLICY_NODE_FIELDS)
            if (!contains(previous.bounds, a.bounds)) return reject(ManualDemoBoundsReason.POLICY_ORIGINAL_NODE_GEOMETRY)
            if (!contains(current.bounds, b.bounds)) return reject(ManualDemoBoundsReason.POLICY_CURRENT_NODE_GEOMETRY)
        }
        val candidate = candidates.single()
        val oldButton = original.nodes.singleOrNull { it.path == candidate.nodePath } ?: return reject(ManualDemoBoundsReason.POLICY_CANDIDATE_NODE)
        val newButton = fresh.nodes.single { it.path == oldButton.path }
        if (candidate.nodePath == "0" || candidate.role != ButtonRole.CLOSE || candidate.evidence != EvidenceLevel.E2_LABELED_NODE)
            return reject(ManualDemoBoundsReason.POLICY_CANDIDATE_ROLE_EVIDENCE)
        if (F9AutoCloser.role(candidate.label) != ButtonRole.CLOSE) return reject(ManualDemoBoundsReason.POLICY_CANDIDATE_LABEL)
        if (candidate.viewId != oldButton.viewId || candidate.bounds != oldButton.bounds) return reject(ManualDemoBoundsReason.POLICY_CANDIDATE_BINDING)
        if (!oldButton.clickable || !oldButton.enabled || NodeAction.CLICK !in oldButton.actions ||
            !F9AutoCloser.supportedCloseWidget(oldButton.className, true)) return reject(ManualDemoBoundsReason.POLICY_CANDIDATE_ACTION)
        if (newButton.path !in fresh.visiblePaths) return reject(ManualDemoBoundsReason.POLICY_CANDIDATE_NOT_VISIBLE)
        val labels = listOfNotNull(oldButton.text, oldButton.contentDescription).filter { it.isNotBlank() }
        if (labels.isEmpty() || candidate.label !in labels || labels.any { F9AutoCloser.role(it) != ButtonRole.CLOSE })
            return reject(ManualDemoBoundsReason.POLICY_AMBIGUOUS_LABELS)
        if (original.nodes.count { n -> n.clickable && listOfNotNull(n.text, n.contentDescription).any { F9AutoCloser.role(it) != null } } != 1)
            return reject(ManualDemoBoundsReason.POLICY_MULTIPLE_ACTION_LABELS)
        if (F9ContentSafety.protectedPackage(fresh.packageName) ||
            F9ContentSafety.blocked(fresh.nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }.joinToString("\n"))) return reject(ManualDemoBoundsReason.POLICY_PROTECTED_CONTENT)
        val dx = current.bounds.left.toLong() - previous.bounds.left
        val dy = current.bounds.top.toLong() - previous.bounds.top
        fun shifted(a: ScreenBounds, b: ScreenBounds) = b.left.toLong() - a.left == dx && b.right.toLong() - a.right == dx &&
            b.top.toLong() - a.top == dy && b.bottom.toLong() - a.bottom == dy
        val translation = shifted(previous.bounds, current.bounds) && original.nodes.indices.all {
            shifted(original.nodes[it].bounds, fresh.nodes[it].bounds)
        }
        val rootOnlyResize = !shifted(previous.bounds, current.bounds) &&
            original.nodes.indices.drop(1).all { original.nodes[it].bounds == fresh.nodes[it].bounds }
        if (translation) { reportDemoBounds(report, ManualDemoBoundsReason.POLICY_RETAIN_TRANSLATION); return true }
        if (rootOnlyResize) { reportDemoBounds(report, ManualDemoBoundsReason.POLICY_RETAIN_ROOT_RESIZE); return true }
        return reject(if (shifted(previous.bounds, current.bounds)) ManualDemoBoundsReason.POLICY_NONCOHERENT_TRANSLATION
            else ManualDemoBoundsReason.POLICY_DESCENDANT_BOUNDS)
    }
}
