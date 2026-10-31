package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupRootFinderTest {
    @Test fun chromeAlertDialogIsTheRoot() {
        val root = PopupRootFinder.find(SimpleNodes.elevenStreet(), SCREEN)
        assertNotNull(root)
        assertEquals("0/1/4", root!!.path)
        assertEquals(PopupRootKind.ALERT_DIALOG, root.kind)
        // The toolbar and page nodes are outside; the footer links are invisible anyway.
        assertFalse(root.contains(node("0/0")))
        assertFalse(root.contains(node("0/1/2")))
        assertTrue(root.contains(node("0/1/4/3")))
    }

    @Test fun auctionCentredDialogIsTheRoot() {
        val root = PopupRootFinder.find(SimpleNodes.auction(), SCREEN)
        assertEquals("0/0/1", root?.path)
        assertEquals(PopupRootKind.ALERT_DIALOG, root?.kind)
    }

    @Test fun dimBackgroundWithSiblingCardGivesTheirParentAsRoot() {
        val root = PopupRootFinder.find(SimpleNodes.oliveYoung(), SCREEN)
        assertEquals("0/1", root?.path)
        assertEquals(PopupRootKind.DIM_OVERLAY, root?.kind)
    }

    @Test fun dimBackgroundWithChildCardGivesTheDimNodeAsRoot() {
        val nodes = listOf(
            node("0", bounds = SCREEN),
            node("0/0", desc = "배경을 눌러 닫기", clickable = true, bounds = SCREEN),
            node("0/0/0", text = "이벤트 안내", bounds = ScreenBounds(100, 800, 980, 1500)),
            node("0/0/1", text = "닫기", clickable = true, bounds = ScreenBounds(100, 1500, 980, 1600)),
        )
        val root = PopupRootFinder.find(nodes, SCREEN)
        assertEquals("0/0", root?.path)
        assertEquals(PopupRootKind.DIM_OVERLAY, root?.kind)
    }

    @Test fun plainPageWithoutDialogOrDimHasNoRoot() {
        val nodes = listOf(
            node("0", bounds = SCREEN),
            node("0/0", text = "뉴스 제목", bounds = ScreenBounds(0, 0, 1080, 200)),
            node("0/1", text = "더보기", clickable = true, bounds = ScreenBounds(0, 200, 1080, 300)),
            // A full-screen clickable list is not a dim background: it has content of its own.
            node("0/2", "android.widget.ScrollView", clickable = true, bounds = ScreenBounds(0, 300, 1080, 2340)),
            node("0/2/0", text = "기사 1", clickable = true, bounds = ScreenBounds(0, 300, 1080, 600)),
            node("0/2/1", text = "기사 2", clickable = true, bounds = ScreenBounds(0, 600, 1080, 900)),
        )
        assertNull(PopupRootFinder.find(nodes, SCREEN))
    }

    /** 11번가 main page after the sheet closed (LG 10/31): a clickable near-screen web container is not a dim. */
    @Test fun ordinaryWebPageWithNearScreenClickableContainerHasNoRoot() {
        val nodes = listOf(
            node("0", bounds = SCREEN),
            node("0/0", "android.widget.EditText", text = "m.11st.co.kr", clickable = true, bounds = ScreenBounds(100, 60, 900, 180)),
            node("0/1", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/1/0", clickable = true, bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/1/0/0", text = "11번가 앱에서 앱 한정 혜택 받기", bounds = ScreenBounds(0, 200, 800, 300)),
            node("0/1/0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(1000, 200, 1080, 300)),
            node("0/1/0/2", "android.widget.Image", desc = "타임딜 배너", clickable = true, bounds = ScreenBounds(0, 320, 1080, 900)),
            node("0/1/0/3", text = "로그인", clickable = true, bounds = ScreenBounds(0, 2200, 200, 2300)),
            // A text-less clickable leaf covering the page next to bounded content: still no close button in a card.
            node("0/1/1", clickable = true, bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/1/2", text = "오늘의 특가", bounds = ScreenBounds(0, 900, 1080, 1200)),
        )
        assertNull(PopupRootFinder.find(nodes, SCREEN))
    }

    @Test fun dimWithoutACloseButtonInItsCardIsNotAnOverlay() {
        val nodes = listOf(
            node("0", bounds = SCREEN),
            node("0/0", clickable = true, bounds = SCREEN),
            node("0/1", "android.widget.LinearLayout", bounds = ScreenBounds(60, 700, 1020, 1700)),
            node("0/1/0", text = "쿠폰 받기", clickable = true, bounds = ScreenBounds(60, 1500, 1020, 1600)),
        )
        assertNull(PopupRootFinder.find(nodes, SCREEN))
    }

    @Test fun rootKeyIgnoresContentButNotPlaceOrKind() {
        val a = PopupRootFinder.find(SimpleNodes.auction(), SCREEN)!!
        val moved = SimpleNodes.auction().map { if (it.path == "0/0/1") it.copy(bounds = ScreenBounds(90, 700, 990, 1800)) else it }
        val b = PopupRootFinder.find(moved, SCREEN)!!
        assertEquals(a.key, PopupRootFinder.find(SimpleNodes.auction(), SCREEN)!!.key)
        assertTrue(a.key != b.key)
    }

    @Test fun boundedDialogWindowMakesTheWholeTreeTheRoot() {
        val nodes = listOf(
            node("0", bounds = ScreenBounds(100, 800, 980, 1600)),
            node("0/0", text = "공지", bounds = ScreenBounds(100, 800, 980, 1000)),
            node("0/1", text = "닫기", clickable = true, bounds = ScreenBounds(100, 1400, 980, 1600)),
        )
        val root = PopupRootFinder.find(nodes, SCREEN, activeWindowBounded = true)
        assertEquals("0", root?.path)
        assertEquals(PopupRootKind.DIALOG_WINDOW, root?.kind)
    }

    @Test fun invisibleDialogNodeIsIgnored() {
        val nodes = listOf(
            node("0", bounds = SCREEN),
            node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(0, 2340, 1080, 2340)),
        )
        assertNull(PopupRootFinder.find(nodes, SCREEN))
    }

    @Test fun twoDialogsTakeTheLaterOneOnTop() {
        val nodes = listOf(
            node("0", bounds = SCREEN),
            node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(0, 1000, 1080, 1500)),
            node("0/1", "android.app.AlertDialog", bounds = ScreenBounds(0, 1600, 1080, 2300)),
        )
        assertEquals("0/1", PopupRootFinder.find(nodes, SCREEN)?.path)
    }
}
