package kr.closeforyou.simpleclose

import kotlinx.coroutines.runBlocking
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionDemoRules
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F35: B_DEMO_RULES.md 8-5 B안 (로그인 홍보 팝업, 재호 결정) and 8-1 (neighbours read). Device unverified. */
class F35Test {
    private val lg = ScreenBounds(0, 0, 1080, 2460)
    private val card = ScreenBounds(42, 900, 1038, 1700)

    private fun dialog(vararg texts: Pair<String, Boolean>): List<NodeSnapshot> =
        listOf(node("0", "android.widget.FrameLayout", bounds = card)) + texts.mapIndexed { i, (text, button) ->
            val top = 940 + i * 120
            node("0/$i", if (button) "android.widget.Button" else "android.widget.TextView", text = text, clickable = button,
                bounds = ScreenBounds(90, top, 990, top + 110))
        }

    private fun decide(nodes: List<NodeSnapshot>): Pair<PopupClassification, SimpleDecision> {
        val p = SimplePopupAnalysis.build(nodes, PopupRoot("0", card, PopupRootKind.DIALOG_WINDOW), "x", 1, lg, 1, 0)
        val kind = runBlocking { DecisionPopupClassifier().classify(p.analysis) }
        val promo = PromoPurchaseGate.allowed(true, kind, false, p.analysis.texts, p.scan.promoBuyCount) +
            PromoLoginGate.allowed(true, kind, false, p.analysis.texts, p.scan.promoLoginCount)
        return kind to SimpleCloseDecider.decide(kind, RememberChoiceRules.suspiciousByB(p.analysis), p.scan.candidates,
            HandlingPreset.CLOSE_ALL.autoCloseKinds(), p.scan.forbiddenCount, riskCount = p.scan.riskCount, promoBuyCount = promo)
    }

    @Test fun loginInvitationLabels() {
        for (label in listOf("로그인하러 가기", "로그인하러 가기 >", "로그인하고 혜택 받기", "로그인하고 쿠폰 받기", "로그인", "Log in")) {
            assertTrue(label, DecisionDemoRules.isPromoLoginLabel(label))
        }
        for (label in listOf("로그인하고 구매하기", "로그인 후 결제", "로그인 동의", "로그인 정보 변경", "비밀번호 찾기")) {
            assertFalse(label, DecisionDemoRules.isPromoLoginLabel(label))
        }
    }

    // 8-5 B안: a coupon promotion with "로그인하러 가기" and "닫기": only the close is pressed, by itself.
    @Test fun aPromotionsLoginInvitationIsNotItsDecision() {
        val (kind, d) = decide(dialog("누구나 쿠폰 받고 할인 혜택을 누리세요" to false, "로그인하러 가기 >" to true, "닫기" to true))
        assertTrue(kind == PopupClassification.COUPON || kind == PopupClassification.ADVERTISEMENT)
        assertEquals("닫기", (d as SimpleDecision.AutoClose).candidate.label)
    }

    // Counter-examples: no promotion words, a terms or password trace, a decision urged in the body: still asked.
    @Test fun withoutAPromotionTheLoginIsStillADecision() {
        for (body in listOf("로그인이 필요한 서비스입니다", "쿠폰 받으려면 약관을 확인해 주세요", "쿠폰 받고 비밀번호를 변경하세요", "지금 구매하면 쿠폰 증정")) {
            val (_, d) = decide(dialog(body to false, "로그인하러 가기" to true, "닫기" to true))
            assertFalse(body, d is SimpleDecision.AutoClose)
        }
        // A login button that asks for more than a login is never a promotion's invitation.
        val (_, d) = decide(dialog("누구나 쿠폰 받고 할인 혜택" to false, "로그인하고 구매하기" to true, "닫기" to true))
        assertFalse(d is SimpleDecision.AutoClose)
    }

    @Test fun theLoginButtonIsNeverAPressTarget() {
        val p = SimplePopupAnalysis.build(dialog("누구나 쿠폰 받고 할인 혜택" to false, "로그인하러 가기" to true, "닫기" to true),
            PopupRoot("0", card, PopupRootKind.DIALOG_WINDOW), "x", 1, lg, 1, 0)
        assertEquals(listOf("닫기"), p.scan.candidates.map { it.label })
        assertEquals(1, p.scan.promoLoginCount)
        assertEquals(listOf("로그인하러 가기"), p.scan.riskButtons.map { it.label })
    }

    @Test fun cardSiblingsInTheRead() {
        val root = PopupRoot("0/1/0", ScreenBounds(90, 900, 990, 1500), PopupRootKind.MODAL_CARD)
        val cardOnly = listOf(node("0/1/0", bounds = root.bounds), node("0/1/0/0", text = "안내", bounds = ScreenBounds(120, 950, 960, 1050)))
        assertFalse(PopupNeighbors.siblingsRead(cardOnly, root))
        assertTrue(PopupNeighbors.siblingsRead(cardOnly + node("0/1/1", text = "정기 결제 동의", clickable = true, bounds = ScreenBounds(120, 1520, 960, 1640)), root))
    }
}
