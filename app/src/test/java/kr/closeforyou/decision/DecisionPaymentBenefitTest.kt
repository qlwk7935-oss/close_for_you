package kr.closeforyou.decision

import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.simpleclose.SimpleLabelRules
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B_DEMO_RULES.md 1-7 (재호 11/1, LG 올리브영 앱): a payment *benefit* ("결제 혜택", "결제 할인", "카드 결제 시
 * 적립") inside promotion wording is not a payment decision, the same way R1 treats "가입하면 포인트".
 */
class DecisionPaymentBenefitTest {
    private val token = MockPopupFixtures.notice().snapshot.token

    private fun kind(vararg texts: String) = DecisionKinds.of(PopupAnalysis(
        token,
        texts = texts.mapIndexed { i, t -> ExtractedText(t, ScreenBounds(80, 400 + i * 100, 1000, 490 + i * 100), TextSource.ACCESSIBILITY_TREE, null) }.toPersistentList(),
        limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE),
    ))

    /** The drawer banner link 재호 saw classified as a decision on the LG (DIM_OVERLAY → CLASSIFIED_DECISION). */
    private val oliveYoungBanner = "결제 혜택 총정리 블프 특가에 결제 할인까지! 리워드 받기"

    @Test fun theOliveYoungBannerIsAPromotionNotADecision() {
        assertEquals(DecisionKind.ADVERTISEMENT, kind("테마드로워", oliveYoungBanner, "닫기"))
        assertFalse(SimpleLabelRules.isRisky(oliveYoungBanner))
    }

    @Test fun paymentBenefitPhrasesAreMentions() {
        for (t in listOf("카드 결제 시 10% 즉시 할인 이벤트", "네이버페이 결제 혜택 최대 5천원 적립", "간편결제 할인 쿠폰 받으세요", "앱 결제 캐시백 이벤트 진행 중")) {
            assertTrue(t, DecisionKeywords.isBenefitMention(t))
            assertEquals(t, DecisionKind.ADVERTISEMENT == kind(t) || DecisionKind.COUPON == kind(t), true)
        }
    }

    @Test fun aRealPaymentStepStaysADecision() {
        // The benefit phrase is left out; every other decision signal beside it still decides.
        assertEquals(DecisionKind.USER_DECISION, kind("카드 결제 시 5% 할인", "결제하기"))
        assertEquals(DecisionKind.USER_DECISION, kind("결제 혜택 안내", "결제 수단을 선택하세요", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, kind("결제 할인을 받으시겠어요?"))
        assertEquals(DecisionKind.USER_DECISION, kind("자동 결제 할인 혜택"))
        assertEquals(DecisionKind.USER_DECISION, kind("결제 혜택을 받으려면 약관에 동의해 주세요"))
        assertEquals(DecisionKind.USER_DECISION, kind("결제 할인 받고 지금 결제하세요"))
        // Short labels stay decisions: a button named for the payment is the payment.
        assertTrue(SimpleLabelRules.isRisky("결제하기"))
        assertTrue(SimpleLabelRules.isRisky("결제 할인"))
    }

    @Test fun thePromoPurchaseRuleUsesTheSameMeasure() {
        // 1-4 ④: "결제" inside a payment benefit is not a checkout trace either.
        val sale = arrayOf("주말 특가 최대 50% 할인", "카드 결제 시 10% 추가 할인", "지금 구매하기", "닫기")
        assertEquals(DecisionKind.ADVERTISEMENT, DecisionKinds.of(analysis(*sale), promoPurchase = true))
        val checkout = arrayOf("주말 특가 최대 50% 할인", "카드 결제 시 10% 추가 할인", "총 결제 금액 41,000원", "구매하기")
        assertEquals(DecisionKind.USER_DECISION, DecisionKinds.of(analysis(*checkout), promoPurchase = true))
    }

    private fun analysis(vararg texts: String) = PopupAnalysis(
        token,
        texts = texts.mapIndexed { i, t -> ExtractedText(t, ScreenBounds(80, 400 + i * 100, 1000, 490 + i * 100), TextSource.ACCESSIBILITY_TREE, null) }.toPersistentList(),
        limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE),
    )
}
