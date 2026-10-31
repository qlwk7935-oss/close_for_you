package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/** How the popup root was recognised. Logged as a code; never with text. */
enum class PopupRootKind {
    /** A node whose class is a Dialog (Chrome exports `role="dialog"` web popups as android.app.AlertDialog). */
    ALERT_DIALOG,
    /** The active window itself is a bounded dialog window over the same app's main window. */
    DIALOG_WINDOW,
    /** An app's own overlay: a clickable near-screen dim background with a bounded card. */
    DIM_OVERLAY,
}

/** The popup's own subtree: every node at [path] or below it. */
data class PopupRoot(val path: String, val bounds: ScreenBounds, val kind: PopupRootKind) {
    fun contains(node: NodeSnapshot): Boolean = node.path == path || node.path.startsWith("$path/")
}

/**
 * Finds one popup root in a visible-node tree ([NodeSnapshot] DTOs in walk order, paths "0/i/j").
 * Pure Kotlin so the device dumps (11번가, 옥션, 올리브영) run as JVM tests.
 *
 * Order: a Dialog node (①) wins; without one, a bounded dialog window (②, decided by the collector
 * from the window list); then a dim overlay (③). Nodes with empty bounds or outside the screen are
 * never part of a root and never a root, so Chrome's zero-height footer links cannot be a popup.
 */
object PopupRootFinder {
    /** An overlay background covers at least this much of the screen. */
    private const val DIM_MIN_AREA_RATIO = 0.85

    /** A card is bounded: smaller than this part of the screen (same bar as A's detector). */
    private const val CARD_MAX_AREA_RATIO = 0.96

    fun find(nodes: List<NodeSnapshot>, screen: ScreenBounds, activeWindowBounded: Boolean = false): PopupRoot? {
        val visible = nodes.filter { isVisible(it, screen) }
        dialogRoot(visible)?.let { return it }
        if (activeWindowBounded) {
            val root = visible.firstOrNull { it.path == "0" } ?: visible.firstOrNull() ?: return null
            return PopupRoot(root.path, root.bounds, PopupRootKind.DIALOG_WINDOW)
        }
        return dimOverlayRoot(visible, screen)
    }

    /** True when the node can be seen: non-empty bounds that lie inside the screen. */
    fun isVisible(node: NodeSnapshot, screen: ScreenBounds): Boolean {
        val b = node.bounds
        return b.width > 0 && b.height > 0 && b.right > screen.left && b.left < screen.right &&
            b.bottom > screen.top && b.top < screen.bottom
    }

    private fun dialogRoot(nodes: List<NodeSnapshot>): PopupRoot? {
        val dialogs = nodes.filter { it.className?.contains("dialog", ignoreCase = true) == true }
        if (dialogs.isEmpty()) return null
        val outermost = dialogs.filter { dialog -> dialogs.none { dialog.path != it.path && dialog.path.startsWith("${it.path}/") } }
        // Several dialogs at once: the last in walk order is drawn on top (later sibling).
        val chosen = outermost.last()
        return PopupRoot(chosen.path, chosen.bounds, PopupRootKind.ALERT_DIALOG)
    }

    /**
     * A clickable node covering most of the screen (the dim background), not the tree root, with a
     * bounded card either among its children or among its siblings. The root is the smallest
     * subtree holding both: the dim node itself, or its parent.
     */
    private fun dimOverlayRoot(nodes: List<NodeSnapshot>, screen: ScreenBounds): PopupRoot? {
        val screenArea = area(screen)
        if (screenArea <= 0) return null
        val byPath = nodes.associateBy { it.path }
        val dims = nodes.filter { it.path != "0" && it.clickable && area(it.bounds).toDouble() / screenArea >= DIM_MIN_AREA_RATIO }
        for (dim in dims.asReversed()) {
            val children = nodes.filter { it.path.startsWith("${dim.path}/") }
            if (children.any { isCard(it, screenArea) }) return PopupRoot(dim.path, dim.bounds, PopupRootKind.DIM_OVERLAY)
            // A dim background next to its card is a leaf; a scrolling list that fills the screen is not.
            if (children.isNotEmpty()) continue
            val parentPath = dim.path.substringBeforeLast('/', missingDelimiterValue = "")
            val parent = byPath[parentPath] ?: continue
            val siblings = nodes.filter { it.path != dim.path && it.path.startsWith("$parentPath/") && !it.path.startsWith("${dim.path}/") }
            if (siblings.any { isCard(it, screenArea) }) return PopupRoot(parent.path, parent.bounds, PopupRootKind.DIM_OVERLAY)
        }
        return null
    }

    /** A bounded, non-clickable container or text: the card's own buttons sit inside it. A clickable item is a list row. */
    private fun isCard(node: NodeSnapshot, screenArea: Long): Boolean {
        val a = area(node.bounds)
        return a > 0 && a.toDouble() / screenArea < CARD_MAX_AREA_RATIO && !node.clickable
    }

    private fun area(b: ScreenBounds): Long = b.width.toLong() * b.height.toLong()
}
