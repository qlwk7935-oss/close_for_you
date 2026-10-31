package kr.closeforyou.simpleclose

import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F15: the probe that finds the popup root before the read, and the guarantee that reading only that
 * subtree gives B, the button scan and the decider exactly what the whole tree gave.
 */
class PopupProbeTest {
    /** The fixtures as a probe tree: children follow from the paths, as in the live tree. */
    private class SnapshotTree(nodes: List<NodeSnapshot>) : ProbeTree<NodeSnapshot> {
        private val byPath = nodes.associateBy { it.path }
        val visited = mutableListOf<String>()
        override fun info(node: NodeSnapshot): ProbeNodeInfo {
            visited += node.path
            return ProbeNodeInfo(node.className, node.bounds, node.clickable, !node.text.isNullOrBlank())
        }
        override fun childCount(node: NodeSnapshot): Int = generateSequence(0) { it + 1 }.takeWhile { byPath.containsKey("${node.path}/$it") }.count()
        override fun child(node: NodeSnapshot, index: Int): NodeSnapshot? = byPath["${node.path}/$index"]
        override fun release(node: NodeSnapshot) = Unit
    }

    private fun probe(nodes: List<NodeSnapshot>, max: Int = 200): Pair<ProbeResult, SnapshotTree> {
        val tree = SnapshotTree(nodes)
        return PopupProbe.find(nodes.first { it.path == "0" }, tree, SCREEN, max, 64) to tree
    }

    /** What the collector reads for a hit: the node at the path and everything below it. */
    private fun subtree(nodes: List<NodeSnapshot>, path: String) = nodes.filter { it.path == path || it.path.startsWith("$path/") }

    @Test fun findsTheDialogOfEachDeviceDump() {
        assertEquals("0/1/4", probe(SimpleNodes.elevenStreet()).first.path)
        assertEquals("0/0/1", probe(SimpleNodes.auction()).first.path)
        assertEquals("0/0", probe(SimpleNodes.youTubeSkip()).first.path)
        assertEquals("0/0", probe(SimpleNodes.chromePermission()).first.path)
        assertEquals(ProbeHitKind.DIALOG, probe(SimpleNodes.auction()).first.kind)
    }

    @Test fun findsTheDimOverlayParentWhenThereIsNoDialog() {
        val (result, _) = probe(SimpleNodes.oliveYoung())
        assertEquals("0/1", result.path)
        assertEquals(ProbeHitKind.DIM_PARENT, result.kind)
    }

    @Test fun lastChildFirstReachesAModalAtTheEndOfAPageWithoutWalkingThePage() {
        val page = (0 until 300).map { node("0/0/$it", text = "상품 $it", bounds = ScreenBounds(0, 200 + it, 1080, 260 + it)) }
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        ) + page + listOf(
            node("0/0/300", "android.app.AlertDialog", bounds = ScreenBounds(0, 1600, 1080, 2300)),
            node("0/0/300/0", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(900, 1600, 1080, 1700)),
        )
        val (result, tree) = probe(nodes)
        assertEquals("0/0/300", result.path)
        assertEquals(3, result.visited)
        assertEquals(listOf("0", "0/0", "0/0/300"), tree.visited)
    }

    @Test fun aSizedSubtreeWhollyOffScreenIsNotEntered() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", bounds = ScreenBounds(0, 3000, 1080, 9000)),
            node("0/0/0", "android.app.AlertDialog", bounds = ScreenBounds(0, 3000, 1080, 4000)),
        )
        val (result, tree) = probe(nodes)
        assertNull(result.path)
        assertTrue("0/0/0" !in tree.visited)
    }

    @Test fun noPopupOrTheCapGivesNoPathSoTheWholeTreeIsRead() {
        val page = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN)) +
            (0 until 50).map { node("0/$it", text = "글 $it", bounds = ScreenBounds(0, it * 40, 1080, it * 40 + 40)) }
        assertNull(probe(page).first.path)
        // A dim seen before the cap is not trusted: a Dialog beyond the cap would win in the full read.
        val dimThenMore = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", bounds = SCREEN),
            node("0/1", bounds = SCREEN),
            node("0/1/0", "android.widget.LinearLayout", bounds = ScreenBounds(60, 700, 1020, 1700)),
            node("0/1/1", clickable = true, bounds = SCREEN),
        ) + (0 until 20).map { node("0/0/$it", bounds = ScreenBounds(0, it, 10, it + 10)) }
        val (capped, tree) = probe(dimThenMore, max = 4)
        assertTrue("0/1/1" in tree.visited)
        assertTrue(capped.capped)
        assertNull(capped.path)
    }

    @Test fun ofTwoDialogsTheProbeTakesTheOneTheRootFinderTakes() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(0, 300, 1080, 900)),
            node("0/1", "android.app.AlertDialog", bounds = ScreenBounds(0, 1000, 1080, 1600)),
        )
        assertEquals(PopupRootFinder.find(nodes, SCREEN)!!.path, probe(nodes).first.path)
    }

    // ---- Safety: the subtree alone decides exactly what the whole tree decided ----

    /** A page with a pay button and its own 닫기 outside the popup, and a forbidden button inside it. */
    private fun pageWithButtonsOutside() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(0, 200, 540, 300)),
        node("0/0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 200, 1080, 300)),
        node("0/0/2", text = "지금 구독하면 첫 달 무료", bounds = ScreenBounds(0, 320, 1080, 420)),
        node("0/0/3", "android.app.AlertDialog", bounds = ScreenBounds(60, 800, 1020, 1700)),
        node("0/0/3/0", "android.widget.TextView", text = "오늘 밤 서버 점검이 있어요", bounds = ScreenBounds(60, 800, 1020, 1000)),
        node("0/0/3/1", "android.widget.Button", text = "앱에서 구매하기", clickable = true, bounds = ScreenBounds(60, 1400, 540, 1600)),
        node("0/0/3/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1400, 1020, 1600)),
    )

    @Test fun theSubtreeGivesTheSameRootButtonsTextsAndJudgementAsTheWholeTree() = runTest {
        val fixtures = mapOf(
            "11번가" to SimpleNodes.elevenStreet(), "옥션" to SimpleNodes.auction(), "올리브영" to SimpleNodes.oliveYoung(),
            "유튜브" to SimpleNodes.youTubeSkip(), "크롬 권한" to SimpleNodes.chromePermission(), "밖에 버튼" to pageWithButtonsOutside(),
        )
        val classifier = DecisionPopupClassifier()
        for ((name, full) in fixtures) {
            val path = probe(full).first.path
            assertNotNull(name, path)
            val part = subtree(full, path!!)
            val fullRoot = PopupRootFinder.find(full, SCREEN)!!
            val partRoot = PopupRootFinder.find(part, SCREEN)
            assertEquals(name, fullRoot, partRoot)
            val a = SimplePopupAnalysis.build(full, fullRoot, "p", 7, SCREEN, 1L, 0L)
            val b = SimplePopupAnalysis.build(part, partRoot!!, "p", 7, SCREEN, 1L, 0L)
            assertEquals(name, a.analysis, b.analysis)
            assertEquals(name, a.scan, b.scan)
            assertEquals(name, a.fingerprint, b.fingerprint)
            val byA = classifier.classify(a.analysis)
            assertEquals(name, byA, classifier.classify(b.analysis))
            assertEquals(name, RememberChoiceRules.suspiciousByB(a.analysis), RememberChoiceRules.suspiciousByB(b.analysis))
            assertEquals(name, ImageDialogAdHint.check(a, byA, false), ImageDialogAdHint.check(b, byA, false))
        }
    }

    @Test fun buttonsOutsideThePopupNeverReachTheScanAndTheForbiddenOneInsideStillCounts() {
        val full = pageWithButtonsOutside()
        val part = subtree(full, probe(full).first.path!!)
        val scan = CloseButtonFinder.scan(part, PopupRootFinder.find(part, SCREEN)!!)
        assertEquals(listOf("node:0/0/3/2"), scan.candidates.map { it.id })
        assertEquals(1, scan.forbiddenCount) // 앱에서 구매하기
        assertTrue(part.none { it.path == "0/0/0" || it.path == "0/0/1" })
    }
}
