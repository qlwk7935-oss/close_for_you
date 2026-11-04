package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F58: an ordinary move is not asked about again; it ends when the screen changes; after the time it is asked, never pressed by itself. */
class F58Test {
    private val root = PopupRoot("0/0/1", ScreenBounds(90, 600, 990, 1800), PopupRootKind.FRONT_LAYER)
    private val other = PopupRoot("0/0/5", ScreenBounds(90, 100, 990, 500), PopupRootKind.FRONT_LAYER)
    private val hold = MoveHold("7:popup", root, cover = null, startedAt = 15_000)

    // The prompt came up at 0, the person heard it and chose the move at 15 s: the 10 s cooldown is over.
    @Test fun chosenAfterTenSecondsIsNotAskedAgain() {
        assertTrue(hold.startedAt > SimpleCloseEngineFeature.SAME_POPUP_COOLDOWN_MILLIS)
        assertEquals(HoldStep.KEEP, MoveHoldRules.step(hold, 15_540, 0, "7:popup", root, coverPresent = null))
        assertEquals(HoldStep.KEEP, MoveHoldRules.step(hold, 40_000, 0, "7:popup", root, coverPresent = null))
    }

    @Test fun thePersonClosingItEndsTheHold() {
        assertEquals(HoldStep.END_POPUP_GONE, MoveHoldRules.stepNoRoot(hold, 20_000, 19_000, rootPresent = false))
        assertTrue(MoveHoldRules.releasesAuto(HoldStep.END_POPUP_GONE))
    }

    @Test fun anotherPopupEndsTheHold() {
        assertEquals(HoldStep.END_OTHER_POPUP, MoveHoldRules.step(hold, 20_000, 0, "7:new", other, coverPresent = null))
        assertFalse(MoveHoldRules.releasesAuto(HoldStep.END_OTHER_POPUP))
    }

    // AGENTS 6-7: the time ends, the popup is still there: asked, not pressed.
    @Test fun afterTheTimeItIsNeverPressedByItself() {
        assertEquals(HoldStep.END_TIMEOUT, MoveHoldRules.step(hold, 15_000 + MoveHoldRules.QUIET_MILLIS, 0, "7:popup", root, null))
        assertFalse(MoveHoldRules.releasesAuto(HoldStep.END_TIMEOUT))
        val failed = LinkedHashSet<String>()
        val holds = AutoPressHolds(failed)
        holds.hold("7:popup")
        // The engine's check: `decided is AutoClose && words in autoFailed` -> Ask.
        assertTrue("7:popup" in failed)
    }

    @Test fun whatTheMoveHeldIsFreedWhenThePopupWent() {
        val failed = LinkedHashSet<String>()
        val holds = AutoPressHolds(failed)
        holds.hold("7:popup")
        holds.release("7:popup")
        assertFalse("7:popup" in failed)
    }

    // AGENTS 6-6: a real failure is never freed.
    @Test fun aRealFailureStays() {
        val failed = linkedSetOf("7:failed")
        val holds = AutoPressHolds(failed)
        holds.hold("7:failed")
        holds.release("7:failed")
        assertTrue("7:failed" in failed)
        holds.hold("7:later")
        holds.failed("7:later")
        holds.release("7:later")
        assertTrue("7:later" in failed)
    }

    @Test fun theLinesHoldCodesAndBoundsOnly() {
        assertEquals("BUTTON_MOVE role=USER_DECISION to=90,1500,540,1700", PopupStack.buttonMoveLine("USER_DECISION", ScreenBounds(90, 1500, 540, 1700)))
        assertEquals("MOVE_HOLD_END reason=END_POPUP_GONE afterMs=4200", MoveHoldRules.line(HoldStep.END_POPUP_GONE, 4_200))
    }
}
