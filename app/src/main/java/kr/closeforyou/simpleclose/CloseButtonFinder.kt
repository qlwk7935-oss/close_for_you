package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.NodeSnapshot

/** What the popup's clickable nodes gave: press targets, and counts of what was refused (for logs). */
data class ButtonScan(
    val candidates: ImmutableList<CloseCandidate>,
    val forbiddenCount: Int,
    val unlabeledCount: Int,
    val otherCount: Int,
) {
    val closeCandidates: List<CloseCandidate> get() = candidates.filter { it.role == ButtonRole.CLOSE }
}

/**
 * Close/skip buttons inside one [PopupRoot]: every clickable, enabled node (Button, View, TextView,
 * ImageButton alike). A clickable node without its own text or description is named by the texts of
 * its descendants, in walk order (YouTube's "건너뛰기" sits in an unnamed clickable box). The role comes
 * from [SimpleLabelRules]; a forbidden word anywhere in the name refuses the node, and an unnamed
 * clickable node (an X icon) is never a candidate: the prompt then says "대신 닫을 수 없어요".
 *
 * Candidate ids follow B's form ("node:<path>") so C's prompt and the engine's re-check agree.
 */
object CloseButtonFinder {
    fun scan(nodes: List<NodeSnapshot>, root: PopupRoot): ButtonScan {
        val inside = nodes.filter(root::contains)
        val candidates = mutableListOf<CloseCandidate>()
        var forbidden = 0
        var unlabeled = 0
        var other = 0
        for (node in inside) {
            if (!node.clickable || !node.enabled) continue
            val label = nameOf(node, inside)
            val verdict = SimpleLabelRules.judge(label)
            val role = verdict.role
            if (role != null) {
                candidates += CloseCandidate(
                    id = "node:${node.path}",
                    nodePath = node.path,
                    viewId = node.viewId,
                    label = label,
                    bounds = node.bounds,
                    evidence = EvidenceLevel.E2_LABELED_NODE,
                    role = role,
                )
            } else {
                when (verdict.reject) {
                    SimpleLabelReject.FORBIDDEN -> forbidden++
                    SimpleLabelReject.NO_LABEL -> unlabeled++
                    else -> other++
                }
            }
        }
        return ButtonScan(candidates.toPersistentList(), forbidden, unlabeled, other)
    }

    /**
     * The node's own text, else its description, else its descendants' texts joined with a space.
     * Only descendants that are not themselves clickable are used, so a card's own buttons do not
     * name the card.
     */
    fun nameOf(node: NodeSnapshot, nodes: List<NodeSnapshot>): String? {
        node.text?.takeIf { it.isNotBlank() }?.let { return it }
        node.contentDescription?.takeIf { it.isNotBlank() }?.let { return it }
        val prefix = "${node.path}/"
        val joined = nodes.filter { it.path.startsWith(prefix) && !it.clickable }
            .mapNotNull { child -> child.text?.takeIf { it.isNotBlank() } ?: child.contentDescription?.takeIf { it.isNotBlank() } }
            .joinToString(" ")
        return joined.ifBlank { null }
    }
}
