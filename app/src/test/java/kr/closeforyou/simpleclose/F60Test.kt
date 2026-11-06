package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F60: a popup under a zero-size root late in a long page (예스24 #welcomeTodayPop), and its stillness read from its own subtree. */
class F60Test {
    private class Tree(nodes: List<NodeSnapshot>) : TailTree<NodeSnapshot> {
        private val byPath = nodes.associateBy { it.path }
        override fun look(node: NodeSnapshot, path: String): TailLook {
            val zero = node.bounds.width <= 0 || node.bounds.height <= 0
            return TailLook(node, TreeWalkRules.descend(true, node.bounds, SCREEN), zero)
        }
        override fun childCount(node: NodeSnapshot) = generateSequence(0) { it + 1 }.takeWhile { byPath.containsKey("${node.path}/$it") }.count()
        override fun child(node: NodeSnapshot, index: Int) = byPath["${node.path}/$index"]
        override fun release(node: NodeSnapshot) = Unit
    }

    private val none = ScreenBounds(0, 0, 0, 0)
    private val head = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN), node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)))
    private fun items(range: IntRange, parent: String = "0/0") = range.map { node("$parent/$it", text = "상품 $it", clickable = true, bounds = ScreenBounds(0, 200 + it % 2000, 1080, 260 + it % 2000)) }

    /** A dim layer with its card, a close and a 결제 button, at [at]. */
    private fun layer(at: String, withPay: Boolean = true) = listOf(
        node(at, bounds = SCREEN),
        node("$at/0", clickable = true, bounds = SCREEN),
        node("$at/1", bounds = ScreenBounds(90, 600, 990, 1700)),
        node("$at/1/0", text = "회원 전용 쿠폰 혜택", bounds = ScreenBounds(90, 600, 990, 800)),
        node("$at/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1500, 990, 1700)),
    ) + if (withPay) listOf(
        node("$at/1/2", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(90, 1500, 540, 1700)),
    ) else emptyList()

    private fun subtreeOf(nodes: List<NodeSnapshot>) = { path: String -> nodes.filter { it.path == path || it.path.startsWith("$path/") } }
    private fun walk(nodes: List<NodeSnapshot>) = TailRead.walk(nodes.first(), Tree(nodes), TailRead.MAX_NODES, SimpleCloseEngineFeature.MAX_DEPTH) { false }

    // 예스24: a zero-size root with the popup in it, the 2,500th of 2,600; deferred past its sized siblings it was never reached.
    @Test fun aZeroSizeRootWithChildrenIsReadAtOnce() {
        val popupAt = "0/0/2500"
        val nodes = head + items(0 until 2_500) + node(popupAt, bounds = none) + layer("$popupAt/0") + items(2_501..2_600)
        val tail = walk(nodes)
        assertTrue(tail.nodes.any { it.path == "$popupAt/0/1/1" })
        assertTrue(tail.nodes.indexOfFirst { it.path == popupAt } in 0 until 110)
        val partial = nodes.take(WholeReadCap.MAX_NODES)
        val (read, outcome) = TailRead.assist(partial, tail, SCREEN, bounded = false, subtreeOf(nodes))
        assertEquals(TailOutcome.FOUND, outcome)
        assertTrue(PopupRootFinder.find(read, SCREEN)!!.path.startsWith(popupAt))
    }

    @Test fun aZeroSizeLeafStillComesAfterItsSizedSiblings() {
        val nodes = head + node("0/0/0", text = "본문", bounds = ScreenBounds(0, 200, 1080, 400)) + node("0/0/1", bounds = none)
        val paths = walk(nodes).nodes.map { it.path }
        assertTrue(paths.indexOf("0/0/0") < paths.indexOf("0/0/1"))
        assertEquals(nodes.size, walk(nodes).visited)
    }

    // The cost: the walk's caps are the same, so a big zero-size wrapper now spends them first.
    @Test fun theCapsStayAndABigZeroSizeWrapperCanUseThemUp() {
        fun page(kids: Int) = head + layer("0/0/0") + node("0/0/1", bounds = none) + items(0 until kids, "0/0/1")
        val small = walk(page(300))
        assertFalse(small.aborted)
        assertTrue(small.nodes.any { it.path == "0/0/0/1/1" })
        val big = walk(page(450))
        assertEquals(TailRead.MAX_NODES, big.visited)
        assertTrue(big.aborted)
        // A popup before such a wrapper is not reached this read. Measured on devices only by the regression rows.
        assertFalse(big.nodes.any { it.path == "0/0/0/1/1" })
    }

    private val box = ScreenBounds(90, 600, 990, 1700)
    private fun dialog(bounds: ScreenBounds = box) = listOf(
        node("0/0/7", "android.app.AlertDialog", bounds = bounds),
        node("0/0/7/0", text = "회원 전용 쿠폰 혜택", bounds = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.top + 200)),
        node("0/0/7/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(bounds.left, bounds.bottom - 200, bounds.right, bounds.bottom)),
    )

    /** One read again of the popup found in [prev]: merged, the root found again, [SettleRules] asked. */
    private fun next(prev: List<NodeSnapshot>, state: SettleState?, now: Long, fresh: List<NodeSnapshot>): Triple<SettleVerdict, SettleState, List<NodeSnapshot>> {
        val before = PopupRootFinder.find(prev, SCREEN)!!
        val nodes = TailSettle.merged(prev, before.rereadPaths(), subtreeOf(head + items(0..3) + fresh))!!
        val root = PopupRootFinder.find(nodes, SCREEN)!!
        val (verdict, after) = SettleRules.next(state, SettleRules.key(7, root.kind, root.path), root.bounds, SettleRules.members(root, nodes), now)
        return Triple(verdict, after, nodes)
    }

    private fun firstState(nodes: List<NodeSnapshot>): SettleState {
        val root = PopupRootFinder.find(nodes, SCREEN)!!
        val (verdict, state) = SettleRules.next(null, SettleRules.key(7, root.kind, root.path), root.bounds, SettleRules.members(root, nodes), 1_000L)
        assertEquals(SettleVerdict.WAIT, verdict)
        return state
    }

    @Test fun readAgainStandingStillIsStable() {
        val first = head + items(0..3) + dialog()
        val (verdict, _, nodes) = next(first, firstState(first), 1_150L, dialog())
        assertEquals(SettleVerdict.STABLE, verdict)
        // The page around it is kept from the first read, the popup is the new one.
        assertEquals(first.map { it.path }, nodes.map { it.path })
    }

    @Test fun readAgainMovedWaitsUntilItStands() {
        val first = head + items(0..3) + dialog(ScreenBounds(90, 900, 990, 2000))
        val (moved, state, nodes) = next(first, firstState(first), 1_150L, dialog())
        assertEquals(SettleVerdict.WAIT, moved)
        val (still, _, _) = next(nodes, state, 1_300L, dialog())
        assertEquals(SettleVerdict.STABLE, still)
    }

    // F50's reason: the 결제 button came a moment later, nothing is judged before it is there.
    @Test fun aButtonAddedInTheReadAgainWaitsAndIsThere() {
        val at = "0/0/9"
        val first = head + items(0..3) + layer(at, withPay = false)
        val root = PopupRootFinder.find(first, SCREEN)
        assertNotNull(root)
        val again = TailSettle.merged(first, root!!.rereadPaths(), subtreeOf(layer(at)))!!
        val (verdict, _) = SettleRules.next(firstState(first), SettleRules.key(7, root.kind, root.path), root.bounds, SettleRules.members(PopupRootFinder.find(again, SCREEN)!!, again), 1_150L)
        assertEquals(SettleVerdict.WAIT, verdict)
        assertEquals(1, SimplePopupAnalysis.build(again, PopupRootFinder.find(again, SCREEN)!!, "p", 7, SCREEN, 1L, 0L).scan.riskCount)
    }

    @Test fun aReadAgainThatFailedIsNotStillness() {
        val nodes = head + dialog()
        assertNull(TailSettle.merged(nodes, setOf("0/0/7")) { null })
        assertNull(TailSettle.merged(nodes, setOf("0/0/7")) { emptyList() })
        // A flat banner's parts: one gone is all gone.
        assertNull(TailSettle.merged(nodes, setOf("0/0/7/0", "0/0/7/1")) { if (it.endsWith("0")) listOf(nodes[3]) else null })
    }

    @Test fun onlyTheSameAppSoonEnough() {
        assertTrue(TailSettle.usable("p", 1_000L, "p", 1_150L))
        assertTrue(TailSettle.usable("p", 1_000L, "p", 1_000L + SettleRules.FORGET_MILLIS))
        assertFalse(TailSettle.usable("p", 1_000L, "p", 1_001L + SettleRules.FORGET_MILLIS))
        assertFalse(TailSettle.usable("p", 1_000L, "q", 1_150L))
        assertFalse(TailSettle.usable("p", 1_000L, "p", 999L))
    }
}
