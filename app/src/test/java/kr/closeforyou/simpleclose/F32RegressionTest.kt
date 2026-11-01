package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.HelpAppsMode
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import kr.closeforyou.ui.voice.settings.autoCloseKinds
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
        // F32 still refused it when the window came with its own size (nothing wider listed); F33 takes a floating window.
        assertNull(WindowBoundsRules.activeBounded(dialog.copy(window = card), listOf(status, dialog.copy(window = card), nav), lg))
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

    @Test fun f33TheDialogWithItsOwnSizeAndNothingElseOfTheApp() {
        // LG fbc54ae: `WINDOWS app=1 [S?:wPrN A*S:wPrP] active=NO_WIDER_SAME_APP_WINDOW` — a system bar, then the Dialog
        // (window and root both the card), the activity not listed.
        val dialog = WindowShape(16, WindowKind.APPLICATION, 21, true, card, card)
        val shapes = listOf(status, dialog)
        assertEquals("WINDOWS app=1 [S?:wPrN A*S:wPrP] active=BOUNDED above=-",
            WindowBoundsRules.line(shapes, 16, lg, WindowBoundsRules.activeBounded(dialog, shapes, lg), emptyList()))
        assertNull(WindowBoundsRules.activeBounded(dialog, shapes, lg))
        // With the surface insets in the window's bounds, too.
        assertNull(WindowBoundsRules.activeBounded(dialog.copy(window = withInsets), listOf(status, dialog.copy(window = withInsets)), lg))
    }

    @Test fun f33NotAFloatingDialog() {
        fun alone(b: ScreenBounds) = WindowShape(9, WindowKind.APPLICATION, 20, true, b, b).let { WindowBoundsRules.activeBounded(it, listOf(it), lg) }
        // Split screen halves (top / bottom, left / right in landscape terms).
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, alone(ScreenBounds(0, 0, 1080, 1200)))
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, alone(ScreenBounds(0, 1260, 1080, 2460)))
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, alone(ScreenBounds(0, 0, 540, 2460)))
        // Picture in picture: small, in a corner.
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, alone(ScreenBounds(600, 1900, 1040, 2150)))
        // A small banner window at the top, a bottom sheet window.
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, alone(ScreenBounds(0, 80, 1080, 300)))
        assertEquals(ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW, alone(ScreenBounds(0, 1600, 1080, 2460)))
        // A keyboard is not an application window.
        val ime = WindowShape(3, WindowKind.INPUT_METHOD, 25, null, ScreenBounds(0, 1500, 1080, 2340), null)
        assertEquals(ActiveWindowReject.NOT_APPLICATION, WindowBoundsRules.activeBounded(ime, listOf(ime), lg))
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

/**
 * F32 (d)(e) (LG 11/1 16:33, 11번가 app `AdsOneDayPopupActivity`: a transparent activity dimmed by its window flag,
 * NO_POPUP_ROOT ×24): a screen-sized window showing one centred card is a popup ([PopupRootKind.DIM_WINDOW]).
 */
class F32DimWindowTest {
    private val lg = ScreenBounds(0, 0, 1080, 2460)

    /** The 11번가 popup activity: decor and content fill the screen, the card in the middle, the round X under it. */
    private fun elevenStreetApp(xName: String? = null) = listOf(
        node("0", "android.widget.FrameLayout", bounds = lg),
        node("0/0", "android.widget.FrameLayout", bounds = lg),
        node("0/0/0", "android.view.ViewGroup", bounds = ScreenBounds(90, 640, 990, 1700)),
        node("0/0/0/0", "android.widget.ImageView", desc = "그랜드십일절", bounds = ScreenBounds(90, 640, 990, 1100)),
        node("0/0/0/1", "android.widget.TextView", text = "누구나 쿠폰 받고 혜택을 누리세요", bounds = ScreenBounds(130, 1120, 950, 1260)),
        node("0/0/0/2", "android.widget.TextView", text = "로그인하러 가기 >", clickable = true, bounds = ScreenBounds(130, 1500, 950, 1640)),
        node("0/0/1", "android.widget.ImageView", desc = xName, clickable = true, bounds = ScreenBounds(480, 1760, 600, 1880)),
    )

    @Test fun elevenStreetsPopupActivityIsFoundTheLoginIsNeverPressed() {
        val nodes = elevenStreetApp()
        val root = requireNotNull(PopupRootFinder.find(nodes, lg))
        assertEquals(PopupRootKind.DIM_WINDOW, root.kind)
        val popup = SimplePopupAnalysis.build(nodes, root, "com.elevenst", 1, lg, 1, 0)
        assertNotNull(popup.scan.xButton)
        assertTrue(popup.scan.candidates.isEmpty())
        assertTrue(popup.scan.riskButtons.any { it.label == "로그인하러 가기 >" })
        // B calls it a decision ("로그인"): asked with [버튼으로 이동] only; the X is a move target, never pressed by us.
        val decided = SimpleCloseDecider.decide(PopupClassification.USER_DECISION, false, popup.scan.candidates,
            HandlingPreset.CLOSE_ALL.autoCloseKinds(), popup.scan.forbiddenCount, riskCount = popup.scan.riskCount)
        assertEquals(SimpleDecision.DenyDecision, decided)
        // A named X ("닫기") is the press; with the 로그인 button beside it 강력 모드 still asks.
        val named = elevenStreetApp("닫기")
        val p2 = SimplePopupAnalysis.build(named, requireNotNull(PopupRootFinder.find(named, lg)), "com.elevenst", 1, lg, 1, 0)
        assertEquals(listOf("닫기"), p2.scan.candidates.map { it.label })
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.COUPON, false, p2.scan.candidates,
            HandlingPreset.CLOSE_ALL.autoCloseKinds(), p2.scan.forbiddenCount, riskCount = p2.scan.riskCount))
    }

    @Test fun p04AsATransparentFullScreenDecorToo() {
        val card = ScreenBounds(42, 911, 1038, 1548)
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.widget.LinearLayout", bounds = card),
            node("0/0/0", "android.widget.FrameLayout", bounds = ScreenBounds(90, 950, 990, 1076)),
            node("0/0/0/0", "android.widget.ImageButton", clickable = true, bounds = ScreenBounds(864, 950, 990, 1076)),
            node("0/0/1", "android.widget.TextView", text = "게임 아이템 특가", bounds = ScreenBounds(90, 1090, 990, 1170)),
            node("0/0/2", "android.widget.Button", text = "자세히 보기", clickable = true, bounds = ScreenBounds(90, 1400, 990, 1520)),
        )
        val root = requireNotNull(PopupRootFinder.find(nodes, lg))
        assertEquals(PopupRootKind.DIM_WINDOW, root.kind)
        assertNotNull(CloseButtonFinder.scan(nodes, root).xButton)
    }

    @Test fun ordinaryScreensAreNotACard() {
        // A splash: a logo in the middle, nothing to press.
        val splash = listOf(node("0", "android.widget.FrameLayout", bounds = lg), node("0/0", "android.widget.ImageView", bounds = ScreenBounds(340, 1030, 740, 1430)))
        assertNull(PopupRootFinder.find(splash, lg))
        // An app screen: toolbar, list, tab bar: the content fills the screen.
        val app = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.widget.ImageButton", desc = "메뉴", clickable = true, bounds = ScreenBounds(0, 80, 140, 220)),
            node("0/1", "android.widget.TextView", text = "오늘의 특가", bounds = ScreenBounds(0, 240, 1080, 2200)),
            node("0/2", "android.widget.Button", text = "홈", clickable = true, bounds = ScreenBounds(0, 2240, 216, 2340)),
        )
        assertNull(PopupRootFinder.find(app, lg))
        // A centred login form: an input box, not a popup.
        val form = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.widget.EditText", bounds = ScreenBounds(90, 1000, 990, 1120)),
            node("0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(90, 1200, 990, 1320)),
        )
        assertNull(PopupRootFinder.find(form, lg))
        // Without a close or an X, only the activity's own name makes it a popup.
        val noClose = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.view.ViewGroup", bounds = ScreenBounds(90, 640, 990, 1700)),
            node("0/0/0", "android.widget.TextView", text = "누구나 쿠폰 받고", bounds = ScreenBounds(130, 1120, 950, 1260)),
            node("0/0/1", "android.widget.TextView", text = "로그인하러 가기 >", clickable = true, bounds = ScreenBounds(130, 1500, 950, 1640)),
        )
        assertNull(PopupRootFinder.find(noClose, lg))
        assertEquals(PopupRootKind.DIM_WINDOW, PopupRootFinder.find(noClose, lg, popupActivityHint = true)?.kind)
    }

    @Test fun activityNamesLikeAPopup() {
        assertTrue(PopupActivityName.looksLikePopup("com.elevenst.ads.AdsOneDayPopupActivity"))
        assertTrue(PopupActivityName.looksLikePopup("x.y.EventDialogActivity"))
        assertTrue(PopupActivityName.looksLikePopup("x.y.AdActivity"))
        for (name in listOf("com.elevenst.intro.MainActivity", "x.y.LoadingActivity", "x.y.AddressActivity", "x.y.ReadActivity", "android.app.Dialog", null)) {
            assertFalse(name.toString(), PopupActivityName.looksLikePopup(name))
        }
    }
}
