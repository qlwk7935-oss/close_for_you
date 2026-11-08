package kr.closeforyou.simpleclose

import kr.closeforyou.R
import kr.closeforyou.core.contract.VoiceCommand
import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.ButtonNameDisplay
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.choice.textRes
import kr.closeforyou.ui.voice.command.VoiceOutcome
import kr.closeforyou.ui.voice.command.VoicePolicy
import kr.closeforyou.ui.voice.command.VoiceUtterance
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F61 (동결 빌드): F59's front press switched off; button names shown without icon glyphs, judged as read. */
class F61Test {
    private val card = ScreenBounds(90, 600, 990, 1800)
    private val closeBack = ScreenBounds(540, 1500, 990, 1700)
    private val closeFront = ScreenBounds(540, 1600, 980, 1700)
    private val root = PopupRoot("0/0/1", card, PopupRootKind.FRONT_LAYER)
    private val cover = PopupRoot("0/0/2", ScreenBounds(100, 700, 980, 1750), PopupRootKind.FRONT_LAYER)

    private fun back() = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/1", bounds = card),
        node("0/0/1/0", text = "다른 안내", bounds = ScreenBounds(90, 600, 990, 700)),
        node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = closeBack),
    )

    private fun front() = listOf(
        node("0/0/2", bounds = cover.bounds),
        node("0/0/2/0", text = "LMS 앱 업데이트 공지", bounds = ScreenBounds(100, 700, 980, 900)),
        node("0/0/2/1", "android.widget.Button", text = "닫기", clickable = true, bounds = closeFront),
    )

    private fun target() = FrontTarget(cover, "0/0/2/1", closeFront, FrontKind.CLOSE)

    private fun seen(suspicious: Boolean = false) =
        FrontSeen(SimplePopupAnalysis.build(front(), cover, "p", 7, SCREEN, 1L, 0L), PopupClassification.NOTICE, suspicious)

    private fun plannedBack() = ChoicePromptPlanner.plan(
        SimplePopupAnalysis.build(back(), root, "p", 7, SCREEN, 1L, 0L).analysis, PopupClassification.NOTICE,
        labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first(),
    )

    @Test fun theFrozenBuildHasTheFrontPressOff() {
        assertFalse(SimpleCloseEngineFeature.OVERLAP_FRONT_PRESS)
    }

    // Every check passed, and still no press: the line says switched off, never OFFERED.
    @Test fun offNoPressAndNoOfferedLine() {
        assertNull(OverlapPressRules.miss(target(), seen()))
        val offer = OverlapPressRules.offerOf(target(), seen(), miss = null)
        assertNull(offer.press)
        assertNull(offer.scam)
        val line = OverlapPressRules.offerLine(offer, miss = null)
        assertEquals(OverlapPressRules.SWITCHED_OFF_LINE, line)
        assertFalse(line.startsWith("OVERLAP_PRESS_OFFERED"))
        // With a reason of its own, that reason (a code) is logged, still not OFFERED.
        val decision = OverlapPressRules.offerOf(target(), seen(), FrontPressMiss.DECISION)
        assertEquals("OVERLAP_PRESS_NOT_OFFERED reason=DECISION", OverlapPressRules.offerLine(decision, FrontPressMiss.DECISION))
    }

    @Test fun offAScamInFrontIsStillWarnedOf() {
        val scam = seen(suspicious = true)
        val offer = OverlapPressRules.offerOf(target(), scam, FrontPressMiss.SCAM)
        assertNull(offer.press)
        assertTrue(offer.scam === scam.popup)
        val shown = SimplePromptOptions.overlapped(plannedBack(), frontKnown = true, frontScam = true, frontKind = FrontKind.CLOSE)
        assertEquals(PromptSummary.POPUPS_OVERLAP_FRONT_SCAM, shown.summary)
        assertTrue(OverlapPressRules.readTarget(SimplePopupAnalysis.build(back(), root, "p", 7, SCREEN, 1L, 0L), offer.press, offer.scam) === scam.popup)
    }

    @Test fun onItIsF59() {
        val offer = OverlapPressRules.offerOf(target(), seen(), miss = null, pressOn = true)
        assertNotNull(offer.press)
        assertTrue(OverlapPressRules.offerLine(offer, null, pressOn = true).startsWith("OVERLAP_PRESS_OFFERED"))
    }

    // Even handed a name, the prompt is F57's: the move, the read, keep; the summary does not promise a press.
    @Test fun offThePromptHasNoFrontPress() {
        val shown = SimplePromptOptions.overlapped(plannedBack(), frontKnown = true, pressLabel = "닫기")
        assertTrue(shown.options.none { it.candidateId == SimplePromptOptions.FRONT_PRESS_ID })
        assertTrue(shown.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS })
        assertEquals(PromptSummary.POPUPS_OVERLAP, shown.summary)
        assertEquals(R.string.ui_choice_summary_overlap, shown.summary.textRes())
        assertNotNull(shown.options.singleOrNull { it.kind == PromptOptionKind.MOVE_TO_BUTTON && it.candidateId == SimplePromptOptions.FRONT_ID })
        assertNull(shown.options.single { it.kind == PromptOptionKind.READ_CONTENT }.labelRes)
        assertNotNull(shown.options.singleOrNull { it.kind == PromptOptionKind.KEEP_OPEN })
        assertEquals(VoiceOutcome.Say(R.string.ui_choice_cannot_press), VoicePolicy.decide(VoiceUtterance.Command(VoiceCommand.CLOSE), shown))
        // The excerpt is the ordinary prompt's (no press to take it from the front).
        assertEquals("다른 안내", OverlapPressRules.excerptSource("다른 안내", PopupClassification.NOTICE, false, null))
    }

    @Test fun iconGlyphsAreDroppedFromTheShownName() {
        assertEquals("닫기", ButtonNameDisplay.shown("\uE5CD닫기"))
        // LMS (우진 SM-F966N): the close read as "\uEA02닫기".
        assertEquals("닫기", ButtonNameDisplay.shown("\uEA02닫기"))
        assertEquals("닫기", ButtonNameDisplay.shown("\uE5CD 닫기 \uF8FF"))
        // Planes 15 and 16 (surrogate pairs).
        assertEquals("닫기", ButtonNameDisplay.shown("\uDB80\uDC00닫기\uDBFF\uDFFD"))
        assertEquals("광고 닫기", ButtonNameDisplay.shown("  광고 \uE000  닫기  "))
        assertEquals("닫기", ButtonNameDisplay.shown("닫기"))
        assertNull(ButtonNameDisplay.shown("\uE5CD"))
        assertNull(ButtonNameDisplay.shown("  "))
        assertNull(ButtonNameDisplay.shown(null))
        // Neighbours of the ranges stay.
        assertEquals("\uF900닫기", ButtonNameDisplay.shown("\uF900닫기"))
        // The role-name shortening sees the shown name: "\uE5CD닫기" is kept as "닫기", not turned into the role word.
        assertNull(ButtonNameDisplay.roleNameRes(ButtonNameDisplay.shown("\uE5CD닫기"), ButtonRole.CLOSE))
    }

    // The label as read goes on to every judgement: the prompt keeps it, failed names compare on it.
    @Test fun theJudgedLabelIsUnchanged() {
        val raw = "\uE5CD닫기"
        val shown = SimplePromptOptions.overlapped(plannedBack(), frontKnown = true, pressLabel = raw, pressOn = true)
        assertEquals(raw, shown.options.first { it.candidateId == SimplePromptOptions.FRONT_PRESS_ID }.buttonLabel)
        val press = PromptOption(PromptOptionKind.PRESS_CANDIDATE, candidateId = "c1", candidateRole = ButtonRole.CLOSE, buttonLabel = raw)
        val prompt = plannedBack().copy(options = persistentListOf(press))
        val adjusted = SimplePromptOptions.adjust(prompt, emptyList(), failedLabels = setOf(ButtonLabelRules.normalize(raw)))
        assertTrue(adjusted.options.none { it.candidateId == "c1" })
        assertEquals(raw, SimplePromptOptions.adjust(prompt, emptyList()).options.single().buttonLabel)
    }
}
