package kr.closeforyou.simpleclose

import kotlinx.coroutines.runBlocking
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionDemoRules
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F31: B's 6-1 pre-press recheck and 6-2 chain guard as the engine wires them: the guard's numbers come from
 * [SimpleCloseEngineFeature], the recheck sees the purchase-promotion count gated as the decision saw it.
 */
class F31RegressionTest {
    private val strongKinds = setOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON, PopupClassification.NOTICE, PopupClassification.UNKNOWN)

    private fun popup(vararg body: String, buttons: List<String>): SimplePopup {
        val nodes: List<NodeSnapshot> = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/0/0", "android.app.AlertDialog", bounds = ScreenBounds(80, 700, 1000, 2000)),
        ) + body.mapIndexed { i, t -> node("0/0/0/$i", text = t, bounds = ScreenBounds(120, 800 + i * 90, 960, 880 + i * 90)) } +
            buttons.mapIndexed { i, b -> node("0/0/0/${body.size + i}", "android.widget.Button", text = b, clickable = true, bounds = ScreenBounds(120, 1500 + i * 110, 960, 1600 + i * 110)) }
        val root = requireNotNull(PopupRootFinder.find(nodes, SCREEN))
        return SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, SCREEN, 1, 0)
    }

    /** As [SimpleCloseAccessibilityService.prePressStop] builds the fresh popup. */
    private fun recheck(decided: PopupClassification, fresh: SimplePopup, closable: Set<PopupClassification> = strongKinds): PrePressBlock? = runBlocking {
        val kind = DecisionPopupClassifier().classify(fresh.analysis)
        val gated = PromoPurchaseGate.allowed(DecisionDemoRules.PROMO_PURCHASE, kind, false, fresh.analysis.texts, fresh.scan.promoBuyCount)
        PrePressRecheck.check(decided, fresh.copy(scan = fresh.scan.copy(promoBuyCount = gated)), closable)
    }

    @Test fun aCheckedPromotionStillPassesAndAPaymentStepStops() {
        val promo = popup("주말 한정 최대 50% 할인", "캠핑 특가", buttons = listOf("닫기", "지금 구매하기"))
        assertNull(recheck(PopupClassification.ADVERTISEMENT, promo))
        val paying = popup("주말 한정 최대 50% 할인", "결제 수단을 선택하세요", buttons = listOf("닫기", "지금 구매하기"))
        assertEquals(PrePressBlock.BECAME_DECISION_OR_SCAM, recheck(PopupClassification.ADVERTISEMENT, paying))
        val reserve = popup("주말 한정 최대 50% 할인", buttons = listOf("닫기", "예약"))
        assertEquals(PrePressBlock.RISKY_BUTTON_APPEARED, recheck(PopupClassification.ADVERTISEMENT, reserve))
    }

    @Test fun strongModeDoesNotStopOnAKindChange() {
        val notice = popup("서비스 점검 안내", "11월 2일 새벽 점검이 있습니다", buttons = listOf("닫기"))
        assertNull(recheck(PopupClassification.ADVERTISEMENT, notice))
        assertEquals(PrePressBlock.KIND_CHANGED, recheck(PopupClassification.ADVERTISEMENT, notice, closable = setOf(PopupClassification.ADVERTISEMENT)))
    }

    private fun guard() = ChainGuard(
        SimpleCloseEngineFeature.CHAIN_MAX_AUTO, SimpleCloseEngineFeature.CHAIN_WINDOW_MILLIS, SimpleCloseEngineFeature.CHAIN_NEW_POPUP_AFTER_PRESS_MILLIS,
    )

    @Test fun theGuardsNumbersComeFromTheFeature() {
        val g = guard()
        var now = 0L
        repeat(SimpleCloseEngineFeature.CHAIN_MAX_AUTO) {
            assertNull(g.check("app", "p$it", now))
            g.pressed("app", "p$it", now, automatic = true)
            now += SimpleCloseEngineFeature.CHAIN_NEW_POPUP_AFTER_PRESS_MILLIS
        }
        assertEquals(ChainBlock.RATE_LIMIT, g.check("app", "next", now))
        assertNull(g.check("app", "next", SimpleCloseEngineFeature.CHAIN_WINDOW_MILLIS + 10_000))
    }

    @Test fun anotherPopupRightAfterOurPressIsAskedTheSameOneIsNot() {
        // 11번가: the sheet closed, then a coupon popup 0.8 s later. Asked; LG to tell whether 1.5 s is too strict.
        val g = guard()
        g.pressed("chrome", "1:sheet", 10_000, automatic = true)
        assertEquals(ChainBlock.OPENED_BY_OUR_PRESS, g.check("chrome", "1:coupon", 10_800))
        assertNull(g.check("chrome", "1:sheet", 10_800))
        assertNull(g.check("chrome", "1:coupon", 10_000 + SimpleCloseEngineFeature.CHAIN_NEW_POPUP_AFTER_PRESS_MILLIS + 1))
        // The person's own press counts for the "after our press" rule, not for the rate.
        g.pressed("app", "a", 20_000, automatic = false)
        assertEquals(ChainBlock.OPENED_BY_OUR_PRESS, g.check("app", "b", 20_500))
    }
}
