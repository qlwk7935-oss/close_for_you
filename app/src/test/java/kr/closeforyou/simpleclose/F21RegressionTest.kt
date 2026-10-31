package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F21 (LG 11/1 06:49~53, `8dd26e4`): 11번가 labels, root priority and the order the sheet really closes in. */
class F21RegressionTest {
    private class Tree(nodes: List<NodeSnapshot>) : ProbeTree<NodeSnapshot> {
        private val byPath = nodes.associateBy { it.path }
        override fun info(node: NodeSnapshot) = ProbeNodeInfo(node.className, node.bounds, node.clickable, !node.text.isNullOrBlank())
        override fun childCount(node: NodeSnapshot) = generateSequence(0) { it + 1 }.takeWhile { byPath.containsKey("${node.path}/$it") }.count()
        override fun child(node: NodeSnapshot, index: Int) = byPath["${node.path}/$index"]
        override fun release(node: NodeSnapshot) = Unit
    }

    @Test fun closeVerbAtTheEndIsACloseWhateverItNamesButInstallingIsNot() {
        listOf("앱설치 안내 레이어 닫기", "앱 설치 배너 닫기", "다운로드 안내 닫기", "앱 안내 레이어 닫기").forEach {
            assertEquals(it, ButtonRole.CLOSE, SimpleLabelRules.roleOf(it))
        }
        listOf("설치하기", "앱 설치", "앱에서 열기", "다운로드", "설치하고 닫기", "설치 후 닫기", "앱 설치 및 닫기", "구매 안내 닫기").forEach {
            assertNull(it, SimpleLabelRules.roleOf(it))
        }
    }

    /** The 11번가 shape: an outer full-width layer (with its own 닫기) over the page, and the sheet as a Dialog inside the page. */
    private fun outerLayerAndSheet() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0/0", text = "오늘의 특가 상품", bounds = ScreenBounds(0, 200, 1080, 400)),
        node("0/0/0/1", "android.app.AlertDialog", bounds = ScreenBounds(0, 1632, 1080, 2337)),
        node("0/0/0/1/0", "android.widget.Button", text = "앱설치 안내 레이어 닫기", clickable = true, bounds = ScreenBounds(960, 1650, 1060, 1750)),
        node("0/0/0/1/1", "android.widget.Button", text = "모바일 웹으로 볼래요", clickable = true, bounds = ScreenBounds(40, 2150, 1040, 2300)),
        node("0/0/1", bounds = ScreenBounds(90, 600, 990, 1500)),
        node("0/0/1/0", text = "이벤트 안내", bounds = ScreenBounds(90, 600, 990, 800)),
        node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1400, 990, 1500)),
    )

    @Test fun theSheetDialogBeatsAnOuterLayerInTheProbeAndTheFinder() {
        val nodes = outerLayerAndSheet()
        val probe = PopupProbe.find(nodes.first(), Tree(nodes), SCREEN, 200, 64)
        assertEquals(ProbeHitKind.DIALOG, probe.kind)
        assertEquals("0/0/0/1", probe.path)
        val root = PopupRootFinder.find(nodes, SCREEN)!!
        assertEquals("0/0/0/1", root.path)
        val popup = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 7, SCREEN, 1L, 0L)
        // The outer layer's 닫기 is not part of the sheet; the sheet's stay-on-web answer goes first.
        assertEquals(listOf("모바일 웹으로 볼래요", "앱설치 안내 레이어 닫기"), popup.scan.candidates.map { it.label })
        assertEquals("모바일 웹으로 볼래요", StrongCloseRules.pick(popup.scan.candidates)!!.label)
    }

    @Test fun aLayerAloneIsStillUsedWhenThereIsNoDialog() {
        val nodes = outerLayerAndSheet().filterNot { it.path.startsWith("0/0/0/1") }
        val probe = PopupProbe.find(nodes.first(), Tree(nodes), SCREEN, 200, 64)
        assertEquals(ProbeHitKind.LAYER, probe.kind)
        assertEquals("0/0/1", probe.path)
    }

    @Test fun plainCloseStillBeatsHidingForAWhileAndTheBackground() {
        val ordered = listOf("모바일 웹으로 볼래요", "닫기", "오늘 하루 보지 않기", "괜찮아요").map(SimpleLabelRules::clarity)
        assertEquals(ordered.sorted(), ordered)
        assertEquals(SimpleLabelRules.clarity("닫기") + 1, SimpleLabelRules.clarity("배경을 눌러 닫기"))
    }
}
