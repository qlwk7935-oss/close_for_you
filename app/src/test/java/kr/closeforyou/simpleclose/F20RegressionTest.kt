package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F20 (LG 11/1 06:22, `6d797fe`): the 11번가 sheet pressed by 강력 모드, "closed" too early and pressed again;
 * the probe missed it; decision popups only explained; no video codes. Plus the cases that worked on the
 * device (옥션, 올리브영 sheet, Chrome dialog), kept as they are.
 */
class F20RegressionTest {
    private class Tree(nodes: List<NodeSnapshot>) : ProbeTree<NodeSnapshot> {
        private val byPath = nodes.associateBy { it.path }
        override fun info(node: NodeSnapshot) = ProbeNodeInfo(node.className, node.bounds, node.clickable, !node.text.isNullOrBlank())
        override fun childCount(node: NodeSnapshot) = generateSequence(0) { it + 1 }.takeWhile { byPath.containsKey("${node.path}/$it") }.count()
        override fun child(node: NodeSnapshot, index: Int) = byPath["${node.path}/$index"]
        override fun release(node: NodeSnapshot) = Unit
    }

    private fun popupOf(nodes: List<NodeSnapshot>): SimplePopup =
        SimplePopupAnalysis.build(nodes, PopupRootFinder.find(nodes, SCREEN)!!, "com.android.chrome", 7, SCREEN, 1L, 0L)

    /** The 11번가 page as on the device: the sheet, then a zero-height footer holding hundreds of zero-size links. */
    private fun elevenStreetWithFooter(): List<NodeSnapshot> {
        val sheet = SimpleNodes.elevenStreet().filter { it.path != "0/1/5" && it.path != "0/1/6" }
        val footer = listOf(node("0/1/5", bounds = ScreenBounds(0, 2337, 1080, 2337))) +
            (0 until 400).map { node("0/1/5/$it", text = "바닥글 링크 $it", clickable = true, bounds = ScreenBounds(0, 2337, 200, 2337)) }
        return sheet + footer
    }

    // ---- (d) the probe finds the sheet before the footer ----

    @Test fun theProbeFindsTheSheetBeforeWalkingAZeroHeightFooter() {
        val nodes = elevenStreetWithFooter()
        val probe = PopupProbe.find(nodes.first { it.path == "0" }, Tree(nodes), SCREEN, 200, 64)
        assertEquals("0/1/4", probe.path)
        assertEquals(ProbeHitKind.DIALOG, probe.kind)
        assertTrue(probe.visited < 20)
    }

    @Test fun aModalInsideAZeroSizeWrapperIsStillFound() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", bounds = ScreenBounds(0, 0, 0, 0)),
            node("0/0/0", "android.app.AlertDialog", bounds = ScreenBounds(90, 600, 990, 1600)),
            node("0/1", text = "본문", bounds = ScreenBounds(0, 200, 1080, 2340)),
        )
        assertEquals("0/0/0", PopupProbe.find(nodes.first(), Tree(nodes), SCREEN, 200, 64).path)
    }

    // ---- (a) 강력 모드 presses what the prompt offers first ----

    @Test fun strongModePressesThePromptsFirstButton() {
        for (nodes in listOf(SimpleNodes.elevenStreet(), SimpleNodes.oliveYoung(), SimpleNodes.auction())) {
            val popup = popupOf(nodes)
            val pick = StrongCloseRules.pick(popup.scan.candidates)!!
            val prompt = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.UNKNOWN, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false)
            assertEquals(pick.id, prompt.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE }.candidateId)
        }
        assertEquals("모바일 웹으로 볼래요", StrongCloseRules.pick(popupOf(SimpleNodes.elevenStreet()).scan.candidates)!!.label)
        assertEquals("닫기", StrongCloseRules.pick(popupOf(SimpleNodes.oliveYoung()).scan.candidates)!!.label)
    }

    @Test fun theSheetsOwnCloseBeatsStayingOnTheWebAndNeverTheAppButton() {
        val popup = popupOf(elevenStreetWithFooter())
        assertEquals(listOf("모바일 웹으로 볼래요", "앱설치 안내 레이어 닫기"), popup.scan.candidates.map { it.label })
        assertTrue(popup.scan.candidates.none { it.label == "11번가 앱에서 확인" })
    }

    // ---- (b) strict close: the same popup again is not a close ----

    @Test fun theSameSheetFoundAgainIsAReappearanceAndAnotherPopupIsNot() {
        val before = popupOf(SimpleNodes.elevenStreet())
        val memory = PopupMemory.of(before)
        // Moved a little (animating) and at another path: still the same sheet.
        val moved = SimpleNodes.elevenStreet().map { n ->
            if (n.path.startsWith("0/1/4")) n.copy(path = n.path.replaceFirst("0/1/4", "0/1/7"), bounds = n.bounds.copy(top = n.bounds.top + 40, bottom = n.bounds.bottom + 3)) else n
        }
        val again = PopupRootFinder.find(moved, SCREEN)!!
        assertTrue(memory.reappeared(again, moved))
        val other = SimpleNodes.auction()
        assertFalse(memory.reappeared(PopupRootFinder.find(other, SCREEN)!!, other))
    }

    // ---- (c) after a failed automatic close: ask, without the failed button ----

    @Test fun theFailurePromptSaysSoAndDropsOnlyTheFailedButton() {
        val popup = popupOf(SimpleNodes.elevenStreet())
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.NOTICE, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false)
        val failed = popup.scan.candidates.first()
        val prompt = SimplePromptOptions.adjust(planned, popup.scan.riskButtons, setOf(kr.closeforyou.core.model.ButtonLabelRules.normalize(failed.label!!)), failedNow = true)
        assertEquals(PromptSummary.AUTO_CLOSE_FAILED, prompt.summary)
        val presses = prompt.options.filter { it.kind == PromptOptionKind.PRESS_CANDIDATE }
        // F21: the failed button is gone altogether (no move either); the next one is the press.
        assertEquals(listOf("앱설치 안내 레이어 닫기"), presses.map { it.buttonLabel })
        assertTrue(prompt.options.none { it.candidateId == failed.id })
        assertTrue(prompt.options.any { it.kind == PromptOptionKind.KEEP_OPEN })
        assertFalse(prompt.briefQuestion)
    }

    // ---- Problem 2: a decision popup is asked, with moves only ----

    @Test fun aDecisionPopupIsAskedWithMovesAndNothingToPress() {
        val popup = popupOf(SimpleNodes.chromePermission())
        assertEquals(listOf("허용"), popup.scan.riskButtons.map { it.label })
        assertTrue(popup.scan.riskButtons.all { it.role == ButtonRole.USER_DECISION })
        assertTrue(popup.scan.candidates.none { it.label == "허용" })
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.USER_DECISION, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false)
        val prompt = SimplePromptOptions.adjust(planned, popup.scan.riskButtons)
        assertTrue(prompt.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS })
        assertEquals(listOf("허용"), prompt.options.filter { it.kind == PromptOptionKind.MOVE_TO_BUTTON }.map { it.buttonLabel })
        assertTrue(prompt.options.any { it.kind == PromptOptionKind.READ_CONTENT })
        assertTrue(prompt.options.any { it.kind == PromptOptionKind.KEEP_OPEN })
        assertEquals(PromptSummary.NEEDS_REVIEW, prompt.summary)
        // The move is an option of this prompt, so the overlay can select it.
        val move = prompt.options.first { it.kind == PromptOptionKind.MOVE_TO_BUTTON }
        assertTrue(ChoicePromptPlanner.select(prompt, move) is kr.closeforyou.ui.voice.choice.PromptSelection.MoveToButton)
    }

    @Test fun placeIsAThirdOfTheScreen() {
        assertEquals(ScreenPlace.TOP, SimplePromptOptions.place(ScreenBounds(0, 100, 10, 200), SCREEN))
        assertEquals(ScreenPlace.MIDDLE, SimplePromptOptions.place(ScreenBounds(0, 1100, 10, 1200), SCREEN))
        assertEquals(ScreenPlace.BOTTOM, SimplePromptOptions.place(ScreenBounds(0, 2100, 1080, 2300), SCREEN))
    }

    // ---- Kept from the device: 옥션, 올리브영 sheet, Chrome dialog ----

    @Test fun whatWorkedOnTheDeviceStillWorks() {
        val all = HandlingPreset.CLOSE_ALL.autoCloseKinds()
        fun strong(nodes: List<NodeSnapshot>, kind: PopupClassification): SimpleDecision {
            val popup = popupOf(nodes)
            return SimpleCloseDecider.decide(kind, false, popup.scan.candidates, all, popup.scan.forbiddenCount,
                unknownEnabled = true, riskCount = popup.scan.riskCount, strongEnabled = true)
        }
        assertEquals("닫기", (strong(SimpleNodes.auction(), PopupClassification.ADVERTISEMENT) as SimpleDecision.AutoClose).candidate.label)
        // An app's own sheet over its content (올리브영 app, 11/1 06:2x: DETECTED_BOTTOM_SHEET, UNKNOWN, closed).
        val oliveSheet = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.widget.FrameLayout", bounds = ScreenBounds(0, 0, 1080, 2340)),
            node("0/0/0", text = "오늘의 특가", bounds = ScreenBounds(0, 200, 1080, 400)),
            node("0/1", "android.widget.LinearLayout", bounds = ScreenBounds(0, 1300, 1080, 2340)),
            node("0/1/0", "android.widget.TextView", text = "이번 주 혜택 모음", bounds = ScreenBounds(40, 1350, 1040, 1450)),
            node("0/1/1", "android.widget.TextView", text = "오늘 그만 보기", clickable = true, bounds = ScreenBounds(40, 2200, 540, 2300)),
            node("0/1/2", "android.widget.TextView", text = "닫기", clickable = true, bounds = ScreenBounds(540, 2200, 1040, 2300)),
        )
        assertEquals(PopupRootKind.BOTTOM_SHEET, PopupRootFinder.find(oliveSheet, SCREEN)!!.kind)
        assertEquals("닫기", (strong(oliveSheet, PopupClassification.UNKNOWN) as SimpleDecision.AutoClose).candidate.label)
        // A Chrome link's own dialog.
        val chromeDialog = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/0/0", "android.app.AlertDialog", bounds = ScreenBounds(90, 700, 990, 1500)),
            node("0/0/0/0", text = "새 소식이 있어요", bounds = ScreenBounds(90, 760, 990, 900)),
            node("0/0/0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(90, 1350, 990, 1500)),
        )
        assertTrue(strong(chromeDialog, PopupClassification.UNKNOWN) is SimpleDecision.AutoClose)
    }

    // ---- Problem 3: video diagnostics ----

    private fun youtube(vararg extra: NodeSnapshot) = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.widget.FrameLayout", bounds = ScreenBounds(0, 0, 1080, 610)),
        node("0/1", "android.widget.Button", text = "구독", clickable = true, bounds = ScreenBounds(800, 700, 1060, 800)),
    ) + extra

    @Test fun aSkipButtonNamedOnlyByItsViewIdIsFound() {
        val nodes = youtube(node("0/0/0", clickable = true, bounds = ScreenBounds(780, 440, 1080, 530), viewId = "com.google.android.youtube:id/skip_ad_button"))
        val scan = VideoAdSkip.scan(nodes, SCREEN)
        assertEquals(VideoAdDiag.FOUND, scan.diag)
        assertEquals("node:0/0/0", (scan.result as VideoAdSkipResult.Found).candidate.id)
    }

    @Test fun eachReasonForNotPressingHasItsOwnCode() {
        val marker = node("0/0/1", text = "광고", bounds = ScreenBounds(20, 540, 80, 590))
        assertEquals(VideoAdDiag.NO_CANDIDATE, VideoAdSkip.scan(youtube(marker), SCREEN).diag)
        assertEquals(VideoAdDiag.NOT_CLICKABLE, VideoAdSkip.scan(youtube(marker, node("0/0/0", text = "광고 건너뛰기", bounds = ScreenBounds(780, 440, 1080, 530))), SCREEN).diag)
        assertEquals(VideoAdDiag.OFFSCREEN, VideoAdSkip.scan(youtube(marker, node("0/0/0", text = "광고 건너뛰기", clickable = true, bounds = ScreenBounds(780, 2400, 1080, 2500))), SCREEN).diag)
        assertEquals(VideoAdDiag.LABEL_UNNAMED, VideoAdSkip.scan(youtube(node("0/0/0", bounds = ScreenBounds(780, 440, 1080, 530), viewId = "com.google.android.youtube:id/skip_ad_button")), SCREEN).diag)
        assertEquals(VideoAdDiag.NO_AD_MARKER, VideoAdSkip.scan(youtube(node("0/2", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(780, 2100, 1060, 2250))), SCREEN).diag)
        assertEquals(VideoAdDiag.NONE, VideoAdSkip.scan(youtube(), SCREEN).diag)
        assertTrue(VideoAdSkip.scan(youtube(marker), SCREEN).adMarker)
        assertNull(VideoAdSkip.scan(youtube(marker), SCREEN).result)
    }
}
