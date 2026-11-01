package kr.closeforyou.simpleclose

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
import org.junit.Assert.assertTrue
import org.junit.Test

/** B_DEMO_RULES.md 1절 in the demo engine: what 강력 모드 does with a sale popup that has a purchase button. */
class PromoPurchaseDeciderTest {
    private val closeAll = HandlingPreset.CLOSE_ALL.autoCloseKinds()

    private fun popup(vararg body: String, buttons: List<String>): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", text = "페이지 본문", bounds = ScreenBounds(0, 200, 1080, 900)),
        node("0/0/1", "android.app.AlertDialog", bounds = ScreenBounds(80, 700, 1000, 1900)),
    ) + body.mapIndexed { i, t -> node("0/0/1/$i", text = t, bounds = ScreenBounds(120, 800 + i * 90, 960, 880 + i * 90)) } +
        buttons.mapIndexed { i, b -> node("0/0/1/${body.size + i}", "android.widget.Button", text = b, clickable = true, bounds = ScreenBounds(120, 1500 + i * 110, 960, 1600 + i * 110)) }

    private fun decide(nodes: List<NodeSnapshot>, rule: Boolean): SimpleDecision {
        val root = requireNotNull(PopupRootFinder.find(nodes, SCREEN))
        val p = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, SCREEN, 1, 0)
        val kind = DecisionKinds.of(p.analysis, promoPurchase = rule)
        val display = when (kind) {
            DecisionKind.USER_DECISION -> PopupClassification.USER_DECISION
            DecisionKind.ADVERTISEMENT -> PopupClassification.ADVERTISEMENT
            DecisionKind.COUPON -> PopupClassification.COUPON
            DecisionKind.NOTICE -> PopupClassification.NOTICE
            else -> PopupClassification.UNKNOWN
        }
        return SimpleCloseDecider.decide(display, kind == DecisionKind.SUSPICIOUS, p.scan.candidates, closeAll, p.scan.forbiddenCount,
            riskCount = p.scan.riskCount, promoBuyCount = p.scan.promoBuyCount, promoPurchase = rule)
    }

    private val p31 = popup("주말 한정 최대 50% 할인", "캠핑, 생활용품 특가", "41,000원", buttons = listOf("닫기", "지금 구매하기", "오늘 하루 보지 않기"))

    @Test fun aSaleBannerIsClosedByItsCloseNeverByItsPurchaseButton() {
        assertEquals(SimpleDecision.DenyDecision, decide(p31, rule = false))
        val d = decide(p31, rule = true)
        assertTrue(d.toString(), d is SimpleDecision.AutoClose && d.candidate.label == "닫기")
    }

    @Test fun aCheckoutStaysDeniedWithTheRuleOn() {
        val checkout = popup("특가 상품", "총 결제 금액 41,000원", buttons = listOf("닫기", "구매하기"))
        assertEquals(SimpleDecision.DenyDecision, decide(checkout, rule = true))
        val question = popup("50% 할인", "이 상품을 구매하시겠습니까?", buttons = listOf("취소", "구매하기"))
        assertEquals(SimpleDecision.DenyDecision, decide(question, rule = true))
    }

    @Test fun anotherRiskyButtonBesideThePurchaseStillAsks() {
        // "회원가입" is a decision word: B calls the popup a decision before the engine even counts buttons.
        val signUp = popup("50% 할인", buttons = listOf("닫기", "구매하기", "회원가입"))
        assertEquals(SimpleDecision.DenyDecision, decide(signUp, rule = true))
        // "예약" (bare) is risky only to the engine (B 리뷰 11/1): asked, not closed.
        val reserve = popup("50% 할인", buttons = listOf("닫기", "구매하기", "예약"))
        assertEquals(SimpleDecision.Ask, decide(reserve, rule = true))
    }
}
