package kr.closeforyou.simpleclose

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F24 (LG 11/1 G마켓 웹: 25 reads in 25 s; 예스24: a 3.6 s whole read, then a probe read every 0.2 s): the read
 * budget on a page with no popup, and the purchase switch's default.
 */
class ReadBudgetTest {
    @Test fun repaintGapsDoubleToOneSecondAndStretchWithTheReadCost() {
        // F26: cap 3 → 1 s (LG 올리브영 앱 waited 3.2 s); never shorter than 6 × the last read's cost while quiet.
        assertEquals(listOf(200L, 400L, 800L, 1_000L, 1_000L), (0..4).map { ReadBudget.contentGap(it) })
        assertEquals(1_000L, ReadBudget.contentGap(100))
        assertEquals(1_800L, ReadBudget.contentGap(4, lastCost = 300))
        assertEquals(1_000L, ReadBudget.contentGap(4, lastCost = 100))
        assertEquals(200L, ReadBudget.contentGap(0, lastCost = 300))
        assertEquals(3_000L, ReadBudget.contentGap(2, lastCost = 1_750))
    }

    @Test fun onlyALargeNonListContainerChangingLooksLikeAPopup() {
        assertTrue(ReadBudget.likelyPopup(true, "android.widget.FrameLayout", 1.0))
        assertTrue(ReadBudget.likelyPopup(true, "androidx.constraintlayout.widget.ConstraintLayout", 0.7))
        assertFalse(ReadBudget.likelyPopup(true, "android.widget.FrameLayout", 0.3))
        assertFalse(ReadBudget.likelyPopup(false, "android.widget.FrameLayout", 1.0))
        listOf("android.webkit.WebView", "androidx.recyclerview.widget.RecyclerView", "android.widget.ListView",
            "androidx.viewpager2.widget.ViewPager2", "android.widget.ScrollView").forEach { assertFalse(it, ReadBudget.likelyPopup(true, it, 1.0)) }
    }

    @Test fun aSlowPageWalksTheWholeTreeOnlyOnWindowChangesAndRarely() {
        val normal = (0 until 9).count { ReadBudget.wholeRead(DetectTrigger.RECHECK, it, slowPage = false, videoWeb = false) }
        val slow = (0 until 9).count { ReadBudget.wholeRead(DetectTrigger.RECHECK, it, slowPage = true, videoWeb = false) }
        assertEquals(3, normal)
        assertEquals(1, slow)
        assertTrue(ReadBudget.wholeRead(DetectTrigger.WINDOW_EVENT, 0, slowPage = true, videoWeb = false))
        assertFalse(ReadBudget.wholeRead(DetectTrigger.TALKBACK_FOCUS, 0, slowPage = true, videoWeb = false))
        assertTrue(ReadBudget.wholeRead(DetectTrigger.RECHECK, 0, slowPage = true, videoWeb = true))
    }

    /** A page that repaints every 50 ms (a carousel) and never has a popup; each read costs [cost] ms. */
    private class Carousel(scope: TestScope, private val cost: Long, private val found: () -> Boolean = { false }) {
        var lastInspection = 0L
        var busy = 0L
        var reads = 0
        val clock = scope.testScheduler
        val jobs = EngineJobs(scope)
        val events = SimpleInspectionEvents(
            { jobs }, { false }, { lastInspection }, { 100_000L + clock.currentTime },
            { true }, {}, { _, _ ->
                lastInspection = 100_000L + clock.currentTime
                reads++
                delay(cost)
                busy += cost
                found()
            }, SimpleTraceSink.NONE,
        )
        fun repaint() = events.onEvent("com.example.shop", SimpleInspectionEvent.CONTENT)
    }

    @Test fun aCarouselWithoutAPopupSpendsAtMostATenthOfTheTimeReading() = runTest {
        val page = Carousel(this, cost = 300)
        val total = 60_000L
        var t = 0L
        while (t < total) {
            page.repaint()
            advanceTimeBy(50)
            runCurrent()
            t += 50
        }
        // The budget holds: a 0.3 s read waits 1.8 s (F26: 6 × cost, cap 1 s otherwise), so about 14% at most.
        val pct = page.busy * 100 / total
        assertTrue("read $pct% of the time in ${page.reads} reads", pct <= 15)
    }

    @Test fun aWindowChangeStartsTheBudgetAgainButRepaintsDoNot() = runTest {
        val page = Carousel(this, cost = 100)
        repeat(200) { page.repaint(); advanceTimeBy(50); runCurrent() } // 10 s of repaints: deep in the budget
        val before = page.reads
        repeat(20) { page.repaint(); advanceTimeBy(50); runCurrent() } // 1 s more: at most one read
        assertTrue(page.reads - before <= 1)
        page.events.onEvent("com.example.shop", SimpleInspectionEvent.WINDOW)
        advanceTimeBy(SimpleCloseEngineFeature.DEBOUNCE_MILLIS + 1)
        runCurrent()
        assertTrue(page.reads - before >= 1)
    }

    @Test fun aRepaintThatLikelyAddedAPopupIsReadAtOnceEvenDeepInTheBudget() = runTest {
        val page = Carousel(this, cost = 100)
        repeat(200) { page.repaint(); advanceTimeBy(50); runCurrent() } // 10 s of repaints: deep in the budget
        val before = page.reads
        page.events.onEvent("com.example.shop", SimpleInspectionEvent.CONTENT_LIKELY_POPUP)
        advanceTimeBy(SimpleCloseEngineFeature.DEBOUNCE_MILLIS + 100 + 1)
        runCurrent()
        assertEquals(before + 1, page.reads)
    }

    @Test fun afterACloseRepaintsWaitAtOnce() = runTest {
        val page = Carousel(this, cost = 100)
        page.events.backOff()
        page.repaint()
        advanceTimeBy(300)
        runCurrent()
        val first = page.reads
        repeat(10) { page.repaint(); advanceTimeBy(50); runCurrent() } // 0.5 s
        assertTrue(page.reads - first <= 1)
        assertTrue(ReadBudget.contentGap(ReadBudget.AFTER_CLOSE) >= 800L)
    }

    // ---- the purchase switch (재호 to decide; default = ask, as before F23) ----

    private fun withBuy() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(90, 600, 990, 1700)),
        node("0/0/0", text = "가을 특가 이벤트", bounds = ScreenBounds(90, 600, 990, 800)),
        node("0/0/1", "android.widget.Button", text = "구매하기", clickable = true, bounds = ScreenBounds(90, 1500, 540, 1700)),
        node("0/0/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1500, 990, 1700)),
    )

    @Test fun aBuyButtonKeepsThePopupAskedByDefaultAndNotWithTheSwitchOff() {
        assertTrue(SimpleCloseEngineFeature.ASK_WHEN_PURCHASE_BUTTON)
        val nodes = withBuy()
        val root = PopupRootFinder.find(nodes, SCREEN)!!
        assertEquals(1, CloseButtonFinder.scan(nodes, root).riskCount)
        assertEquals(0, CloseButtonFinder.scan(nodes, root, askWhenPurchase = false).riskCount)
        assertTrue(SimpleLabelRules.isRisky("구매하기"))
        assertFalse(SimpleLabelRules.isRisky("구매하기", askWhenPurchase = false))
        assertTrue(SimpleLabelRules.isRisky("결제하기", askWhenPurchase = false))
        // Either way the buy button is never a press target.
        assertTrue(CloseButtonFinder.scan(nodes, root, askWhenPurchase = false).candidates.none { it.label == "구매하기" })
    }
}
