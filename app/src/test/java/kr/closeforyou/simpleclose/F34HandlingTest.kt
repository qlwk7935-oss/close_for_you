package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.choice.RememberChoiceRules
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F34-2 (LG 11/2, 시험 앱 32종 정답지 비교): handling and classification. Device unverified. */
class F34HandlingTest {
    private val lg = ScreenBounds(0, 0, 1080, 2460)
    private val card = ScreenBounds(42, 900, 1038, 1560)

    private fun dialog(vararg children: Pair<String, Boolean>): List<kr.closeforyou.core.model.NodeSnapshot> =
        listOf(node("0", "android.widget.FrameLayout", bounds = card)) + children.mapIndexed { i, (text, button) ->
            val top = 940 + i * 120
            node("0/$i", if (button) "android.widget.Button" else "android.widget.TextView", text = text, clickable = button,
                bounds = ScreenBounds(90, top, 990, top + 110))
        }

    private fun popupOf(nodes: List<kr.closeforyou.core.model.NodeSnapshot>): SimplePopup =
        SimplePopupAnalysis.build(nodes, PopupRoot("0", card, PopupRootKind.DIALOG_WINDOW), "kr.closeforyou.popuptest", 1, lg, 1, 0)

    // P21/P22: B's scam check; the prompt says so and offers the close as a move only.
    @Test fun scamPopupsGetTheWarningPromptWithMovesOnly() {
        for (texts in listOf(listOf("축하합니다! 1등 당첨", "가상 경품을 받으려면 아래 버튼을 누르세요(시험용).", "경품 받기"),
            listOf("휴대폰이 감염되었습니다", "바이러스 2개가 발견되었습니다(시험용).", "지금 검사하기"))) {
            val popup = popupOf(dialog(texts[0] to false, texts[1] to false, texts[2] to true, "닫기" to true))
            assertTrue(RememberChoiceRules.suspiciousByB(popup.analysis))
            assertEquals(SimpleDecision.DenySuspicious, SimpleCloseDecider.decide(PopupClassification.UNKNOWN, true, popup.scan.candidates,
                HandlingPreset.CLOSE_ALL.autoCloseKinds()))
            // What askSuspicious builds.
            val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.USER_DECISION, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false)
                .copy(summary = PromptSummary.SUSPICIOUS, cannotPressForUser = false)
            val shown = SimplePromptOptions.adjust(planned, popup.scan.candidates.filter { it.role == ButtonRole.CLOSE })
            assertEquals(PromptSummary.SUSPICIOUS, shown.summary)
            assertFalse(shown.cannotPressForUser)
            assertTrue(shown.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS })
            assertEquals(listOf("닫기"), shown.options.filter { it.kind == PromptOptionKind.MOVE_TO_BUTTON }.map { it.buttonLabel })
            assertEquals(listOf(PromptOptionKind.MOVE_TO_BUTTON, PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN), shown.options.map { it.kind })
        }
    }

    // P23: nothing to press yet, then "닫기" after 3 s.
    @Test fun aLateCloseIsWaitedForNotAsked() {
        val waiting = popupOf(dialog("적립금 쿠폰 도착" to false, "3초 뒤 닫기 버튼이 나타납니다(시험용)." to false))
        val all = HandlingPreset.CLOSE_ALL.autoCloseKinds()
        assertTrue(LateCloseWait.applies(PopupClassification.COUPON, false, waiting.scan, all))
        assertTrue(LateCloseWait.applies(PopupClassification.UNKNOWN, false, waiting.scan, all))
        // Not in a preset that would not close it, not for a decision or a scam.
        assertFalse(LateCloseWait.applies(PopupClassification.COUPON, false, waiting.scan, HandlingPreset.CLOSE_ADS_ONLY.autoCloseKinds()))
        assertFalse(LateCloseWait.applies(PopupClassification.USER_DECISION, false, waiting.scan, all))
        assertFalse(LateCloseWait.applies(PopupClassification.COUPON, true, waiting.scan, all))
        // Something to press already (a risky button, an unnamed one): asked as before.
        val risky = popupOf(dialog("적립금 쿠폰 도착" to false, "결제하기" to true))
        assertFalse(LateCloseWait.applies(PopupClassification.COUPON, false, risky.scan, all))
        // The close came: closed by itself.
        val ready = popupOf(dialog("적립금 쿠폰 도착" to false, "3초 뒤 닫기 버튼이 나타납니다(시험용)." to false, "닫기" to true))
        assertFalse(LateCloseWait.applies(PopupClassification.COUPON, false, ready.scan, all))
        assertTrue(SimpleCloseDecider.decide(PopupClassification.COUPON, false, ready.scan.candidates, all) is SimpleDecision.AutoClose)
    }

    // P19: an install ad whose only button is "닫기".
    @Test fun anInstallAdWithOnlyACloseIsAsked() {
        val p19 = popupOf(dialog("신작 게임 광고" to false, "지금 다운로드 순위 1위. 시험용 광고입니다." to false, "닫기" to true))
        val pick = p19.scan.candidates.single()
        assertTrue(InstallAdLoneClose.suspected(p19, PopupClassification.ADVERTISEMENT, pick))
        // P01-like: an ad with its own "자세히 보기" and a close: the close is closed as before.
        val p01 = popupOf(dialog("가을 할인 쿠폰" to false, "앱 설치하면 쿠폰" to false, "자세히 보기" to true, "닫기" to true))
        assertFalse(InstallAdLoneClose.suspected(p01, PopupClassification.COUPON, p01.scan.candidates.single()))
        // No install words: a plain one-button ad is closed as before.
        val plain = popupOf(dialog("가을 할인 쿠폰" to false, "닫기" to true))
        assertFalse(InstallAdLoneClose.suspected(plain, PopupClassification.COUPON, plain.scan.candidates.single()))
        // A notice ("업데이트 설치 안내") is a notice.
        assertFalse(InstallAdLoneClose.suspected(p19, PopupClassification.NOTICE, pick))
    }

    // 9: B's UNKNOWN named by B's own wider word lists.
    @Test fun wordHintNamesUnknownPopups() {
        assertEquals(WordHint.COUPON, WordKindHint.of(PopupClassification.UNKNOWN, false, listOf("누구나 쿠폰 받고 할인 혜택"), 0))
        // B's wide decision words win: "첫 구매" keeps it UNKNOWN.
        assertEquals(WordHint.DENIED, WordKindHint.of(PopupClassification.UNKNOWN, false, listOf("누구나 쿠폰 받고 첫 구매 혜택"), 0))
        assertEquals(WordHint.NONE, WordKindHint.of(PopupClassification.COUPON, false, listOf("누구나 쿠폰 받고"), 0))
        assertEquals(WordHint.DENIED, WordKindHint.of(PopupClassification.UNKNOWN, false, listOf("쿠폰 받고 결제하기"), 0))
        assertEquals(WordHint.DENIED, WordKindHint.of(PopupClassification.UNKNOWN, false, listOf("쿠폰"), riskCount = 1))
        assertEquals(WordHint.DENIED, WordKindHint.of(PopupClassification.UNKNOWN, true, listOf("쿠폰"), 0))
        assertEquals(WordHint.NONE, WordKindHint.of(PopupClassification.UNKNOWN, false, listOf("안녕하세요"), 0))
        assertEquals(WordHint.NONE, WordKindHint.of(PopupClassification.UNKNOWN, false, emptyList(), 0))
    }

    // 9: the image hint is not only for dialog roots any more.
    @Test fun imageHintOnEveryPopupRootButAVideoCover() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.view.ViewGroup", bounds = ScreenBounds(90, 640, 990, 1700)),
            node("0/0/0", "android.widget.ImageView", bounds = ScreenBounds(90, 640, 990, 1500)),
            node("0/0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(90, 1560, 990, 1680)),
        )
        for (kind in listOf(PopupRootKind.DIM_WINDOW, PopupRootKind.MODAL_CARD, PopupRootKind.BOTTOM_SHEET, PopupRootKind.DIM_OVERLAY)) {
            val popup = SimplePopupAnalysis.build(nodes, PopupRoot("0/0", ScreenBounds(90, 640, 990, 1700), kind), "x", 1, lg, 1, 0)
            assertNull(kind.name, ImageDialogAdHint.check(popup, PopupClassification.UNKNOWN, suspicious = false))
        }
        val cover = SimplePopupAnalysis.build(nodes, PopupRoot("0/0", ScreenBounds(90, 640, 990, 1700), PopupRootKind.VIDEO_COVER), "x", 1, lg, 1, 0)
        assertEquals(AdHintMiss.NOT_DIALOG_ROOT, ImageDialogAdHint.check(cover, PopupClassification.UNKNOWN, suspicious = false))
    }

    // 8: a remembered popup is not held by the count; our press opening a new popup still is.
    @Test fun chainGuardLetsRememberedPopupsThrough() {
        val guard = ChainGuard()
        for (i in 0 until 3) guard.pressed("app", "p$i", i * 10_000L, automatic = true)
        assertEquals(ChainBlock.RATE_LIMIT, guard.check("app", "p9", 40_000L))
        assertNull(guard.check("app", "p9", 40_000L, remembered = true))
        assertEquals(ChainBlock.OPENED_BY_OUR_PRESS, guard.check("app", "p9", 20_500L, remembered = true))
    }
}
