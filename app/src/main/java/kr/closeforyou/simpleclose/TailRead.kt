package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/** F51: what the tail read takes from one node: its snapshot when visible, whether to go below it, its size. */
class TailLook(val snapshot: NodeSnapshot?, val descend: Boolean, val zeroSize: Boolean, val password: Boolean = false)

/** F51: the tail read's view of a tree: live nodes in production, [NodeSnapshot]s in JVM tests. */
interface TailTree<N> {
    fun look(node: N, path: String): TailLook
    fun childCount(node: N): Int
    fun child(node: N, index: Int): N?
    fun release(node: N)
}

class TailWalk(val nodes: List<NodeSnapshot>, val visited: Int, val aborted: Boolean, val password: Boolean, val depthCut: List<String>)

/** F51: how the tail read went (debug `READ_TAIL_*`). [SUBTREE_CUT]: a root was found but its own subtree did not read whole. */
enum class TailOutcome { FOUND, NONE, ABORTED, SUBTREE_CUT }

/** F51: the tail read's result for the log: the outcome, the nodes it added, the live nodes it fetched, its time. */
class TailReadInfo(val outcome: TailOutcome, val nodes: Int, val visited: Int, val millis: Long) {
    fun line(): String = "TAIL_READ result=${outcome.name} nodes=$nodes visited=$visited ms=$millis"
}

/**
 * F51 (준영 SM-M446K Android 16 11/4, 예스24 웹: about 2,600 nodes, the popup about the 2,500th in walk order; the whole
 * walk stopped at [WholeReadCap] with the beginning of the page, the probe found no Dialog, dim or layer, `NO_ROOT
 * aborted=true`). After a whole walk that stopped at its cap and gave no popup root, one short read from the end:
 * last child first (popups and sheets are appended at the end of the page), zero-size subtrees after their sized
 * siblings, off-screen subtrees skipped, as the probe. It keeps what it reads (the probe keeps nothing), so
 * [PopupRootFinder] judges it with all its rules. Its own caps, apart from the whole walk's: [MAX_NODES] live nodes,
 * [MAX_MILLIS]; the popup it finds is read again whole ([SUBTREE_MAX_MILLIS]), or not used.
 * F60 (예스24 #welcomeTodayPop, a zero-size root holding the popup): only a zero-size node with no children waits for its
 * siblings. One with children is read in its place: portal wrappers hold popups. The caps are the same, so a big zero-size
 * wrapper can use them up before a popup earlier in the page (F60Test).
 * Worst case for one read on such a page: [WholeReadCap.MAX_MILLIS] + [MAX_MILLIS] + [SUBTREE_MAX_MILLIS] = 1.1 s,
 * at most once every [WholeReadCap.ABORTED_RETRY_MILLIS] on the page (F25 was 6.1 s on every read). Pure; JVM tested.
 */
object TailRead {
    const val MAX_NODES = 400
    const val MAX_MILLIS = 300L
    const val SUBTREE_MAX_MILLIS = 200L

    fun <N> walk(root: N, tree: TailTree<N>, maxVisited: Int, maxDepth: Int, expired: () -> Boolean): TailWalk {
        val out = ArrayList<NodeSnapshot>(128)
        val depthCut = mutableListOf<String>()
        var visited = 0
        var aborted = false
        var password = false

        // Returns whether the node had no size (its parent then walks it after its sized siblings, [revisit]).
        fun visit(node: N, path: String, depth: Int, deferZero: Boolean, revisit: Boolean = false): Boolean {
            if (aborted) return false
            if (depth > maxDepth) {
                if (depthCut.size < 8) depthCut += path
                return false
            }
            if (!revisit) {
                if (visited >= maxVisited || (visited and 3) == 0 && expired()) {
                    aborted = true
                    return false
                }
                visited += 1
            }
            val seen = tree.look(node, path)
            if (seen.zeroSize && deferZero && tree.childCount(node) == 0) return true
            if (!seen.descend) return false
            seen.snapshot?.let { out += it }
            if (seen.password) password = true
            val zero = mutableListOf<Int>()
            for (index in tree.childCount(node) - 1 downTo 0) {
                val child = tree.child(node, index) ?: continue
                try {
                    if (visit(child, "$path/$index", depth + 1, deferZero = true)) zero += index
                } finally {
                    tree.release(child)
                }
                if (aborted) return false
            }
            for (index in zero) {
                val child = tree.child(node, index) ?: continue
                try {
                    visit(child, "$path/$index", depth + 1, deferZero = false, revisit = true)
                } finally {
                    tree.release(child)
                }
                if (aborted) return false
            }
            return false
        }

        visit(root, ROOT, 0, deferZero = false)
        return TailWalk(out, visited, aborted, password, depthCut)
    }

    /** The nodes of [partial] and [tail] once each (by path), in walk order (parent first, then children by index). */
    fun merge(partial: List<NodeSnapshot>, tail: List<NodeSnapshot>): List<NodeSnapshot> {
        val seen = partial.mapTo(HashSet()) { it.path }
        return (partial + tail.filter { seen.add(it.path) }).sortedWith(WALK_ORDER)
    }

    /**
     * The read after a capped whole walk: [partial] with the [tail] walk merged in. A popup root found there is read
     * again whole ([readSubtree], its own caps; F25 safety: a 결제·동의 button must not be missing from it); when that
     * does not come back, [partial] as it was (no popup from the tail).
     */
    fun assist(partial: List<NodeSnapshot>, tail: TailWalk, screen: ScreenBounds, bounded: Boolean, readSubtree: (String) -> List<NodeSnapshot>?): Pair<List<NodeSnapshot>, TailOutcome> {
        val merged = merge(partial, tail.nodes)
        val path = PopupRootFinder.find(merged, screen, bounded)?.path?.takeIf { it != ROOT }
            ?: return partial to if (tail.aborted) TailOutcome.ABORTED else TailOutcome.NONE
        val whole = readSubtree(path) ?: return partial to TailOutcome.SUBTREE_CUT
        return (merged.filterNot { it.path == path || it.path.startsWith("$path/") } + whole).sortedWith(WALK_ORDER) to TailOutcome.FOUND
    }

    /** Whether the tail read runs: the flag, a whole walk that stopped at its cap, and no popup root in what it read. */
    fun needed(wholeAborted: Boolean, partial: List<NodeSnapshot>, screen: ScreenBounds, bounded: Boolean): Boolean =
        SimpleCloseEngineFeature.TAIL_READ_AFTER_CAP && wholeAborted &&
            PopupRootFinder.find(partial, screen, bounded)?.path?.takeIf { it != ROOT } == null

    val WALK_ORDER = Comparator<NodeSnapshot> { a, b ->
        val x = a.path.split('/')
        val y = b.path.split('/')
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toIntOrNull() ?: 0).compareTo(y[i].toIntOrNull() ?: 0)
            if (c != 0) return@Comparator c
        }
        x.size.compareTo(y.size)
    }

    private const val ROOT = "0"
}
