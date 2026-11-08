package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 11/7 G마켓 웹 (준영 SM-M446K, 69fe45e): a coupon bubble's × floats above the tab bar while it sits in the bar's subtree.
 * A container bottom banner counts, and its close is a candidate, only when the close is drawn inside the banner.
 */
class BannerBoundsTest {
    private val screen = ScreenBounds(0, 0, 1080, 2408)
    private val bar = ScreenBounds(0, 2135, 1080, 2273)

    private fun page(): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = screen),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 226, 1080, 2273)),
        node("0/0/0", "android.view.View", bounds = ScreenBounds(0, 226, 1080, 2273)),
        node("0/0/0/0", "android.widget.TextView", text = "오늘의 특가 상품", bounds = ScreenBounds(40, 300, 1040, 380)),
    )

    /** The tab bar, last in the page, with a coupon bubble and its × in its subtree but drawn above it. */
    private fun gmarket(closeInside: Boolean = false): List<NodeSnapshot> = page() + listOf(
        node("0/0/1", "android.view.View", bounds = bar),
        node("0/0/1/0", "android.widget.Button", text = "홈", clickable = true, bounds = ScreenBounds(0, 2150, 216, 2273)),
        node("0/0/1/1", "android.widget.Button", text = "카테고리", clickable = true, bounds = ScreenBounds(216, 2150, 432, 2273)),
        node("0/0/1/2", "android.widget.TextView", text = "크록스 11%쿠폰 찾았나요?", bounds = ScreenBounds(270, 1966, 700, 2048)),
        node("0/0/1/3", "android.widget.Button", text = "닫기", clickable = true,
            bounds = if (closeInside) ScreenBounds(900, 2160, 1060, 2260) else ScreenBounds(705, 1966, 787, 2048)),
    )

    @Test fun aTabBarWithABubbleCloseAboveItIsNotABanner() {
        val nodes = gmarket()
        assertNull(PopupRootFinder.find(nodes, screen))
    }

    @Test fun theSameBannerWithItsCloseInsideIsStillFound() {
        val nodes = gmarket(closeInside = true)
        val root = PopupRootFinder.find(nodes, screen)
        assertNotNull(root)
        assertEquals(PopupRootKind.BOTTOM_BANNER, root!!.kind)
        val popup = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, screen, 1, 0)
        assertEquals(listOf("닫기"), popup.scan.candidates.map { it.label })
    }

    @Test fun onlyTheCloseInsideTheBannerIsACandidate() {
        val nodes = gmarket(closeInside = true) +
            node("0/0/1/4", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(705, 1966, 787, 2048))
        val root = PopupRoot("0/0/1", bar, PopupRootKind.BOTTOM_BANNER)
        val candidates = CloseButtonFinder.scan(nodes, root).candidates
        assertEquals(listOf("0/0/1/3"), candidates.map { it.nodePath })
    }

    @Test fun otherPopupsKeepACloseOutsideTheirCard() {
        // 11번가 style: the X sits just outside the card's box. Not a banner: unchanged.
        val card = ScreenBounds(100, 600, 980, 1600)
        val nodes = page() + listOf(
            node("0/0/1", "android.app.AlertDialog", bounds = card),
            node("0/0/1/0", "android.widget.TextView", text = "앱 설치하고 쿠폰 받기", bounds = ScreenBounds(150, 700, 930, 800)),
            node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(880, 520, 980, 590)),
        )
        val root = PopupRoot("0/0/1", card, PopupRootKind.ALERT_DIALOG)
        assertEquals(listOf("0/0/1/1"), CloseButtonFinder.scan(nodes, root).candidates.map { it.nodePath })
    }

    @Test fun aButtonThatMovedOutIsStoppedBeforeThePress() {
        val root = PopupRoot("0/0/1", bar, PopupRootKind.BOTTOM_BANNER)
        val inside = CloseButtonFinder.scan(gmarket(closeInside = true), root).candidates
        val button = inside.single()
        assertFalse(BannerBounds.movedOut(root, button, inside))
        // Read again: the same node now drawn above the bar.
        val moved = CloseButtonFinder.scan(gmarket(closeInside = false), root).candidates
        assertTrue(moved.isEmpty())
        assertTrue(BannerBounds.movedOut(root, button, moved))
        // A flat banner (members) or another kind is never stopped by this.
        val flat = PopupRoot("0/0/1/3", bar, PopupRootKind.BOTTOM_BANNER, members = setOf("0/0/1/3"))
        assertFalse(BannerBounds.movedOut(flat, button, emptyList()))
        assertFalse(BannerBounds.movedOut(PopupRoot("0/0/1", bar, PopupRootKind.FRONT_LAYER), button, emptyList()))
    }

    @Test fun anIconGlyphBeforeTheCloseKeepsItBesideACheckbox() {
        // 우진 SM-F966N 11/7 LMS: "닫기" in the same row as the "오늘 하루 보지 않기" checkbox.
        val card = ScreenBounds(107, 775, 973, 1877)
        val base = listOf(
            node("0", "android.widget.FrameLayout", bounds = screen),
            node("0/0", "android.view.View", bounds = card),
            node("0/0/0", "android.widget.TextView", text = "LMS 신규앱 설치 안내", bounds = ScreenBounds(150, 820, 930, 900)),
            node("0/0/1", CloseButtonFinder.CHECKABLE_CLASS, clickable = true, bounds = ScreenBounds(128, 1812, 175, 1859)),
        )
        val root = PopupRoot("0/0", card, PopupRootKind.FRONT_LAYER)
        val withClose = base + node("0/0/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(821, 1804, 966, 1869))
        assertEquals(listOf("0/0/2"), CloseButtonFinder.scan(withClose, root).candidates.map { it.nodePath })
        // The checkbox alone: nothing to press.
        assertTrue(CloseButtonFinder.scan(base, root).candidates.isEmpty())
        // 쿠팡 style: a hide label beside the checkbox stays out.
        val hide = base + node("0/0/2", "android.widget.TextView", text = "오늘 하루 보지 않기", clickable = true, bounds = ScreenBounds(180, 1805, 600, 1865))
        assertTrue(CloseButtonFinder.scan(hide, root).candidates.isEmpty())
    }
}
