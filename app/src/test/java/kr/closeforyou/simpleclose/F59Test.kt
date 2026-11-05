package kr.closeforyou.simpleclose

import kotlinx.coroutines.runBlocking
import kr.closeforyou.R
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.contract.VoiceCommand
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.SafetyReason
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.choice.PromptSummary
import kr.closeforyou.ui.voice.choice.textRes
import kr.closeforyou.ui.voice.command.VoiceOutcome
import kr.closeforyou.ui.voice.command.VoicePolicy
import kr.closeforyou.ui.voice.command.VoiceUtterance
import kr.closeforyou.ui.voice.settings.HandlingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F59: the overlap prompt may press the named close of the popup taken to be in front, once, when nothing about it is doubtful. */
class F59Test {
    private val card = ScreenBounds(90, 600, 990, 1800)
    private val closeBack = ScreenBounds(540, 1500, 990, 1700)
    private val closeFront = ScreenBounds(540, 1600, 980, 1700)
    private val root = PopupRoot("0/0/1", card, PopupRootKind.FRONT_LAYER)
    private val cover = PopupRoot("0/0/2", ScreenBounds(100, 700, 980, 1750), PopupRootKind.FRONT_LAYER)

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

    private fun front(closeLabel: String? = "닫기", vararg more: NodeSnapshot) = listOf(
        node("0/0/2", bounds = cover.bounds),
        node("0/0/2/0", text = "LMS 앱 업데이트 공지", bounds = ScreenBounds(100, 700, 980, 900)),
        node("0/0/2/1", "android.widget.Button", text = closeLabel, clickable = true, bounds = closeFront),
    ) + more

    private fun look(nodes: List<NodeSnapshot>) = StackLook(nodes, SCREEN, emptyList(), 1, false, nodes.size, 6)

    private fun target(path: String = "0/0/2/1", bounds: ScreenBounds = closeFront, kind: FrontKind = FrontKind.CLOSE, on: PopupRoot = cover) =
        FrontTarget(on, path, bounds, kind)

    private fun seen(nodes: List<NodeSnapshot>, kind: PopupClassification = PopupClassification.NOTICE, suspicious: Boolean = false) =
        FrontSeen(SimplePopupAnalysis.build(nodes, cover, "p", 7, SCREEN, 1L, 0L), kind, suspicious)

    @Test fun aPlainCloseInAKnownPopupIsOffered() {
        assertNull(OverlapPressRules.miss(target(), seen(front())))
        val press = OverlapPressRules.offer(target(), seen(front()))!!
        assertEquals("0/0/2/1", press.candidate.nodePath)
        // F57's own path gets here: the target found over the stopped popup.
        val hit = PopupStack.hit(closeBack, root, look(page() + back() + front()))!!
        assertEquals(FrontKind.CLOSE, hit.front!!.kind)
        assertNull(OverlapPressRules.miss(hit.front, seen(front())))
    }

    @Test fun noFrontOrNoNamedCloseIsNotOffered() {
        assertEquals(FrontPressMiss.NO_FRONT, OverlapPressRules.miss(null, seen(front())))
        assertEquals(FrontPressMiss.NOT_A_CLOSE, OverlapPressRules.miss(target(kind = FrontKind.FIRST), seen(front())))
        assertEquals(FrontPressMiss.UNREAD, OverlapPressRules.miss(target(), null))
        val banner = PopupRoot("0/0/2", cover.bounds, PopupRootKind.BOTTOM_BANNER)
        assertEquals(FrontPressMiss.COVER_KIND, OverlapPressRules.miss(target(on = banner), seen(front())))
    }

    // An unnamed X is never a candidate: nothing to pick, and frontTarget never calls it a close.
    @Test fun anUnnamedXIsNotOffered() {
        val nodes = page() + back() + front(closeLabel = null)
        val hit = PopupStack.hit(closeBack, root, look(nodes))!!
        assertEquals(FrontKind.FIRST, hit.front!!.kind)
        assertEquals(FrontPressMiss.NOT_A_CLOSE, OverlapPressRules.miss(hit.front, seen(front(closeLabel = null))))
        assertEquals(FrontPressMiss.TARGET_CHANGED, OverlapPressRules.miss(target(), seen(front(closeLabel = null))))
    }

    // ButtonLabelRules: "오늘 하루 보지 않기" closes and also changes something; the engine's own rules call it a close, the shared ones do not.
    @Test fun aCloseThatDoesMoreIsNotOffered() {
        assertEquals(FrontPressMiss.LABEL_NOT_PLAIN_CLOSE, OverlapPressRules.miss(target(), seen(front(closeLabel = "오늘 하루 보지 않기"))))
    }

    @Test fun whatTheExistingJudgementsRefuseIsNotOffered() {
        assertEquals(FrontPressMiss.SCAM, OverlapPressRules.miss(target(), seen(front(), suspicious = true)))
        assertEquals(FrontPressMiss.DECISION, OverlapPressRules.miss(target(), seen(front(), PopupClassification.USER_DECISION)))
        assertEquals(FrontPressMiss.UNKNOWN_KIND, OverlapPressRules.miss(target(), seen(front(), PopupClassification.UNKNOWN)))
    }

    @Test fun aRiskyButtonBesideTheCloseIsNotOffered() {
        val pay = node("0/0/2/2", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(100, 1600, 500, 1700))
        assertEquals(FrontPressMiss.RISK_BUTTON, OverlapPressRules.miss(target(), seen(front("닫기", pay))))
        // A promotion's purchase button stops a popup being asked elsewhere; here it still counts.
        val buy = node("0/0/2/2", "android.widget.Button", text = "지금 구매하기", clickable = true, bounds = ScreenBounds(100, 1600, 500, 1700))
        assertEquals(FrontPressMiss.RISK_BUTTON, OverlapPressRules.miss(target(), seen(front("닫기", buy), PopupClassification.ADVERTISEMENT)))
    }

    @Test fun theStoppedPopupsButtonIsNeverTheTarget() {
        val hit = PopupStack.hit(closeBack, root, look(page() + back() + front()))!!
        val press = OverlapPressRules.offer(hit.front!!, seen(front()))!!
        assertFalse(press.candidate.nodePath!!.startsWith(root.path))
        // A target inside the stopped popup does not match what the front popup's own scan picks.
        assertEquals(FrontPressMiss.TARGET_CHANGED, OverlapPressRules.miss(target(path = "0/0/1/1", bounds = closeBack), seen(front())))
    }

    @Test fun theRecheckStopsTheClickWhenAnythingChanged() {
        val press = FrontPress(target(), seen(front()).popup, OverlapPressRules.offer(target(), seen(front()))!!.candidate, PopupClassification.NOTICE)
        assertNull(OverlapPressRules.recheck(press, seen(front())))
        // Gone, or not read whole.
        assertEquals(FrontPressMiss.UNREAD, OverlapPressRules.recheck(press, null))
        // Moved.
        val moved = listOf(front()[0], front()[1], node("0/0/2/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(540, 1500, 980, 1600)))
        assertEquals(FrontPressMiss.TARGET_CHANGED, OverlapPressRules.recheck(press, seen(moved)))
        // Another path.
        val path = listOf(front()[0], front()[1], node("0/0/2/2", "android.widget.Button", text = "닫기", clickable = true, bounds = closeFront))
        assertEquals(FrontPressMiss.TARGET_CHANGED, OverlapPressRules.recheck(press, seen(path)))
        // Another name at the same place.
        assertEquals(FrontPressMiss.LABEL_CHANGED, OverlapPressRules.recheck(press, seen(front(closeLabel = "공지 닫기"))))
        assertEquals(FrontPressMiss.LABEL_NOT_PLAIN_CLOSE, OverlapPressRules.recheck(press, seen(front(closeLabel = "오늘 하루 보지 않기"))))
        // Became a decision, or grew a risky button.
        assertEquals(FrontPressMiss.DECISION, OverlapPressRules.recheck(press, seen(front(), PopupClassification.USER_DECISION)))
        val pay = node("0/0/2/2", "android.widget.Button", text = "결제하기", clickable = true, bounds = ScreenBounds(100, 1600, 500, 1700))
        assertEquals(FrontPressMiss.RISK_BUTTON, OverlapPressRules.recheck(press, seen(front("닫기", pay))))
    }

    @Test fun somethingLaterOverTheFrontStopsItTheStoppedPopupDoesNot() {
        // Only the popup behind: nothing over the front.
        assertNull(PopupStack.overFront(closeFront, cover, look(page() + back())))
        // A card later in the page over the same button.
        val later = listOf(
            node("0/0/3", bounds = ScreenBounds(120, 1000, 960, 1740)),
            node("0/0/3/0", text = "또 다른 공지", bounds = ScreenBounds(120, 1000, 960, 1100)),
        )
        assertEquals(StackBlock.COVER_LATER, PopupStack.overFront(closeFront, cover, look(page() + back() + later)))
        // A window above is sure.
        val windows = listOf(WindowShape(1, WindowKind.APPLICATION, 1, true, SCREEN, SCREEN), WindowShape(2, WindowKind.APPLICATION, 2, true, cover.bounds, cover.bounds))
        assertEquals(StackBlock.WINDOW_ABOVE, PopupStack.overFront(closeFront, cover, StackLook(emptyList(), SCREEN, windows, 1, false, 0, 1)))
    }

    // The service asks the same rule when offering and right before the click (frontPressMiss).
    @Test fun aPartialLookStopsTheOfferAndTheClick() {
        val clean = StackLook(page() + back(), SCREEN, emptyList(), 1, false, 10, 6)
        assertNull(OverlapPressRules.lookMiss(closeFront, cover, clean))
        val partial = StackLook(page() + back(), SCREEN, emptyList(), 1, true, 400, 250)
        assertEquals(FrontPressMiss.LOOK_PARTIAL, OverlapPressRules.lookMiss(closeFront, cover, partial))
        // Something read over the button still says so when the look is whole.
        val later = listOf(
            node("0/0/3", bounds = ScreenBounds(120, 1000, 960, 1740)),
            node("0/0/3/0", text = "또 다른 공지", bounds = ScreenBounds(120, 1000, 960, 1100)),
        )
        assertEquals(FrontPressMiss.COVERED, OverlapPressRules.lookMiss(closeFront, cover, look(page() + back() + later)))
    }

    // 8-46 unchanged: a partial look does not change what an ordinary popup's check finds.
    @Test fun aPartialLookLeavesTheOrdinaryCheckAlone() {
        val nodes = page() + back() + front()
        val partial = StackLook(nodes, SCREEN, emptyList(), 1, true, 400, 250)
        assertEquals(PopupStack.hit(closeBack, root, look(nodes))!!.block, PopupStack.hit(closeBack, root, partial)!!.block)
        assertNull(PopupStack.block(closeFront, cover, StackLook(page(), SCREEN, emptyList(), 1, true, 400, 250)))
    }

    // readStack returns no nodes and partial=false for a root with a window id. The front never has one: PopupStack.hit makes it, in the same
    // window, and a stopped popup with its own window or a window above gives no front.
    @Test fun theFrontNeverHasItsOwnWindow() {
        val nodes = page() + back() + front()
        assertNull(PopupStack.hit(closeBack, root, look(nodes))!!.front!!.cover.windowId)
        val later = listOf(
            node("0/0/3", bounds = ScreenBounds(120, 1000, 960, 1740)),
            node("0/0/3/0", text = "또 다른 공지", bounds = ScreenBounds(120, 1000, 960, 1100)),
        )
        val covered = PopupStack.hit(closeBack, root, look(page() + back() + later))!!
        assertEquals(StackBlock.COVER_LATER, covered.block)
        assertNull(covered.front!!.cover.windowId)
        assertNull(PopupStack.hit(closeBack, root.copy(windowId = 7), look(nodes)))
        val windows = listOf(WindowShape(1, WindowKind.APPLICATION, 1, true, SCREEN, SCREEN), WindowShape(2, WindowKind.APPLICATION, 2, true, cover.bounds, cover.bounds))
        val above = PopupStack.hit(closeFront, root, StackLook(nodes, SCREEN, windows, 1, false, 0, 1))!!
        assertEquals(StackBlock.WINDOW_ABOVE, above.block)
        assertNull(above.front)
    }

    // Read and a scam: said first, whatever else there is to say.
    @Test fun aScamInFrontComesBeforeEveryOtherCode() {
        val scam = seen(front(), suspicious = true)
        val rest: (FrontSeen?) -> FrontPressMiss? = { OverlapPressRules.miss(target(), it) }
        assertEquals(FrontPressMiss.SCAM, OverlapPressRules.firstMiss(target(), scam, FrontPressMiss.LOOK_PARTIAL, rest))
        assertEquals(FrontPressMiss.SCAM, OverlapPressRules.firstMiss(target(), scam, FrontPressMiss.COVERED, rest))
        assertEquals(FrontPressMiss.SCAM, OverlapPressRules.firstMiss(target(), scam, FrontPressMiss.UNREAD, rest))
        assertEquals(FrontPressMiss.SCAM, OverlapPressRules.firstMiss(target(kind = FrontKind.FIRST), scam, null, rest))
    }

    // Not read: not known, as before, in the same order as before.
    @Test fun notReadSaysNothingAboutAScam() {
        val rest: (FrontSeen?) -> FrontPressMiss? = { OverlapPressRules.miss(target(), it) }
        assertEquals(FrontPressMiss.LOOK_PARTIAL, OverlapPressRules.firstMiss(target(), null, FrontPressMiss.LOOK_PARTIAL, rest))
        assertEquals(FrontPressMiss.COVERED, OverlapPressRules.firstMiss(target(), null, FrontPressMiss.COVERED, rest))
        assertEquals(FrontPressMiss.UNREAD, OverlapPressRules.firstMiss(target(), null, null, rest))
        assertEquals(FrontPressMiss.NOT_A_CLOSE, OverlapPressRules.firstMiss(target(kind = FrontKind.FIRST), null, FrontPressMiss.LOOK_PARTIAL, rest))
        val banner = PopupRoot("0/0/2", cover.bounds, PopupRootKind.BOTTOM_BANNER)
        assertFalse(OverlapPressRules.readable(target(on = banner)))
        // Read and fine: the other codes still count.
        assertEquals(FrontPressMiss.LOOK_PARTIAL, OverlapPressRules.firstMiss(target(), seen(front()), FrontPressMiss.LOOK_PARTIAL, rest))
        assertNull(OverlapPressRules.firstMiss(target(), seen(front()), null, rest))
    }

    @Test fun aScamInFrontWarnsAndOffersNoPress() {
        val popup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.NOTICE, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        val shown = SimplePromptOptions.overlapped(planned, frontKnown = true, pressLabel = "닫기", frontScam = true, frontKind = FrontKind.CLOSE)
        assertEquals(PromptSummary.POPUPS_OVERLAP_FRONT_SCAM, shown.summary)
        assertEquals(R.string.ui_choice_summary_overlap_front_scam, shown.summary.textRes())
        assertTrue(shown.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE || it.kind == PromptOptionKind.REMEMBER_ALWAYS })
        assertNotNull(shown.options.singleOrNull { it.kind == PromptOptionKind.MOVE_TO_BUTTON && it.candidateId == SimplePromptOptions.FRONT_ID })
        assertNotNull(shown.options.singleOrNull { it.kind == PromptOptionKind.KEEP_OPEN })
        // Voice cannot press there either: nothing to press.
        assertEquals(VoiceOutcome.Say(R.string.ui_choice_cannot_press), VoicePolicy.decide(VoiceUtterance.Command(VoiceCommand.CLOSE), shown))
        // Without the scam nothing changes.
        assertEquals(PromptSummary.POPUPS_OVERLAP, SimplePromptOptions.overlapped(planned, frontKnown = true).summary)
    }

    // What is read goes with what would be closed.
    @Test fun theFrontsWordsAreReadOnlyWhereItsCloseIsOffered() {
        val backPopup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val press = OverlapPressRules.offer(target(), seen(front()))!!
        assertTrue(OverlapPressRules.readTarget(backPopup, press) === press.popup)
        assertTrue(OverlapPressRules.readTarget(backPopup, null) === backPopup)
        fun read(prompt: kr.closeforyou.ui.voice.choice.ChoicePrompt) = prompt.options.single { it.kind == PromptOptionKind.READ_CONTENT }
        val shown = promptWithPress()
        assertEquals(R.string.ui_choice_read_front_content, read(shown).labelRes)
        val plain = ChoicePromptPlanner.plan(backPopup.analysis, PopupClassification.NOTICE, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        assertNull(read(plain).labelRes)
        assertNull(read(SimplePromptOptions.overlapped(plain, frontKnown = true)).labelRes)
        assertEquals(R.string.ui_choice_read_front_content, read(SimplePromptOptions.overlapped(plain, frontKnown = true, frontScam = true, frontKind = FrontKind.CLOSE)).labelRes)
    }

    @Test fun aPressPromptShowsTheFrontNoticesWordsNeverTheStoppedPopups() {
        val press = OverlapPressRules.offer(target(), seen(front()))!!
        val words = OverlapPressRules.excerptSource("다른 안내", PopupClassification.NOTICE, false, press)!!
        assertTrue(words.contains("LMS 앱 업데이트 공지"))
        assertFalse(words.contains("다른 안내"))
        // The stopped popup need not be a notice, and the focus being in its bounds does not say TalkBack read the front.
        assertEquals(words, OverlapPressRules.excerptSource(null, PopupClassification.ADVERTISEMENT, true, press))
    }

    @Test fun aPressPromptOnAnAdInFrontShowsNoWords() {
        val press = OverlapPressRules.offer(target(), seen(front(), kind = PopupClassification.ADVERTISEMENT))!!
        assertNull(OverlapPressRules.excerptSource("다른 안내", PopupClassification.NOTICE, false, press))
    }

    @Test fun withoutAPressTheWordsAreAsBefore() {
        assertEquals("다른 안내", OverlapPressRules.excerptSource("다른 안내", PopupClassification.NOTICE, false, null))
        assertNull(OverlapPressRules.excerptSource("다른 안내", PopupClassification.NOTICE, true, null))
        assertNull(OverlapPressRules.excerptSource("다른 안내", PopupClassification.ADVERTISEMENT, false, null))
        assertNull(OverlapPressRules.excerptSource(null, PopupClassification.NOTICE, false, null))
    }

    // A scam in front is refused before any offer, so its words have no way into the excerpt.
    @Test fun aScamsWordsAreNotShown() {
        val scam = seen(front(), suspicious = true)
        assertEquals(FrontPressMiss.SCAM, OverlapPressRules.miss(target(), scam))
        assertEquals("다른 안내", OverlapPressRules.excerptSource("다른 안내", PopupClassification.NOTICE, false, press = null))
    }

    private fun plannedBack(): kr.closeforyou.ui.voice.choice.ChoicePrompt {
        val popup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        return ChoicePromptPlanner.plan(popup.analysis, PopupClassification.NOTICE, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
    }

    // No named close in a scam: the first clickable item may be the bait, so no way there either.
    @Test fun aScamWithNoNamedCloseOffersNoMove() {
        val shown = SimplePromptOptions.overlapped(plannedBack(), frontKnown = true, frontScam = true, frontKind = FrontKind.FIRST)
        assertEquals(PromptSummary.POPUPS_OVERLAP_FRONT_SCAM_NO_MOVE, shown.summary)
        assertEquals(R.string.ui_choice_summary_overlap_front_scam_no_move, shown.summary.textRes())
        assertTrue(shown.options.none { it.kind == PromptOptionKind.MOVE_TO_BUTTON || it.kind == PromptOptionKind.PRESS_CANDIDATE })
        assertNotNull(shown.options.singleOrNull { it.kind == PromptOptionKind.KEEP_OPEN })
        // Not said which kind: the safer one.
        assertEquals(PromptSummary.POPUPS_OVERLAP_FRONT_SCAM_NO_MOVE, SimplePromptOptions.overlapped(plannedBack(), frontKnown = true, frontScam = true).summary)
        // The move's own window is as before, whatever the kind.
        val ordinary = SimplePromptOptions.overlapped(plannedBack(), frontKnown = true, frontKind = FrontKind.FIRST)
        assertEquals(PromptSummary.POPUPS_OVERLAP, ordinary.summary)
        assertNotNull(ordinary.options.singleOrNull { it.kind == PromptOptionKind.MOVE_TO_BUTTON })
    }

    // The stopped popup's words under the warning would pass for the scam's.
    @Test fun aScamWarningShowsNoExcerptOthersKeepIt() {
        val withWords = plannedBack().copy(shownExcerpt = "다른 안내")
        assertNull(SimplePromptOptions.overlapped(withWords, frontKnown = true, frontScam = true, frontKind = FrontKind.CLOSE).shownExcerpt)
        assertNull(SimplePromptOptions.overlapped(withWords, frontKnown = true, frontScam = true, frontKind = FrontKind.FIRST).shownExcerpt)
        assertEquals("다른 안내", SimplePromptOptions.overlapped(withWords, frontKnown = true).shownExcerpt)
        assertEquals("다른 안내", SimplePromptOptions.overlapped(withWords, frontKnown = false).shownExcerpt)
        assertEquals("다른 안내", SimplePromptOptions.overlapped(withWords, frontKnown = true, pressLabel = "닫기").shownExcerpt)
    }

    // A scam's read is the scam's, never the stopped popup's in its place.
    @Test fun aScamWarningReadsTheFrontPopup() {
        val backPopup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val scam = seen(front(), suspicious = true).popup
        assertTrue(OverlapPressRules.readTarget(backPopup, null, scam) === scam)
        assertTrue(OverlapPressRules.readTarget(backPopup, null, null) === backPopup)
    }

    @Test fun thePressPromptKeepsItsOwnWords() {
        val shown = SimplePromptOptions.overlapped(plannedBack(), frontKnown = true, pressLabel = "닫기")
        assertEquals(R.string.ui_choice_summary_overlap_press, shown.summary.textRes())
        assertEquals(R.string.ui_choice_press_front, shown.options.first().labelRes)
    }

    @Test fun theRootLineHoldsACodeOnly() {
        assertEquals("OVERLAP_PRESS_ROOT_LEFT value=true", OverlapPressRules.rootLeftLine(true))
        assertEquals("OVERLAP_PRESS_ROOT_LEFT value=false", OverlapPressRules.rootLeftLine(false))
        assertEquals("OVERLAP_PRESS_ROOT_LEFT value=null", OverlapPressRules.rootLeftLine(null))
    }

    private fun promptWithPress(): kr.closeforyou.ui.voice.choice.ChoicePrompt {
        val popup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.NOTICE, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        return SimplePromptOptions.overlapped(planned, frontKnown = true, pressLabel = "닫기")
    }

    // The press is refused before anything can use it up: a tap still gets it, once.
    @Test fun voiceDoesNotPressTheFrontATapStillCan() {
        val shown = promptWithPress()
        assertTrue(shown.options.single { it.candidateId == SimplePromptOptions.FRONT_PRESS_ID }.tapOnly)
        val outcome = VoicePolicy.decide(VoiceUtterance.Command(VoiceCommand.CLOSE), shown)
        assertEquals(VoiceOutcome.TapOnly(R.string.ui_voice_press_front_on_screen), outcome)
        val press = OverlapPressRules.offer(target(), seen(front()))!!
        assertTrue(press.take())
        assertFalse(press.take())
    }

    @Test fun voiceStillWorksForTheOtherOptions() {
        val shown = promptWithPress()
        fun chosen(command: VoiceCommand) = (VoicePolicy.decide(VoiceUtterance.Command(command), shown) as VoiceOutcome.Choose).option.kind
        assertEquals(PromptOptionKind.MOVE_TO_BUTTON, chosen(VoiceCommand.MOVE_TO_BUTTON))
        assertEquals(PromptOptionKind.READ_CONTENT, chosen(VoiceCommand.READ_CONTENT))
        assertEquals(PromptOptionKind.KEEP_OPEN, chosen(VoiceCommand.KEEP_OPEN))
        // Without the press the prompt is F57's: no press to choose, as before.
        val plain = SimplePromptOptions.overlapped(shown, frontKnown = true)
        assertEquals(VoiceOutcome.Say(R.string.ui_choice_cannot_press), VoicePolicy.decide(VoiceUtterance.Command(VoiceCommand.CLOSE), plain))
    }

    // AGENTS 6-6: one press per prompt; a failure is never pressed again.
    @Test fun itIsPressedOnceAndNotAgainAfterAFailure() {
        val press = OverlapPressRules.offer(target(), seen(front()))!!
        assertTrue(press.take())
        assertFalse(press.take())
        val token = press.popup.analysis.token
        val notClosed = ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = ClickOutcome.STILL_PRESENT)
        assertEquals(FrontPressStep.NOT_CLOSED, OverlapPressRules.after(notClosed))
        val rejected = ActionResult(token, ActionStatus.ATTEMPT_FAILED, SafetyReason.UNVERIFIED_TARGET, attempted = true, outcome = ClickOutcome.UNKNOWN)
        assertEquals(FrontPressStep.NOT_CLOSED, OverlapPressRules.after(rejected))
        assertEquals(FrontPressStep.NOT_PRESSED, OverlapPressRules.after(ActionResult(token, ActionStatus.STALE, SafetyReason.STALE_SCREEN, attempted = false)))
        assertEquals(FrontPressStep.LEFT_APP, OverlapPressRules.after(ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = ClickOutcome.LEFT_APP)))
        assertEquals(FrontPressStep.CLOSED, OverlapPressRules.after(ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED)))
        // The failed name is not offered again on this popup.
        assertEquals(FrontPressMiss.FAILED_BEFORE, OverlapPressRules.miss(target(), seen(front()), setOf("닫기")))
    }

    // After the front popup closed: the one behind is judged again, and an automatic close of it becomes a question.
    @Test fun thePopupBehindIsNotPressedByItself() {
        val failed = LinkedHashSet<String>()
        val holds = AutoPressHolds(failed)
        holds.hold("7:back")
        // The engine's check: `decided is AutoClose && words in autoFailed` -> Ask; the remembered path checks the same set.
        assertTrue("7:back" in failed)
    }

    @Test fun thePromptOffersThePressFirstNeverAlwaysAndNameInIt() {
        val popup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.NOTICE, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        val shown = SimplePromptOptions.overlapped(planned, frontKnown = true, pressLabel = "닫기")
        assertEquals(PromptSummary.POPUPS_OVERLAP_PRESS, shown.summary)
        val first = shown.options.first()
        assertEquals(PromptOptionKind.PRESS_CANDIDATE, first.kind)
        assertEquals(SimplePromptOptions.FRONT_PRESS_ID, first.candidateId)
        assertEquals("닫기", first.buttonLabel)
        assertEquals(R.string.ui_choice_press_front, first.labelRes)
        assertEquals(1, shown.options.count { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        assertTrue(shown.options.none { it.kind == PromptOptionKind.REMEMBER_ALWAYS })
        assertNotNull(shown.options.singleOrNull { it.kind == PromptOptionKind.MOVE_TO_BUTTON && it.candidateId == SimplePromptOptions.FRONT_ID })
        assertNotNull(shown.options.singleOrNull { it.kind == PromptOptionKind.KEEP_OPEN })
        // The move comes after the press.
        assertEquals(PromptOptionKind.MOVE_TO_BUTTON, shown.options[1].kind)
        assertNotEquals(SimplePromptOptions.FRONT_ID, SimplePromptOptions.FRONT_PRESS_ID)
    }

    // Anything else: F57 exactly.
    @Test fun withoutAPressThePromptIsF57s() {
        val popup = SimplePopupAnalysis.build(page() + back(), root, "p", 7, SCREEN, 1L, 0L)
        val planned = ChoicePromptPlanner.plan(popup.analysis, PopupClassification.NOTICE, labelPolicy = SimpleLabelPolicy, rememberChoicesEnabled = false, preset = HandlingPreset.entries.first())
        val move = SimplePromptOptions.overlapped(planned, frontKnown = true)
        assertEquals(PromptSummary.POPUPS_OVERLAP, move.summary)
        assertTrue(move.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        val none = SimplePromptOptions.overlapped(planned, frontKnown = false, pressLabel = "닫기")
        assertEquals(PromptSummary.POPUPS_OVERLAP_NO_MOVE, none.summary)
        assertEquals(listOf(PromptOptionKind.KEEP_OPEN), none.options.map { it.kind })
        // After a failed press: said so, no press.
        val failed = SimplePromptOptions.overlapped(planned, frontKnown = true, failedNow = true)
        assertEquals(PromptSummary.AUTO_CLOSE_FAILED, failed.summary)
        assertTrue(failed.options.none { it.kind == PromptOptionKind.PRESS_CANDIDATE })
    }

    // The words of the LMS notice are not known yet: this fixture only shows the kind check does not refuse a plain notice.
    @Test fun aPlainNoticeHasAKnownKind() = runBlocking {
        val popup = seen(front()).popup
        val kind = OverlapPressRules.kindOf(popup, DecisionPopupClassifier().classify(popup.analysis), suspicious = false)
        assertNotEquals(PopupClassification.UNKNOWN, kind)
        assertNotEquals(PopupClassification.USER_DECISION, kind)
    }

    @Test fun theLinesHoldCodesAndBoundsOnly() {
        val press = OverlapPressRules.offer(target(), seen(front()))!!
        val lines = listOf(
            OverlapPressRules.offeredLine(press),
            OverlapPressRules.notOfferedLine(FrontPressMiss.RISK_BUTTON),
            OverlapPressRules.chosenLine(press),
            OverlapPressRules.recheckFailedLine(FrontPressMiss.TARGET_CHANGED.name, closeFront),
            OverlapPressRules.resultLine(FrontPressStep.CLOSED),
            OverlapPressRules.notOfferedLine(FrontPressMiss.LOOK_PARTIAL),
            OverlapPressRules.recheckFailedLine(FrontPressMiss.LOOK_PARTIAL.name, closeFront),
            OverlapPressRules.VOICE_REFUSED_LINE,
        )
        assertEquals("OVERLAP_PRESS_OFFERED cover=100,700,980,1750 to=540,1600,980,1700", lines[0])
        assertEquals("OVERLAP_PRESS_NOT_OFFERED reason=RISK_BUTTON", lines[1])
        assertEquals("OVERLAP_PRESS_CHOSEN by=TAP to=540,1600,980,1700", lines[2])
        assertEquals("OVERLAP_PRESS_RECHECK_FAILED reason=TARGET_CHANGED to=540,1600,980,1700", lines[3])
        assertEquals("OVERLAP_PRESS_RESULT code=CLOSED", lines[4])
        assertEquals("OVERLAP_PRESS_NOT_OFFERED reason=LOOK_PARTIAL", lines[5])
        assertEquals("OVERLAP_PRESS_RECHECK_FAILED reason=LOOK_PARTIAL to=540,1600,980,1700", lines[6])
        assertEquals("OVERLAP_PRESS_VOICE_REFUSED", lines[7])
        lines.forEach { line -> assertFalse(line.contains("닫기") || line.contains("LMS") || line.contains("0/0/2")) }
    }
}
