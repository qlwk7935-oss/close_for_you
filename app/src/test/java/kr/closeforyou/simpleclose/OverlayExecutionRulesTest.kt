package kr.closeforyou.simpleclose

import kotlinx.coroutines.runBlocking
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** B_DEMO_RULES.md 6절 (11/2 proposal): the re-check right before a press, and consecutive popups. */
class OverlayExecutionRulesTest {
    private val strongKinds = setOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON, PopupClassification.NOTICE, PopupClassification.UNKNOWN)
    private val adsOnly = setOf(PopupClassification.ADVERTISEMENT)

    private fun sheet(vararg body: String, buttons: List<String> = listOf("닫기")): SimplePopup {
        val nodes: List<NodeSnapshot> = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/0/0", text = "페이지 본문", bounds = ScreenBounds(0, 200, 1080, 900)),
            node("0/0/1", "android.app.AlertDialog", bounds = ScreenBounds(0, 1300, 1080, 2340)),
        ) + body.mapIndexed { i, t -> node("0/0/1/$i", text = t, bounds = ScreenBounds(40, 1350 + i * 90, 1040, 1430 + i * 90)) } +
            buttons.mapIndexed { i, b -> node("0/0/1/${body.size + i}", "android.widget.Button", text = b, clickable = true, bounds = ScreenBounds(40, 2000 + i * 100, 1040, 2090 + i * 100)) }
        val root = requireNotNull(PopupRootFinder.find(nodes, SCREEN))
        return SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, SCREEN, 1, 0)
    }

    private fun recheck(decided: PopupClassification, fresh: SimplePopup, kinds: Set<PopupClassification> = strongKinds) =
        runBlocking { PrePressRecheck.check(decided, fresh, kinds) }

    @Test fun aCarouselSlideIsNotAReasonToStop() {
        assertNull(recheck(PopupClassification.ADVERTISEMENT, sheet("최대 50% 할인", "2 / 8")))
        assertNull(recheck(PopupClassification.ADVERTISEMENT, sheet("오늘만 특가 세일", "3 / 8"), adsOnly))
    }

    @Test fun aPromotionThatTurnedIntoAPaymentOrConsentStepIsStopped() {
        assertEquals(PrePressBlock.BECAME_DECISION_OR_SCAM, recheck(PopupClassification.ADVERTISEMENT, sheet("최대 50% 할인", "결제 수단을 선택하세요")))
        assertEquals(PrePressBlock.BECAME_DECISION_OR_SCAM, recheck(PopupClassification.ADVERTISEMENT, sheet("마케팅 정보 수신에 동의하시겠습니까?")))
        assertEquals(PrePressBlock.BECAME_DECISION_OR_SCAM, recheck(PopupClassification.ADVERTISEMENT, sheet("축하합니다! 경품 당첨")))
    }

    @Test fun aRiskyButtonThatAppearedIsStopped() {
        assertEquals(PrePressBlock.RISKY_BUTTON_APPEARED, recheck(PopupClassification.ADVERTISEMENT, sheet("최대 50% 할인", buttons = listOf("닫기", "예약"))))
    }

    @Test fun anotherKindThePresetDoesNotCloseIsStopped() {
        assertEquals(PrePressBlock.KIND_CHANGED, recheck(PopupClassification.ADVERTISEMENT, sheet("서버 점검 안내"), adsOnly))
        // Strong mode closes notices and unknowns too: not a reason to stop there.
        assertNull(recheck(PopupClassification.ADVERTISEMENT, sheet("서버 점검 안내")))
    }

    @Test fun threeAutomaticClosesAMinutePerApp() {
        val guard = ChainGuard()
        var t = 10_000L
        for (i in 0 until 3) {
            assertNull(guard.check("app", "p$i", t))
            guard.pressed("app", "p$i", t, automatic = true)
            t += 5_000
        }
        assertEquals(ChainBlock.RATE_LIMIT, guard.check("app", "p3", t))
        assertNull("another app has its own count", guard.check("other", "q", t))
        assertNull("a minute after the first close", guard.check("app", "p3", 70_001))
    }

    @Test fun aNewPopupRightAfterOurPressIsAsked() {
        val guard = ChainGuard()
        guard.pressed("app", "sheet", 1_000, automatic = true)
        assertEquals(ChainBlock.OPENED_BY_OUR_PRESS, guard.check("app", "new-layer", 1_800))
        assertNull("the same popup coming back is PopupMemory's case", guard.check("app", "sheet", 1_800))
        assertNull("after the window", guard.check("app", "new-layer", 2_600))
        // The person's own press also counts for the opened-by-press rule, not for the rate.
        guard.pressed("app", "card", 10_000, automatic = false)
        assertEquals(ChainBlock.OPENED_BY_OUR_PRESS, guard.check("app", "other", 10_500))
    }
}
