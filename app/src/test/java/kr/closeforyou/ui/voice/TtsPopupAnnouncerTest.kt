package kr.closeforyou.ui.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsPopupAnnouncerTest {
    private class FakeSpeechEngine(
        readiness: SpeechReadiness = SpeechReadiness.READY,
        var completes: Boolean = true,
    ) : SpeechEngine {
        val state = MutableStateFlow(readiness)
        override val readiness: StateFlow<SpeechReadiness> = state
        val spoken = mutableListOf<String>()
        var stopCount = 0
        var shutDown = false

        override suspend fun speak(text: String): Boolean {
            spoken += text
            return completes
        }

        override fun stop() {
            stopCount++
        }

        override fun shutdown() {
            shutDown = true
        }
    }

    private val token = MockPopupFixtures.notice().snapshot.token
    private fun request(text: String) = AnnouncementRequest(token, AnnouncementKind.CONTENT, text)

    @Test fun completedPlaybackReportsTtsCompletedOnly() = runBlocking {
        val engine = FakeSpeechEngine()
        val receipt = TtsPopupAnnouncer(engine, NOTHING).announce(request("공지 팝업이에요"))
        assertEquals(AnnouncementState.TTS_COMPLETED, receipt.state)
        assertEquals(listOf("공지 팝업이에요"), engine.spoken)
    }

    @Test fun interruptedPlaybackClaimsNoDelivery() = runBlocking {
        val engine = FakeSpeechEngine(completes = false)
        val receipt = TtsPopupAnnouncer(engine, NOTHING).announce(request("광고 팝업이에요"))
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, receipt.state)
    }

    @Test fun unavailableEngineDoesNotSpeakButKeepsForRepeat() = runBlocking {
        val engine = FakeSpeechEngine(readiness = SpeechReadiness.INITIALIZING)
        val announcer = TtsPopupAnnouncer(engine, NOTHING)
        assertFalse(announcer.announcementAvailable)
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, announcer.announce(request("첫 안내")).state)
        assertTrue(engine.spoken.isEmpty())

        engine.state.value = SpeechReadiness.READY
        assertTrue(announcer.announcementAvailable)
        assertEquals(AnnouncementState.TTS_COMPLETED, announcer.repeatLast().state)
        assertEquals(listOf("첫 안내"), engine.spoken)
    }

    @Test fun blankTextIsNotSpokenOrRemembered() = runBlocking {
        val engine = FakeSpeechEngine()
        val history = AnnouncementHistory()
        val receipt = TtsPopupAnnouncer(engine, NOTHING, history).announce(request("  "))
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, receipt.state)
        assertTrue(engine.spoken.isEmpty())
        assertEquals(0, history.size())
    }

    @Test fun repeatLastReplaysMostRecentAnnouncement() = runBlocking {
        val engine = FakeSpeechEngine()
        val announcer = TtsPopupAnnouncer(engine, NOTHING)
        announcer.announce(request("첫 번째"))
        announcer.announce(request("두 번째"))
        assertEquals(AnnouncementState.TTS_COMPLETED, announcer.repeatLast().state)
        assertEquals("두 번째", engine.spoken.last())
    }

    @Test fun repeatWithEmptyHistoryExplainsButClaimsNothing() = runBlocking {
        val engine = FakeSpeechEngine()
        val receipt = TtsPopupAnnouncer(engine, NOTHING).repeatLast()
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, receipt.state)
        assertEquals(listOf(NOTHING), engine.spoken)
    }

    @Test fun stopAndCloseReleaseEngineAndForgetText() = runBlocking {
        val engine = FakeSpeechEngine()
        val history = AnnouncementHistory()
        val announcer = TtsPopupAnnouncer(engine, NOTHING, history)
        announcer.announce(request("기억될 안내"))
        announcer.stop()
        assertEquals(1, engine.stopCount)
        announcer.close()
        assertTrue(engine.shutDown)
        assertEquals(0, history.size())
    }

    @Test fun historyKeepsOnlyRecentEntriesAndRedactsText() {
        val history = AnnouncementHistory(capacity = 2)
        history.record(request("가장 오래된 비밀 문장"))
        history.record(request("둘"))
        history.record(request("셋"))
        assertEquals(2, history.size())
        assertEquals("셋", history.last()?.text)
        assertFalse(history.toString().contains("비밀"))
        assertFalse(history.toString().contains("셋"))
    }

    private companion object {
        const val NOTHING = "다시 들을 안내가 없어요."
    }
}
