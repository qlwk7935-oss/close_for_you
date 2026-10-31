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

/**
 * Which subtree the probe found: a Dialog node itself, the parent of a dim background (dim + card), or
 * (F16) a front layer: a large box over an earlier sibling that covers half of it.
 */
enum class ProbeHitKind { DIALOG, DIM_PARENT, LAYER }

/**
 * [path]: the subtree to read ("0/i/j"), or null (read the whole tree as before). [visited]: nodes fetched.
 * [context]: for a [ProbeHitKind.LAYER], the earlier sibling under it; the collector adds that one node (not
 * its subtree) so [PopupRootFinder] can see the layer is over the page.
 */
class ProbeResult(val path: String?, val kind: ProbeHitKind?, val visited: Int, val capped: Boolean, val context: String? = null)

/** F20: what the probe of a read did (debug log `PROBE_*`): hit by kind, or missed (capped or not). */
enum class ProbeOutcome { NOT_RUN, HIT_DIALOG, HIT_DIM_PARENT, HIT_LAYER, MISS_CAPPED, MISS }

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
 * - F16 ([SimpleCloseEngineFeature.FIND_FRONT_LAYERS]): a large box ([PopupRootFinder.layerSized]) that an
 *   earlier sibling covers by half is a front layer. Its earlier siblings come after it here, so the first
 *   one that lies under it ends the probe like a Dialog, before that sibling's own subtree (the page) is
 *   walked. A layer whose parent is the dim's parent is the dim's card: [ProbeHitKind.DIM_PARENT] then.
 *
 * The probe decides nothing about safety: the caller reads the subtree, runs [PopupRootFinder] on it,
 * and reads the whole tree when no root comes out. Pure Kotlin; JVM tested.
 */
object PopupProbe {
    private const val DIM_MIN_AREA_RATIO = 0.85

    fun <N> find(
        root: N,
        tree: ProbeTree<N>,
        screen: ScreenBounds,
        maxVisited: Int,
        maxDepth: Int,
        frontLayers: Boolean = SimpleCloseEngineFeature.FIND_FRONT_LAYERS,
    ): ProbeResult {
        val screenArea = screen.width.toLong() * screen.height
        var visited = 0
        var capped = false
        var dimParent: String? = null
        var hit: ProbeResult? = null

        // [above]: later siblings already seen that are layer-sized. Sets [hit] when the probe can stop.
        // F20: [deferZero] = a zero-size node is not entered now (it returns its empty bounds); its parent comes
        // back to it after its sized siblings ([revisit]: already counted).
        fun visit(node: N, path: String, depth: Int, above: List<Pair<Int, ScreenBounds>>, deferZero: Boolean = false, revisit: Boolean = false): ScreenBounds? {
            if (!revisit && visited >= maxVisited) {
                capped = true
                return null
            }
            if (!revisit) visited += 1
            val info = tree.info(node)
            val b = info.bounds
            val sized = b.width > 0 && b.height > 0
            if (!sized && deferZero) return b
            val onScreen = b.left < screen.right && screen.left < b.right && b.top < screen.bottom && screen.top < b.bottom
            if (sized && !onScreen) return null
            if (sized && info.className?.contains("dialog", ignoreCase = true) == true) {
                hit = ProbeResult(path, ProbeHitKind.DIALOG, 0, false)
                return b
            }
            if (dimParent == null && path != ROOT && sized && info.clickable && !info.hasText && screenArea > 0 &&
                b.width.toLong() * b.height >= screenArea * DIM_MIN_AREA_RATIO) {
                dimParent = path.substringBeforeLast('/')
            }
            // F16: this node (page content) lies under a later sibling: that sibling is a front layer. Decided here,
            // before this node's own subtree (the page) is walked.
            if (frontLayers && sized) {
                above.firstOrNull { (_, layer) -> PopupRootFinder.coversHalf(b, layer) }?.let { (index, _) ->
                    val parent = path.substringBeforeLast('/')
                    hit = if (parent == dimParent) ProbeResult(parent, ProbeHitKind.DIM_PARENT, 0, false)
                    else ProbeResult("$parent/$index", ProbeHitKind.LAYER, 0, false, context = path)
                    return b
                }
            }
            if (depth >= maxDepth) return b
            val layers = mutableListOf<Pair<Int, ScreenBounds>>()
            val zeroSize = mutableListOf<Int>()
            for (index in tree.childCount(node) - 1 downTo 0) {
                val child = tree.child(node, index) ?: continue
                val childBounds = try { visit(child, "$path/$index", depth + 1, layers, deferZero = true) } finally { tree.release(child) }
                if (hit != null || capped) return b
                if (childBounds != null && (childBounds.width <= 0 || childBounds.height <= 0)) {
                    zeroSize += index
                    continue
                }
                if (frontLayers && childBounds != null && childBounds.width > 0 && childBounds.height > 0 && PopupRootFinder.layerSized(childBounds, screen)) {
                    layers += index to childBounds
                }
            }
            // F20 (LG 11/1 11번가: READ_FULL_AFTER_PROBE): a zero-height footer after the sheet held hundreds of
            // zero-size links and used up the probe before the sheet. Zero-size subtrees (portal wrappers may hold a
            // modal) are walked only after their sized siblings.
            for (index in zeroSize) {
                val child = tree.child(node, index) ?: continue
                try { visit(child, "$path/$index", depth + 1, emptyList(), revisit = true) } finally { tree.release(child) }
                if (hit != null || capped) return b
            }
            return b
        }

        visit(root, ROOT, 0, emptyList())
        hit?.let { return ProbeResult(it.path, it.kind, visited, capped, it.context) }
        return when {
            dimParent != null && !capped -> ProbeResult(dimParent, ProbeHitKind.DIM_PARENT, visited, capped)
            else -> ProbeResult(null, null, visited, capped)
        }
    }

    private const val ROOT = "0"
}
