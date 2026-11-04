package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F50: a popup is judged once it stands still; "still shown" is told by its window; a no-root read leaves a code line. */
class F50Test {
    private val key = SettleRules.key(7, PopupRootKind.DIALOG_WINDOW, "0")
    // 에뮬레이터 P01: first read mid-animation (42 px right, 821 px lower), then where it settled.
    private val settled = ScreenBounds(42, 900, 954, 1600)
    private val opening = ScreenBounds(84, 1721, 996, 2421)

    @Test fun aDialogComingInIsReadAgainUntilItStandsStill() {
        val (v1, s1) = SettleRules.next(null, key, opening, 3, now = 1_000)
        assertEquals(SettleVerdict.WAIT, v1)
        // Moved, and its close came (3 → 4 nodes): read again.
        val (v2, s2) = SettleRules.next(s1, key, settled, 4, now = 1_150)
        assertEquals(SettleVerdict.WAIT, v2)
        // The same twice in a row: judged now.
        val (v3, s3) = SettleRules.next(s2, key, settled, 4, now = 1_300)
        assertEquals(SettleVerdict.STABLE, v3)
        assertEquals(300L, 1_300 - s3.firstAt)
        // Later reads of it (repaints): judged as always, nothing new logged.
        assertEquals(SettleVerdict.STILL, SettleRules.next(s3, key, settled, 4, now = 1_900).first)
    }

    @Test fun theUsualPathCostsOneReadAgain() {
        val (first, s) = SettleRules.next(null, key, settled, 4, now = 0)
        assertEquals(SettleVerdict.WAIT, first)
        val (second, s2) = SettleRules.next(s, key, settled, 4, now = SettleRules.READ_AGAIN_MILLIS)
        assertEquals(SettleVerdict.STABLE, second)
        assertEquals(1, s2.reads)
    }

    @Test fun aPopupThatNeverStandsStillIsJudgedAtTheLimit() {
        var s: SettleState? = null
        var v = SettleVerdict.WAIT
        var t = 0L
        var reads = 0
        while (v == SettleVerdict.WAIT) {
            val r = SettleRules.next(s, key, ScreenBounds(0, t.toInt(), 1080, t.toInt() + 700), 4, now = t)
            v = r.first
            s = r.second
            t += SettleRules.READ_AGAIN_MILLIS
            reads += 1
        }
        assertEquals(SettleVerdict.LIMIT, v)
        assertTrue(reads <= SettleRules.MAX_READS + 1)
        assertTrue(t <= SettleRules.MAX_MILLIS + 2 * SettleRules.READ_AGAIN_MILLIS)
    }

    @Test fun anotherRootOrALongPauseIsANewSighting() {
        val (_, s) = SettleRules.next(null, key, settled, 4, now = 0)
        val (_, stable) = SettleRules.next(s, key, settled, 4, now = 150)
        assertEquals(SettleVerdict.WAIT, SettleRules.next(stable, SettleRules.key(7, PopupRootKind.DIALOG_WINDOW, "0/1"), settled, 4, now = 300).first)
        assertEquals(SettleVerdict.WAIT, SettleRules.next(stable, key, settled, 4, now = 150 + SettleRules.FORGET_MILLIS + 1).first)
        // The key never holds the bounds (they are what moves).
        assertFalse(key.contains("900"))
    }

    @Test fun stillShownIsToldByTheWindow() {
        val app = "kr.closeforyou.popuptest"
        // The quiet run: the Dialog's window still listed, its app in front.
        assertEquals(true, StillShownRules.byWindow(app, app, listed = true))
        assertEquals(false, StillShownRules.byWindow(app, app, listed = false))
        assertEquals(false, StillShownRules.byWindow("other", app, listed = true))
        assertNull(StillShownRules.byWindow(null, app, listed = true))
        assertNull(StillShownRules.byWindow(app, app, listed = null))
        // P01 dl=dr=-42 dt=db=821: a same-size move, read again once; then (still shown) asked.
        val live = ScreenBounds(opening.left - 42, opening.top + 821, opening.right - 42, opening.bottom + 821)
        assertTrue(ClickRecheck.sameSizeMove(RecheckMiss.ROOT_MOVED, opening, live))
        assertEquals(MissStep.REREAD, ClickRecheck.afterMiss(RecheckMiss.ROOT_MOVED, shift = true, rereadDone = false, stillShown = true, bottomBanner = false))
        assertEquals(MissStep.ASK, ClickRecheck.afterMiss(RecheckMiss.ROOT_MOVED, shift = true, rereadDone = true, stillShown = true, bottomBanner = false))
    }

    @Test fun aMovedPagePopupIsFoundAnywhereByItsWords() {
        val oldRoot = PopupRoot("0/1", ScreenBounds(0, 1700, 1080, 2400), PopupRootKind.DIM_OVERLAY)
        val old = listOf(node("0/1/0", "광고 문구", ScreenBounds(40, 1800, 1040, 1900)))
        val memory = PopupMemory(old.mapNotNull { it.text }.toSet(), old.map { it.bounds }.toSet(), oldRoot.bounds, oldRoot.bounds)
        val screen = ScreenBounds(0, 0, 1080, 2400)
        val newRoot = PopupRoot("0/1", ScreenBounds(0, 100, 1080, 800), PopupRootKind.DIM_OVERLAY)
        val moved = listOf(node("0/1/0", "광고 문구", ScreenBounds(40, 200, 1040, 300)))
        // Far from where it was: the old place check misses it, the words find it.
        assertNull(memory.reappearance(newRoot, moved, screen))
        assertTrue(memory.foundAnywhere(newRoot, moved, screen))
        assertFalse(memory.foundAnywhere(newRoot, listOf(node("0/1/0", "다른 글", ScreenBounds(40, 200, 1040, 300))), screen))
    }

    @Test fun theNoRootLineHasCodesAndCountsOnly() {
        val screen = ScreenBounds(0, 0, 1080, 2400)
        val nodes = listOf(
            node("0", null, screen),
            node("0/0", null, ScreenBounds(0, 63, 1080, 2400), clickable = true),
            node("0/0/0", "닫기", ScreenBounds(900, 90, 1050, 230), clickable = true),
        )
        val line = NoRootLine.line(TreeReadMode.FULL_AFTER_PROBE, ProbeOutcome.MISS, nodes, screen, wholeAborted = false, wholeOffMillis = 7_300)
        assertEquals("NO_ROOT mode=FULL_AFTER_PROBE probe=MISS nodes=3 shown=3 clickable=2 bigBlank=1 namedClose=1 aborted=false wholeOffMs=7300", line)
        assertFalse(line.contains("닫기"))
    }

    private fun node(path: String, text: String?, bounds: ScreenBounds, clickable: Boolean = false) =
        NodeSnapshot(path = path, viewId = null, className = "android.view.View", text = text, contentDescription = null,
            clickable = clickable, enabled = true, bounds = bounds)
}
