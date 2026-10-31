package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F16 강력 모드: the popup types of the test app as JVM trees (structure only, synthetic words), what the
 * finder makes of each, and what "전부 닫기" + "자동 닫기" does with it. Plus the page shapes that must
 * never be taken for a popup.
 */
class StrongCloseTest {
    private val closeAll = HandlingPreset.CLOSE_ALL.autoCloseKinds()

    private fun page(vararg extra: NodeSnapshot): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0/0", text = "오늘의 추천 상품", bounds = ScreenBounds(0, 200, 1080, 400)),
        node("0/0/0/1", text = "상품 하나", clickable = true, bounds = ScreenBounds(0, 400, 1080, 900)),
        node("0/0/0/2", "android.widget.Button", text = "바로 구매", clickable = true, bounds = ScreenBounds(0, 900, 1080, 1000)),
    ) + extra

    /** A full-width sheet over the page: "앱에서 열기" or "모바일 웹으로 볼래요" (11번가/쿠팡 style, no Dialog mark). */
    private fun appOpenSheet() = page(
        node("0/0/1", bounds = ScreenBounds(0, 1500, 1080, 2340)),
        node("0/0/1/0", text = "앱에서 더 많은 혜택을 받아 보세요", bounds = ScreenBounds(40, 1550, 1040, 1700)),
        node("0/0/1/1", "android.widget.Button", text = "앱에서 열기", clickable = true, bounds = ScreenBounds(40, 1900, 1040, 2050)),
        node("0/0/1/2", "android.widget.Button", text = "모바일 웹으로 볼래요", clickable = true, bounds = ScreenBounds(40, 2100, 1040, 2250)),
    )

    /** A centred box drawn over the page, no Dialog mark, no dim node. */
    private fun unmarkedLayer() = page(
        node("0/0/1", bounds = ScreenBounds(90, 600, 990, 1700)),
        node("0/0/1/0", text = "가을 맞이 특별 혜택", bounds = ScreenBounds(90, 600, 990, 800)),
        node("0/0/1/1", "android.widget.Image", desc = "혜택 안내 그림", bounds = ScreenBounds(90, 800, 990, 1500)),
        node("0/0/1/2", text = "오늘 하루 보지 않기", clickable = true, bounds = ScreenBounds(90, 1550, 540, 1700)),
        node("0/0/1/3", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1550, 990, 1700)),
    )

    /** A native Dialog with only an unnamed X. */
    private fun unnamedXDialog() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.app.Dialog", bounds = ScreenBounds(90, 700, 990, 1600)),
        node("0/0/0", text = "새 기능을 소개해요", bounds = ScreenBounds(90, 780, 990, 900)),
        node("0/0/1", "android.widget.ImageView", clickable = true, bounds = ScreenBounds(900, 700, 990, 790)),
    )

    /** A risky button beside a plain close. */
    private fun riskyMix() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(90, 700, 990, 1600)),
        node("0/0/0", text = "프리미엄 첫 달 무료", bounds = ScreenBounds(90, 780, 990, 900)),
        node("0/0/1", "android.widget.Button", text = "지금 구독하기", clickable = true, bounds = ScreenBounds(90, 1400, 540, 1600)),
        node("0/0/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1400, 990, 1600)),
    )

    private fun analyse(nodes: List<NodeSnapshot>, bounded: Boolean = false): SimplePopup {
        val root = PopupRootFinder.find(nodes, SCREEN, bounded)
        assertNotNull(root)
        return SimplePopupAnalysis.build(nodes, root!!, "com.example", 7, SCREEN, 1L, 0L)
    }

    private fun strong(popup: SimplePopup, kind: PopupClassification = PopupClassification.UNKNOWN, preset: HandlingPreset = HandlingPreset.CLOSE_ALL) =
        SimpleCloseDecider.decide(kind, false, popup.scan.candidates, preset.autoCloseKinds(), popup.scan.forbiddenCount,
            unknownEnabled = true, riskCount = popup.scan.riskCount, strongEnabled = true)

    // ---- Each type: found, and what 강력 모드 does ----

    @Test fun dimOverlayClosesWithThePlainCloseNotTheBackground() {
        val popup = analyse(SimpleNodes.oliveYoung())
        assertEquals(PopupRootKind.DIM_OVERLAY, popup.root.kind)
        val decision = strong(popup, PopupClassification.COUPON) as SimpleDecision.AutoClose
        assertEquals("node:0/1/1/3", decision.candidate.id) // "닫기", before "배경을 눌러 닫기"; "쿠폰 받기" never
    }

    @Test fun appOpenSheetIsABottomSheetClosedByStayingOnTheWeb() {
        val popup = analyse(appOpenSheet())
        assertEquals(PopupRootKind.BOTTOM_SHEET, popup.root.kind)
        assertEquals("0/0/1", popup.root.path)
        assertTrue(popup.scan.candidates.none { it.label == "앱에서 열기" })
        assertEquals(0, popup.scan.riskCount)
        val decision = strong(popup) as SimpleDecision.AutoClose
        assertEquals("모바일 웹으로 볼래요", decision.candidate.label)
        assertTrue(decision.unknown)
    }

    @Test fun unmarkedLayerIsAFrontLayerClosedByItsPlainClose() {
        val popup = analyse(unmarkedLayer())
        assertEquals(PopupRootKind.FRONT_LAYER, popup.root.kind)
        assertEquals(2, popup.scan.closeCandidates.size)
        assertEquals("닫기", (strong(popup, PopupClassification.ADVERTISEMENT) as SimpleDecision.AutoClose).candidate.label)
    }

    @Test fun nativeDialogWindowIsFoundAsTheBoundedWindow() {
        val window = listOf(
            node("0", "android.widget.FrameLayout", bounds = ScreenBounds(90, 700, 990, 1600)),
            node("0/0", text = "업데이트가 있어요", bounds = ScreenBounds(90, 780, 990, 900)),
            node("0/1", "android.widget.Button", text = "나중에", clickable = true, bounds = ScreenBounds(90, 1400, 990, 1600)),
        )
        val popup = analyse(window, bounded = true)
        assertEquals(PopupRootKind.DIALOG_WINDOW, popup.root.kind)
        assertEquals("나중에", (strong(popup, PopupClassification.NOTICE) as SimpleDecision.AutoClose).candidate.label)
    }

    @Test fun anUnnamedXIsNeverAutomaticButIsOfferedToThePerson() {
        val popup = analyse(unnamedXDialog())
        assertTrue(popup.scan.candidates.isEmpty())
        assertEquals("node:0/0/1", popup.scan.xButton?.id)
        assertNull(popup.scan.xButton?.label)
        HandlingPreset.entries.forEach { assertEquals(it.name, SimpleDecision.Ask, strong(popup, PopupClassification.ADVERTISEMENT, it)) }
    }

    @Test fun aSymbolOnlyXIsAnXButtonToo() {
        val nodes = unnamedXDialog().map { if (it.path == "0/0/1") it.copy(text = "×") else it }
        val popup = analyse(nodes)
        assertTrue(popup.scan.candidates.isEmpty())
        assertEquals("×", popup.scan.xButton?.label)
    }

    @Test fun twoXShapesAreAGuessSoNoneIsOffered() {
        val nodes = unnamedXDialog() + node("0/0/2", "android.widget.ImageView", clickable = true, bounds = ScreenBounds(90, 700, 180, 790))
        assertNull(analyse(nodes).scan.xButton)
    }

    @Test fun aRiskyButtonBesideTheCloseKeepsItAskedInEveryPreset() {
        val popup = analyse(riskyMix())
        assertEquals(1, popup.scan.riskCount)
        HandlingPreset.entries.forEach { preset ->
            PopupClassification.entries.filter { it != PopupClassification.USER_DECISION }.forEach { kind ->
                assertEquals("$preset $kind", SimpleDecision.Ask, strong(popup, kind, preset))
            }
        }
    }

    @Test fun decisionAndScamStayDeniedInStrongMode() {
        val popup = analyse(unmarkedLayer())
        assertEquals(SimpleDecision.DenyDecision, strong(popup, PopupClassification.USER_DECISION))
        assertEquals(SimpleDecision.DenySuspicious, SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, true, popup.scan.candidates, closeAll,
            riskCount = 0, strongEnabled = true))
    }

    @Test fun otherPresetsAndTheSwitchOffKeepTheirOldRules() {
        val popup = analyse(appOpenSheet())
        // "광고만 닫기": an unknown sheet is asked; two closes in an ad are asked (no picking outside 강력 모드).
        assertEquals(SimpleDecision.Ask, strong(popup, PopupClassification.UNKNOWN, HandlingPreset.CLOSE_ADS_ONLY))
        val twoCloses = analyse(unmarkedLayer())
        assertEquals(SimpleDecision.Ask, strong(twoCloses, PopupClassification.ADVERTISEMENT, HandlingPreset.CLOSE_ADS_ONLY))
        // No live consent (switch off): nothing is closed.
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, twoCloses.scan.candidates,
            kotlinx.collections.immutable.persistentSetOf(), strongEnabled = true))
    }

    // ---- Never a popup ----

    @Test fun ordinaryPageShapesAreNotFrontLayers() {
        // Stacked sections (none under another), a sticky bottom bar and a floating button.
        val stacked = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/0/0", bounds = ScreenBounds(0, 200, 1080, 1000)),
            node("0/0/0/0", text = "오늘의 추천 상품", bounds = ScreenBounds(0, 200, 1080, 400)),
            node("0/0/1", bounds = ScreenBounds(0, 1000, 1080, 1800)),
            node("0/0/1/0", text = "닫기", clickable = true, bounds = ScreenBounds(900, 1000, 1080, 1100)),
            node("0/0/2", bounds = ScreenBounds(0, 2200, 1080, 2340)),
            node("0/0/2/0", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(900, 2200, 1080, 2340)),
            node("0/0/3", bounds = ScreenBounds(940, 2000, 1060, 2120)),
            node("0/0/3/0", text = "닫기", clickable = true, bounds = ScreenBounds(940, 2000, 1060, 2120)),
        )
        assertNull(PopupRootFinder.find(stacked, SCREEN))
    }

    @Test fun aSideMenuOrATopDropdownIsNotAPopup() {
        val drawer = page(
            node("0/0/1", bounds = ScreenBounds(0, 200, 800, 2340)),
            node("0/0/1/0", text = "메뉴", bounds = ScreenBounds(0, 200, 800, 300)),
            node("0/0/1/1", "android.widget.Button", text = "메뉴 닫기", clickable = true, bounds = ScreenBounds(700, 200, 800, 300)),
        )
        assertNull(PopupRootFinder.find(drawer, SCREEN))
        val dropdown = page(
            node("0/0/1", bounds = ScreenBounds(0, 200, 1080, 1100)),
            node("0/0/1/0", text = "카테고리", bounds = ScreenBounds(0, 200, 1080, 300)),
            node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(900, 200, 1080, 300)),
        )
        assertNull(PopupRootFinder.find(dropdown, SCREEN))
        val links = listOf(node("0/0/1", bounds = ScreenBounds(90, 600, 990, 1900))) +
            (0 until 12).map { node("0/0/1/$it", text = "링크 $it", clickable = true, bounds = ScreenBounds(90, 600 + it * 100, 990, 700 + it * 100)) } +
            node("0/0/1/12", text = "닫기", clickable = true, bounds = ScreenBounds(90, 1800, 990, 1900))
        val manyLinks = page(*links.toTypedArray())
        assertNull(PopupRootFinder.find(manyLinks, SCREEN))
    }

    @Test fun aCookieStripIsNotAPopup() {
        val cookie = page(
            node("0/0/1", bounds = ScreenBounds(0, 1900, 1080, 2340)),
            node("0/0/1/0", text = "이 사이트는 쿠키를 사용합니다", bounds = ScreenBounds(40, 1950, 1040, 2100)),
            node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(40, 2150, 1040, 2300)),
        )
        assertNull(PopupRootFinder.find(cookie, SCREEN))
    }

    @Test fun aLayerWithOnlyAnUnnamedXIsNotAFrontLayerButAModalCardThatIsOnlyAsked() {
        val nodes = page(
            node("0/0/1", bounds = ScreenBounds(90, 600, 990, 1700)),
            node("0/0/1/0", text = "가을 맞이 특별 혜택", bounds = ScreenBounds(90, 600, 990, 800)),
            node("0/0/1/1", "android.widget.ImageView", clickable = true, bounds = ScreenBounds(900, 600, 990, 690)),
        )
        // F22: a centred box over the page with an unnamed corner X is a modal card; its X is only offered (6-3).
        val popup = analyse(nodes)
        assertEquals(PopupRootKind.MODAL_CARD, popup.root.kind)
        assertTrue(popup.scan.candidates.isEmpty())
        assertEquals("node:0/0/1/1", popup.scan.xButton?.id)
        assertEquals(SimpleDecision.Ask, strong(popup))
    }

    @Test fun frontLayersCanBeSwitchedOff() {
        assertNull(PopupRootFinder.find(unmarkedLayer(), SCREEN, frontLayers = false))
    }

    // ---- The F15 probe finds layers too, and the subtree decides the same ----

    private class Tree(nodes: List<NodeSnapshot>) : ProbeTree<NodeSnapshot> {
        private val byPath = nodes.associateBy { it.path }
        override fun info(node: NodeSnapshot) = ProbeNodeInfo(node.className, node.bounds, node.clickable, !node.text.isNullOrBlank())
        override fun childCount(node: NodeSnapshot) = generateSequence(0) { it + 1 }.takeWhile { byPath.containsKey("${node.path}/$it") }.count()
        override fun child(node: NodeSnapshot, index: Int) = byPath["${node.path}/$index"]
        override fun release(node: NodeSnapshot) = Unit
    }

    @Test fun theProbeFindsALayerWithThePageNodeUnderItAndTheSubtreeDecidesTheSame() {
        for (full in listOf(appOpenSheet(), unmarkedLayer())) {
            val probe = PopupProbe.find(full.first { it.path == "0" }, Tree(full), SCREEN, 200, 64)
            assertEquals(ProbeHitKind.LAYER, probe.kind)
            assertEquals("0/0/1", probe.path)
            assertEquals("0/0/0", probe.context)
            // What the collector reads: the context node alone, then the layer's subtree.
            val part = listOfNotNull(full.firstOrNull { it.path == probe.context }) + full.filter { it.path == probe.path || it.path.startsWith("${probe.path}/") }
            val fullRoot = PopupRootFinder.find(full, SCREEN)!!
            assertEquals(fullRoot, PopupRootFinder.find(part, SCREEN))
            val a = SimplePopupAnalysis.build(full, fullRoot, "p", 7, SCREEN, 1L, 0L)
            val b = SimplePopupAnalysis.build(part, fullRoot, "p", 7, SCREEN, 1L, 0L)
            assertEquals(a.analysis, b.analysis)
            assertEquals(a.scan, b.scan)
        }
    }

    @Test fun theProbeStillTakesTheDimParentForADimWithItsCard() {
        val probe = PopupProbe.find(SimpleNodes.oliveYoung().first { it.path == "0" }, Tree(SimpleNodes.oliveYoung()), SCREEN, 200, 64)
        assertEquals(ProbeHitKind.DIM_PARENT, probe.kind)
        assertEquals("0/1", probe.path)
    }

    // ---- Labels ----

    @Test fun politeDismissalsCloseOnlyAsTheWholeLabel() {
        listOf(
            "오늘 하루 보지 않기", "오늘 그만 보기", "다시 보지 않기", "7일간 보지 않기", "3일 동안 보지 않기", "괜찮아요",
            "다음에", "나중에", "모바일 웹으로 볼래요", "웹으로 계속", "그냥 볼게요", "아니요, 괜찮습니다", "Close", "No thanks", "Not now",
        ).forEach { assertEquals(it, ButtonRole.CLOSE, SimpleLabelRules.roleOf(it)) }
        assertEquals(ButtonRole.SKIP, SimpleLabelRules.roleOf("Skip"))
        listOf("확인", "OK", "앱에서 열기", "앱 설치", "쿠폰 받기", "오늘 하루 무료 체험", "다음에 결제하기", "×", "X").forEach {
            assertNull(it, SimpleLabelRules.roleOf(it))
        }
    }

    @Test fun riskyAndXMarkLabels() {
        listOf("구매하기", "지금 구독하기", "주문하기", "동의", "허용", "결제", "Buy now", "Place order", "회원가입").forEach { assertTrue(it, SimpleLabelRules.isDecision(it)) }
        // F23: buy/order alone do not keep a popup asked (answer key L04~L06); payment, consent, subscribe, permission… do.
        listOf("지금 구독하기", "동의", "허용", "결제", "회원가입", "로그인", "Pay now", "Allow").forEach { assertTrue(it, SimpleLabelRules.isRisky(it)) }
        listOf("구매하기", "주문하기", "Buy now", "Place order").forEach { assertFalse(it, SimpleLabelRules.isRisky(it)) }
        listOf("앱에서 열기", "쿠폰 받기", "닫기", "오늘 하루 보지 않기", "자세히 보기", null).forEach { assertFalse("$it", SimpleLabelRules.isRisky(it)) }
        listOf("×", "X", "✕", "[x]").forEach { assertTrue(it, SimpleLabelRules.isXMark(it)) }
        assertFalse(SimpleLabelRules.isXMark("닫기"))
    }

    @Test fun clarityPutsThePlainCloseFirst() {
        val ordered = listOf("닫기", "광고 레이어 닫기", "오늘 하루 보지 않기", "괜찮아요").map(SimpleLabelRules::clarity)
        assertEquals(ordered.sorted(), ordered)
        assertTrue(ordered.first() < ordered.last())
    }
}
