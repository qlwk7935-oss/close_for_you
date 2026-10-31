package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds

/** What the probe reads from one live node: no text is kept, only whether it has any. */
class ProbeNodeInfo(
    val className: String?,
    val bounds: ScreenBounds,
    val clickable: Boolean,
    val hasText: Boolean,
)

/** The probe's view of a tree: live nodes in production, [kr.closeforyou.core.model.NodeSnapshot]s in JVM tests. */
interface ProbeTree<N> {
    fun info(node: N): ProbeNodeInfo
    fun childCount(node: N): Int
    fun child(node: N, index: Int): N?
    fun release(node: N)
}

/** Which subtree the probe found: a Dialog node itself, or the parent of a dim background (dim + card). */
enum class ProbeHitKind { DIALOG, DIM_PARENT }

/** [path]: the subtree to read ("0/i/j"), or null (read the whole tree as before). [visited]: nodes fetched. */
class ProbeResult(val path: String?, val kind: ProbeHitKind?, val visited: Int, val capped: Boolean)

/**
 * F15 (LG 11/1 02:53: an 11번가 read took 2.0 s for 1,424 nodes, about 1.4 ms per node; the popup's
 * own subtree is a few dozen): before reading the whole page, look for the popup root cheaply and read
 * only its subtree.
 *
 * - Last child first: a web modal is usually appended at the end of the page (and drawn on top), so
 *   it comes early; the first Dialog found this way is the last one in walk order, the one
 *   [PopupRootFinder] would pick.
 * - A node with a size that lies wholly off screen is not entered (a probe miss only costs time: the
 *   caller then reads the whole tree).
 * - At most [maxVisited] nodes; a Dialog node (Chrome exports `role="dialog"` as AlertDialog) ends it.
 * - A dim background ([PopupRootFinder]'s first test: clickable, no text, near-screen, not the root)
 *   is used only when the probe went through the whole tree without a Dialog, since a Dialog wins.
 *
 * The probe decides nothing about safety: the caller reads the subtree, runs [PopupRootFinder] on it,
 * and reads the whole tree when no root comes out. Pure Kotlin; JVM tested.
 */
object PopupProbe {
    private const val DIM_MIN_AREA_RATIO = 0.85

    fun <N> find(root: N, tree: ProbeTree<N>, screen: ScreenBounds, maxVisited: Int, maxDepth: Int): ProbeResult {
        val screenArea = screen.width.toLong() * screen.height
        var visited = 0
        var capped = false
        var dimParent: String? = null

        // Returns the dialog path when found.
        fun visit(node: N, path: String, depth: Int): String? {
            if (visited >= maxVisited) {
                capped = true
                return null
            }
            visited += 1
            val info = tree.info(node)
            val b = info.bounds
            val sized = b.width > 0 && b.height > 0
            val onScreen = b.left < screen.right && screen.left < b.right && b.top < screen.bottom && screen.top < b.bottom
            if (sized && !onScreen) return null
            if (sized && info.className?.contains("dialog", ignoreCase = true) == true) return path
            if (dimParent == null && path != ROOT && sized && info.clickable && !info.hasText && screenArea > 0 &&
                b.width.toLong() * b.height >= screenArea * DIM_MIN_AREA_RATIO) {
                dimParent = path.substringBeforeLast('/')
            }
            if (depth >= maxDepth) return null
            for (index in tree.childCount(node) - 1 downTo 0) {
                val child = tree.child(node, index) ?: continue
                val found = try { visit(child, "$path/$index", depth + 1) } finally { tree.release(child) }
                if (found != null) return found
                if (capped) return null
            }
            return null
        }

        val dialog = visit(root, ROOT, 0)
        return when {
            dialog != null -> ProbeResult(dialog, ProbeHitKind.DIALOG, visited, capped)
            dimParent != null && !capped -> ProbeResult(dimParent, ProbeHitKind.DIM_PARENT, visited, capped)
            else -> ProbeResult(null, null, visited, capped)
        }
    }

    private const val ROOT = "0"
}
