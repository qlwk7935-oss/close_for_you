package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 갤럭시 11/3 쿠팡: "7일간 보지 않기" with a check mark is not a close button. */
class HideCheckboxTest {
    private val screen = ScreenBounds(0, 0, 1080, 2408)

    private fun popup(vararg row: NodeSnapshot, close: Boolean = false): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = screen),
        node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(100, 500, 980, 1900)),
        node("0/0/0", "android.widget.TextView", text = "쿠폰이 지급되었습니다", bounds = ScreenBounds(150, 800, 930, 1000)),
    ) + row + (if (close) listOf(node("0/0/9", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(150, 1700, 930, 1850))) else emptyList())

    private fun labels(nodes: List<NodeSnapshot>) =
        SimplePopupAnalysis.build(nodes, PopupRootFinder.find(nodes, screen)!!, "kr.test", 1, screen, 1, 0).scan.candidates.map { it.label }

    private val box = node("0/0/1", "android.widget.CheckBox", clickable = true, bounds = ScreenBounds(120, 560, 170, 610))

    @Test fun aCheckboxNamedHideIsNotACandidate() {
        val named = node("0/0/1", "android.widget.CheckBox", text = "7일간 보지 않기", clickable = true, bounds = ScreenBounds(120, 560, 500, 610))
        assertTrue(labels(popup(named)).isEmpty())
    }

    @Test fun aLabelBesideACheckboxIsNotACandidate() {
        val label = node("0/0/2", "android.widget.TextView", text = "7일간 보지 않기", clickable = true, bounds = ScreenBounds(180, 555, 500, 615))
        assertTrue(labels(popup(box, label)).isEmpty())
    }

    @Test fun theRealCloseIsStillFound() {
        val label = node("0/0/2", "android.widget.TextView", text = "7일간 보지 않기", clickable = true, bounds = ScreenBounds(180, 555, 500, 615))
        assertEquals(listOf("닫기"), labels(popup(box, label, close = true)))
    }

    @Test fun aCloseInTheSameRowAsTheCheckboxIsStillFound() {
        val hide = node("0/0/2", "android.widget.TextView", text = "오늘 하루 보지 않기", clickable = true, bounds = ScreenBounds(180, 555, 600, 615))
        val close = node("0/0/3", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(760, 555, 930, 615))
        assertEquals(listOf("닫기"), labels(popup(box, hide, close)))
    }

    @Test fun aPlainHideButtonStillCounts() {
        val button = node("0/0/2", "android.widget.Button", text = "오늘 하루 보지 않기", clickable = true, bounds = ScreenBounds(150, 1700, 930, 1850))
        assertEquals(listOf("오늘 하루 보지 않기"), labels(popup(button)))
    }
}
