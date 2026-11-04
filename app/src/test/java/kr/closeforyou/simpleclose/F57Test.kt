package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.R
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.settings.HandlingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F57: after a move nothing is asked for a while; the move goes to the popup taken to be in front, or is not offered. */
class F57Test {
    private val card = ScreenBounds(90, 600, 990, 1800)
    private val closeBack = ScreenBounds(540, 1500, 990, 1700)
    private val root = PopupRoot("0/0/1", card, PopupRootKind.FRONT_LAYER)
    private val cover = PopupRoot("0/0/2", ScreenBounds(100, 700, 980, 1750), PopupRootKind.FRONT_LAYER)
    private val hold = MoveHold("7:back", root, cover, startedAt = 1_000)

    private fun page() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", text = "강의 목록", bounds = ScreenBounds(0, 200, 1080, 2340)),
    )

    private fun back() = listOf(
        node("0/0/1", bounds = card),
        node("0/0/1/0", text = "다른 안내", bounds = ScreenBounds(90, 600, 990, 700)),
        node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = closeBack),
    )

    private fun look(nodes: List<NodeSnapshot>) = StackLook(nodes, SCREEN, emptyList(), 1, false, nodes.size, 6)

    // 18:18:34 MOVED_TO_BUTTON, 18:18:34.945 STACK_BLOCKED: the same popup read again 0.5 s later.
    @Test fun afterAMoveTheSamePopupIsNotAskedAgain() {
        assertEquals(HoldStep.KEEP, MoveHoldRules.step(hold, 1_544, 0, "7:back", root, coverPresent = true))
        // Well past the old 10 s cooldown: finding the button and a double tap.
        assertEquals(HoldStep.KEEP, MoveHoldRules.step(hold, 25_000, 0, "7:back", root, coverPresent = true))
        // Unknown about the cover: stays quiet, never presses on a guess.
        assertEquals(HoldStep.KEEP, MoveHoldRules.step(hold, 2_000, 0, "7:back", root, coverPresent = null))
        // The cover itself read as the popup: still the same two.
        assertEquals(HoldStep.KEEP, MoveHoldRules.step(hold, 2_000, 0, "7:front", cover, coverPresent = true))
    }

    @Test fun touchesGiveMoreTimeUpToTheLimit() {
        val q = MoveHoldRules.QUIET_MILLIS
        assertEquals(HoldStep.END_TIMEOUT, MoveHoldRules.step(hold, 1_000 + q, 0, "7:back", root, true))
        assertEquals(HoldStep.KEEP, MoveHoldRules.step(hold, 1_000 + q, 20_000, "7:back", root, true))
        assertEquals(HoldStep.END_TIMEOUT, MoveHoldRules.step(hold, 1_000 + MoveHoldRules.MAX_MILLIS, 1_000 + MoveHoldRules.MAX_MILLIS - 1, "7:back", root, true))
        assertTrue(q > SimpleCloseEngineFeature.SAME_POPUP_COOLDOWN_MILLIS)
    }

    // D: the person closed the front popup: the one behind is judged again.
    @Test fun aRealChangeEndsIt() {
        assertEquals(HoldStep.END_COVER_GONE, MoveHoldRules.step(hold, 2_000, 0, "7:back", root, coverPresent = false))
        assertEquals(HoldStep.END_OTHER_POPUP, MoveHoldRules.step(hold, 2_000, 0, "7:new", PopupRoot("0/0/9", ScreenBounds(0, 0, 500, 500), PopupRootKind.FRONT_LAYER), true))
        assertEquals(HoldStep.END_POPUP_GONE, MoveHoldRules.stepNoRoot(hold, 2_000, 0, rootPresent = false))
        assertEquals(HoldStep.KEEP, MoveHoldRules.stepNoRoot(hold, 2_000, 0, rootPresent = true))
        assertEquals(HoldStep.KEEP, MoveHoldRules.stepNoRoot(hold, 2_000, 0, rootPresent = null))
        assertEquals(HoldStep.END_TIMEOUT, MoveHoldRules.stepNoRoot(hold, 1_000 + MoveHoldRules.QUIET_MILLIS, 0, rootPresent = true))
    }

    @Test fun aMoveGoesToTheCoversNamedCloseNeverToTheStoppedButton() {
        val front = listOf(
            node("0/0/2", bounds = cover.bounds),
            node("0/0/2/0", text = "LMS 앱 업데이트 공지", bounds = ScreenBounds(100, 700, 980, 900)),
            node("0/0/2/1", "android.widget.Button", text = "오늘 하루 보지 않기", clickable = true, bounds = ScreenBounds(100, 1600, 500, 1700)),
            node("0/0/2/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1600, 980, 1700)),
        )
        val hit = PopupStack.hit(closeBack, root, look(page() + back() + front))!!
        assertEquals(StackBlock.COVER_LATER, hit.block)
        val target = hit.front!!
        assertEquals("0/0/2/2", target.path)
        assertEquals(FrontKind.CLOSE, target.kind)
        assertEquals("0/0/2", target.cover.path)
        assertFalse(target.path.startsWith(root.path))
    }

    @Test fun withoutACloseTheMoveGoesToItsFirstReadableNode() {
        val front = listOf(
            node("0/0/2", bounds = cover.bounds),
            node("0/0/2/0", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(100, 700, 980, 800)),
            node("0/0/2/1", text = "LMS 앱 업데이트 공지", bounds = ScreenBounds(100, 900, 980, 1000)),
        )
        val target = PopupStack.hit(closeBack, root, look(page() + back() + front))!!.front!!
        assertEquals("0/0/2/1", target.path)
        assertEquals(FrontKind.FIRST, target.kind)
        // Only clickables, one of them risky: the other one, never the risky one.
        val buttons = listOf(
            node("0/0/2", bounds = cover.bounds),
            node("0/0/2/0", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(100, 700, 980, 800)),
            node("0/0/2/1", "android.widget.Button", text = "자세히", clickable = true, bounds = ScreenBounds(100, 900, 980, 1000)),
        )
        assertEquals("0/0/2/1", PopupStack.hit(closeBack, root, look(page() + back() + buttons))!!.front!!.path)
    }

    // C: another popup earlier in the page may be in front or behind: not known, no move.
    @Test fun noFrontKnownMeansNoMove() {
        val dialog = listOf(
            node("0/0/0", "android.app.AlertDialog", bounds = card),
            node("0/0/0/0", text = "공지", bounds = ScreenBounds(90, 600, 990, 700)),
            node("0/0/0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = closeBack),
        )
        val nodes = listOf(node("0", "android.widget.FrameLayout", bounds = SCREEN), node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340))) + dialog + back()
        val hit = PopupStack.hit(closeBack, root, look(nodes))!!
        assertEquals(StackBlock.POPUP_OVERLAP, hit.block)
        assertNull(hit.front)
        // A window above is sure, but its tree is not read here.
        val windows = listOf(WindowShape(1, WindowKind.APPLICATION, 1, true, SCREEN, SCREEN), WindowShape(2, WindowKind.APPLICATION, 2, true, card, card))
        assertNull(PopupStack.hit(closeBack, root, StackLook(emptyList(), SCREEN, windows, 1, false, 0, 1))!!.front)

        val popup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.UNKNOWN, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        val shown = SimplePromptOptions.overlapped(planned, frontKnown = false)
        assertEquals(PromptSummary.POPUPS_OVERLAP_NO_MOVE, shown.summary)
        assertEquals(listOf(PromptOptionKind.KEEP_OPEN), shown.options.map { it.kind })
    }

    @Test fun theMoveOptionIsOneFixedLabelNotTheStoppedButton() {
        val popup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.UNKNOWN, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        val move = SimplePromptOptions.overlapped(planned, frontKnown = true).options.single { it.kind == PromptOptionKind.MOVE_TO_BUTTON }
        assertEquals(SimplePromptOptions.FRONT_ID, move.candidateId)
        assertEquals(R.string.ui_choice_move_to_front, move.labelRes)
        assertNull(move.buttonLabel)
    }

    // E: an ordinary prompt's moves are built as before.
    @Test fun anOrdinaryMoveIsUnchanged() {
        val risky = CloseCandidate("node:0/1", "0/1", null, "결제하기", closeBack, EvidenceLevel.E2_LABELED_NODE, ButtonRole.USER_DECISION)
        val popup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.UNKNOWN, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        val move = SimplePromptOptions.adjust(planned, listOf(risky)).options.single { it.kind == PromptOptionKind.MOVE_TO_BUTTON }
        assertEquals("node:0/1", move.candidateId)
        assertEquals("결제하기", move.buttonLabel)
        assertNull(move.labelRes)
        assertNotNull(planned.options.firstOrNull { it.kind == PromptOptionKind.PRESS_CANDIDATE })
    }

    @Test fun theLinesHoldBoundsAndCodesOnly() {
        val target = FrontTarget(cover, "0/0/2/2", ScreenBounds(540, 1600, 980, 1700), FrontKind.CLOSE)
        assertEquals("OVERLAP_MOVE target=CLOSE cover=100,700,980,1750 to=540,1600,980,1700 moved=true", PopupStack.moveLine(target, true))
        assertEquals("MOVE_HOLD_END reason=END_COVER_GONE afterMs=4200", MoveHoldRules.line(HoldStep.END_COVER_GONE, 4_200))
    }
}
