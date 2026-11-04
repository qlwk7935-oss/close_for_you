package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F43 (준영 SM-M446K Android 16, c279ee3, 시험 앱 P33 아래 시트: `A*S:wPrP active=NO_WIDER_SAME_APP_WINDOW`, NO_POPUP_ROOT
 * with TalkBack on and off): a Dialog stuck to the bottom of the screen, full width, with the activity under it not listed,
 * is the popup window. A split-screen lower half is not.
 */
class F43Test {
    private val screen = ScreenBounds(0, 0, 1080, 2408)
    private val status = WindowShape(1, WindowKind.SYSTEM, 30, null, ScreenBounds(0, 0, 1080, 80), null)
    private val nav = WindowShape(2, WindowKind.SYSTEM, 29, null, ScreenBounds(0, 2280, 1080, 2408), null)
    private fun app(id: Int, b: ScreenBounds, same: Boolean? = true, root: ScreenBounds? = b) =
        WindowShape(id, WindowKind.APPLICATION, 20 + id, same, b, root)
    private fun judge(active: WindowShape, vararg others: WindowShape) =
        WindowBoundsRules.activeJudgement(active, listOf(status, *others, active, nav), screen)

    private val sheet = ScreenBounds(0, 1700, 1080, 2408)

    @Test fun p33TheSheetAloneIsThePopupWindow() {
        val active = app(16, sheet)
        assertEquals(ActiveJudgement(null, BottomSheetVerdict.ACCEPTED), judge(active))
        // Ending above the navigation bar, too.
        assertEquals(ActiveJudgement(null, BottomSheetVerdict.ACCEPTED), judge(app(16, ScreenBounds(0, 1600, 1080, 2280))))
        // The debug line stays codes only.
        val shapes = listOf(status, active, nav)
        assertEquals("WINDOWS app=1 [A*S:wPrP S?:wPrN S?:wPrN] active=BOUNDED above=-",
            WindowBoundsRules.line(shapes.sortedByDescending { it.layer }, 16, screen, WindowBoundsRules.activeBounded(active, shapes, screen), emptyList()))
    }

    @Test fun withTheActivityListedUnderItTheOldRuleTakesIt() {
        // F12: a wider window of the app under the sheet: bounded as before, not by the sheet rule.
        assertEquals(ActiveJudgement(null), judge(app(16, sheet), app(3, screen)))
    }

    @Test fun splitScreenLowerPartIsNotASheet() {
        val lowerThird = app(16, ScreenBounds(0, 1600, 1080, 2408))
        // Another app above it.
        assertEquals(ActiveJudgement(ActiveWindowReject.BOTTOM_SHEET_SPLIT, BottomSheetVerdict.REJECTED_SPLIT),
            judge(lowerThird, app(3, ScreenBounds(0, 0, 1080, 1570), same = false)))
        // The same app in a second window above it (it is wider, but not under the active one).
        assertEquals(ActiveJudgement(ActiveWindowReject.BOTTOM_SHEET_SPLIT, BottomSheetVerdict.REJECTED_SPLIT),
            judge(lowerThird, app(3, ScreenBounds(0, 0, 1080, 1570))))
        // Only the divider listed.
        val divider = WindowShape(4, WindowKind.SPLIT_DIVIDER, 25, null, ScreenBounds(0, 1570, 1080, 1600), null)
        assertEquals(ActiveWindowReject.BOTTOM_SHEET_SPLIT, judge(lowerThird, divider).reject)
        // A 50:50 lower half is taller than a sheet: not sheet-shaped, the old answer.
        assertEquals(ActiveJudgement(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW), judge(app(16, ScreenBounds(0, 1234, 1080, 2408))))
        // The same app's upper half active, its lower half listed: not wider over it any more (F43), still not a popup.
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW,
            judge(app(16, ScreenBounds(0, 0, 1080, 1150)), app(3, ScreenBounds(0, 1180, 1080, 2408))).reject)
    }

    @Test fun otherShapesKeepTheirOldAnswers() {
        // P14 (a whole-screen activity) and one stopping above the navigation bar.
        assertEquals(ActiveJudgement(ActiveWindowReject.NOT_BOUNDED), judge(app(16, screen)))
        assertEquals(ActiveJudgement(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW), judge(app(16, ScreenBounds(0, 0, 1080, 2280))))
        // P15 (a toast is a system window), and a toast-sized app window at the bottom (not full width).
        val toast = WindowShape(9, WindowKind.SYSTEM, 40, null, ScreenBounds(240, 2000, 840, 2130), null)
        assertEquals(ActiveWindowReject.NOT_APPLICATION, judge(toast).reject)
        assertEquals(ActiveJudgement(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW), judge(app(16, ScreenBounds(240, 2000, 840, 2130))))
        // A thin bar at the bottom (smaller than a sheet), a top banner window, a sheet floating above the bottom.
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, judge(app(16, ScreenBounds(0, 2200, 1080, 2280))).reject)
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, judge(app(16, ScreenBounds(0, 80, 1080, 500))).reject)
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, judge(app(16, ScreenBounds(0, 1500, 1080, 2000))).reject)
        // F33: a centred Dialog alone, and F30 a touch-modal one: as before, not through the sheet rule.
        val card = ScreenBounds(42, 900, 1038, 1540)
        assertEquals(ActiveJudgement(null), judge(app(16, card)))
        assertEquals(ActiveJudgement(null), judge(app(16, screen, root = card)))
        // F12: a Dialog over its listed activity.
        assertEquals(ActiveJudgement(null), judge(app(16, card), app(3, screen)))
        // The keyboard is not an application window.
        val ime = WindowShape(5, WindowKind.INPUT_METHOD, 26, null, ScreenBounds(0, 1500, 1080, 2280), null)
        assertNull(WindowBoundsRules.activeJudgement(ime, listOf(ime), screen).sheet)
    }
}
