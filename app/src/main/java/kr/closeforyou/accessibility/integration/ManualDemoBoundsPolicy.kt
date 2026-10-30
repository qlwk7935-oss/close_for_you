package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kr.closeforyou.accessibility.action.F9AutoCloser
import kr.closeforyou.accessibility.action.F9ContentSafety
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.accessibility.NativeActionPresence
import kr.closeforyou.accessibility.NativeNodeActions
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
    POLICY_ROOT_PATH, POLICY_ROOT_CLICKABLE, POLICY_ROOT_INTERACTIVE, POLICY_ROOT_WINDOW, POLICY_NODE_FIELDS,
    POLICY_ORIGINAL_NODE_GEOMETRY, POLICY_CURRENT_NODE_GEOMETRY,
    POLICY_CANDIDATE_NODE, POLICY_CANDIDATE_ROLE_EVIDENCE, POLICY_CANDIDATE_LABEL,
    POLICY_CANDIDATE_BINDING, POLICY_CANDIDATE_ACTION, POLICY_CANDIDATE_NOT_VISIBLE,
    POLICY_AMBIGUOUS_LABELS, POLICY_MULTIPLE_ACTION_LABELS, POLICY_PROTECTED_CONTENT,
    POLICY_NONCOHERENT_TRANSLATION, POLICY_DESCENDANT_BOUNDS,
    POLICY_REFLOW_PATH, POLICY_REFLOW_PARENT, POLICY_INTERACTIVE_BOUNDS,
    POLICY_NATIVE_ACTIONS_UNCONFIRMED, POLICY_NATIVE_ACTIONS_CHANGED,
    POLICY_REFLOW_OVERLAP, POLICY_LAYOUT_LIMIT,
    POLICY_RETAIN_TRANSLATION, POLICY_RETAIN_ROOT_RESIZE, POLICY_RETAIN_PASSIVE_REFLOW,
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
 * resizes while every descendant stays fixed, or proven descriptor-level passive layout reflows.
 * Reflow never moves an interactive/unknown widget or relaxes any execution comparison.
 * No coordinate conversion, rebase, retry, token/time mutation, or framework call exists here. */
internal object ManualDemoBoundsPolicy {
    /** Observable role only. Window binding, exact descriptors/actions, graph/containment and
     * candidate identity are independent mandatory checks; this never authenticates native ABA. */
    private fun structuralRootRole(node: NodeSnapshot): Boolean = node.path == "0" && !node.clickable &&
        (node.className == "android.widget.FrameLayout" || node.className == "android.widget.LinearLayout") &&
        node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()

    /** Also used by a selected Debug Demo read that ends in strict SAME. Missing action data
     * must not gain an approval by taking the equal-DTO fallback. No geometry permission here. */
    fun nativeActionFailure(original: PopupSnapshot, fresh: F9Scene, originalActions: NativeNodeActions?): ManualDemoBoundsReason? {
        val count = original.nodes.size
        if (count !in 1..1_200 || fresh.nodes.size != count) return ManualDemoBoundsReason.POLICY_NATIVE_ACTIONS_UNCONFIRMED
        var changed = false
        for (i in original.nodes.indices) {
            val before = originalActions?.summary(original, count, i)
            val after = fresh.nativeActions?.summary(fresh.nodes, count, i)
            if (before == null || after == null || !before.hasExactKnownSet || !after.hasExactKnownSet ||
                !before.matchesProjection(original.nodes[i].actions) || !after.matchesProjection(fresh.nodes[i].actions))
                return ManualDemoBoundsReason.POLICY_NATIVE_ACTIONS_UNCONFIRMED
            if (before != after) changed = true
        }
        return if (changed) ManualDemoBoundsReason.POLICY_NATIVE_ACTIONS_CHANGED else null
    }

    fun retains(original: PopupSnapshot, fresh: F9Scene, nativeId: Int, listing: ManualDisplayListing,
        candidates: List<CloseCandidate>, originalActions: NativeNodeActions? = null,
        report: ((ManualDemoBoundsReason) -> Unit)? = null): Boolean {
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
        if (oldRoot.clickable || newRoot.clickable) return reject(ManualDemoBoundsReason.POLICY_ROOT_CLICKABLE)
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
        nativeActionFailure(original, fresh, originalActions)?.let { return reject(it) }
        val oldActions = original.nodes.indices.map { requireNotNull(originalActions).state(original, original.nodes.size, it) }
        // A structural ROOT is NOT a passive descendant. Its role and exact stable capability
        // set are separate from whole-popup identity and from execution, which stays retired.
        if (!structuralRootRole(oldRoot) || !structuralRootRole(newRoot))
            return reject(ManualDemoBoundsReason.POLICY_DESCENDANT_BOUNDS)
        val rootCapabilities = requireNotNull(originalActions).summary(original, original.nodes.size, 0)
        if (oldRoot.actions.isNotEmpty() || !rootCapabilities.hasOnlyStructuralRootCapabilities)
            return reject(ManualDemoBoundsReason.POLICY_ROOT_INTERACTIVE)
        val dx = current.bounds.left.toLong() - previous.bounds.left
        val dy = current.bounds.top.toLong() - previous.bounds.top
        fun shifted(a: ScreenBounds, b: ScreenBounds) = b.left.toLong() - a.left == dx && b.right.toLong() - a.right == dx &&
            b.top.toLong() - a.top == dy && b.bottom.toLong() - a.bottom == dy
        val translation = shifted(previous.bounds, current.bounds) && original.nodes.indices.all {
            shifted(original.nodes[it].bounds, fresh.nodes[it].bounds)
        }
        val rootOnlyResize = !shifted(previous.bounds, current.bounds) &&
            original.nodes.indices.drop(1).all { original.nodes[it].bounds == fresh.nodes[it].bounds }
        // A window translation with independently stationary/moving children is still ambiguous.
        if (!translation && shifted(previous.bounds, current.bounds)) return reject(ManualDemoBoundsReason.POLICY_NONCOHERENT_TRANSLATION)
        val reflowFailure = checkedLayout(original.nodes, fresh.nodes, oldActions, !translation && !rootOnlyResize, ::contains)
        if (reflowFailure != null) return reject(reflowFailure)
        reportDemoBounds(report, when {
            translation -> ManualDemoBoundsReason.POLICY_RETAIN_TRANSLATION
            rootOnlyResize -> ManualDemoBoundsReason.POLICY_RETAIN_ROOT_RESIZE
            else -> ManualDemoBoundsReason.POLICY_RETAIN_PASSIVE_REFLOW
        })
        return true
    }

    /** All Demo geometry paths require bound full-native action metadata and a valid graph.
     * No missing metadata, raw native handle, repaired Rect, or render-order assumption is used.
     * Window-level occlusion remains checked by the Android reader before this function. */
    private fun checkedLayout(before: List<NodeSnapshot>, after: List<NodeSnapshot>,
        nativeActions: List<NativeActionPresence>, reflow: Boolean,
        contains: (ScreenBounds, ScreenBounds) -> Boolean): ManualDemoBoundsReason? {
        fun passiveContainer(i: Int): Boolean { val n = before[i]; return !n.clickable && n.actions.isEmpty() &&
            nativeActions[i] == NativeActionPresence.NONE &&
            (n.className == "android.widget.FrameLayout" || n.className == "android.widget.LinearLayout") &&
            n.text.isNullOrBlank() && n.contentDescription.isNullOrBlank() }
        if (!structuralRootRole(before.first()) || !structuralRootRole(after.first()))
            return ManualDemoBoundsReason.POLICY_DESCENDANT_BOUNDS
        val byPath = HashMap<String, Int>(before.size)
        val hasChildren = BooleanArray(before.size)
        byPath["0"] = 0
        // Android's bounded preorder paths, including parents, must be represented in both
        // unchanged descriptor graphs. Do not infer a missing parent or repair a clipped Rect.
        for (i in 1 until before.size) {
            val path = before[i].path
            val split = path.lastIndexOf('/')
            if (split < 1 || path.length > 301) return ManualDemoBoundsReason.POLICY_REFLOW_PATH
            val segment = path.substring(split + 1)
            if (segment.isEmpty() || segment.length > 4 || segment.any { it !in '0'..'9' } ||
                (segment.length > 1 && segment[0] == '0') || segment.toInt() >= 1_200)
                return ManualDemoBoundsReason.POLICY_REFLOW_PATH
            val parent = byPath[path.substring(0, split)] ?: return ManualDemoBoundsReason.POLICY_REFLOW_PATH
            // Only ROOT has the separately verified structural role. Every other reflow
            // parent must still be genuinely passive; no descendant capability exemption.
            if ((reflow && parent != 0 && !passiveContainer(parent)) || !contains(before[parent].bounds, before[i].bounds) ||
                !contains(after[parent].bounds, after[i].bounds)) return ManualDemoBoundsReason.POLICY_REFLOW_PARENT
            hasChildren[parent] = true
            byPath[path] = i
        }
        for (i in 1 until before.size) {
            val n = before[i]
            if (!reflow || n.bounds == after[i].bounds) continue
            if (n.clickable || n.actions.isNotEmpty() || nativeActions[i] != NativeActionPresence.NONE)
                return ManualDemoBoundsReason.POLICY_INTERACTIVE_BOUNDS
            val body = n.className == "android.widget.TextView" && !hasChildren[i] &&
                !n.text.isNullOrBlank() && listOfNotNull(n.text, n.contentDescription).all { F9AutoCloser.role(it) == null }
            if (!passiveContainer(i) && !body) return ManualDemoBoundsReason.POLICY_DESCENDANT_BOUNDS
        }
        val interactive = before.indices.filter { before[it].clickable || before[it].actions.isNotEmpty() || nativeActions[it] != NativeActionPresence.NONE }
        val prefixes = before.map { it.path + "/" }
        fun intersects(a: ScreenBounds, b: ScreenBounds) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        var comparisons = 0
        for (i in before.indices) {
            if (before[i].bounds == after[i].bounds || before[i].clickable || before[i].actions.isNotEmpty() || nativeActions[i] != NativeActionPresence.NONE) continue
            for (j in interactive) {
                if (++comparisons > 16_384) return ManualDemoBoundsReason.POLICY_LAYOUT_LIMIT
                // Proven ancestry plus both parent-containment chains is legitimate layout,
                // whereas unrelated overlap has no z-order/opaque-region proof. Fail closed
                // even if it was also present before; isVisibleToUser is not occlusion evidence.
                if (before[j].path.startsWith(prefixes[i]) || before[i].path.startsWith(prefixes[j])) continue
                if (intersects(before[i].bounds, before[j].bounds) || intersects(after[i].bounds, after[j].bounds))
                    return ManualDemoBoundsReason.POLICY_REFLOW_OVERLAP
            }
        }
        return null
    }
}
