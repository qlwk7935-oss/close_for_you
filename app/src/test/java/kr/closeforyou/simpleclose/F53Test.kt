package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.settings.HandlingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F53: a press is not sent to a button something may lie over; another popup after a close is told. */
class F53Test {
    private val closeBack = ScreenBounds(540, 1500, 990, 1700)

    private fun page() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", text = "강의 목록", bounds = ScreenBounds(0, 200, 1080, 2340)),
    )

    private fun layer(index: Int, close: ScreenBounds, card: ScreenBounds, words: String) = listOf(
        node("0/0/$index", bounds = card),
        node("0/0/$index/0", text = words, bounds = ScreenBounds(card.left, card.top, card.right, card.top + 100)),
        node("0/0/$index/1", "android.widget.Button", text = "닫기", clickable = true, bounds = close),
    )

    private fun dialog(index: Int, close: ScreenBounds, card: ScreenBounds, words: String) =
        listOf(node("0/0/$index", "android.app.AlertDialog", bounds = card)) +
            layer(index, close, card, words).drop(1)

    private fun look(nodes: List<NodeSnapshot>, windows: List<WindowShape> = emptyList(), base: Int = 1, partial: Boolean = false) =
        StackLook(nodes, SCREEN, windows, base, partial, nodes.size, 12)

    private fun window(id: Int, layer: Int, box: ScreenBounds, root: ScreenBounds? = box) = WindowShape(id, WindowKind.APPLICATION, layer, true, box, root)

    // canvas.suwon: 10:30:08 a FRONT_LAYER was picked and pressed; the notice in front did not count.
    @Test fun anotherPopupOverTheSamePlaceStopsThePress() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val back = layer(2, closeBack, card, "다른 안내")
        val front = dialog(1, ScreenBounds(540, 1500, 990, 1700), card, "LMS 앱 업데이트 공지")
        val nodes = page() + front + back
        val root = PopupRoot("0/0/2", card, PopupRootKind.FRONT_LAYER)
        assertEquals(StackBlock.POPUP_OVERLAP, PopupStack.block(closeBack, root, look(nodes)))
        assertEquals(1, PopupStack.others(nodes, root, SCREEN).size)
    }

    @Test fun theDialogThatTheFinderPicksIsNotPressedEitherWhenALayerLiesThere() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val nodes = page() + dialog(1, closeBack, card, "공지") + layer(2, closeBack, card, "다른 안내")
        val picked = PopupRootFinder.find(nodes, SCREEN)!!
        assertEquals(PopupRootKind.ALERT_DIALOG, picked.kind)
        // Later in the DOM and over the button: the Dialog's close may be under it.
        assertEquals(StackBlock.COVER_LATER, PopupStack.block(closeBack, picked, look(nodes)))
    }

    @Test fun somethingWithNoPopupShapeLaterOverTheButtonStopsItToo() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val notice = listOf(
            node("0/0/3", bounds = ScreenBounds(100, 700, 980, 1750)),
            node("0/0/3/0", text = "공지 본문", bounds = ScreenBounds(100, 700, 980, 900)),
        )
        val nodes = page() + layer(2, closeBack, card, "다른 안내") + notice
        assertEquals(StackBlock.COVER_LATER, PopupStack.block(closeBack, PopupRoot("0/0/2", card, PopupRootKind.FRONT_LAYER), look(nodes)))
    }

    // A modal early in the DOM (F25's "portal at the top"): the page is later and under it, its sections do not count.
    @Test fun thePageLaterThanAnEarlyModalDoesNotStopIt() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val nodes = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN), node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340))) +
            layer(0, closeBack, card, "웹 광고") + listOf(
                node("0/0/1", bounds = ScreenBounds(0, 200, 1080, 2340)),
                node("0/0/1/0", text = "본문 카드", bounds = ScreenBounds(100, 1400, 980, 1800)),
                node("0/0/2", text = "전체 폭 구역", bounds = ScreenBounds(0, 1300, 1080, 1900)),
            )
        assertNull(PopupStack.block(closeBack, PopupRoot("0/0/0", card, PopupRootKind.FRONT_LAYER), look(nodes)))
    }

    @Test fun aCloseNotUnderTheOtherPopupIsStillPressed() {
        val low = ScreenBounds(90, 1400, 990, 2200)
        val high = ScreenBounds(90, 300, 990, 900)
        val close = ScreenBounds(540, 2000, 990, 2150)
        val nodes = page() + dialog(1, ScreenBounds(540, 700, 990, 880), high, "위쪽 공지") + layer(2, close, low, "아래쪽 안내")
        assertNull(PopupStack.block(close, PopupRoot("0/0/2", low, PopupRootKind.FRONT_LAYER), look(nodes)))
    }

    // Single popups: nothing changes (P01 own window, P13 page layer, P34 big layer with a page under it, device dumps).
    @Test fun aLonePopupIsNeverStopped() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val p13 = page() + layer(1, closeBack, card, "웹 광고")
        assertNull(PopupStack.block(closeBack, PopupRoot("0/0/1", card, PopupRootKind.FRONT_LAYER), look(p13)))
        val whole = ScreenBounds(0, 63, 1080, 2340)
        val big = ScreenBounds(900, 87, 1056, 231)
        val p34 = page() + layer(1, big, whole, "큰 레이어")
        assertNull(PopupStack.block(big, PopupRoot("0/0/1", whole, PopupRootKind.FRONT_LAYER), look(p34)))
        val own = PopupRoot("0", card, PopupRootKind.DIALOG_WINDOW, windowId = 7)
        assertNull(PopupStack.block(closeBack, own, look(emptyList(), listOf(window(1, 1, SCREEN), window(7, 2, card)), base = 7)))
        for (dump in listOf(SimpleNodes.elevenStreet(), SimpleNodes.auction(), SimpleNodes.oliveYoung())) {
            val root = PopupRootFinder.find(dump, SCREEN) ?: continue
            val scan = CloseButtonFinder.scan(dump, root)
            for (button in scan.candidates) assertNull(PopupStack.block(button.bounds, root, look(dump)))
        }
    }

    @Test fun aWindowAboveIsSure() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val root = PopupRoot("0", card, PopupRootKind.DIALOG_WINDOW, windowId = 7)
        val windows = listOf(window(1, 1, SCREEN), window(7, 2, card), window(9, 3, ScreenBounds(100, 1400, 980, 1800)))
        assertEquals(StackBlock.WINDOW_ABOVE, PopupStack.block(closeBack, root, look(emptyList(), windows, base = 7)))
        // Below it, elsewhere, or a whole-screen window whose card is elsewhere: not over it.
        assertFalse(PopupStack.windowAbove(closeBack, listOf(window(1, 1, SCREEN), window(7, 2, card)), 7))
        assertFalse(PopupStack.windowAbove(closeBack, listOf(window(7, 2, card), window(9, 3, ScreenBounds(0, 0, 1080, 300))), 7))
        assertFalse(PopupStack.windowAbove(closeBack, listOf(window(7, 2, card), window(9, 3, SCREEN, ScreenBounds(0, 0, 1080, 300))), 7))
        // Our own overlay and the system's are not application windows.
        val ours = WindowShape(9, WindowKind.ACCESSIBILITY_OVERLAY, 9, false, SCREEN, SCREEN)
        assertFalse(PopupStack.windowAbove(closeBack, listOf(window(7, 2, card), ours), 7))
    }

    @Test fun aPartialLookStopsOnlyOnWhatItRead() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val nodes = page() + layer(2, closeBack, card, "다른 안내")
        val line = PopupStack.line(null, closeBack, PopupRoot("0/0/2", card, PopupRootKind.FRONT_LAYER), look(nodes, partial = true))
        assertTrue(line, line.contains("partial=true"))
        assertNull(PopupStack.block(closeBack, PopupRoot("0/0/2", card, PopupRootKind.FRONT_LAYER), look(nodes, partial = true)))
    }

    @Test fun bannersAndVideoSkipsAreNotLookedAt() {
        assertFalse(PopupStack.applies(PopupRoot("0/1", closeBack, PopupRootKind.BOTTOM_BANNER)))
        assertFalse(PopupStack.applies(PopupRoot("0/1", closeBack, PopupRootKind.VIDEO_COVER)))
        assertTrue(PopupStack.applies(PopupRoot("0/1", closeBack, PopupRootKind.FRONT_LAYER)))
    }

    @Test fun theLinesHoldBoundsAndCodesOnly() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val nodes = page() + dialog(1, closeBack, card, "LMS 앱 업데이트 공지") + layer(2, closeBack, card, "다른 안내")
        val root = PopupRoot("0/0/2", card, PopupRootKind.FRONT_LAYER)
        val stack = PopupStack.line(StackBlock.POPUP_OVERLAP, closeBack, root, look(nodes))
        val press = PopupStack.pressLine("USER", root, closeBack)
        assertEquals("PRESS_TARGET by=USER kind=FRONT_LAYER root=90,600,990,1800 button=540,1500,990,1700", press)
        assertEquals("ROOT_PICK kind=FRONT_LAYER root=90,600,990,1800 mode=POPUP_SUBTREE", PopupStack.pickLine(root, TreeReadMode.POPUP_SUBTREE))
        assertTrue(stack, stack.startsWith("STACK_LOOK block=POPUP_OVERLAP button=540,1500,990,1700 others=1 [ALERT_DIALOG:90,600,990,1800]"))
        for (line in listOf(stack, press)) assertFalse(line, line.contains("공지") || line.contains("닫기") || line.contains("0/0"))
    }

    @Test fun thePromptOffersMovesOnly() {
        val card = ScreenBounds(90, 600, 990, 1800)
        val nodes = page() + layer(1, closeBack, card, "다른 안내")
        val popup = SimplePopupAnalysis.build(nodes, PopupRoot("0/0/1", card, PopupRootKind.FRONT_LAYER), "p", 7, SCREEN, 1L, 0L)
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.UNKNOWN, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        assertNotNull(planned.options.firstOrNull { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        val shown = SimplePromptOptions.overlapped(planned, frontKnown = true)
        assertEquals(PromptSummary.POPUPS_OVERLAP, shown.summary)
        assertTrue(shown.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS })
        assertEquals(listOf(SimplePromptOptions.FRONT_ID), shown.options.filter { it.kind == PromptOptionKind.MOVE_TO_BUTTON }.map { it.candidateId })
        assertFalse(shown.briefQuestion)
    }

}
