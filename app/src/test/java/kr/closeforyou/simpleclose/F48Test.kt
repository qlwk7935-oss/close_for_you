package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F48: the re-check right before a press tells which part failed, and only a button mismatch is said as a failure. */
class F48Test {
    private val screen = ScreenBounds(0, 0, 1080, 2400)
    // P34: a full-screen layer in the activity's own window, under the status bar (#73 top padding).
    private val layer = ScreenBounds(0, 63, 1080, 2400)

    @Test fun aP34LayerAsReadPassesTheRecheck() {
        assertNull(ClickRecheck.packageMiss("kr.closeforyou.popuptest", "kr.closeforyou.popuptest"))
        assertNull(ClickRecheck.rootMiss(visible = true, live = layer, expected = layer, mustBeDialog = false, isDialog = false))
        assertNull(ClickRecheck.targetMiss(visible = true, enabled = true, clickable = true, sameName = true))
        // A same-window layer is looked up in the active window (no window id bound), as before.
        val root = PopupRoot("0/0/1", layer, PopupRootKind.DIM_OVERLAY)
        assertNull(PopupRootFinder.bindWindow(root, popupWindowId = null, activeWindowId = 7).windowId)
    }

    @Test fun eachPartTellsWhyAndTheCheckIsAsStrictAsBefore() {
        assertEquals(RecheckMiss.NO_ACTIVE_WINDOW, ClickRecheck.packageMiss(null, "a"))
        assertEquals(RecheckMiss.OTHER_APP, ClickRecheck.packageMiss("b", "a"))
        assertEquals(RecheckMiss.ROOT_HIDDEN, ClickRecheck.rootMiss(false, layer, layer, false, false))
        // One pixel off is still "moved": never loosened.
        assertEquals(RecheckMiss.ROOT_MOVED, ClickRecheck.rootMiss(true, layer.copy(top = 64), layer, false, false))
        assertEquals(RecheckMiss.ROOT_NOT_DIALOG, ClickRecheck.rootMiss(true, layer, layer, mustBeDialog = true, isDialog = false))
        assertNull(ClickRecheck.rootMiss(true, layer, layer, mustBeDialog = true, isDialog = true))
        assertEquals(RecheckMiss.TARGET_HIDDEN, ClickRecheck.targetMiss(false, true, true, true))
        assertEquals(RecheckMiss.TARGET_DISABLED, ClickRecheck.targetMiss(true, false, true, true))
        assertEquals(RecheckMiss.TARGET_NOT_CLICKABLE, ClickRecheck.targetMiss(true, true, false, true))
        assertEquals(RecheckMiss.TARGET_NAME_CHANGED, ClickRecheck.targetMiss(true, true, true, false))
    }

    @Test fun onlyAButtonMismatchIsSaidAsAFailure() {
        for (miss in RecheckMiss.entries) {
            val said = ClickRecheck.saysFailure(miss, bottomBanner = false)
            assertEquals(miss.name, miss.group == RecheckGroup.TARGET, said)
            // F37: a bottom banner stays quiet.
            assertFalse(ClickRecheck.saysFailure(miss, bottomBanner = true))
        }
        assertTrue(ClickRecheck.saysFailure(RecheckMiss.TARGET_NAME_CHANGED, bottomBanner = false))
        assertFalse(ClickRecheck.saysFailure(RecheckMiss.OTHER_APP, bottomBanner = false))
        assertFalse(ClickRecheck.saysFailure(RecheckMiss.ROOT_MISSING, bottomBanner = false))
    }

    @Test fun traceCodesByGroup() {
        assertEquals(SimpleTrace.RECHECK_GONE_PACKAGE, ClickRecheck.trace(RecheckMiss.NO_ACTIVE_WINDOW.group))
        assertEquals(SimpleTrace.RECHECK_GONE_ROOT, ClickRecheck.trace(RecheckMiss.ROOT_UNREADABLE.group))
        assertEquals(SimpleTrace.RECHECK_GONE_TARGET, ClickRecheck.trace(RecheckMiss.TARGET_UNREADABLE.group))
    }

    @Test fun theLineHasCodesAndEdgeOffsetsOnly() {
        // The #73 padding applied after the read would show as the top edge moving by the status bar's height.
        assertEquals("RECHECK_MISS reason=ROOT_MOVED dl=0 dt=63 dr=0 db=0",
            ClickRecheck.line(RecheckMiss.ROOT_MOVED, expected = screen, live = layer))
        assertEquals("RECHECK_MISS reason=TARGET_NAME_CHANGED", ClickRecheck.line(RecheckMiss.TARGET_NAME_CHANGED, screen, null))
        assertEquals("RECHECK_MISS reason=OTHER_APP", ClickRecheck.line(RecheckMiss.OTHER_APP))
    }
}
