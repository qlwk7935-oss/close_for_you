package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupMemoryTest {
    private fun memory(nodes: List<kr.closeforyou.core.model.NodeSnapshot>): PopupMemory {
        val root = PopupRootFinder.find(nodes, SCREEN)!!
        return PopupMemory.of(SimplePopupAnalysis.build(nodes, root, "p", 7, SCREEN, 1L, 0L))
    }

    @Test fun popupWordsAndPlacesAreRemembered_pageWordsAreNot() {
        val m = memory(SimpleNodes.elevenStreet())
        // The sheet's own text, its button, and a node at the sheet's place: leftovers.
        assertTrue(m.matches("앱에서만 제공되는 혜택을 놓치고 계신 건 아닌가요?", null, ScreenBounds(0, 0, 10, 10)))
        assertTrue(m.matches(null, "모바일 웹으로 볼래요", ScreenBounds(0, 0, 10, 10)))
        assertTrue(m.matches("다른 글", null, ScreenBounds(40, 2150, 1040, 2300)))
        // The page's banner text, outside the popup: a fine focus target.
        assertFalse(m.matches("11번가 앱에서 앱 한정 혜택 받기", null, ScreenBounds(0, 200, 800, 300)))
        assertFalse(m.matches("오늘의 특가 상품", null, ScreenBounds(0, 320, 1080, 420)))
    }

    // ---- F4 (LG 10/31 21:00: TalkBack still read the closed 11번가 sheet again) ----

    @Test fun anythingInsideTheOldPopupAreaIsALeftover() {
        val m = memory(SimpleNodes.elevenStreet())
        // A DOM node with other words, inside the sheet's area (1632..2337).
        assertTrue(m.matches("바뀐 글", null, ScreenBounds(40, 2000, 600, 2100)))
        assertFalse(m.matches("바뀐 글", null, ScreenBounds(40, 1500, 600, 1632)))
    }

    @Test fun aFullScreenOverlayAreaIsNotUsed() {
        val m = PopupMemory(emptySet(), emptySet(), area = null)
        assertFalse(m.matches("오늘의 특가 상품", null, ScreenBounds(0, 320, 1080, 420)))
    }

    @Test fun pickSkipsLeftoversAndTakesTheHighestItem() {
        val list = listOf(
            FocusCandidate(ScreenBounds(0, 1700, 1080, 1800), leftover = true, heading = false),
            FocusCandidate(ScreenBounds(0, 900, 1080, 1000), leftover = false, heading = false),
            FocusCandidate(ScreenBounds(0, 320, 1080, 420), leftover = false, heading = true),
        )
        assertEquals(PageFocusRules.Pick(2, FocusTargetKind.PAGE_HEADING), PageFocusRules.pick(list, webViewNamed = true))
    }

    @Test fun pickFallsBackToTheWebViewThenReportsWhy() {
        val leftovers = listOf(FocusCandidate(ScreenBounds(0, 1700, 1080, 1800), leftover = true, heading = false))
        assertEquals(FocusTargetKind.WEBVIEW_ROOT, PageFocusRules.pick(leftovers, webViewNamed = true).kind)
        assertEquals(FocusTargetKind.OVERLAPS_OLD_POPUP, PageFocusRules.pick(leftovers, webViewNamed = false).kind)
        assertEquals(FocusTargetKind.NONE, PageFocusRules.pick(emptyList(), webViewNamed = false).kind)
    }

    @Test fun traceNamesExistForEveryKindAndMiss() {
        FocusTargetKind.entries.forEach { SimpleTrace.focusTarget(it) }
        AdHintMiss.entries.forEach { SimpleTrace.adHintMiss(it) }
    }
}
