package kr.closeforyou.decision

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B_DEMO_RULES.md 1-8: a link named by its promotion is a promotion; a plain purchase link stays a decision. */
class DecisionPromoLinkTest {
    private val token = MockPopupFixtures.notice().snapshot.token

    private fun kind(vararg texts: String, promoPurchase: Boolean = true) = DecisionKinds.of(
        PopupAnalysis(
            token,
            texts = texts.mapIndexed { i, t -> ExtractedText(t, ScreenBounds(80, 400 + i * 100, 1000, 490 + i * 100), TextSource.ACCESSIBILITY_TREE, null) }.toPersistentList(),
            limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE),
        ),
        promoPurchase = promoPurchase,
    )

    @Test fun theAuctionLinkIsAnAd() {
        assertEquals(DecisionKind.USER_DECISION, kind("삼성 BIG 혜택 구매하러가기", "닫기", promoPurchase = false))
        assertEquals(DecisionKind.ADVERTISEMENT, kind("삼성 BIG 혜택 구매하러가기", "닫기"))
        assertEquals(DecisionKind.ADVERTISEMENT, kind("빅스마일데이 특가 구매하러 가기", "닫기"))
        assertEquals(DecisionKind.ADVERTISEMENT, kind("오늘의 최저가 주문하러 가기 >", "닫기"))
    }

    @Test fun aLinkWithoutAPromotionStaysADecision() {
        assertFalse(DecisionDemoRules.isPromoPurchaseLink("상품 구매하러 가기"))
        assertEquals(DecisionKind.USER_DECISION, kind("상품 구매하러 가기", "닫기"))
    }

    @Test fun aDecisionWordBeforeTheLinkStaysADecision() {
        listOf("구독 혜택 구매하러 가기", "회원가입 혜택 구매하러 가기", "카드 결제 혜택 구매하러 가기", "약관 동의 혜택 구매하러 가기").forEach {
            assertFalse(it, DecisionDemoRules.isPromoPurchaseLink(it))
            assertEquals(it, DecisionKind.USER_DECISION, kind(it, "닫기"))
        }
    }

    @Test fun onlyTheMovingWordCounts() {
        listOf("삼성 혜택 구매하기", "혜택 구매 완료", "혜택 구매하시겠어요?", "혜택 구매하러 가기 전 확인").forEach {
            assertFalse(it, DecisionDemoRules.isPromoPurchaseLink(it))
        }
    }

    @Test fun aLongLabelIsNotALink() {
        assertFalse(DecisionDemoRules.isPromoPurchaseLink("이 상품은 환불이 어려운 상품이며 특가로 제공되는 한정 수량 상품입니다 구매하러 가기"))
    }

    @Test fun aCheckoutTraceBesideTheLinkStaysADecision() {
        assertEquals(DecisionKind.USER_DECISION, kind("삼성 BIG 혜택 구매하러가기", "총 결제 금액 41,000원", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, kind("삼성 BIG 혜택 구매하러가기", "자동 결제에 동의합니다", "닫기"))
    }

    @Test fun aLoginInvitationIsNotCountedAsAPurchaseLink() {
        assertTrue(DecisionDemoRules.isPromoPurchaseLink("쿠폰 혜택 주문하러 가기"))
        assertFalse(DecisionDemoRules.isPromoPurchaseLink("로그인하러 가기"))
    }
}
