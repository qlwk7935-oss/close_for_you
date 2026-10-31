package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.command.KoreanVoiceCommandMatcher
import kr.closeforyou.ui.voice.command.VoiceOutcome
import kr.closeforyou.ui.voice.command.VoicePolicy
import kr.closeforyou.ui.voice.command.VoiceUtterance
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F23: D's real-site answer key (L01~L20) against the engine's rules, voice "버튼으로 이동", YouTube web. */
class F23RegressionTest {
    private fun popupOf(nodes: List<NodeSnapshot>) =
        SimplePopupAnalysis.build(nodes, PopupRootFinder.find(nodes, SCREEN)!!, "com.android.chrome", 7, SCREEN, 1L, 0L)

    private fun strong(popup: SimplePopup, kind: PopupClassification) = SimpleCloseDecider.decide(
        kind, false, popup.scan.candidates, HandlingPreset.CLOSE_ALL.autoCloseKinds(), popup.scan.forbiddenCount,
        unknownEnabled = true, riskCount = popup.scan.riskCount, strongEnabled = true,
    )

    private fun dialog(vararg buttons: String) = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", "android.app.AlertDialog", bounds = ScreenBounds(90, 600, 990, 1700)),
        node("0/0/0/0", text = "가을 특가 이벤트", bounds = ScreenBounds(90, 600, 990, 800)),
    ) + buttons.mapIndexed { i, label ->
        node("0/0/0/${i + 1}", "android.widget.Button", text = label, clickable = true, bounds = ScreenBounds(90 + i * 200, 1500, 280 + i * 200, 1700))
    }

    // ---- 3. answer key ----

    @Test fun anEventPopupWithABuyButtonIsClosedByItsCloseNeverByBuy() {
        // L04/L05/L06/L13: EVENT_POPUP, forbidden BUY, expected CLOSE_OK.
        val popup = popupOf(dialog("구매하기", "닫기"))
        assertEquals(0, popup.scan.riskCount)
        assertEquals(listOf("구매하기"), popup.scan.riskButtons.map { it.label })
        val decision = strong(popup, PopupClassification.ADVERTISEMENT) as SimpleDecision.AutoClose
        assertEquals("닫기", decision.candidate.label)
    }

    @Test fun paymentConsentSubscribePermissionOrLoginBesideTheCloseKeepItAsked() {
        // L12 subscribe, L20 permission, L01 login: asked (재호 F16 list), whatever the answer key says for L12.
        for (risky in listOf("결제하기", "동의하기", "구독하기", "허용", "로그인")) {
            val popup = popupOf(dialog(risky, "닫기"))
            assertEquals(risky, SimpleDecision.Ask, strong(popup, PopupClassification.ADVERTISEMENT))
        }
    }

    @Test fun bDecidingItIsADecisionStillWins() {
        // L03 쿠팡 web / L17 배민 app: expected ASK as USER_DECISION.
        val popup = popupOf(dialog("주문하기", "닫기"))
        assertEquals(SimpleDecision.DenyDecision, strong(popup, PopupClassification.USER_DECISION))
    }

    @Test fun hideTodayIsOnlyPressedWhenThereIsNoPlainClose() {
        // L04~L18 list HIDE_TODAY as forbidden in D's key; 재호 F16 made it a close. It never beats a plain close.
        val both = popupOf(dialog("오늘 하루 보지 않기", "닫기"))
        assertEquals("닫기", (strong(both, PopupClassification.ADVERTISEMENT) as SimpleDecision.AutoClose).candidate.label)
    }

    // ---- 2. voice "○○ 버튼으로 이동" ----

    private fun decisionPrompt() = run {
        val popup = popupOf(SimpleNodes.chromePermission())
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.USER_DECISION, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false)
        SimplePromptOptions.adjust(planned, popup.scan.riskButtons)
    }

    @Test fun movingToAPermissionButtonByVoiceOnlyMoves() {
        val heard = KoreanVoiceCommandMatcher.classify("허용 버튼으로 이동")
        assertTrue(heard is VoiceUtterance.MoveTo)
        val outcome = VoicePolicy.decide(heard, decisionPrompt()) as VoiceOutcome.Choose
        assertEquals(PromptOptionKind.MOVE_TO_BUTTON, outcome.option.kind)
        assertEquals("허용", outcome.option.buttonLabel)
        assertTrue(KoreanVoiceCommandMatcher.classify("허용 버튼 어디야") is VoiceUtterance.MoveTo)
    }

    @Test fun pressingAPermissionButtonByVoiceIsStillRefused() {
        assertEquals(VoiceUtterance.Refused, KoreanVoiceCommandMatcher.classify("허용해"))
        assertEquals(VoiceUtterance.Refused, KoreanVoiceCommandMatcher.classify("결제 눌러"))
        assertFalse(KoreanVoiceCommandMatcher.classify("허용 버튼으로 이동하지 마") is VoiceUtterance.MoveTo)
    }

    @Test fun aMoveNeverPicksAPressAndSaysPickOnScreenWhenUnclear() {
        val prompt = decisionPrompt()
        val unnamed = VoicePolicy.decide(VoiceUtterance.MoveTo(KoreanVoiceCommandMatcher.normalize("버튼으로 이동")), prompt)
        // One move option: that one.
        assertEquals(PromptOptionKind.MOVE_TO_BUTTON, (unnamed as VoiceOutcome.Choose).option.kind)
        val popup = popupOf(dialog("결제하기", "동의하기", "닫기"))
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.UNKNOWN, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false)
        val two = SimplePromptOptions.adjust(planned, popup.scan.riskButtons)
        assertTrue(VoicePolicy.decide(VoiceUtterance.MoveTo(KoreanVoiceCommandMatcher.normalize("버튼으로 이동")), two) is VoiceOutcome.Say)
        val named = VoicePolicy.decide(VoiceUtterance.MoveTo(KoreanVoiceCommandMatcher.normalize("동의 버튼으로 가")), two) as VoiceOutcome.Choose
        assertEquals("동의하기", named.option.buttonLabel)
        assertEquals(PromptOptionKind.MOVE_TO_BUTTON, named.option.kind)
    }

    // ---- 4. YouTube web ----

    private fun youtubeWeb(urlBar: Boolean, skipLabel: String) = listOfNotNull(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        if (urlBar) node("0/0", "android.widget.EditText", text = "m.youtube.com/watch?v=x", bounds = ScreenBounds(100, 60, 900, 180), viewId = "com.android.chrome:id/url_bar") else null,
        node("0/1", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/1/0", bounds = ScreenBounds(0, 200, 1080, 810)),
        node("0/1/0/0", text = "광고", bounds = ScreenBounds(20, 740, 80, 790)),
        node("0/1/0/1", "android.widget.Button", text = skipLabel, clickable = true, bounds = ScreenBounds(780, 640, 1080, 730)),
        node("0/1/1", "android.widget.Button", text = "구독", clickable = true, bounds = ScreenBounds(800, 900, 1060, 1000)),
    )

    @Test fun youTubeWebSkipIsFoundWithOrWithoutTheAddressBar() {
        for ((bar, label) in listOf(true to "건너뛰기", true to "광고 건너뛰기", false to "광고 건너뛰기", false to "Skip ad")) {
            val nodes = youtubeWeb(bar, label)
            assertTrue("$bar $label", VideoAdSkip.isVideoContext("com.android.chrome", nodes))
            val found = VideoAdSkip.scan(nodes, SCREEN).result as VideoAdSkipResult.Found
            assertEquals("node:0/1/0/1", found.candidate.id)
        }
        // Without the address bar, a bare "건너뛰기" is not enough to call it YouTube.
        assertFalse(VideoAdSkip.isVideoContext("com.android.chrome", youtubeWeb(false, "건너뛰기")))
    }
}
