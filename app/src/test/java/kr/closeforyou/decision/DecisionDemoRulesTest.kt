package kr.closeforyou.decision

import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B_DEMO_RULES.md 1절 "구매 오판" proposal, tested with the rule on (it ships off). */
class DecisionDemoRulesTest {
    private val token = MockPopupFixtures.notice().snapshot.token

    private fun analysis(vararg texts: String) = PopupAnalysis(
        token,
        texts = texts.mapIndexed { i, t -> ExtractedText(t, ScreenBounds(80, 400 + i * 100, 1000, 490 + i * 100), TextSource.ACCESSIBILITY_TREE, null) }.toPersistentList(),
        limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE),
    )

    private fun on(vararg texts: String) = DecisionKinds.of(analysis(*texts), promoPurchase = true)
    private fun off(vararg texts: String) = DecisionKinds.of(analysis(*texts), promoPurchase = false)

    // F28 (11/1, 재호 승인): switched on for the demo; the engine still asks unless all four conditions hold.
    @Test fun shipsOnForTheDemo() = assertTrue(DecisionDemoRules.PROMO_PURCHASE)

    /** 모아마켓 P31 (midterm deck page 10): a sale banner with a purchase button. */
    @Test fun aSaleBannerWithItsPurchaseButtonIsAnAd() {
        val p31 = arrayOf("주말 한정 최대 50% 할인", "캠핑, 생활용품 특가", "82,000원", "41,000원", "지금 구매하기", "오늘 하루 보지 않기", "닫기")
        assertEquals(DecisionKind.USER_DECISION, off(*p31))
        assertEquals(DecisionKind.ADVERTISEMENT, on(*p31))
        assertEquals(DecisionKind.ADVERTISEMENT, on("오늘만 특가", "바로 구매", "닫기"))
        assertEquals(DecisionKind.COUPON, on("5천원 할인 쿠폰", "구매하러 가기", "닫기"))
        assertEquals(DecisionKind.ADVERTISEMENT, on("Weekend sale 30% off", "Buy now", "Close"))
    }

    @Test fun anythingOfACheckoutKeepsItADecision() {
        for (checkout in listOf(
            arrayOf("오늘만 특가", "총 결제 금액 41,000원", "구매하기", "닫기"),
            arrayOf("특가 상품", "이 상품을 구매하시겠습니까?", "구매하기", "취소"),
            arrayOf("정기 구독 첫 달 50% 할인", "구매하기", "닫기"),
            arrayOf("최대 50% 할인", "카드 등록하고 바로 구매", "구매하기", "닫기"),
            arrayOf("특가 상품", "약관에 동의합니다", "구매하기", "닫기"),
            arrayOf("특가 상품", "자동 결제로 매달 할인", "구매하기", "닫기"),
            arrayOf("특가 상품", "수량 1개", "주문하기", "닫기"),
            arrayOf("특가 상품", "배송지를 확인해 주세요", "주문하기", "닫기"),
            arrayOf("특가 상품", "결제 수단 선택", "구매하기", "닫기"),
            arrayOf("50% 할인", "구매하시겠어요?", "구매하기", "닫기"),
        )) {
            assertEquals(checkout.joinToString(), DecisionKind.USER_DECISION, on(*checkout))
        }
    }

    @Test fun onlyAWholeCallToActionIsLeftOut() {
        // A purchase word inside a sentence or a different button is still a decision.
        assertEquals(DecisionKind.USER_DECISION, on("쿠폰 적용하고 바로 구매하기", "구매하기", "닫기")) // held-out de-03
        assertEquals(DecisionKind.USER_DECISION, on("오늘만 특가", "구매 확정", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, on("오늘만 특가", "1개 구매", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, on("오늘만 특가", "로그인하고 구매하기", "닫기"))
        // No promotion wording beside it: a plain purchase prompt stays a decision.
        assertEquals(DecisionKind.USER_DECISION, on("새 상품이 입고되었습니다", "구매하기", "닫기"))
        // Another decision next to the promotion's purchase button still decides.
        assertEquals(DecisionKind.USER_DECISION, on("50% 할인", "알림 받기에 동의", "구매하기", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, on("50% 할인", "멤버십 가입하기", "구매하기", "닫기"))
        // Scam first, as always.
        assertEquals(DecisionKind.SUSPICIOUS, on("축하합니다! 경품 당첨", "50% 할인", "구매하기"))
    }

    @Test fun labelsThatCountAsAPromotionsCallToAction() {
        for (label in listOf("구매하기", "지금 구매하기", "바로 구매", "바로구매", "바로 구매하기", "구매하러 가기", "주문하기", "지금 주문하기", "Buy now", "Shop now")) {
            assertTrue(label, DecisionDemoRules.isPromoPurchaseLabel(label))
        }
        for (label in listOf("구매 확정", "구매 동의", "1개 구매", "구매하시겠어요?", "결제하기", "로그인하고 구매하기", "장바구니 담기", "구독하기")) {
            assertFalse(label, DecisionDemoRules.isPromoPurchaseLabel(label))
        }
    }

    /** Every held-out and study decision stays non-closable with the rule on (the safety bar of 6-1). */
    @Test fun noRecordedDecisionBecomesClosableWithTheRuleOn() {
        val kindSet = DecisionKindEvaluationSet.samples.filter { it.truth == Kind6.DECISION || it.truth == Kind6.SUSPICIOUS }
            .filter { s -> DecisionKinds.of(analysisOf(s.texts), promoPurchase = true) in DecisionAutoClose.CLOSABLE }
        assertTrue("held-out: ${kindSet.map { it.id }}", kindSet.isEmpty())
        val study = DecisionOverWarningStudyTest().samples.filter { it.truth == DecisionOverWarningStudyTest.Truth.DECISION }
            .filter { s -> DecisionKinds.of(analysisOf(s.texts.map { it to TextSource.ACCESSIBILITY_TREE }), promoPurchase = true) in DecisionAutoClose.CLOSABLE }
        assertTrue("study: ${study.map { it.id }}", study.isEmpty())
        // And the rule changes nothing else in the held-out 80.
        val changed = DecisionKindEvaluationSet.samples.filter { s ->
            DecisionKinds.of(analysisOf(s.texts), promoPurchase = true) != DecisionKinds.of(analysisOf(s.texts), promoPurchase = false)
        }
        assertEquals(emptyList<String>(), changed.map { it.id })
    }

    private fun analysisOf(texts: List<Pair<String, TextSource>>) = PopupAnalysis(
        token,
        texts = texts.mapIndexed { i, (t, src) -> ExtractedText(t, ScreenBounds(80, 400 + i * 100, 1000, 490 + i * 100), src, null) }.toPersistentList(),
        limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE),
    )
}
