package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/** F53: why a button is not pressed: what may lie over it. */
enum class StackBlock {
    /** An application window above the popup's window lies over the button (the window layer says so). */
    WINDOW_ABOVE,
    /** Something of a size, later in the page's order than the popup, lies over the button. */
    COVER_LATER,
    /** Another popup lies over the same place; which one is in front cannot be told. */
    POPUP_OVERLAP,
}

/** F53: a look at what else is on screen ([nodes] outside the popup's own subtree; [partial] = a cap stopped the walk). */
class StackLook(val nodes: List<NodeSnapshot>, val screen: ScreenBounds, val windows: List<WindowShape>, val baseWindowId: Int,
    val partial: Boolean, val visited: Int, val millis: Long)

/**
 * F53 (우진 SM-F966N, canvas.suwon.ac.kr, TalkBack 켬: a notice in front, [닫기] closed another popup behind it, the card said
 * closed, the notice stayed). The tree has no drawing order for web content: walk order is the DOM, and a higher z-index
 * earlier in the DOM is drawn over it. `ACTION_CLICK` reaches a node whatever lies over it. So what can be told is:
 * - windows have layers: a window above that lies over the button is sure ([StackBlock.WINDOW_ABOVE]);
 * - inside one window only shapes: a node of a size later in the page, or another popup by [PopupRootFinder]'s own rules, whose
 *   box holds the button's middle. Which is in front is not known, so the button is not pressed.
 * A popup with nothing over the button is pressed as before. The walk has caps ([StackLook.partial]): what it did not read
 * cannot stop a press; that limit is in the docs (8-46). Pure; JVM tested.
 */
object PopupStack {
    /** The look's own caps: nodes fetched from the end of the tree, and its time. */
    const val LOOK_MAX_NODES = 400
    const val LOOK_MAX_MILLIS = 250L

    /** At most this many other popups are looked for. */
    private const val MAX_OTHERS = 4

    /** A cover is at least this part of the screen (a page's small floating button is not). */
    private const val COVER_MIN_AREA_RATIO = 0.05
    private const val COVER_MAX_AREA_RATIO = 0.7
    private const val COVER_SIDE_MARGIN = 0.02

    /** Roots whose press is not about a popup in front: a bottom banner hides nothing (F37), a video's skip (F36). */
    fun applies(root: PopupRoot): Boolean = root.kind != PopupRootKind.BOTTOM_BANNER && root.kind != PopupRootKind.VIDEO_COVER

    /** The other popups in [nodes] by the finder's own rules: never [root], inside it, or around it. */
    fun others(nodes: List<NodeSnapshot>, root: PopupRoot, screen: ScreenBounds): List<PopupRoot> {
        val found = mutableListOf<PopupRoot>()
        var rest = nodes.filterNot { root.contains(it) }
        while (found.size < MAX_OTHERS) {
            val other = PopupRootFinder.find(rest, screen) ?: break
            rest = rest.filterNot { other.contains(it) }
            if (other.path != "0" && !root.path.startsWith("${other.path}/")) found += other
        }
        return found
    }

    /** What lies over [button], most certain first; null = nothing that was read. */
    fun block(button: ScreenBounds, root: PopupRoot, look: StackLook): StackBlock? {
        if (windowAbove(button, look.windows, look.baseWindowId)) return StackBlock.WINDOW_ABOVE
        if (root.windowId != null) return null
        if (coverLater(button, root, look.nodes, look.screen)) return StackBlock.COVER_LATER
        return if (others(look.nodes, root, look.screen).any { holds(it.bounds, button) }) StackBlock.POPUP_OVERLAP else null
    }

    /** An application window with a higher layer holds the button's middle (a card in a whole-screen window: its root view's box). */
    fun windowAbove(button: ScreenBounds, windows: List<WindowShape>, baseWindowId: Int): Boolean {
        val base = windows.firstOrNull { it.id == baseWindowId } ?: return false
        return windows.any { w ->
            w.id != baseWindowId && w.kind == WindowKind.APPLICATION && w.layer > base.layer && holds(smaller(w), button)
        }
    }

    private fun smaller(w: WindowShape): ScreenBounds {
        val root = w.root ?: return w.window
        return if (root.width > 0 && root.height > 0 && root.width.toLong() * root.height < w.window.width.toLong() * w.window.height) root else w.window
    }

    /**
     * A card later in walk order than the popup over the button: a sibling of the popup or of one of its ancestors (a page's
     * own cards sit deeper), not touching the side edges and not page-sized, with something in it. A modal early in the DOM has
     * the whole page later than it: the page's full-width sections and inner cards do not count.
     */
    fun coverLater(button: ScreenBounds, root: PopupRoot, nodes: List<NodeSnapshot>, screen: ScreenBounds): Boolean {
        val screenArea = screen.width.toLong() * screen.height
        val margin = screen.width * COVER_SIDE_MARGIN
        return nodes.any { node ->
            val parent = node.path.substringBeforeLast('/', missingDelimiterValue = "")
            val area = node.bounds.width.toLong() * node.bounds.height
            !root.contains(node) && parent.isNotEmpty() && root.path.startsWith("$parent/") &&
                TailRead.WALK_ORDER.compare(node, rootMark(root)) > 0 && holds(node.bounds, button) &&
                node.bounds.left > screen.left + margin && node.bounds.right < screen.right - margin &&
                area >= screenArea * COVER_MIN_AREA_RATIO && area <= screenArea * COVER_MAX_AREA_RATIO && hasContent(node, nodes)
        }
    }

    private fun rootMark(root: PopupRoot) = NodeSnapshot(root.path, null, null, null, null, false, false, root.bounds)

    private fun hasContent(node: NodeSnapshot, nodes: List<NodeSnapshot>): Boolean =
        named(node) || node.clickable || nodes.any { it.path.startsWith("${node.path}/") && (named(it) || it.clickable) }

    private fun named(node: NodeSnapshot) = !node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()

    private fun holds(box: ScreenBounds, button: ScreenBounds): Boolean {
        val x = (button.left + button.right) / 2
        val y = (button.top + button.bottom) / 2
        return x >= box.left && x < box.right && y >= box.top && y < box.bottom
    }

    /** One debug line: bounds and codes only (never text, a path, or a window id). */
    fun line(block: StackBlock?, button: ScreenBounds, root: PopupRoot, look: StackLook): String {
        val others = if (root.windowId != null) emptyList() else others(look.nodes, root, look.screen)
        val shown = others.joinToString(",") { "${it.kind.name}:${box(it.bounds)}" }
        return "STACK_LOOK block=${block?.name ?: "NONE"} button=${box(button)} others=${others.size} [$shown] partial=${look.partial} visited=${look.visited} ms=${look.millis}"
    }

    /** The popup and the button a press goes to (which candidate it was, next time one is pressed wrongly). */
    fun pressLine(by: String, root: PopupRoot, button: ScreenBounds): String =
        "PRESS_TARGET by=$by kind=${root.kind.name} root=${box(root.bounds)} button=${box(button)}"

    /** Which popup was picked this read. */
    fun pickLine(root: PopupRoot, mode: TreeReadMode): String = "ROOT_PICK kind=${root.kind.name} root=${box(root.bounds)} mode=${mode.name}"

    private fun box(b: ScreenBounds) = "${b.left},${b.top},${b.right},${b.bottom}"
}
