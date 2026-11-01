package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.settings.HelpAppsMode
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F32 (LG 11/1 16:00, 2e5537e: 시험 앱 P04 still NO_POPUP_ROOT, no WINDOW_BOUNDS_FROM_ROOT). dumpsys: the Dialog is a
 * TYPE_APPLICATION window 996×637 (DIM_BEHIND, touch-modal) over the activity's BASE_APPLICATION window 1080×2460, both
 * the test app's. The window lists below are the shapes `getWindows()` can give for that screen.
 */
class F32RegressionTest {
    private val lg = ScreenBounds(0, 0, 1080, 2460)
    private val card = ScreenBounds(42, 911, 1038, 1548) // 996 × 637, centred
    private val withInsets = ScreenBounds(-42, 827, 1122, 1632) // surface insets 84
    private val status = WindowShape(1, WindowKind.SYSTEM, 30, null, ScreenBounds(0, 0, 1080, 80), null)
    private val nav = WindowShape(2, WindowKind.SYSTEM, 29, null, ScreenBounds(0, 2340, 1080, 2460), null)

    @Test fun theActivityWindowNotListedUnderATouchModalDialog() {
        // The Dialog's touchable region is the whole screen: its window says full, and the activity below is left out.
        val dialog = WindowShape(16, WindowKind.APPLICATION, 21, true, lg, card)
        assertNull(WindowBoundsRules.activeBounded(dialog, listOf(status, dialog, nav), lg))
        // Before F32 this was the answer: no wider window of the same app listed.
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW,
            WindowBoundsRules.activeBounded(dialog.copy(window = card), listOf(status, dialog.copy(window = card), nav), lg))
    }

    @Test fun bothWindowsListed() {
        val base = WindowShape(17, WindowKind.APPLICATION, 20, true, lg, lg)
        // The Dialog active, reporting its frame (with or without the surface insets).
        for (frame in listOf(card, withInsets)) {
            val dialog = WindowShape(16, WindowKind.APPLICATION, 21, true, frame, card)
            assertNull(frame.toString(), WindowBoundsRules.activeBounded(dialog, listOf(status, dialog, base, nav), lg))
        }
        // The activity active (TalkBack's focus stayed there): not a popup window itself, the Dialog is read as the window above.
        assertEquals(ActiveWindowReject.NOT_BOUNDED, WindowBoundsRules.activeBounded(base, listOf(base, nav), lg))
        assertTrue(WindowBoundsRules.bounded(lg, card, lg))
    }

    @Test fun ordinaryWindowsStayOrdinary() {
        // A whole-screen activity, also one whose root stops above the navigation bar.
        val app = WindowShape(5, WindowKind.APPLICATION, 20, true, lg, lg)
        assertEquals(ActiveWindowReject.NOT_BOUNDED, WindowBoundsRules.activeBounded(app, listOf(app), lg))
        val aboveNav = app.copy(root = ScreenBounds(0, 0, 1080, 2340))
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, WindowBoundsRules.activeBounded(aboveNav, listOf(aboveNav, nav), lg))
        // Split screen: half a screen, nothing wider of its own.
        val half = WindowShape(6, WindowKind.APPLICATION, 20, true, ScreenBounds(0, 0, 1080, 1200), ScreenBounds(0, 0, 1080, 1200))
        val other = WindowShape(7, WindowKind.APPLICATION, 20, false, ScreenBounds(0, 1260, 1080, 2460), ScreenBounds(0, 1260, 1080, 2460))
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, WindowBoundsRules.activeBounded(half, listOf(half, other), lg))
        // Not an application window at all.
        assertEquals(ActiveWindowReject.NOT_APPLICATION, WindowBoundsRules.activeBounded(status, listOf(status), lg))
    }

    @Test fun theDebugLineHasCodesOnly() {
        val dialog = WindowShape(16, WindowKind.APPLICATION, 21, true, lg, card)
        val line = WindowBoundsRules.line(listOf(nav, dialog, status), 16, lg, null, listOf(AppWindowReject.NOT_POPUP))
        assertEquals("WINDOWS app=1 [S?:wPrN S?:wPrN A*S:wFrP] active=BOUNDED above=NOT_POPUP", line)
        assertFalse(line.contains("popuptest"))
    }

    @Test fun theDialogMergedIntoOneTreeIsAModalCard() {
        // If the Dialog came inside the activity's tree: the page, then the centred card with its unnamed X.
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.widget.LinearLayout", bounds = lg),
            node("0/0/0", "android.widget.TextView", text = "시나리오 P04", bounds = ScreenBounds(0, 100, 1080, 200)),
            node("0/1", "android.widget.FrameLayout", bounds = card),
            node("0/1/0", "android.widget.LinearLayout", bounds = card),
            node("0/1/0/0", "android.widget.FrameLayout", bounds = ScreenBounds(90, 950, 990, 1076)),
            node("0/1/0/0/0", "android.widget.ImageButton", clickable = true, bounds = ScreenBounds(864, 950, 990, 1076)),
            node("0/1/0/1", "android.widget.TextView", text = "게임 아이템 특가", bounds = ScreenBounds(90, 1090, 990, 1170)),
            node("0/1/0/2", "android.widget.Button", text = "자세히 보기", clickable = true, bounds = ScreenBounds(90, 1400, 990, 1520)),
        )
        val root = requireNotNull(PopupRootFinder.find(nodes, lg))
        assertEquals(PopupRootKind.MODAL_CARD, root.kind)
        assertNotNull(CloseButtonFinder.scan(nodes, root).xButton)
    }

    @Test fun theTestAppIsInScopeAndOnlyOurOwnPackageIsLeftOut() {
        val all = UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL)
        assertTrue(SimpleAppAllowance.isInScope("kr.closeforyou.popuptest", "kr.closeforyou", all, emptySet()))
        assertNull(SimpleAppAllowance.scopeMiss("kr.closeforyou.popuptest", "kr.closeforyou", all, emptySet()))
        assertEquals(ScopeMiss.OWN_APP, SimpleAppAllowance.scopeMiss("kr.closeforyou", "kr.closeforyou", all, emptySet()))
    }
}
