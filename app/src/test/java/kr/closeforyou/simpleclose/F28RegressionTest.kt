package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionDemoRules
import kr.closeforyou.decision.DecisionKind
import kr.closeforyou.decision.DecisionKinds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F28: PR #69 wired into the demo engine. 1) a promotion's purchase button stops keeping it asked only on B's four
 * conditions, 2) sensitive screens and password fields are left alone, 3) video skips outside YouTube need an ad mark
 * beside them and B's check, 4) a prompt closes when its app leaves the screen.
 */
class F28RegressionTest {
    private val closeAll = HandlingPreset.CLOSE_ALL.autoCloseKinds()

    private fun popup(vararg body: String, buttons: List<String>): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", text = "페이지 본문", bounds = ScreenBounds(0, 200, 1080, 600)),
        node("0/0/1", "android.app.AlertDialog", bounds = ScreenBounds(80, 700, 1000, 2000)),
    ) + body.mapIndexed { i, t -> node("0/0/1/$i", text = t, bounds = ScreenBounds(120, 800 + i * 90, 960, 880 + i * 90)) } +
        buttons.mapIndexed { i, b -> node("0/0/1/${body.size + i}", "android.widget.Button", text = b, clickable = true, bounds = ScreenBounds(120, 1500 + i * 110, 960, 1600 + i * 110)) }

    /** The service's path: B's kind, then the gate, then the decider, with today's switches. */
    private fun decide(nodes: List<NodeSnapshot>, hinted: Boolean = false): SimpleDecision {
        val root = requireNotNull(PopupRootFinder.find(nodes, SCREEN))
        val p = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, SCREEN, 1, 0)
        val kind = DecisionKinds.of(p.analysis)
        val byB = when (kind) {
            DecisionKind.USER_DECISION -> PopupClassification.USER_DECISION
            DecisionKind.ADVERTISEMENT -> PopupClassification.ADVERTISEMENT
            DecisionKind.COUPON -> PopupClassification.COUPON
            DecisionKind.NOTICE -> PopupClassification.NOTICE
            else -> PopupClassification.UNKNOWN
        }
        val classification = if (hinted) PopupClassification.ADVERTISEMENT else byB
        val promoBuy = PromoPurchaseGate.allowed(DecisionDemoRules.PROMO_PURCHASE, byB, hinted, p.analysis.texts, p.scan.promoBuyCount)
        return SimpleCloseDecider.decide(classification, kind == DecisionKind.SUSPICIOUS, p.scan.candidates, closeAll, p.scan.forbiddenCount,
            riskCount = p.scan.riskCount, promoBuyCount = promoBuy)
    }

    private fun assertClosesWith(label: String, d: SimpleDecision) {
        assertTrue(d.toString(), d is SimpleDecision.AutoClose && d.candidate.label == label)
    }

    @Test fun theSwitchesAreAsDecided() {
        assertTrue(DecisionDemoRules.PROMO_PURCHASE)
        // 재호 정책 "묻기 유지": the purchase button is still a reason to ask, unless B's four conditions hold.
        assertTrue(SimpleCloseEngineFeature.ASK_WHEN_PURCHASE_BUTTON)
    }

    @Test fun answerKeyPromotionsCloseByTheirCloseOnly() {
        // L04 11번가 / L05 G마켓 / L06 옥션 / L13 인터파크 shapes: a sale, a purchase call to action, a close.
        assertClosesWith("닫기", decide(popup("십일절 최대 70% 할인", "오늘만 특가", buttons = listOf("닫기", "바로 구매하기", "오늘 하루 보지 않기"))))
        assertClosesWith("닫기", decide(popup("빅스마일데이 특가", "최대 50% 할인 쿠폰", buttons = listOf("오늘 하루 보지 않기", "지금 구매하기", "닫기"))))
        assertClosesWith("닫기", decide(popup("올킬 특가 30% 할인", buttons = listOf("구매하기", "닫기"))))
        assertClosesWith("닫기", decide(popup("티켓 오픈 기념 할인 쿠폰", "선착순 특가", buttons = listOf("닫기", "지금 구매하기"))))
    }

    @Test fun neverThePurchaseButtonItself() {
        val nodes = popup("주말 한정 최대 50% 할인", buttons = listOf("지금 구매하기"))
        // No close at all: nothing to press, so asked (the purchase is never a candidate).
        assertEquals(SimpleDecision.Ask, decide(nodes))
        val root = requireNotNull(PopupRootFinder.find(nodes, SCREEN))
        val scan = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, SCREEN, 1, 0).scan
        assertTrue(scan.candidates.isEmpty())
        assertEquals(1, scan.promoBuyCount)
    }

    @Test fun aCheckoutTraceKeepsItAskedOrDenied() {
        for (trace in listOf("총 결제 금액 41,000원", "이 상품을 구매하시겠습니까?", "정기 구독 혜택", "수량 1개", "배송지를 확인해 주세요", "카드 등록 시 추가 할인")) {
            val d = decide(popup("50% 할인 특가", trace, buttons = listOf("닫기", "구매하기")))
            assertFalse("$trace: $d", d is SimpleDecision.AutoClose)
        }
    }

    @Test fun anyOtherRiskyButtonStillAsks() {
        for (other in listOf("예약", "회원가입", "알림 켜기", "결제하기")) {
            val d = decide(popup("50% 할인 특가", buttons = listOf("닫기", "구매하기", other)))
            assertFalse("$other: $d", d is SimpleDecision.AutoClose)
        }
    }

    @Test fun noPromotionWordsOrTheImageHintNeverCount() {
        // No promotion words: B has no reason to call it a promotion.
        assertFalse(decide(popup("새 상품이 입고되었습니다", buttons = listOf("닫기", "구매하기"))) is SimpleDecision.AutoClose)
        // The image-dialog hint (not B) called it an ad: the gate gives 0.
        assertEquals(0, PromoPurchaseGate.allowed(true, PopupClassification.UNKNOWN, true, emptyList(), 1))
        assertEquals(0, PromoPurchaseGate.allowed(false, PopupClassification.ADVERTISEMENT, false, emptyList(), 1))
    }

    // 3) Sensitive screens.

    @Test fun aPasswordFieldOrASensitiveAddressLeavesTheScreenAlone() {
        val sale = popup("50% 할인", buttons = listOf("닫기"))
        assertEquals(SensitiveScreenReason.PASSWORD_FIELD, SensitiveScreen.reason(sale, SCREEN, passwordSeen = true, address = null))
        // A popup-only read does not reach the address bar: the bar looked up apart still counts.
        assertEquals(SensitiveScreenReason.WEB_ADDRESS, SensitiveScreen.reason(sale, SCREEN, false, "nid.naver.com/nidlogin.login"))
        assertEquals(SensitiveScreenReason.WEB_ADDRESS, SensitiveScreen.reason(sale, SCREEN, false, "m.11st.co.kr/order/checkout"))
        for (r in SensitiveScreenReason.entries) SensitiveScreen.trace(r)
    }

    @Test fun shoppingHomesAreNotBlocked() {
        // Footer 로그인·개인정보처리방침 links, the Chrome address bar (an EditText), a search box.
        assertNull(SensitiveScreen.reason(SimpleNodes.elevenStreet(), SCREEN, false, "m.11st.co.kr"))
        val home = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.widget.EditText", text = "m.gmarket.co.kr", clickable = true, bounds = ScreenBounds(100, 60, 900, 180), viewId = "com.android.chrome:id/url_bar"),
            node("0/1", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/1/0", "android.widget.EditText", text = "검색어를 입력하세요", bounds = ScreenBounds(40, 220, 1040, 320)),
            node("0/1/1", text = "로그인", clickable = true, bounds = ScreenBounds(40, 2100, 200, 2160)),
            node("0/1/2", text = "개인정보처리방침", clickable = true, bounds = ScreenBounds(220, 2100, 600, 2160)),
            node("0/1/3", text = "고객센터 1566-5701", bounds = ScreenBounds(40, 2200, 600, 2260)),
        )
        assertNull(SensitiveScreen.reason(home, SCREEN, false, "m.gmarket.co.kr"))
        assertNull(SensitiveScreen.reason(home, SCREEN, false, null))
        // The app itself (올리브영) with no address at all.
        assertNull(SensitiveScreen.reason(SimpleNodes.auction(), SCREEN, false, null))
    }

    // 4) Video skips.

    private fun skipScreen(vararg extra: NodeSnapshot): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", bounds = ScreenBounds(0, 200, 1080, 1400)),
        node("0/0/0", text = "상품 A", bounds = ScreenBounds(0, 200, 1080, 400)),
        node("0/0/1", text = "광고", bounds = ScreenBounds(900, 220, 1060, 280)),
        node("0/1", bounds = ScreenBounds(0, 1500, 1080, 2300)),
    ) + extra

    @Test fun aTutorialSkipBesideAListsAdLabelIsNotPressed() {
        // 쿠팡: "광고" labels in the product list, the tutorial's "건너뛰기" in its own box.
        val nodes = skipScreen(
            node("0/1/0", text = "이제 장바구니에서 바로 담아 보세요", bounds = ScreenBounds(40, 1550, 1040, 1700)),
            node("0/1/1", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(40, 2100, 500, 2250)),
        )
        val outside = VideoAdSkip.scan(nodes, SCREEN, youtube = false)
        assertNull(outside.result)
        assertEquals(VideoAdDiag.AD_MARK_NOT_NEAR, outside.diag)
    }

    @Test fun aSignUpsMarketingConsentIsNoAdMark() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", bounds = ScreenBounds(0, 200, 1080, 2300)),
            node("0/0/0", text = "광고성 정보 수신 동의 (선택)", bounds = ScreenBounds(40, 400, 1040, 500)),
            node("0/0/1", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(40, 2100, 500, 2250)),
        )
        assertNull(VideoAdSkip.scan(nodes, SCREEN, youtube = false).result)
        assertNull(VideoAdSkip.scan(nodes, SCREEN, youtube = true).result)
        assertTrue(VideoAdSkip.deniedByB(nodes, SCREEN))
    }

    @Test fun anAdsOwnSkipOutsideYouTubeAndYouTubeAsBefore() {
        // A player box: its "광고" mark and its "건너뛰기" are siblings.
        val player = skipScreen(
            node("0/1/0", text = "광고 · 0:15", bounds = ScreenBounds(40, 1550, 400, 1620)),
            node("0/1/1", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(700, 2100, 1040, 2250)),
        )
        assertTrue(VideoAdSkip.scan(player, SCREEN, youtube = false).result is VideoAdSkipResult.Found)
        assertFalse(VideoAdSkip.deniedByB(player, SCREEN))
        // YouTube: an ad mark anywhere counts, as before F28.
        val far = skipScreen(node("0/1/1", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(700, 2100, 1040, 2250)))
        assertTrue(VideoAdSkip.scan(far, SCREEN, youtube = true).result is VideoAdSkipResult.Found)
        assertNull(VideoAdSkip.scan(far, SCREEN, youtube = false).result)
    }

    // 5) The prompt's app left.

    @Test fun thePromptGoesWhenItsAppLeavesTheScreen() {
        assertFalse(PromptedApp.left("com.oliveyoung", setOf("com.oliveyoung")))
        // Our own app's activity in front (LG 11/1 14:29), or any other app.
        assertTrue(PromptedApp.left("com.oliveyoung", setOf("kr.closeforyou")))
        assertTrue(PromptedApp.left("com.oliveyoung", setOf("com.sec.android.app.launcher")))
        // Split screen with the popup's app still there: stays.
        assertFalse(PromptedApp.left("com.oliveyoung", setOf("kr.closeforyou", "com.oliveyoung")))
        // Not known (no windows, a window without a root): nothing done.
        assertFalse(PromptedApp.left("com.oliveyoung", null))
    }
}
