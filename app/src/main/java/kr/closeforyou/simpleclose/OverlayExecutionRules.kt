package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionDemoRules
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.ui.voice.choice.RememberChoiceRules

/** Why an automatic press is held back right before it happens (B_DEMO_RULES.md 6절). Codes only. */
enum class PrePressBlock {
    /** The popup now reads as an important decision or scam-like (it was not when decided). */
    BECAME_DECISION_OR_SCAM,
    /** A button with a decision word appeared in the popup since the decision. */
    RISKY_BUTTON_APPEARED,
    /** The popup is now another kind the preset does not close. */
    KIND_CHANGED,
}

/**
 * B_DEMO_RULES.md 6-1 (proposal, 11/2): the execution basis of a same-window overlay right before the press.
 *
 * The engine already re-checks that the popup root is still at the same place and the target still has the
 * same name ([SimpleTreeCollector.rootStillPresent], [SimpleTreeCollector.targetMatches]). A same-window overlay
 * shares its window with the page, so its words can change under an unchanged root: a carousel slide, or a
 * step that turns a promotion into a payment or consent step. Comparing the words themselves would stop every
 * carousel (예스24 "8/8", 롯데온 "1/1"), so this compares **B's verdict** on the fresh popup instead.
 */
object PrePressRecheck {
    private val classifier = DecisionPopupClassifier()

    /** Null = still fine to press; [fresh] is the popup read again just before the press. */
    suspend fun check(decided: PopupClassification, fresh: SimplePopup, closableKinds: Set<PopupClassification>): PrePressBlock? {
        val kind = classifier.classify(fresh.analysis)
        if (RememberChoiceRules.suspiciousByB(fresh.analysis) || kind == PopupClassification.USER_DECISION) return PrePressBlock.BECAME_DECISION_OR_SCAM
        if (fresh.scan.riskCount > fresh.scan.promoBuyCount) return PrePressBlock.RISKY_BUTTON_APPEARED
        // Strong mode closes every kind but decisions and scams; elsewhere the kind must stay one the preset closes.
        if (kind != decided && kind !in closableKinds) return PrePressBlock.KIND_CHANGED
        return null
    }
}

/** Why the next automatic close in a chain is asked instead (B_DEMO_RULES.md 6-2). Codes only. */
enum class ChainBlock {
    /** More than [ChainGuard.maxPerWindow] automatic closes in this app within [ChainGuard.windowMillis]. */
    RATE_LIMIT,
    /** A new popup appeared right after our own press: the press may have opened it (a fake close). */
    OPENED_BY_OUR_PRESS,
}

/**
 * B_DEMO_RULES.md 6-2 (proposal, 11/2): consecutive popups. In memory only, per service session, keyed by
 * package and the popup's coarse key; no screen text. Same limits as B's reviewed auto-close policy
 * ([kr.closeforyou.decision.DecisionAutoClose]: 3 per app per minute), plus one rule for the demo engine:
 * a *different* popup that appears within [openedByPressMillis] of our own press is asked, not pressed, since
 * a fake close that opens another layer looks exactly like that. The same popup coming back is
 * [PopupMemory.reappeared]'s case and is not counted here.
 */
class ChainGuard(
    private val maxPerWindow: Int = 3,
    private val windowMillis: Long = 60_000,
    private val openedByPressMillis: Long = 1_500,
) {
    private val closes = HashMap<String, ArrayDeque<Long>>()
    private var lastPress: Triple<String, String, Long>? = null

    /** Before an automatic close of [popupKey] in [packageName] at [now]. */
    @Synchronized
    fun check(packageName: String, popupKey: String, now: Long): ChainBlock? {
        lastPress?.let { (pkg, key, at) ->
            if (pkg == packageName && key != popupKey && now - at in 0 until openedByPressMillis) return ChainBlock.OPENED_BY_OUR_PRESS
        }
        val recent = closes[packageName]?.count { now - it in 0 until windowMillis } ?: 0
        return if (recent >= maxPerWindow) ChainBlock.RATE_LIMIT else null
    }

    /** Right after any press of ours (automatic or the person's choice). */
    @Synchronized
    fun pressed(packageName: String, popupKey: String, now: Long, automatic: Boolean) {
        lastPress = Triple(packageName, popupKey, now)
        if (!automatic) return
        val list = closes.getOrPut(packageName) { ArrayDeque() }
        list.addLast(now)
        while (list.isNotEmpty() && now - list.first() >= windowMillis) list.removeFirst()
        if (closes.size > 32) closes.remove(closes.keys.first())
    }
}

/**
 * B_DEMO_RULES.md 8-1 (review of F32~F33, proposal): what sits right beside a modal card root. A card found by shape
 * ([PopupRootKind.MODAL_CARD]) may hold only part of the popup (a front layer or a bottom sheet sits beside the page itself): 11번가 app puts its round X under the card, so a button bar can be the card's sibling too. A "정기 결제 동의" there
 * was neither read by B nor counted as risky, and the popup was closed as a notice. So the nodes next to the card count
 * for the safety checks (never as press targets) when its parent is a small layer, not the page: fewer than
 * [MAX_PARENT_CLICKABLES] clickables under it, and the node within [MAX_GAP_RATIO] of the screen height of the card,
 * overlapping it sideways.
 */
object PopupNeighbors {
    private val SHAPED = setOf(PopupRootKind.MODAL_CARD)
    private const val MAX_PARENT_CLICKABLES = 12
    private const val MAX_GAP_RATIO = 0.1

    fun of(visible: List<NodeSnapshot>, root: PopupRoot, screen: ScreenBounds): List<NodeSnapshot> {
        if (root.kind !in SHAPED || '/' !in root.path) return emptyList()
        val parent = root.path.substringBeforeLast('/')
        val under = visible.filter { it.path.startsWith("$parent/") }
        if (under.count { it.clickable } >= MAX_PARENT_CLICKABLES) return emptyList()
        val gap = screen.height * MAX_GAP_RATIO
        return under.filter { node ->
            val b = node.bounds
            !root.contains(node) && b.left < root.bounds.right && b.right > root.bounds.left &&
                b.top < root.bounds.bottom + gap && b.bottom > root.bounds.top - gap &&
                // A layer behind the card (the dim, the page) is not beside it.
                !(b.left <= root.bounds.left && b.top <= root.bounds.top && b.right >= root.bounds.right && b.bottom >= root.bounds.bottom)
        }
    }

    /** F35: whether [nodes] (one read) hold anything under the card's parent besides the card: its siblings were read. */
    fun siblingsRead(nodes: List<NodeSnapshot>, root: PopupRoot): Boolean {
        if ('/' !in root.path) return false
        val parent = root.path.substringBeforeLast('/')
        return nodes.any { it.path.startsWith("$parent/") && !root.contains(it) }
    }

    /** [scan] with the risky buttons among [neighbors] added: asked and offered as [버튼으로 이동], never pressed. */
    fun withNeighbors(scan: ButtonScan, neighbors: List<NodeSnapshot>): ButtonScan {
        if (neighbors.isEmpty()) return scan
        val clickable = neighbors.filter { it.clickable && it.enabled }.associateWith { CloseButtonFinder.nameOf(it, neighbors) }
        val risky = clickable.filterValues { SimpleLabelRules.isRisky(it) }
        val shown = clickable.filterValues { SimpleLabelRules.isDecision(it) }.map { (node, label) ->
            CloseCandidate("node:${node.path}", node.path, node.viewId, label, node.bounds, EvidenceLevel.E2_LABELED_NODE, ButtonRole.USER_DECISION)
        }
        return scan.copy(
            riskCount = scan.riskCount + risky.size,
            promoBuyCount = scan.promoBuyCount + risky.values.count { it != null && DecisionDemoRules.isPromoPurchaseLabel(it) },
            riskButtons = (scan.riskButtons + shown).toPersistentList(),
        )
    }
}
