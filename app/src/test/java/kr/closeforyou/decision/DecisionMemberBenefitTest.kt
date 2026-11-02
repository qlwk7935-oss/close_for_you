package kr.closeforyou.decision

import kr.closeforyou.core.model.AnalysisLimit
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.simpleclose.WordHint
import kr.closeforyou.simpleclose.WordKindHint
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Test

/** B_DEMO_RULES.md 9-1 (P02: a sign-up benefit named as a noun) and 9-2 (the word hint never overrides B's doubt). */
class DecisionMemberBenefitTest {
    private val token = MockPopupFixtures.notice().snapshot.token

    private fun kind(vararg texts: String) = DecisionKinds.of(PopupAnalysis(
        token,
        texts = texts.mapIndexed { i, t -> ExtractedText(t, ScreenBounds(80, 400 + i * 100, 1000, 490 + i * 100), TextSource.ACCESSIBILITY_TREE, null) }.toPersistentList(),
        limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE),
    ))

    @Test fun aSignUpBenefitNamedAsANounIsAPromotion() {
        // P02 of the test app, as the app shows it.
        assertEquals(DecisionKind.COUPON, kind("신규 가입 이벤트", "가입하면 포인트를 드립니다. 시험용 광고입니다.", "자세히 보기", "닫기"))
        assertEquals(DecisionKind.COUPON, kind("첫 가입 쿠폰 받으세요", "닫기"))
        assertEquals(DecisionKind.ADVERTISEMENT, kind("회원가입 혜택", "오늘만 특가 세일", "닫기"))
    }

    @Test fun signingUpItselfStaysADecision() {
        assertEquals(DecisionKind.USER_DECISION, kind("가입하기", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, kind("회원가입", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, kind("가입 혜택을 받으시겠어요?", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, kind("가입 이벤트 신청하기", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, kind("회원가입 혜택", "지금 가입하세요", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, kind("가입 혜택을 받으려면 약관에 동의해 주세요", "닫기"))
        assertEquals(DecisionKind.USER_DECISION, kind("멤버십 가입 쿠폰", "정기 결제로 매달 할인", "닫기"))
    }

    @Test fun theWordHintNeverOverridesBsDoubt() {
        for (t in listOf("출처를 알 수 없는 쿠폰입니다", "확인되지 않은 광고입니다", "미확인 특가 세일 안내")) {
            assertEquals(t, DecisionKind.UNKNOWN, kind(t, "닫기"))
            assertEquals(t, WordHint.DENIED, WordKindHint.of(PopupClassification.UNKNOWN, false, listOf(t, "닫기"), 0))
        }
    }
}
