package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** F51: after a capped whole walk with no popup, one short read from the end finds a popup late in a huge page (예스24 웹). */
class F51Test {
    private class Tree(nodes: List<NodeSnapshot>) : TailTree<NodeSnapshot> {
        private val byPath = nodes.associateBy { it.path }
        var looks = 0
        override fun look(node: NodeSnapshot, path: String): TailLook {
            looks += 1
            val zero = node.bounds.width <= 0 || node.bounds.height <= 0
            return TailLook(node, TreeWalkRules.descend(true, node.bounds, SCREEN), zero)
        }
        override fun childCount(node: NodeSnapshot) = generateSequence(0) { it + 1 }.takeWhile { byPath.containsKey("${node.path}/$it") }.count()
        override fun child(node: NodeSnapshot, index: Int) = byPath["${node.path}/$index"]
        override fun release(node: NodeSnapshot) = Unit
    }

    /** A dim layer (no Dialog mark, so the probe has nothing to stop at) with its card, a close and a 결제 button. */
    private fun layerAt(index: Int) = listOf(
        node("0/0/$index", bounds = SCREEN),
        node("0/0/$index/0", clickable = true, bounds = SCREEN),
        node("0/0/$index/1", bounds = ScreenBounds(90, 600, 990, 1700)),
        node("0/0/$index/1/0", text = "회원 전용 쿠폰 혜택", bounds = ScreenBounds(90, 600, 990, 800)),
        node("0/0/$index/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1500, 990, 1700)),
        node("0/0/$index/1/2", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(90, 1500, 540, 1700)),
    )

    /** [before] page items, the layer, then [after] items, in walk order (예스24: about 2,500 before, 100 after). */
    private fun hugePage(before: Int, after: Int): List<NodeSnapshot> {
        val head = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN), node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)))
        val items = { range: IntRange -> range.map { node("0/0/$it", text = "상품 $it", clickable = true, bounds = ScreenBounds(0, 200 + it % 2000, 1080, 260 + it % 2000)) } }
        return head + items(0 until before) + layerAt(before) + items(before + 1..before + after)
    }

    private fun subtreeOf(nodes: List<NodeSnapshot>) = { path: String -> nodes.filter { it.path == path || it.path.startsWith("$path/") } }

    @Test fun aLayerAtThe2500thOf2600IsFoundFromTheEnd() {
        val nodes = hugePage(before = 2_500, after = 100)
        assertTrue(nodes.size in 2_600..2_620)
        val partial = nodes.take(WholeReadCap.MAX_NODES)
        // The whole walk stopped at its cap: no popup in the part read (NO_ROOT aborted=true before F51).
        assertNull(PopupRootFinder.find(partial, SCREEN))
        assertTrue(TailRead.needed(true, partial, SCREEN, bounded = false))

        val tree = Tree(nodes)
        val tail = TailRead.walk(nodes.first(), tree, TailRead.MAX_NODES, SimpleCloseEngineFeature.MAX_DEPTH) { false }
        // It reads on to its own cap (it judges nothing itself), the layer among its first 110 nodes.
        assertEquals(TailRead.MAX_NODES, tail.visited)
        assertTrue(tail.nodes.indexOfFirst { it.path == "0/0/2500" } in 0 until 110)
        val (read, outcome) = TailRead.assist(partial, tail, SCREEN, bounded = false, subtreeOf(nodes))
        assertEquals(TailOutcome.FOUND, outcome)
        val root = PopupRootFinder.find(read, SCREEN)
        assertNotNull(root)
        assertEquals(PopupRootKind.DIM_OVERLAY, root!!.kind)
        assertTrue(root.path.startsWith("0/0/2500"))
        // Whole: its 결제 button is there, so it stays asked (F25 safety).
        assertEquals(1, SimplePopupAnalysis.build(read, root, "p", 7, SCREEN, 1L, 0L).scan.riskCount)
        // Walk order kept, each node once.
        assertEquals(read.map { it.path }.distinct(), read.map { it.path })
        assertTrue(read.indexOfFirst { it.path == "0/0/1499" } < read.indexOfFirst { it.path == "0/0/2500" })
    }

    @Test fun aPopupInThePartReadNeedsNoTailRead() {
        val nodes = hugePage(before = 5, after = 3_000)
        val partial = nodes.take(WholeReadCap.MAX_NODES)
        assertEquals("0/0/5", PopupRootFinder.find(partial, SCREEN)!!.path)
        assertFalse(TailRead.needed(true, partial, SCREEN, bounded = false))
        // A whole walk that did not stop: nothing more either.
        assertFalse(TailRead.needed(false, nodes.take(10), SCREEN, bounded = false))
    }

    @Test fun theTailReadStopsAtItsTimeCapAndAddsNothing() {
        val nodes = hugePage(before = 2_500, after = 100)
        val partial = nodes.take(WholeReadCap.MAX_NODES)
        var checks = 0
        val tail = TailRead.walk(nodes.first(), Tree(nodes), TailRead.MAX_NODES, SimpleCloseEngineFeature.MAX_DEPTH) { ++checks > 3 }
        assertTrue(tail.aborted)
        assertTrue(tail.visited <= 16)
        val (read, outcome) = TailRead.assist(partial, tail, SCREEN, bounded = false, subtreeOf(nodes))
        assertEquals(TailOutcome.ABORTED, outcome)
        assertSame(partial, read)
    }

    @Test fun theTailReadStopsAtItsNodeCap() {
        // The layer 1,000 nodes before the end: past the tail's 400, not found, nothing made up.
        val nodes = hugePage(before = 1_600, after = 1_000)
        val partial = nodes.take(WholeReadCap.MAX_NODES)
        val tree = Tree(nodes)
        val tail = TailRead.walk(nodes.first(), tree, TailRead.MAX_NODES, SimpleCloseEngineFeature.MAX_DEPTH) { false }
        assertTrue(tail.aborted)
        assertEquals(TailRead.MAX_NODES, tail.visited)
        assertEquals(TailRead.MAX_NODES, tree.looks)
        assertEquals(TailOutcome.ABORTED, TailRead.assist(partial, tail, SCREEN, bounded = false, subtreeOf(nodes)).second)
    }

    @Test fun aLayerWhoseSubtreeDoesNotReadWholeIsNotUsed() {
        val nodes = hugePage(before = 2_500, after = 100)
        val partial = nodes.take(WholeReadCap.MAX_NODES)
        val tail = TailRead.walk(nodes.first(), Tree(nodes), TailRead.MAX_NODES, SimpleCloseEngineFeature.MAX_DEPTH) { false }
        val (read, outcome) = TailRead.assist(partial, tail, SCREEN, bounded = false) { null }
        assertEquals(TailOutcome.SUBTREE_CUT, outcome)
        assertSame(partial, read)
    }

    @Test fun zeroSizeWrappersComeAfterTheirSizedSiblingsAndOffScreenIsSkipped() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/0/0", text = "본문", bounds = ScreenBounds(0, 200, 1080, 400)),
            node("0/0/1", bounds = ScreenBounds(0, 3000, 1080, 6000)),
            node("0/0/1/0", text = "화면 밖", bounds = ScreenBounds(0, 3000, 1080, 3100)),
            node("0/0/2", bounds = ScreenBounds(0, 0, 0, 0)),
        )
        val order = mutableListOf<String>()
        val tree = object : TailTree<NodeSnapshot> by Tree(nodes) {
            override fun look(node: NodeSnapshot, path: String): TailLook {
                order += path
                val zero = node.bounds.width <= 0 || node.bounds.height <= 0
                // Not visible to the user below the screen: skipped like the whole walk does.
                val visible = node.bounds.top < SCREEN.bottom
                return TailLook(node.takeIf { visible }, TreeWalkRules.descend(visible, node.bounds, SCREEN), zero)
            }
        }
        val tail = TailRead.walk(nodes.first(), tree, TailRead.MAX_NODES, SimpleCloseEngineFeature.MAX_DEPTH) { false }
        assertEquals(listOf("0", "0/0", "0/0/2", "0/0/1", "0/0/0", "0/0/2"), order)
        assertFalse(tail.nodes.any { it.path.startsWith("0/0/1") })
    }

    @Test fun theTailLineHasCodesAndCountsOnly() {
        assertEquals("TAIL_READ result=FOUND nodes=106 visited=112 ms=48", TailReadInfo(TailOutcome.FOUND, 106, 112, 48).line())
        // Worst case for one read on a huge page stays near a second, far below F25's 6.1 s.
        assertTrue(WholeReadCap.MAX_MILLIS + TailRead.MAX_MILLIS + TailRead.SUBTREE_MAX_MILLIS <= 1_100)
    }
}
