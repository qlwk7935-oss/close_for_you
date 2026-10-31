package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F25: the whole-read cap on huge pages (G마켓 6.1 s, 예스24 3.6 s) still lets the popup be found, and found whole. */
class WholeReadCapTest {
    private class Tree(nodes: List<NodeSnapshot>) : ProbeTree<NodeSnapshot> {
        private val byPath = nodes.associateBy { it.path }
        override fun info(node: NodeSnapshot) = ProbeNodeInfo(node.className, node.bounds, node.clickable, !node.text.isNullOrBlank())
        override fun childCount(node: NodeSnapshot) = generateSequence(0) { it + 1 }.takeWhile { byPath.containsKey("${node.path}/$it") }.count()
        override fun child(node: NodeSnapshot, index: Int) = byPath["${node.path}/$index"]
        override fun release(node: NodeSnapshot) = Unit
    }

    private fun dialogAt(index: Int) = listOf(
        node("0/0/$index", "android.app.AlertDialog", bounds = ScreenBounds(90, 600, 990, 1700)),
        node("0/0/$index/0", text = "가을 특가 이벤트", bounds = ScreenBounds(90, 600, 990, 800)),
        node("0/0/$index/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1500, 990, 1700)),
        node("0/0/$index/2", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(90, 1500, 540, 1700)),
    )

    /** A huge page in walk order: [before] page items, the dialog, then [after] items (each on screen, small). */
    private fun hugePage(before: Int, after: Int): List<NodeSnapshot> {
        val head = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN), node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)))
        val items = { range: IntRange -> range.map { node("0/0/$it", text = "상품 $it", clickable = true, bounds = ScreenBounds(0, 200 + it % 2000, 1080, 260 + it % 2000)) } }
        return head + items(0 until before) + dialogAt(before) + items(before + 1..before + after)
    }

    @Test fun aDialogAtTheEndOfAHugePageIsFoundByTheProbe() {
        val nodes = hugePage(before = 3_000, after = 0)
        val probe = PopupProbe.find(nodes.first(), Tree(nodes), SCREEN, 200, 64)
        assertEquals("0/0/3000", probe.path)
        assertTrue(probe.visited < 10)
    }

    @Test fun aDialogAtTheStartOfAHugePageIsInTheCappedWholeRead() {
        val nodes = hugePage(before = 5, after = 3_000)
        val capped = nodes.take(WholeReadCap.MAX_NODES)
        assertEquals("0/0/5", PopupRootFinder.find(capped, SCREEN)!!.path)
    }

    @Test fun aPopupCutByTheCapIsReadAgainWholeSoItsPaymentButtonStillAsks() {
        val nodes = hugePage(before = 10, after = 100)
        // The cap fell inside the dialog: "결제하기" was not read yet.
        val cut = nodes.takeWhile { it.path != "0/0/10/2" }
        val cutPopup = SimplePopupAnalysis.build(cut, PopupRootFinder.find(cut, SCREEN)!!, "p", 7, SCREEN, 1L, 0L)
        assertEquals(0, cutPopup.scan.riskCount)
        val complete = WholeReadCap.complete(cut, SCREEN, bounded = false) { path -> nodes.filter { it.path == path || it.path.startsWith("$path/") } }
        val root = PopupRootFinder.find(complete, SCREEN)
        assertNotNull(root)
        val popup = SimplePopupAnalysis.build(complete, root!!, "p", 7, SCREEN, 1L, 0L)
        assertEquals(1, popup.scan.riskCount)
    }

    @Test fun theProbeStopsAtTheReadsDeadline() {
        // F27: the probe keeps the read's deadline (올리브영 앱 re-check 734 ms with a 250 ms cap).
        val nodes = hugePage(before = 5, after = 3_000)
        var calls = 0
        val tree = object : ProbeTree<NodeSnapshot> by Tree(nodes) {
            override fun expired(): Boolean = ++calls > 3
        }
        val probe = PopupProbe.find(nodes.first(), tree, SCREEN, 200, 64)
        assertTrue(probe.capped)
        assertEquals(null, probe.path)
        assertTrue(probe.visited <= 3)
    }

    @Test fun anAbortedPageWalksWholeAgainOnlyAfterTenSeconds() {
        assertFalse(ReadBudget.wholeRead(DetectTrigger.WINDOW_EVENT, 0, slowPage = false, videoWeb = false, sinceAbortedWhole = 2_000))
        assertFalse(ReadBudget.wholeRead(DetectTrigger.TALKBACK_FOCUS, 0, slowPage = false, videoWeb = false, sinceAbortedWhole = 9_999))
        assertTrue(ReadBudget.wholeRead(DetectTrigger.WINDOW_EVENT, 0, slowPage = false, videoWeb = false, sinceAbortedWhole = 10_000))
        assertTrue(ReadBudget.wholeRead(DetectTrigger.WINDOW_EVENT, 0, slowPage = false, videoWeb = false, sinceAbortedWhole = null))
    }
}
