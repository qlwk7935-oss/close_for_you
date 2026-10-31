package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
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

/** F22 (LG 11/1 06:57~07:03, `8dd26e4` app tests): app windows, the 11번가 app modal, remembering an X, video context. */
class F22RegressionTest {
    private val all = HandlingPreset.CLOSE_ALL.autoCloseKinds()

    // ---- A. app windows ----

    @Test fun aSmallWindowWithNothingToPressIsNotThePopup() {
        val tiny = listOf(
            node("0", "android.widget.FrameLayout", bounds = ScreenBounds(900, 2000, 1060, 2160)),
            node("0/0", "android.widget.ImageView", bounds = ScreenBounds(900, 2000, 1060, 2160)),
        )
        assertFalse(AppWindowRules.isPopup(tiny, SCREEN))
        val noButtons = listOf(
            node("0", "android.widget.FrameLayout", bounds = ScreenBounds(90, 700, 990, 1600)),
            node("0/0", text = "안내", bounds = ScreenBounds(90, 700, 990, 900)),
        )
        assertFalse(AppWindowRules.isPopup(noButtons, SCREEN))
        val dialog = listOf(
            node("0", "android.widget.FrameLayout", bounds = ScreenBounds(90, 700, 990, 1600)),
            node("0/0", text = "새 버전이 있어요", bounds = ScreenBounds(90, 700, 990, 900)),
            node("0/1", "android.widget.Button", text = "나중에", clickable = true, bounds = ScreenBounds(90, 1400, 990, 1600)),
        )
        assertTrue(AppWindowRules.isPopup(dialog, SCREEN))
    }

    /** 11번가 app "앱 전용 혜택 안내": a dim layer, a white box, an unnamed X top right, "혜택 받으러 가기"; a small strip below. */
    private fun elevenStreetAppModal() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0/0", text = "오늘의 특가", bounds = ScreenBounds(0, 200, 1080, 400)),
        node("0/0/1", text = "상품", clickable = true, bounds = ScreenBounds(0, 400, 1080, 900)),
        node("0/1", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/1/0", "android.widget.LinearLayout", bounds = ScreenBounds(80, 600, 1000, 1600)),
        node("0/1/0/0", "android.widget.ImageView", clickable = true, bounds = ScreenBounds(80, 700, 1000, 1350)),
        node("0/1/0/1", "android.widget.TextView", text = "앱 전용 혜택 안내", bounds = ScreenBounds(80, 610, 900, 690)),
        node("0/1/0/2", "android.widget.ImageButton", clickable = true, bounds = ScreenBounds(920, 610, 1000, 690)),
        node("0/1/0/3", "android.widget.Button", text = "혜택 받으러 가기", clickable = true, bounds = ScreenBounds(80, 1450, 1000, 1600)),
        node("0/2", bounds = ScreenBounds(0, 2200, 1080, 2340)),
        node("0/2/0", text = "앱에서 더 편하게", bounds = ScreenBounds(0, 2200, 980, 2340)),
        node("0/2/1", "android.widget.ImageButton", clickable = true, bounds = ScreenBounds(1000, 2240, 1060, 2300)),
    )

    @Test fun theElevenStreetAppModalIsFoundAndItsXIsOfferedNeverPressed() {
        val nodes = elevenStreetAppModal()
        val root = PopupRootFinder.find(nodes, SCREEN)
        assertNotNull(root)
        assertEquals(PopupRootKind.MODAL_CARD, root!!.kind)
        assertEquals("0/1/0", root.path)
        val popup = SimplePopupAnalysis.build(nodes, root, "com.elevenst", 7, SCREEN, 1L, 0L)
        assertTrue(popup.scan.candidates.isEmpty())
        assertEquals("node:0/1/0/2", popup.scan.xButton?.id)
        HandlingPreset.entries.forEach { preset ->
            val decision = SimpleCloseDecider.decide(PopupClassification.UNKNOWN, false, popup.scan.candidates, preset.autoCloseKinds(),
                popup.scan.forbiddenCount, unknownEnabled = true, riskCount = popup.scan.riskCount, strongEnabled = true)
            assertEquals(preset.name, SimpleDecision.Ask, decision)
        }
    }

    @Test fun theSmallStripBelowAndOrdinaryAppScreensAreNotModals() {
        val strip = elevenStreetAppModal().filterNot { it.path.startsWith("0/1") }
        assertNull(PopupRootFinder.find(strip, SCREEN))
        // A centred banner card with a heart icon in its corner, in the page itself (not over a layer).
        val page = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "androidx.recyclerview.widget.RecyclerView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/0/0", "android.widget.LinearLayout", bounds = ScreenBounds(80, 600, 1000, 1600)),
            node("0/0/0/0", text = "추천 상품", bounds = ScreenBounds(80, 610, 900, 690)),
            node("0/0/0/1", "android.widget.ImageButton", clickable = true, bounds = ScreenBounds(920, 610, 1000, 690)),
        )
        assertNull(PopupRootFinder.find(page, SCREEN))
    }

    // ---- B. remembering an X ----

    private class MapStore : RememberedPopupStore {
        val rules = LinkedHashMap<String, RememberedPopup>()
        override fun get(key: String) = rules[key]
        override fun put(rule: RememberedPopup) { rules[rule.key] = rule }
        override fun all() = rules.values.toList()
        override fun remove(key: String) { rules.remove(key) }
        override fun clear() = rules.clear()
    }

    private fun modalPopup(nodes: List<NodeSnapshot> = elevenStreetAppModal()) =
        SimplePopupAnalysis.build(nodes, PopupRootFinder.find(nodes, SCREEN)!!, "com.elevenst", 7, SCREEN, 1L, 0L)

    @Test fun aRememberedXIsFoundOnlyForTheSamePopupInTheSamePlace() {
        val store = MapStore()
        val first = modalPopup()
        assertNull(RememberedPopupRules.match(store, "com.elevenst", first))
        store.put(RememberedPopupRules.ruleFor("com.elevenst", first, first.scan.xButton!!)!!)
        // Shown again later (another screen version): the same X.
        val again = SimplePopupAnalysis.build(elevenStreetAppModal(), PopupRootFinder.find(elevenStreetAppModal(), SCREEN)!!, "com.elevenst", 9, SCREEN, 5L, 9_000L)
        assertEquals("node:0/1/0/2", RememberedPopupRules.match(store, "com.elevenst", again)?.id)
        // Another app, other words, or the box moved: not the same popup.
        assertNull(RememberedPopupRules.match(store, "com.other", again))
        val otherWords = elevenStreetAppModal().map { if (it.path == "0/1/0/1") it.copy(text = "다른 안내") else it }
        assertNull(RememberedPopupRules.match(store, "com.elevenst", modalPopup(otherWords)))
        val moved = elevenStreetAppModal().map { if (it.path.startsWith("0/1/0")) it.copy(bounds = it.bounds.copy(top = it.bounds.top + 50, bottom = it.bounds.bottom + 50)) else it }
        assertNull(RememberedPopupRules.match(store, "com.elevenst", modalPopup(moved)))
    }

    @Test fun aRuleKeepsNoTextAndSurvivesEncoding() {
        val popup = modalPopup()
        val rule = RememberedPopupRules.ruleFor("com.elevenst", popup, popup.scan.xButton!!)!!
        assertFalse(rule.key.contains("elevenst"))
        assertFalse(rule.encode().contains("혜택"))
        assertEquals(rule, RememberedPopup.decode(rule.key, rule.encode()))
    }

    // ---- C. video only in YouTube ----

    @Test fun aShoppingBannersAdMarkIsNotAVideoContext() {
        val shop = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", text = "AD", bounds = ScreenBounds(20, 300, 80, 340)),
            node("0/1", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(780, 2100, 1060, 2250)),
        )
        assertFalse(VideoAdSkip.isVideoContext("com.ebay.kr.auction", shop))
        assertTrue(VideoAdSkip.isVideoContext(VideoAdSkip.YOUTUBE_APP, shop))
        val chrome = listOf(node("0/0", "android.widget.EditText", text = "m.youtube.com/watch", viewId = "com.android.chrome:id/url_bar"))
        assertTrue(VideoAdSkip.isVideoContext("com.android.chrome", chrome))
        val otherSite = listOf(node("0/0", "android.widget.EditText", text = "m.11st.co.kr", viewId = "com.android.chrome:id/url_bar"))
        assertFalse(VideoAdSkip.isVideoContext("com.android.chrome", otherSite))
    }

    // ---- why an app is skipped ----

    @Test fun scopeMissReasons() {
        val allApps = UiVoiceSettings(helpAppsMode = HelpAppsMode.ALL)
        assertNull(SimpleAppAllowance.scopeMiss("com.ebay.kr.auction", "kr.closeforyou", allApps, emptySet()))
        assertNull(SimpleAppAllowance.scopeMiss("com.elevenst", "kr.closeforyou", allApps, emptySet()))
        assertEquals(ScopeMiss.OWN_APP, SimpleAppAllowance.scopeMiss("kr.closeforyou", "kr.closeforyou", allApps, emptySet()))
        assertEquals(ScopeMiss.SENSITIVE, SimpleAppAllowance.scopeMiss("com.kbstar.kbbank", "kr.closeforyou", allApps, emptySet()))
        assertEquals(ScopeMiss.SYSTEM, SimpleAppAllowance.scopeMiss("com.android.systemui", "kr.closeforyou", allApps, emptySet()))
        assertEquals(ScopeMiss.NO_MODE_NOT_ALLOWED, SimpleAppAllowance.scopeMiss("com.elevenst", "kr.closeforyou", UiVoiceSettings(), emptySet()))
        assertEquals(ScopeMiss.NOT_CHOSEN, SimpleAppAllowance.scopeMiss("com.elevenst", "kr.closeforyou", UiVoiceSettings(helpAppsMode = HelpAppsMode.CHOSEN), setOf("com.android.chrome")))
        assertEquals(ScopeMiss.INPUT_METHOD, SimpleAppAllowance.scopeMiss("com.samsung.keyboard", "kr.closeforyou", allApps, emptySet(), setOf("com.samsung.keyboard")))
        // Same answer as isInScope, whatever the case.
        listOf("com.ebay.kr.auction", "kr.closeforyou", "com.kbstar.kbbank", "com.android.systemui").forEach { pkg ->
            assertEquals(pkg, SimpleAppAllowance.isInScope(pkg, "kr.closeforyou", allApps, emptySet()), SimpleAppAllowance.scopeMiss(pkg, "kr.closeforyou", allApps, emptySet()) == null)
        }
        assertTrue(all.isNotEmpty())
    }
}
