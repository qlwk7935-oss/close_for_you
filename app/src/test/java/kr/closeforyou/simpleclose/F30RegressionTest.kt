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

/**
 * F30 (LG 11/1 15:37, 시험 앱 P04 `Dialog`: NO_POPUP_ROOT ×192, no prompt): a touch-modal Dialog window may report the
 * whole screen as its bounds, so it was neither a bounded active window nor an app popup window. Judged by its root
 * view, it is. The 시험 앱 dialogs (P01, P04, P05, P06) as Dialog-window trees, and the web W02/W03 shapes, through
 * the same root finder and button scan the engine uses.
 */
class F30RegressionTest {
    private val card = ScreenBounds(32, 820, 1048, 1620)

    @Test fun aTouchModalDialogIsJudgedByItsRootView() {
        // The window says the whole screen, its root view is the card: bounded.
        assertTrue(WindowBoundsRules.bounded(SCREEN, card, SCREEN))
        assertEquals(card, WindowBoundsRules.shown(SCREEN, card))
        // An activity: window and root both the whole screen: not bounded (as before).
        assertFalse(WindowBoundsRules.bounded(SCREEN, SCREEN, SCREEN))
        // A window that reports its own card (PopupWindow) is bounded with or without its root.
        assertTrue(WindowBoundsRules.bounded(card, null, SCREEN))
        // An empty root view never stands in for the window.
        assertFalse(WindowBoundsRules.bounded(SCREEN, ScreenBounds(0, 0, 0, 0), SCREEN))
    }

    /** One `Dialog` window's tree as the collector reads it: its DecorView "0" at the card, then the content column. */
    private fun dialog(vararg content: NodeSnapshot): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = card),
        node("0/0", "android.widget.LinearLayout", bounds = card),
    ) + content

    private val x = node("0/0/0/0", "android.widget.ImageButton", clickable = true, bounds = ScreenBounds(900, 860, 1030, 990))

    private fun asDialogWindow(nodes: List<NodeSnapshot>): Pair<PopupRoot, ButtonScan> {
        val root = requireNotNull(PopupRootFinder.find(nodes, SCREEN, activeWindowBounded = true))
        return root to CloseButtonFinder.scan(nodes, root)
    }

    @Test fun p04UnlabeledXIsFoundAndOfferedNeverPressed() {
        val p04 = dialog(
            node("0/0/0", "android.widget.FrameLayout", bounds = ScreenBounds(80, 860, 1000, 990)),
            x,
            node("0/0/1", "android.widget.TextView", text = "게임 아이템 특가", bounds = ScreenBounds(80, 1000, 1000, 1080)),
            node("0/0/2", "android.widget.TextView", text = "오른쪽 위 X 버튼에는 글자가 없습니다. 시험용 광고입니다.", bounds = ScreenBounds(80, 1090, 1000, 1250)),
            node("0/0/3", "android.widget.Button", text = "자세히 보기", clickable = true, bounds = ScreenBounds(80, 1300, 1000, 1420)),
        )
        val (root, scan) = asDialogWindow(p04)
        assertEquals(PopupRootKind.DIALOG_WINDOW, root.kind)
        assertNotNull(scan.xButton)
        assertTrue(scan.candidates.isEmpty())
        // The same tree as a window above the app's main window (F16 path) is a popup too.
        assertTrue(AppWindowRules.isPopup(p04, SCREEN))
    }

    @Test fun p01LabeledCloseAndP06FakeXPressTheNamedClose() {
        val p01 = dialog(
            node("0/0/0", "android.widget.TextView", text = "가을 할인 쿠폰", bounds = ScreenBounds(80, 860, 1000, 940)),
            node("0/0/1", "android.widget.TextView", text = "지금 받으면 30퍼센트 할인. 시험용 광고입니다.", bounds = ScreenBounds(80, 950, 1000, 1100)),
            node("0/0/2", "android.widget.Button", text = "자세히 보기", clickable = true, bounds = ScreenBounds(80, 1300, 1000, 1420)),
            node("0/0/3", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(80, 1440, 1000, 1560)),
        )
        assertEquals(listOf("닫기"), asDialogWindow(p01).second.candidates.map { it.label })
        val p06 = dialog(
            node("0/0/0", "android.widget.FrameLayout", bounds = ScreenBounds(80, 860, 1000, 990)),
            x,
            node("0/0/1", "android.widget.TextView", text = "한정 특가 광고", bounds = ScreenBounds(80, 1000, 1000, 1080)),
            node("0/0/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(80, 1440, 1000, 1560)),
        )
        val scan = asDialogWindow(p06).second
        assertEquals("닫기", StrongCloseRules.pick(scan.candidates)?.label)
    }

    @Test fun p05ImageOnlyXHasNothingToPress() {
        // The X is only drawn in the picture: a dialog is found, but no node to press or offer.
        val p05 = dialog(node("0/0/0", "android.widget.ImageView", bounds = card))
        val (root, scan) = asDialogWindow(p05)
        assertEquals(PopupRootKind.DIALOG_WINDOW, root.kind)
        assertTrue(scan.candidates.isEmpty())
        assertNull(scan.xButton)
    }

    @Test fun webW03FakeXAndW02UnlabeledXAsBefore() {
        fun web(vararg inside: NodeSnapshot) = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/0/0", "android.app.AlertDialog", bounds = card),
        ) + inside
        val w03 = web(
            node("0/0/0/0", "android.widget.Button", clickable = true, bounds = ScreenBounds(900, 860, 1030, 990), text = "광고 보기 X"),
            node("0/0/0/1", text = "한정 특가 광고", bounds = ScreenBounds(80, 1000, 1000, 1080)),
            node("0/0/0/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(80, 1440, 1000, 1560)),
        )
        val root3 = requireNotNull(PopupRootFinder.find(w03, SCREEN))
        assertEquals("닫기", StrongCloseRules.pick(CloseButtonFinder.scan(w03, root3).candidates)?.label)
        val w02 = web(
            node("0/0/0/0", "android.widget.Button", clickable = true, bounds = ScreenBounds(900, 860, 1030, 990)),
            node("0/0/0/1", text = "한정 특가 광고", bounds = ScreenBounds(80, 1000, 1000, 1080)),
        )
        val root2 = requireNotNull(PopupRootFinder.find(w02, SCREEN))
        val scan2 = CloseButtonFinder.scan(w02, root2)
        assertTrue(scan2.candidates.isEmpty())
        assertNotNull(scan2.xButton)
    }
}
