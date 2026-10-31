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
        assertEquals(PageFocusRules.Pick(2, FocusTargetKind.PAGE_HEADING), PageFocusRules.pick(list, webViewPresent = true))
    }

    // ---- F9 (LG 10/31 22:29: FOCUS_TARGET_OVERLAPS_OLD_POPUP -> FOCUS_MOVE_FAILED, TalkBack read the popup again) ----

    private val sheetLeftovers = listOf(
        FocusCandidate(ScreenBounds(0, 1700, 1080, 1800), leftover = true, heading = false),
        FocusCandidate(ScreenBounds(0, 1900, 1080, 2000), leftover = true, heading = false),
    )

    @Test fun firstTryWithoutFallbackOnlySaysWhy() {
        assertEquals(PageFocusRules.Pick(null, FocusTargetKind.OVERLAPS_OLD_POPUP), PageFocusRules.pick(sheetLeftovers, webViewPresent = true, fallback = false))
        assertEquals(FocusTargetKind.NONE, PageFocusRules.pick(emptyList(), webViewPresent = true, fallback = false).kind)
    }

    @Test fun whenEverythingOverlapsAnItemAboveThePopupIsTaken() {
        // Shares a word with the sheet (so a leftover by words) but sits wholly above it.
        val list = sheetLeftovers + FocusCandidate(ScreenBounds(0, 320, 1080, 420), leftover = true, heading = false, above = true)
        assertEquals(PageFocusRules.Pick(2, FocusTargetKind.ABOVE_OLD_POPUP), PageFocusRules.pick(list, webViewPresent = true))
    }

    @Test fun thenTheWebViewThenTheToolbar() {
        assertEquals(PageFocusRules.Pick(null, FocusTargetKind.WEBVIEW_ROOT, webViewRoot = true), PageFocusRules.pick(sheetLeftovers, webViewPresent = true))
        assertEquals(PageFocusRules.Pick(null, FocusTargetKind.TOOLBAR_ITEM, toolbar = true), PageFocusRules.pick(sheetLeftovers, webViewPresent = false))
        // Even with nothing named at all, a target is still looked for.
        assertEquals(FocusTargetKind.WEBVIEW_ROOT, PageFocusRules.pick(emptyList(), webViewPresent = true).kind)
    }

    @Test fun aGoodPageItemStillWinsOverEveryFallback() {
        val list = sheetLeftovers + FocusCandidate(ScreenBounds(0, 900, 1080, 1000), leftover = false, heading = false)
        assertEquals(PageFocusRules.Pick(2, FocusTargetKind.PAGE_ITEM), PageFocusRules.pick(list, webViewPresent = true, fallback = false))
    }

    @Test fun aboveIsMeasuredFromThePopupArea() {
        val m = memory(SimpleNodes.elevenStreet())
        assertTrue(m.above(ScreenBounds(0, 320, 1080, 420)))
        assertTrue(m.above(ScreenBounds(0, 1500, 1080, 1632)))
        assertFalse(m.above(ScreenBounds(0, 1600, 1080, 1700)))
        assertFalse(PopupMemory(emptySet(), emptySet(), area = null).above(ScreenBounds(0, 0, 10, 10)))
    }

    @Test fun traceNamesExistForEveryKindAndMiss() {
        FocusTargetKind.entries.forEach { SimpleTrace.focusTarget(it) }
        AdHintMiss.entries.forEach { SimpleTrace.adHintMiss(it) }
    }

    // ---- F12 (LG 10/31: after the person's press TalkBack began the popup text again) ----

    @Test fun beforeTheClickTheFocusGoesAboveThePopupNotBesideIt() {
        val beside = FocusCandidate(ScreenBounds(0, 1700, 300, 1800), leftover = false, heading = false)
        val above = FocusCandidate(ScreenBounds(0, 900, 1080, 1000), leftover = false, heading = false, above = true)
        val higher = FocusCandidate(ScreenBounds(0, 320, 1080, 420), leftover = false, heading = true, above = true)
        assertEquals(PageFocusRules.Pick(2, FocusTargetKind.ABOVE_OLD_POPUP), PageFocusRules.pick(listOf(beside, above, higher), webViewPresent = true, beforeClick = true))
    }

    @Test fun beforeTheClickWithNothingAboveTheWebViewThenTheToolbar() {
        val beside = listOf(FocusCandidate(ScreenBounds(0, 1700, 300, 1800), leftover = false, heading = false))
        assertEquals(PageFocusRules.Pick(null, FocusTargetKind.WEBVIEW_ROOT, webViewRoot = true), PageFocusRules.pick(beside, webViewPresent = true, beforeClick = true))
        assertEquals(PageFocusRules.Pick(null, FocusTargetKind.TOOLBAR_ITEM, toolbar = true), PageFocusRules.pick(beside, webViewPresent = false, beforeClick = true))
    }

    @Test fun preClickTraceNames() {
        assertEquals(SimpleTrace.FOCUS_PRE_CLICK_WEBVIEW_ROOT, SimpleTrace.preClickTarget(FocusTargetKind.WEBVIEW_ROOT))
        assertEquals(SimpleTrace.FOCUS_PRE_CLICK_NONE, SimpleTrace.preClickTarget(FocusTargetKind.PAGE_ITEM))
    }
}
