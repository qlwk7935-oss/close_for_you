package kr.closeforyou.decision

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
            .filter { it.enabled && inside(it.bounds, scope) && inside(it.bounds, snapshot.screenBounds) }
            .mapNotNull(::candidate)
            .toPersistentList()
    }

    /**
     * The active window holds only the popup for a new window or a full-screen transition. A
     * same-window overlay shares it with the page behind, so only one unambiguous Dialog node
     * bounds the search; otherwise the page's own buttons could be mistaken for the popup's.
     */
    private fun popupScope(snapshot: PopupSnapshot, signals: Set<PopupSignal>): ScreenBounds? = when {
        PopupSignal.NEW_WINDOW in signals || PopupSignal.FULL_SCREEN_TRANSITION in signals -> snapshot.screenBounds
        PopupSignal.SAME_WINDOW_OVERLAY in signals -> snapshot.nodes
            .filter { it.className?.contains("dialog", ignoreCase = true) == true }
            .map { it.bounds }
            .distinct()
            .singleOrNull()
        else -> null
    }

    private fun candidate(node: NodeSnapshot): CloseCandidate? {
        val labels = listOfNotNull(node.text, node.contentDescription).map { it.trim() }.filter { it.isNotEmpty() }
        val roles = labels.map(::roleOf).toSet()
        // Every label on the node must say the same close/skip thing; a conflict is not a candidate.
        val labeledRole = roles.singleOrNull()
        val label = labels.firstOrNull()
        return when {
            NodeAction.DISMISS in node.actions && (labels.isEmpty() || labeledRole == ButtonRole.CLOSE) ->
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

    /** Whole-label match, so "오늘 하루 닫기" (grade K) or "닫기 전 저장" are not close buttons. */
    private fun roleOf(label: String): ButtonRole? {
        if (DecisionKeywords.isRiskyButtonLabel(label)) return null
        val normalized = label.lowercase().filterNot { it.isWhitespace() || it in "[]()<>·." }
        return when (normalized) {
            in CLOSE_LABELS -> ButtonRole.CLOSE
            in SKIP_LABELS -> ButtonRole.SKIP
            else -> null
        }
    }

    private fun inside(inner: ScreenBounds, outer: ScreenBounds): Boolean =
        inner.width > 0 && inner.height > 0 &&
            inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom

    private companion object {
        val CLOSE_LABELS = setOf(
            "닫기", "닫음", "창닫기", "팝업닫기", "광고닫기", "공지닫기", "안내닫기", "닫기버튼",
            "close", "closead", "closepopup", "closedialog", "closebutton", "dismiss", "x", "×", "✕", "✖", "╳",
        )
        val SKIP_LABELS = setOf("건너뛰기", "광고건너뛰기", "건너뛰기버튼", "스킵", "광고스킵", "skip", "skipad", "skipads", "skipbutton")
    }
}
