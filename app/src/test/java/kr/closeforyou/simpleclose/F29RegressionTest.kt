package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F29 (LG 11/1 11번가 웹, c750177): the sheet closed, but the strict re-check 220 ms after the click saw it still
 * sliding out and called it back ("대신 닫지 못했어요"). A leaving popup moves between reads; a popup really back
 * sits still. Both must hold.
 */
class F29RegressionTest {
    private val sheet = "0/1/4"
    private val before = SimplePopupAnalysis.build(SimpleNodes.elevenStreet(), PopupRootFinder.find(SimpleNodes.elevenStreet(), SCREEN)!!,
        "com.android.chrome", 1, SCREEN, 1, 0)
    private val memory = PopupMemory.of(before)

    /** The page with the sheet shifted down by [dy] (and shrunk to [height] when given), as Chrome shows it mid-animation. */
    private fun shifted(dy: Int, height: Int? = null): List<NodeSnapshot> = SimpleNodes.elevenStreet().map { n ->
        if (!n.path.startsWith(sheet)) return@map n
        val b = n.bounds.copy(top = n.bounds.top + dy, bottom = n.bounds.bottom + dy)
        n.copy(bounds = if (height != null) b.copy(bottom = b.top + height) else b)
    }

    private fun recheck(nodes: List<NodeSnapshot>): Reappearance? {
        val root = PopupRootFinder.find(nodes, SCREEN) ?: return null
        return memory.reappearance(root, nodes, SCREEN)
    }

    /** What the service's loop decides over these reads (the last one counts as back when still moving). */
    private fun loop(vararg reads: List<NodeSnapshot>?): RecheckVerdict {
        var previous: Reappearance? = null
        for (read in reads) {
            val found = read?.let(::recheck)
            val verdict = RecheckRules.verdict(found, previous)
            if (verdict != RecheckVerdict.MOVING) return verdict
            previous = found
        }
        return RecheckVerdict.BACK
    }

    private val gone = SimpleNodes.elevenStreet().filterNot { it.path.startsWith(sheet) }

    @Test fun aSheetSlidingOutThenGoneIsClosed() {
        assertEquals(RecheckVerdict.MOVING, RecheckRules.verdict(recheck(shifted(300)), null))
        assertEquals(RecheckVerdict.CLOSED, loop(shifted(300), shifted(600), gone))
    }

    @Test fun leftoversOffScreenOrWithoutSizeAreNotThePopup() {
        // Slid past the bottom of the screen, still in the DOM.
        assertNull(recheck(shifted(SCREEN.bottom)))
        // Collapsed to zero height in place.
        assertNull(recheck(shifted(0, height = 0)))
        assertEquals(RecheckVerdict.CLOSED, loop(shifted(300), shifted(SCREEN.bottom)))
    }

    @Test fun theSamePopupBackInItsPlaceIsStillAFailure() {
        // F20 11번가 (the button did nothing) and an 옥션 popup shown again: exactly where it was, at once.
        val found = recheck(SimpleNodes.elevenStreet())
        assertNotNull(found)
        assertTrue(found!!.inPlace)
        assertEquals(RecheckVerdict.BACK, loop(SimpleNodes.elevenStreet()))
        // Also when it comes back after a first read that saw it leave.
        assertEquals(RecheckVerdict.BACK, loop(shifted(300), SimpleNodes.elevenStreet()))
    }

    @Test fun aPopupThatSettlesSomewhereElseIsBack() {
        assertEquals(RecheckVerdict.BACK, loop(shifted(120), shifted(120)))
    }

    @Test fun stillMovingWhenTimeRunsOutIsNeverClosed() {
        assertEquals(RecheckVerdict.BACK, loop(shifted(100), shifted(200), shifted(300), shifted(400)))
        assertTrue(SimpleCloseEngineFeature.CLOSE_RECHECK_SETTLE_MILLIS in 600..1_500)
    }

    @Test fun strongModeStillPressesStayOnWebFirstAfterThePr69Merge() {
        assertEquals("모바일 웹으로 볼래요", StrongCloseRules.pick(before.scan.candidates)?.label)
    }
}
