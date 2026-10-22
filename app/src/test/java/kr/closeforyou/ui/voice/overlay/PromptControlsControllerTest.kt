package kr.closeforyou.ui.voice.overlay

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.contract.VoiceAvailability
import kr.closeforyou.core.contract.VoiceCommand
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.command.SpeakButtonState
import kr.closeforyou.ui.voice.command.SpeakButtonVoiceInput
import kr.closeforyou.ui.voice.command.VoiceUtterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptControlsControllerTest {
    private class FakeInput(var availability: VoiceAvailability = VoiceAvailability.READY) : SpeakButtonVoiceInput {
        val results = MutableSharedFlow<VoiceUtterance>(extraBufferCapacity = 4)
        override val utterances = results
        override val events = emptyFlow<kr.closeforyou.core.contract.VoiceEvent>()
        var starts = 0
        var cancels = 0
        override fun availability() = availability
        override fun startFromSpeakButton(token: SnapshotToken): VoiceAvailability {
            starts++
            return availability
        }
        override fun cancel() {
            cancels++
        }
    }

    private class FakeAnnouncer : PopupAnnouncer {
        var stops = 0
        var repeats = 0
        var announces = 0
        override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
            announces++
            return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        override suspend fun repeatLast(): AnnouncementReceipt {
            repeats++
            return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        }
        override fun stop() {
            stops++
        }
    }

    private val prompt = ChoicePromptPlanner.plan(
        MockPopupFixtures.advertisement().analysis.copy(
            closeCandidates = persistentListOf(
                CloseCandidate("c", "1", null, "닫기", ScreenBounds(0, 0, 10, 10), EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE),
            ),
        ),
        PopupClassification.ADVERTISEMENT,
    )

    private class Harness(scope: TestScope, speak: SpeakButtonState = SpeakButtonState.SHOWN, prompt: kr.closeforyou.ui.voice.choice.ChoicePrompt) {
        val input = FakeInput()
        val announcer = FakeAnnouncer()
        val picked = mutableListOf<PromptOption>()
        val controller = PromptControlsController(
            prompt, scope.backgroundScope, PromptControlsSupport(announcer, input) { speak }, onVoiceOption = { picked += it },
        )
    }

    @Test fun speakStopsOurTtsThenListens() = runTest {
        val h = Harness(this, prompt = prompt)
        h.controller.onSpeak()
        assertEquals(1, h.announcer.stops)
        assertEquals(1, h.input.starts)
        assertTrue(h.controller.state.value.listening)
        assertEquals(R.string.ui_voice_listening, h.controller.state.value.messageRes)
    }

    @Test fun secondPressCancels() = runTest {
        val h = Harness(this, prompt = prompt)
        h.controller.onSpeak()
        h.controller.onSpeak()
        assertEquals(1, h.input.cancels)
        assertFalse(h.controller.state.value.listening)
        assertEquals(R.string.ui_voice_listen_cancelled, h.controller.state.value.messageRes)
    }

    @Test fun recognizedCloseUsesTheTapOption() = runTest {
        val h = Harness(this, prompt = prompt)
        h.controller.onSpeak()
        h.input.results.emit(VoiceUtterance.Command(VoiceCommand.CLOSE))
        runCurrent()
        assertEquals(listOf(PromptOptionKind.PRESS_CANDIDATE), h.picked.map { it.kind })
        assertFalse(h.controller.state.value.listening)
    }

    @Test fun refusedAndUnclearKeepThePromptAndSaySo() = runTest {
        val h = Harness(this, prompt = prompt)
        h.controller.onSpeak()
        h.input.results.emit(VoiceUtterance.Refused)
        runCurrent()
        assertEquals(R.string.ui_voice_refused, h.controller.state.value.messageRes)
        h.controller.onSpeak()
        h.input.results.emit(VoiceUtterance.NotUnderstood)
        runCurrent()
        assertEquals(R.string.ui_voice_not_understood, h.controller.state.value.messageRes)
        assertTrue(h.picked.isEmpty())
    }

    @Test fun silenceEndsQuietly() = runTest {
        val h = Harness(this, prompt = prompt)
        h.controller.onSpeak()
        h.input.results.emit(VoiceUtterance.Silent)
        runCurrent()
        assertFalse(h.controller.state.value.listening)
        assertNull(h.controller.state.value.messageRes)
    }

    @Test fun resultAfterCancelIsIgnored() = runTest {
        val h = Harness(this, prompt = prompt)
        h.controller.onSpeak()
        h.controller.dispose()
        h.input.results.emit(VoiceUtterance.Command(VoiceCommand.CLOSE))
        runCurrent()
        assertTrue(h.picked.isEmpty())
        assertEquals(1, h.input.cancels)
    }

    @Test fun closedPromptIgnoresLatePressesAndResults() = runTest {
        // Review #18: a press or result reaching a disposed controller (window closed or stuck) does nothing.
        val h = Harness(this, prompt = prompt)
        h.controller.dispose()
        h.controller.onSpeak()
        h.controller.onRepeat()
        h.input.results.emit(VoiceUtterance.Command(VoiceCommand.CLOSE))
        runCurrent()
        assertEquals(0, h.input.starts)
        assertEquals(0, h.announcer.repeats)
        assertTrue(h.picked.isEmpty())
    }

    @Test fun hiddenSpeakButtonNeverListens() = runTest {
        val h = Harness(this, SpeakButtonState.PERMISSION_NEEDED, prompt)
        h.controller.onSpeak()
        assertEquals(0, h.input.starts)
        assertFalse(h.controller.state.value.speakVisible)
        assertEquals(R.string.ui_voice_permission_hint, h.controller.state.value.hintRes)
    }

    @Test fun permissionLostAtPressSaysHowToFix() = runTest {
        val h = Harness(this, prompt = prompt)
        h.input.availability = VoiceAvailability.MICROPHONE_PERMISSION_REQUIRED
        h.controller.onSpeak()
        assertFalse(h.controller.state.value.listening)
        assertEquals(R.string.ui_voice_permission_hint, h.controller.state.value.messageRes)
    }

    @Test fun repeatAndStopCancelListeningFirst() = runTest {
        val h = Harness(this, prompt = prompt)
        h.controller.onSpeak()
        h.controller.onRepeat()
        runCurrent()
        assertEquals(1, h.announcer.repeats)
        assertEquals(1, h.input.cancels)
        h.controller.onStopSpeaking()
        assertEquals(2, h.announcer.stops) // once before listening, once now
    }

    @Test fun noSupportShowsNoExtraButtons() = runTest {
        val controller = PromptControlsController(prompt, backgroundScope, PromptControlsSupport.NONE) {}
        assertFalse(controller.state.value.speakVisible)
        assertFalse(controller.state.value.speechButtons)
    }

    private fun Harness.firstAnnouncement(scope: TestScope) = PromptAnnouncementListener(
        announcer, scope.backgroundScope, { PromptAnnounceMode.PANE_TITLE_AND_TTS }, 700L,
        { kr.closeforyou.ui.voice.settings.AnnouncementDetail.SHORT }, { _, _ -> "" },
    )

    @Test fun firstAnnouncementPlaysWhenSpeakNotPressed() = runTest {
        val h = Harness(this, prompt = prompt)
        h.firstAnnouncement(this).onShown(prompt, h.controller::speakPressed)
        advanceTimeBy(701)
        assertEquals(1, h.announcer.announces)
    }

    @Test fun speakBeforeFirstAnnouncementSkipsIt() = runTest {
        val h = Harness(this, prompt = prompt)
        h.firstAnnouncement(this).onShown(prompt, h.controller::speakPressed)
        advanceTimeBy(300)
        h.controller.onSpeak()
        advanceTimeBy(1000)
        assertEquals(0, h.announcer.announces)
    }

    @Test fun skippedAnnouncementIsNotSpokenAfterListeningEnds() = runTest {
        val h = Harness(this, prompt = prompt)
        h.firstAnnouncement(this).onShown(prompt, h.controller::speakPressed)
        h.controller.onSpeak()
        h.controller.onSpeak() // second press cancels listening
        advanceTimeBy(5000)
        assertEquals(0, h.announcer.announces)
    }

    @Test fun speakThatCouldNotListenKeepsTheAnnouncement() = runTest {
        val h = Harness(this, prompt = prompt)
        h.input.availability = VoiceAvailability.MICROPHONE_PERMISSION_REQUIRED
        h.firstAnnouncement(this).onShown(prompt, h.controller::speakPressed)
        h.controller.onSpeak()
        advanceTimeBy(701)
        assertEquals(1, h.announcer.announces)
    }
}
