package kr.closeforyou.ui.voice.overlay

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.TextSource
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.PressFailure
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TalkBackTakeoverTest {
    private val bounds = ScreenBounds(0, 0, 10, 10)
    private fun text(value: String) = ExtractedText(value, bounds, TextSource.ACCESSIBILITY_TREE, null)
    private fun candidate(id: String, label: String, role: ButtonRole) =
        CloseCandidate(id, "1", null, label, bounds, EvidenceLevel.E2_LABELED_NODE, role)

    /** Synthetic popup text (fixture words, not a real screen). */
    private val adWithClose: ChoicePrompt = ChoicePromptPlanner.plan(
        MockPopupFixtures.advertisement().analysis.copy(
            texts = persistentListOf(text("오늘만 특가"), text("닫기")),
            closeCandidates = persistentListOf(candidate("c", "닫기", ButtonRole.CLOSE)),
        ),
        PopupClassification.ADVERTISEMENT,
    )

    private val noPressButton: ChoicePrompt = ChoicePromptPlanner.plan(
        MockPopupFixtures.advertisement().analysis.copy(
            texts = persistentListOf(text("이벤트 안내")),
            closeCandidates = persistentListOf(),
        ),
        PopupClassification.ADVERTISEMENT,
    )

    // --- Plan ---

    @Test fun plannerKeepsPopupTextVerbatimInReadingOrder() {
        assertEquals("오늘만 특가\n닫기", adWithClose.popupText)
    }

    @Test fun popupTextIsNeverInToString() {
        assertFalse(adWithClose.toString().contains("특가"))
    }

    @Test fun buttonIsTheFirstPressCandidate() {
        val index = TalkBackTakeoverPlan.buttonIndex(adWithClose)!!
        assertEquals(PromptOptionKind.PRESS_CANDIDATE, adWithClose.options[index].kind)
    }

    @Test fun withoutPressButtonTheFirstOptionThatPressesNothing() {
        val index = TalkBackTakeoverPlan.buttonIndex(noPressButton)!!
        val kind = noPressButton.options[index].kind
        assertTrue(kind != PromptOptionKind.PRESS_CANDIDATE && kind != PromptOptionKind.REMEMBER_ALWAYS)
        assertEquals(0, index)
    }

    @Test fun noOptionsNoButton() {
        assertNull(TalkBackTakeoverPlan.buttonIndex(adWithClose.copy(options = persistentListOf())))
    }

    private val format: (Int, List<Any>) -> String = { res, args ->
        when (res) {
            R.string.ui_choice_cannot_press -> "CANNOT"
            R.string.ui_choice_video_ad_wait -> "WAIT${args.single()}"
            else -> "?"
        }
    }

    @Test fun speechIsThePopupTextWithAPauseBetweenLines() {
        assertEquals("오늘만 특가.\n닫기", TalkBackTakeoverPlan.speech(adWithClose, format) { "FALLBACK" })
    }

    @Test fun speechReadsSymbolsAsWords() {
        val notice = adWithClose.copy(popupText = "서버 점검 02:00~04:00")
        assertEquals("서버 점검 오전 2시부터 오전 4시까지", TalkBackTakeoverPlan.speech(notice, format) { "FALLBACK" })
    }

    @Test fun onlyDecorationsFallBack() {
        assertEquals("FALLBACK", TalkBackTakeoverPlan.speech(adWithClose.copy(popupText = "★ 🎉"), format) { "FALLBACK" })
    }

    @Test fun cannotPressAddsOnlyTheFixedSafetyLine() {
        assertTrue(noPressButton.cannotPressForUser)
        assertEquals("이벤트 안내.\nCANNOT", TalkBackTakeoverPlan.speech(noPressButton, format) { "FALLBACK" })
    }

    @Test fun noPopupTextFallsBackToTheUsualSentence() {
        assertEquals("FALLBACK", TalkBackTakeoverPlan.speech(adWithClose.copy(popupText = " "), format) { "FALLBACK" })
    }

    @Test fun failedPressFollowUpSaysNothing() {
        assertNull(TalkBackTakeoverPlan.speech(adWithClose.copy(pressFailure = PressFailure.NOT_PRESSED), format) { "X" })
    }

    @Test fun activeOnlyWithSettingAndTalkBack() {
        assertTrue(TalkBackTakeoverFeature.isActive(settingOn = true, touchExplorationOn = true))
        assertFalse(TalkBackTakeoverFeature.isActive(settingOn = false, touchExplorationOn = true))
        assertFalse(TalkBackTakeoverFeature.isActive(settingOn = true, touchExplorationOn = false))
    }

    // --- Listener flow ---

    private class Log {
        val events = mutableListOf<String>()
    }

    private class FakeFocus(private val log: Log, var firstFailures: Int = 0) : PromptFocus {
        override val enabled = true
        override fun moveTo(target: TakeoverFocusTarget): Boolean {
            if (target == TakeoverFocusTarget.FIRST_ITEM && firstFailures > 0) {
                firstFailures--
                log.events += "miss:${target.name}"
                return false
            }
            log.events += "focus:${target.name}"
            return true
        }
    }

    private class FakeAnnouncer(private val log: Log) : PopupAnnouncer {
        var state = AnnouncementState.TTS_COMPLETED
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
            log.events += "say:${request.text}"
            gate?.await()
            return AnnouncementReceipt(state)
        }
        override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
        override fun stop() {
            log.events += "stop"
        }
    }

    private val timing = TakeoverTiming(firstFocusAttempts = 3, firstFocusRetryMillis = 100, speechDelayMillis = 500)

    private fun TestScope.listener(announcer: PopupAnnouncer) = PromptAnnouncementListener(
        announcer, this,
        mode = { PromptAnnounceMode.PANE_TITLE_ONLY },
        ttsDelayMillis = 700,
        detail = { kr.closeforyou.ui.voice.settings.AnnouncementDetail.SHORT },
        sentence = { _, _ -> "SUMMARY" },
        formatted = format,
        takeoverTiming = timing,
    )

    @Test fun firstItemThenPopupTextThenButton() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose, { false }, FakeFocus(log))
        advanceUntilIdle()
        assertEquals(listOf("focus:FIRST_ITEM", "say:오늘만 특가.\n닫기", "focus:CHOICE_BUTTON"), log.events)
    }

    @Test fun speechWaitsForTalkBackToReadTheFirstItem() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose, { false }, FakeFocus(log))
        runCurrent()
        assertEquals(listOf("focus:FIRST_ITEM"), log.events)
        advanceTimeBy(499)
        runCurrent()
        assertEquals(1, log.events.size)
        advanceTimeBy(2)
        runCurrent()
        assertEquals("say:오늘만 특가.\n닫기", log.events[1])
    }

    @Test fun firstFocusIsRetriedUntilTheWindowIsListed() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose, { false }, FakeFocus(log, firstFailures = 2))
        advanceUntilIdle()
        assertEquals(listOf("miss:FIRST_ITEM", "miss:FIRST_ITEM", "focus:FIRST_ITEM"), log.events.take(3))
        assertEquals("focus:CHOICE_BUTTON", log.events.last())
    }

    @Test fun firstFocusNeverFoundStillSpeaks() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose, { false }, FakeFocus(log, firstFailures = 99))
        advanceUntilIdle()
        assertEquals(3, log.events.count { it == "miss:FIRST_ITEM" })
        assertTrue(log.events.contains("say:오늘만 특가.\n닫기"))
    }

    @Test fun speechThatDidNotPlayToTheEndLeavesFocusAlone() = runTest {
        val log = Log()
        val announcer = FakeAnnouncer(log).apply { state = AnnouncementState.NOT_IMPLEMENTED }
        listener(announcer).onShown(adWithClose, { false }, FakeFocus(log))
        advanceUntilIdle()
        assertFalse(log.events.contains("focus:CHOICE_BUTTON"))
    }

    @Test fun speakPressedSkipsSpeechAndButtonMove() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose, { true }, FakeFocus(log))
        advanceUntilIdle()
        assertEquals(listOf("focus:FIRST_ITEM"), log.events)
    }

    @Test fun userTouchStopsReadingAndDropsTheButtonMove() = runTest {
        val log = Log()
        val announcer = FakeAnnouncer(log).apply { gate = CompletableDeferred() }
        val listener = listener(announcer)
        listener.onShown(adWithClose, { false }, FakeFocus(log))
        advanceTimeBy(600)
        runCurrent()
        listener.onUserInteraction()
        announcer.gate?.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("focus:FIRST_ITEM", "say:오늘만 특가.\n닫기", "stop"), log.events)
    }

    @Test fun userTouchAfterTheFlowDoesNothing() = runTest {
        val log = Log()
        val listener = listener(FakeAnnouncer(log))
        listener.onShown(adWithClose, { false }, FakeFocus(log))
        advanceUntilIdle()
        listener.onUserInteraction()
        assertFalse(log.events.contains("stop"))
    }

    @Test fun dismissBeforeSpeechCancelsEverything() = runTest {
        val log = Log()
        val listener = listener(FakeAnnouncer(log))
        listener.onShown(adWithClose, { false }, FakeFocus(log))
        runCurrent()
        listener.onDismissed()
        advanceUntilIdle()
        assertEquals(listOf("focus:FIRST_ITEM", "stop"), log.events)
    }

    @Test fun failedPressFollowUpIsLeftAsBefore() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose.copy(pressFailure = PressFailure.NOT_PRESSED), { false }, FakeFocus(log))
        advanceUntilIdle()
        assertTrue(log.events.isEmpty())
    }

    @Test fun takeoverSpeaksEvenWhenThePaneOnlyModeIsChosen() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose, { false }, FakeFocus(log))
        advanceUntilIdle()
        assertTrue(log.events.any { it.startsWith("say:") })
    }

    @Test fun withoutTakeoverThePaneOnlyModeStaysSilent() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose, { false }, PromptFocus.NONE)
        advanceUntilIdle()
        assertTrue(log.events.isEmpty())
    }

    // ---- F13: one voice ----

    @Test fun excerptKeepsTheFirstWordsCutAtASpace() {
        assertEquals("서버 점검 안내", TalkBackTakeoverPlan.excerpt("서버 점검 안내", 50))
        assertEquals("오늘 서버 점검이 있어요.", TalkBackTakeoverPlan.excerpt("오늘 서버 점검이 있어요\n자세히 보기", 16))
        assertEquals("가나다라마바", TalkBackTakeoverPlan.excerpt("가나다라마바사아자차", 6))
        assertNull(TalkBackTakeoverPlan.excerpt("  \n ", 50))
    }

    @Test fun oneVoiceOnlyWithoutPopupTextForOurSpeech() {
        assertFalse(TalkBackTakeoverPlan.oneVoice(adWithClose))
        assertTrue(TalkBackTakeoverPlan.oneVoice(adWithClose.copy(popupText = null)))
        assertFalse(TalkBackTakeoverPlan.oneVoice(adWithClose.copy(popupText = null), enabled = false))
    }

    @Test fun oneVoiceFocusesTheFirstItemAndNothingElseSpeaks() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose.copy(popupText = null, shownExcerpt = "점검 안내"), { false }, FakeFocus(log))
        advanceUntilIdle()
        assertEquals(listOf("focus:FIRST_ITEM"), log.events)
    }

    @Test fun oneVoiceStillSpeaksWhenTheFirstItemCannotBeFocused() = runTest {
        val log = Log()
        listener(FakeAnnouncer(log)).onShown(adWithClose.copy(popupText = null), { false }, FakeFocus(log, firstFailures = 99))
        advanceUntilIdle()
        assertTrue(log.events.contains("say:SUMMARY"))
    }
}
