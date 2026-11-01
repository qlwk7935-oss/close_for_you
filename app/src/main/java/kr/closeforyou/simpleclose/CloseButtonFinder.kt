package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.decision.DecisionDemoRules

/** What the popup's clickable nodes gave: press targets, and counts of what was refused (for logs). */
data class ButtonScan(
    val candidates: ImmutableList<CloseCandidate>,
    val forbiddenCount: Int,
    val unlabeledCount: Int,
    val otherCount: Int,
    /** F16: buttons with a decision word ([SimpleLabelRules.isRisky]); part of [forbiddenCount]. Any one keeps the popup asked. */
    val riskCount: Int = 0,
    /**
     * F16: the popup's one unnamed X-shaped button (no name, or X marks only), offered to the person as
     * "X 모양 닫기" and pressed only on their tap (AGENTS 6-3); never in [candidates], never automatic.
     */
    val xButton: CloseCandidate? = null,
    /**
     * F20: the risky buttons themselves (role USER_DECISION), for [버튼으로 이동] only: the person is told where
     * they are; they are never pressed and never in [candidates].
     */
    val riskButtons: ImmutableList<CloseCandidate> = persistentListOf(),
    /**
     * B_DEMO_RULES.md 1절: risky buttons that are only a promotion's purchase call to action ("지금 구매하기");
     * part of [riskCount]. Never pressed; they stop keeping the popup asked only when the rule is on and
     * B called the popup an advertisement or a coupon.
     */
    val promoBuyCount: Int = 0,
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
        var risk = 0
        var promoBuy = 0
        val riskButtons = mutableListOf<CloseCandidate>()
        val xShaped = mutableListOf<NodeSnapshot>()
        for (node in inside) {
            if (!node.clickable || !node.enabled) continue
            val label = nameOf(node, inside)
            if (SimpleLabelRules.isRisky(label)) {
                risk++
                if (label != null && DecisionDemoRules.isPromoPurchaseLabel(label)) promoBuy++
                riskButtons += CloseCandidate("node:${node.path}", node.path, node.viewId, label, node.bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.USER_DECISION)
            }
            if ((label == null || SimpleLabelRules.isXMark(label)) && looksLikeX(node, root)) xShaped += node
            val verdict = SimpleLabelRules.judge(label)
            // B review 11/1: a box named by the words inside it is a close only by a complete close phrase.
            val namedByChildren = node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()
            val role = verdict.role?.takeIf { !namedByChildren || SimpleLabelRules.roleOfExactPhrase(label) == it }
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
                    null -> other++
                    SimpleLabelReject.FORBIDDEN -> forbidden++
                    SimpleLabelReject.NO_LABEL -> unlabeled++
                    else -> other++
                }
            }
        }
        // Two or more X-like buttons: which one closes is a guess, so none is offered.
        val x = xShaped.singleOrNull()?.let {
            CloseCandidate("node:${it.path}", it.path, it.viewId, nameOf(it, inside), it.bounds, EvidenceLevel.E4_VISUAL_ONLY, ButtonRole.CLOSE)
        }
        // F20: plainest close first, reading order on a tie ([SimpleLabelRules.clarity]), so C's prompt offers first
        // exactly the button 강력 모드 presses ([StrongCloseRules]).
        val ordered = candidates.sortedBy { if (it.role == ButtonRole.CLOSE) SimpleLabelRules.clarity(it.label) else CLARITY_SKIP }
        return ButtonScan(ordered.toPersistentList(), forbidden, unlabeled, other, risk, x, riskButtons.toPersistentList(), promoBuy)
    }

    /** Small and about square, inside the popup: the shape of an X icon (a big unnamed box is an image or the dim). */
    private fun looksLikeX(node: NodeSnapshot, root: PopupRoot): Boolean {
        val b = node.bounds
        if (b.width <= 0 || b.height <= 0 || node.path == root.path) return false
        val maxSide = maxOf(X_MAX_SIDE_PX, (minOf(root.bounds.width, root.bounds.height) * X_MAX_SIDE_RATIO).toInt())
        val aspect = b.width.toDouble() / b.height
        return b.width <= maxSide && b.height <= maxSide && aspect in 0.5..2.0
    }

    private const val CLARITY_SKIP = 9
    private const val X_MAX_SIDE_PX = 120
    private const val X_MAX_SIDE_RATIO = 0.2

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
