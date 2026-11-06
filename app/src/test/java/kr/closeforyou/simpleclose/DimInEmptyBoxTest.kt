package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 갤럭시 11/3 예스24: a full-screen clickable "닫기" alone in an empty box, the sheet beside that box. */
class DimInEmptyBoxTest {
    private val screen = ScreenBounds(0, 0, 1080, 2408)

    private fun popup(closeInSheet: Boolean = true): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = screen),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 226, 1080, 2273)),
        node("0/0/0", bounds = ScreenBounds(0, 226, 1080, 2274)),
        node("0/0/0/0", bounds = ScreenBounds(0, 1078, 1080, 2274)),
        node("0/0/0/0/0", desc = "오늘은 그만 보기", clickable = true, bounds = ScreenBounds(0, 1078, 540, 1165)),
    ) + (if (closeInSheet) listOf(node("0/0/0/0/1", desc = "닫기", clickable = true, bounds = ScreenBounds(849, 1078, 1080, 1165))) else emptyList()) + listOf(
        node("0/0/0/0/2", "android.widget.TextView", text = "지금 세계의 돈과 기술은", bounds = ScreenBounds(0, 1190, 1080, 2274)),
        node("0/0/0/1", bounds = ScreenBounds(0, 226, 1080, 2274)),
        node("0/0/0/1/0", desc = "닫기", clickable = true, bounds = ScreenBounds(0, 226, 1080, 2274)),
    )

    @Test fun theSheetBesideTheBoxIsThePopup() {
        val nodes = popup()
        val root = PopupRootFinder.find(nodes, screen)!!
        assertEquals("0/0/0", root.path)
        assertEquals(PopupRootKind.DIM_OVERLAY, root.kind)
        val scan = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, screen, 1, 0).scan
        assertTrue(scan.candidates.any { it.label == "닫기" })
    }

    @Test fun aCoverWithoutACloseInTheCardIsNotAPopup() {
        assertNull(PopupRootFinder.find(popup(closeInSheet = false).filterNot { it.path == "0/0/0/1/0" }, screen))
    }

    @Test fun anEmptyBoxWithNothingBesideItIsNotAPopup() {
        val page = listOf(
            node("0", "android.widget.FrameLayout", bounds = screen),
            node("0/0", bounds = ScreenBounds(0, 226, 1080, 2274)),
            node("0/0/0", desc = "닫기", clickable = true, bounds = ScreenBounds(0, 226, 1080, 2274)),
        )
        assertNull(PopupRootFinder.find(page, screen))
    }
}
