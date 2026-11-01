package kr.closeforyou.simpleclose

import kotlinx.coroutines.runBlocking
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** B_DEMO_RULES.md 8절: review of the engine at F33. Before the fix the first case was pressed without asking. */
class BReviewF33Test {
    private val closeAll = HandlingPreset.CLOSE_ALL.autoCloseKinds()
    private val page = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", bounds = SCREEN),
        node("0/0/0", text = "페이지 본문", bounds = ScreenBounds(0, 100, 1080, 2200)),
    )
    private val card = ScreenBounds(120, 700, 960, 1500)

    private fun modal(vararg beside: NodeSnapshot, body: String = "이번 달 이용 안내"): List<NodeSnapshot> = page + listOf(
        node("0/1", bounds = SCREEN),
        node("0/1/0", bounds = card),
        node("0/1/0/0", text = body, bounds = ScreenBounds(160, 760, 920, 860)),
        node("0/1/0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(780, 720, 940, 800)),
    ) + beside

    private fun button(path: String, text: String, top: Int = 1520) =
        node(path, "android.widget.Button", text = text, clickable = true, bounds = ScreenBounds(120, top, 960, top + 120))

    private fun popupOf(nodes: List<NodeSnapshot>): SimplePopup {
        val root = requireNotNull(PopupRootFinder.find(nodes, SCREEN))
        return SimplePopupAnalysis.build(nodes, root, "com.example", 1, SCREEN, 1, 0)
    }

    private fun decide(nodes: List<NodeSnapshot>): SimpleDecision {
        val p = popupOf(nodes)
        val kind = runBlocking { DecisionPopupClassifier().classify(p.analysis) }
        val promo = PromoPurchaseGate.allowed(true, kind, false, p.analysis.texts, p.scan.promoBuyCount)
        return SimpleCloseDecider.decide(kind, RememberChoiceRules.suspiciousByB(p.analysis), p.scan.candidates, closeAll, p.scan.forbiddenCount,
            riskCount = p.scan.riskCount, promoBuyCount = promo)
    }

    @Test fun aDecisionButtonRightBesideTheCardIsSeen() {
        val d = decide(modal(button("0/1/1", "정기 결제 동의")))
        assertEquals(SimpleDecision.DenyDecision, d)
        val p = popupOf(modal(button("0/1/1", "정기 결제 동의")))
        assertEquals(listOf("정기 결제 동의"), p.scan.riskButtons.map { it.label })
        assertEquals("never a press target", listOf("닫기"), p.scan.candidates.map { it.label })
        // A bare request word B does not read as a decision still keeps it asked.
        assertEquals(SimpleDecision.Ask, decide(modal(button("0/1/1", "예약"))))
    }

    @Test fun aPromotionsPurchaseButtonBesideTheCardFollowsThePromotionRule() {
        val d = decide(modal(button("0/1/1", "지금 구매하기"), body = "주말 특가 최대 50% 할인"))
        assertTrue(d.toString(), d is SimpleDecision.AutoClose && d.candidate.label == "닫기")
    }

    @Test fun thePageBehindIsNotTheNeighbour() {
        // Page buttons are the card's cousins, not under its parent layer.
        val onPage = page + node("0/0/1", "android.widget.Button", text = "로그인", clickable = true, bounds = ScreenBounds(120, 1520, 960, 1640)) + modal().drop(3)
        assertTrue(decide(onPage) is SimpleDecision.AutoClose)
        // A parent with many clickables is the page itself.
        val many = (1..12).map { node("0/1/$it", "android.widget.Button", text = "상품 $it", clickable = true, bounds = ScreenBounds(120, 1520, 960, 1640)) }
        assertEquals(emptyList<NodeSnapshot>(), PopupNeighbors.of(modal(*many.toTypedArray()), PopupRoot("0/1/0", card, PopupRootKind.MODAL_CARD), SCREEN))
        // Far from the card, or a layer behind it: not beside it.
        assertTrue(decide(modal(button("0/1/1", "구독하기", top = 2100))) is SimpleDecision.AutoClose)
        val behind = PopupNeighbors.of(modal(node("0/1/1", text = "결제", bounds = ScreenBounds(0, 500, 1080, 1800))), PopupRoot("0/1/0", card, PopupRootKind.MODAL_CARD), SCREEN)
        assertEquals(emptyList<NodeSnapshot>(), behind)
    }

    @Test fun aDialogRootIsLeftAsItWas() {
        val root = PopupRoot("0/1", ScreenBounds(0, 1300, 1080, 2340), PopupRootKind.ALERT_DIALOG)
        assertEquals(emptyList<NodeSnapshot>(), PopupNeighbors.of(modal(button("0/2", "결제하기", 1200)), root, SCREEN))
    }

    @Test fun onlyAPopupWithItsOwnWordsCanBeRemembered() {
        assertTrue(RememberedPopupRules.canRemember(popupOf(modal())))
        val imageOnly = page + listOf(
            node("0/1", bounds = SCREEN),
            node("0/1/0", bounds = card),
            node("0/1/0/0", "android.widget.ImageView", bounds = ScreenBounds(160, 760, 920, 1300)),
            node("0/1/0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(780, 720, 940, 800)),
        )
        assertFalse(RememberedPopupRules.canRemember(popupOf(imageOnly)))
    }

    @Test fun theWaitIsOnlyWhatWasReadOnScreen() {
        fun screenWith(vararg t: String) = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN)) +
            t.mapIndexed { i, s -> node("0/$i", text = s, bounds = ScreenBounds(0, 100 + i * 100, 500, 180 + i * 100)) }
        assertEquals(5, VideoAdSkip.waitSeconds(screenWith("광고", "5초 후 건너뛰기"), SCREEN))
        assertEquals(10, VideoAdSkip.waitSeconds(screenWith("Skip ad in 10s"), SCREEN))
        assertNull("no countdown", VideoAdSkip.waitSeconds(screenWith("광고", "건너뛰기"), SCREEN))
        assertNull("not about skipping", VideoAdSkip.waitSeconds(screenWith("5초 후 시작"), SCREEN))
        assertNull("a decision word", VideoAdSkip.waitSeconds(screenWith("5초 후 건너뛰기 결제"), SCREEN))
        assertNull("two readings", VideoAdSkip.waitSeconds(screenWith("3초 후 건너뛰기", "5초 후 건너뛰기"), SCREEN))
        val hidden = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN), node("0/0", text = "5초 후 건너뛰기", bounds = ScreenBounds(0, 2340, 500, 2340)))
        assertNull("not on screen", VideoAdSkip.waitSeconds(hidden, SCREEN))
    }
}
