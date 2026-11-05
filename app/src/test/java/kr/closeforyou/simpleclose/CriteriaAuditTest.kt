package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionKind
import kr.closeforyou.decision.DecisionKinds
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

/** B_DEMO_RULES.md 12절 (11/4 audit): each case was run on the old rules and gave the wrong result. */
class CriteriaAuditTest {
    private fun popup(body: List<String>, buttons: List<String> = listOf("닫기")): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/1", "android.app.AlertDialog", bounds = ScreenBounds(80, 700, 1000, 1900)),
    ) + body.mapIndexed { i, t -> node("0/0/1/$i", text = t, bounds = ScreenBounds(120, 800 + i * 90, 960, 880 + i * 90)) } +
        buttons.mapIndexed { i, b -> node("0/0/1/${body.size + i}", "android.widget.Button", text = b, clickable = true, bounds = ScreenBounds(120, 1500 + i * 110, 960, 1600 + i * 110)) }

    private fun built(nodes: List<NodeSnapshot>) = SimplePopupAnalysis.build(nodes, PopupRootFinder.find(nodes, SCREEN)!!, "com.android.chrome", 1, SCREEN, 1, 0)
    private fun kind(vararg body: String) = DecisionKinds.of(built(popup(body.toList())).analysis)

    private fun closedUnderCloseAll(nodes: List<NodeSnapshot>): Boolean {
        val p = built(nodes)
        val k = DecisionKinds.of(p.analysis)
        val display = when (k) {
            DecisionKind.USER_DECISION -> PopupClassification.USER_DECISION
            DecisionKind.ADVERTISEMENT -> PopupClassification.ADVERTISEMENT
            DecisionKind.COUPON -> PopupClassification.COUPON
            DecisionKind.NOTICE -> PopupClassification.NOTICE
            else -> PopupClassification.UNKNOWN
        }
        return SimpleCloseDecider.decide(display, k == DecisionKind.SUSPICIOUS, p.scan.candidates, HandlingPreset.CLOSE_ALL.autoCloseKinds(),
            p.scan.forbiddenCount, riskCount = p.scan.riskCount, promoBuyCount = p.scan.promoBuyCount) is SimpleDecision.AutoClose
    }

    @Test fun scamWordingIsNotClosed() {
        listOf(
            listOf("휴대폰 보안 위협 3건 발견. 지금 검사하세요"), listOf("축하합니다! 신세계 상품권 50만원이 지급됩니다"),
            listOf("고객님이 사은품 대상자로 선정되었습니다"), listOf("스마트폰 속도가 느려졌습니다. 지금 최적화"),
            listOf("갤럭시 S25 무료로 드려요"), listOf("Your device is at risk. Scan now"),
            listOf("해킹 시도가 감지되었습니다"), listOf("바이러스가 있습니다"),
        ).forEach { assertEquals(it.toString(), DecisionKind.SUSPICIOUS, kind(*it.toTypedArray())) }
    }

    @Test fun legitimateNoticesAreNotCalledScams() {
        listOf("재해 보상금 지급 안내", "독감 바이러스 예방접종 안내", "해킹 피해 예방 안내", "[당첨자 발표] 10월 이벤트 안내").forEach {
            assertEquals(it, DecisionKind.NOTICE, kind(it))
        }
        assertEquals(DecisionKind.SUSPICIOUS, kind("1등 당첨을 축하합니다"))
        assertEquals(DecisionKind.SUSPICIOUS, kind("휴대폰이 바이러스에 감염되었습니다"))
    }

    @Test fun paymentWordsAreDecisions() {
        listOf("프리미엄 이용권 1개월 구입 9,900원", "미납 요금 안내: 납부 기한이 지났습니다", "100,000원을 출금합니다",
            "카드 정보 등록이 필요합니다", "구독하면 첫 달 무료, 이후 월 9,900원", "Confirm your order", "In-app purchases require permissions", "Sign-up required",
        ).forEach { assertEquals(it, DecisionKind.USER_DECISION, kind(it)) }
    }

    @Test fun harmlessAdsAreNotDecisions() {
        listOf("감동의 가격 최대 50% 할인", "유아동의류 최대 50% 할인", "건강해지는 겨울 식단 30% 할인", "상품권 한정 특가 세일").forEach {
            assertTrue(it, kind(it) != DecisionKind.USER_DECISION && kind(it) != DecisionKind.SUSPICIOUS)
        }
        assertTrue(kind("3만원 이상 금액 20% 할인") != DecisionKind.SUSPICIOUS)
    }

    @Test fun spacedLettersStillCount() {
        assertEquals(DecisionKind.USER_DECISION, kind("동 의 합 니 다"))
        assertEquals(DecisionKind.USER_DECISION, kind("결 제 하 기"))
        assertEquals(DecisionKind.USER_DECISION, kind("결 제하기"))
        assertEquals(DecisionKind.USER_DECISION, kind("알림을 허 용해 주세요"))
        assertEquals(DecisionKind.SUSPICIOUS, kind("경품 당 첨!"))
    }

    @Test fun oneDigitDoesNotDecide() {
        assertEquals(kind("구매 시 10% 할인"), kind("구매 시 5% 할인"))
        assertTrue(kind("구매 시 5% 할인") != DecisionKind.USER_DECISION)
    }

    @Test fun labelsThatActAreNotCloses() {
        listOf("구입 닫기", "결재 닫기", "납부 닫기", "쿠폰 사용하기 닫기", "이벤트 참여하기 닫기", "특가 상품 보러 가기 닫기", "쿠폰 적용 닫기", "확인 닫기", "안 닫기", "자동 닫기").forEach {
            assertNull(it, SimpleLabelRules.judge(it).role)
        }
    }

    @Test fun ordinaryCloseLabelsAreCloses() {
        listOf("광고 닫기", "전면 광고 닫기", "배너 광고 닫기", "공고 닫기", "화면 닫기", "전체화면 닫기", "안내서 닫기", "미리보기 닫기", "앱설치 안내 레이어 닫기").forEach {
            assertEquals(it, ButtonRole.CLOSE, SimpleLabelRules.judge(it).role)
        }
        listOf("앱 설치하고 닫기", "확인 후 닫기", "광고 보고 닫기").forEach { assertNull(it, SimpleLabelRules.judge(it).role) }
    }

    @Test fun aCloseWhoseDescriptionSaysOtherwiseIsNotPressed() {
        val nodes = popup(listOf("오늘의 특가 세일"), emptyList()) +
            node("0/0/1/5", "android.widget.Button", text = "닫기", desc = "자세히 보기", clickable = true, bounds = ScreenBounds(120, 1500, 960, 1600))
        assertFalse(closedUnderCloseAll(nodes))
    }

    @Test fun maskedPersonalDataAndMoreAddressesAreSensitive() {
        listOf("shop.example.com/orders/123", "shop.example.com/payments", "shop.example.com/sign_in", "shop.example.com/mobilelogin", "shop.example.com/login-form", "shop.example.com/cart").forEach {
            assertTrue(it, BrowserSensitivity.sensitiveAddress(it))
        }
        assertFalse(BrowserSensitivity.sensitiveAddress("shop.example.com/event/autumn"))
        val page = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN))
        listOf("010-****-1234", "900101-1******").forEach {
            assertNotNull(it, BrowserSensitivity.check(page + node("0/0", text = it, bounds = ScreenBounds(0, 300, 1080, 400)), SCREEN))
        }
    }

    @Test fun aHomeDiscountBannerIsNotAPaymentPage() {
        val page = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN))
        fun screen(vararg words: String) = page + words.mapIndexed { i, t -> node("0/$i", text = t, bounds = ScreenBounds(0, 300 + i * 100, 1080, 390 + i * 100)) }
        assertNull(BrowserSensitivity.check(screen("신용카드 즉시 할인", "간편결제 5% 추가 할인"), SCREEN))
        assertNull(BrowserSensitivity.check(screen("신용카드 무이자 할부"), SCREEN))
        assertEquals(BrowserSensitiveReason.PAYMENT_PAGE, BrowserSensitivity.check(screen("신용카드", "결제하기"), SCREEN))
        assertEquals(BrowserSensitiveReason.PAYMENT_PAGE, BrowserSensitivity.check(screen("간편결제", "최종 결제 금액 12,000원"), SCREEN))
    }
}
