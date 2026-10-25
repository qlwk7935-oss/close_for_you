package kr.closeforyou.decision

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupDetection
import kr.closeforyou.core.model.PopupSignal
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * B: F3 close/skip candidates from A's immutable node DTOs, tree evidence only (E1/E2).
 * A candidate describes a button; it is never an authorization (DecisionActionPolicy decides).
 * Icon (E3) and pixel (E4) detection and OCR text are deliberately not used here.
 */
class DecisionCloseCandidateFinder {
    fun find(snapshot: PopupSnapshot, detection: PopupDetection): ImmutableList<CloseCandidate> {
        if (detection.token != snapshot.token) return persistentListOf()
        val scope = popupScope(snapshot, detection.signals) ?: return persistentListOf()
        return snapshot.nodes
            .filter { it.enabled && scope.contains(it) && inside(it.bounds, snapshot.screenBounds) }
            .mapNotNull(::candidate)
            .toPersistentList()
    }

    /**
     * The popup's own nodes: an optional subtree root and the bounds that must contain them.
     * [rootPath] is null for a new window, whose nodes are all the popup's.
     */
    internal class PopupScope(val rootPath: String?, val bounds: ScreenBounds) {
        fun contains(node: NodeSnapshot): Boolean = inside(node.bounds, bounds) &&
            (rootPath == null || node.path == rootPath || node.path.startsWith("$rootPath/"))
    }

    /**
     * Only a new window holds nothing but the popup. A same-window overlay shares the window with
     * the page behind, so only the subtree of one unambiguous Dialog node counts: a page button
     * lying under the Dialog's bounds is still not the popup's. A full-screen transition may be an
     * ordinary Activity change and an uncertain detection may be anything, so neither yields
     * candidates (agreed with A in the PR #8 review). Shared with [DecisionPopupScope].
     */
    internal fun popupScope(snapshot: PopupSnapshot, signals: Set<PopupSignal>): PopupScope? = when {
        PopupSignal.INSUFFICIENT_EVIDENCE in signals || PopupSignal.FULL_SCREEN_TRANSITION in signals -> null
        PopupSignal.NEW_WINDOW in signals -> PopupScope(null, snapshot.screenBounds)
        PopupSignal.SAME_WINDOW_OVERLAY in signals -> dialogScope(snapshot.nodes)
        else -> null
    }

    private fun dialogScope(nodes: List<NodeSnapshot>): PopupScope? {
        val dialogs = nodes.filter { it.className?.contains("dialog", ignoreCase = true) == true }
        // Dialog classes nested inside one Dialog (e.g. its layout) belong to that same Dialog.
        val outermost = dialogs.filter { dialog -> dialogs.none { dialog.path.startsWith("${it.path}/") } }
        val dialog = outermost.singleOrNull() ?: return null
        return PopupScope(dialog.path, dialog.bounds)
    }

    private fun candidate(node: NodeSnapshot): CloseCandidate? {
        val labels = listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }
        // Shared contract: every label on the node must say the same close/skip thing.
        val labeledRole = ButtonLabelRules.judgeNode(node.text, node.contentDescription).role
        // Kept verbatim, not trimmed: A's gate (PR #10) compares it with the node's text exactly.
        // Role matching already ignores whitespace, and C trims it for display.
        val label = labels.firstOrNull()
        return when {
            // An unlabeled DISMISS is not offered: A's gate refuses it (agreed after PR #11).
            NodeAction.DISMISS in node.actions && labeledRole == ButtonRole.CLOSE ->
                create(node, label, EvidenceLevel.E1_DISMISS, ButtonRole.CLOSE)
            node.clickable && NodeAction.CLICK in node.actions && label != null &&
                (labeledRole == ButtonRole.CLOSE || labeledRole == ButtonRole.SKIP) ->
                create(node, label, EvidenceLevel.E2_LABELED_NODE, labeledRole)
            else -> null
        }
    }

    private fun create(node: NodeSnapshot, label: String?, evidence: EvidenceLevel, role: ButtonRole) = CloseCandidate(
        id = "node:${node.path}",
        nodePath = node.path,
        viewId = node.viewId,
        label = label,
        bounds = node.bounds,
        evidence = evidence,
        role = role,
    )
}

private fun inside(inner: ScreenBounds, outer: ScreenBounds): Boolean =
    inner.width > 0 && inner.height > 0 &&
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom
