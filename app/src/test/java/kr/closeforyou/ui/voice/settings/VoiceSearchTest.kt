package kr.closeforyou.ui.voice.settings

import kr.closeforyou.R
import kr.closeforyou.core.contract.VoiceAvailability
import kr.closeforyou.ui.voice.command.DictationResult
import kr.closeforyou.ui.voice.command.MicrophoneAccess
import kr.closeforyou.ui.voice.command.SpeakButtonPolicy
import kr.closeforyou.ui.voice.command.SpeakButtonState
import kr.closeforyou.ui.voice.command.VoiceDictation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchTest {
    // --- VoiceSearchQuery ---

    @Test fun requestEndingsAreDropped() {
        assertEquals("카카오톡", VoiceSearchQuery.from("카카오톡 찾아 줘"))
        assertEquals("카카오톡", VoiceSearchQuery.from("  카카오톡 찾아줘.  "))
        assertEquals("유튜브", VoiceSearchQuery.from("유튜브 검색"))
        assertEquals("유튜브", VoiceSearchQuery.from("유튜브 검색해 주세요"))
        assertEquals("쿠팡이츠", VoiceSearchQuery.from("쿠팡이츠 찾아주세요"))
    }

    @Test fun appWordAndParticleGoWithTheEnding() {
        assertEquals("유튜브", VoiceSearchQuery.from("유튜브 앱을 찾아 줘"))
        assertEquals("카카오톡", VoiceSearchQuery.from("카카오톡을 찾아줘"))
        assertEquals("배달의민족", VoiceSearchQuery.from("배달의민족 어플"))
    }

    @Test fun plainNamesStayAsTheyAre() {
        assertEquals("카카오맵", VoiceSearchQuery.from("카카오맵"))
        assertEquals("네이버 웹툰", VoiceSearchQuery.from("네이버 웹툰"))
        // A particle on its own is part of the name, not a request ending.
        assertEquals("하루를", VoiceSearchQuery.from("하루를"))
    }

    @Test fun nothingLeftMeansNoSearch() {
        assertNull(VoiceSearchQuery.from(null))
        assertNull(VoiceSearchQuery.from(""))
        assertNull(VoiceSearchQuery.from("   "))
        assertNull(VoiceSearchQuery.from("찾아 줘"))
        assertNull(VoiceSearchQuery.from("앱 검색"))
        assertNull(VoiceSearchQuery.from("?!"))
    }

    // --- button shown or hidden (same policy as the prompt's [말하기]) ---

    private class Mic(val declared: Boolean = true, val granted: Boolean = true, val onDevice: Boolean = true) : MicrophoneAccess {
        override fun declared() = declared
        override fun granted() = granted
        override fun onDeviceRecognizerAvailable() = onDevice
    }

    private fun shown(mic: Mic, setting: Boolean = true) =
        VoiceSearchState(SpeakButtonPolicy.state(featureEnabled = true, settingEnabled = setting, microphone = mic)).buttonVisible

    @Test fun buttonOnlyWhenSupportedAllowedAndOn() {
        assertTrue(shown(Mic()))
        assertFalse("LG: no on-device recognizer", shown(Mic(onDevice = false)))
        assertFalse("no microphone permission", shown(Mic(granted = false)))
        assertFalse("release: RECORD_AUDIO not declared", shown(Mic(declared = false)))
        assertFalse("voice commands off in settings", shown(Mic(), setting = false))
    }

    // --- controller ---

    private class FakeDictation(var availability: VoiceAvailability = VoiceAvailability.READY) : VoiceDictation {
        var pending: ((DictationResult) -> Unit)? = null
        var starts = 0
        var cancels = 0
        override fun start(onResult: (DictationResult) -> Unit): VoiceAvailability {
            starts++
            if (availability == VoiceAvailability.READY) pending = onResult
            return availability
        }
        override fun cancel() {
            cancels++
        }
        fun deliver(result: DictationResult) = pending!!.invoke(result)
    }

    private fun controller(dictation: FakeDictation, button: SpeakButtonState = SpeakButtonState.SHOWN): Pair<VoiceSearchController, MutableList<String>> {
        val queries = mutableListOf<String>()
        return VoiceSearchController(dictation, button) { queries += it } to queries
    }

    @Test fun heardNameBecomesTheQuery() {
        val dictation = FakeDictation()
        val (search, queries) = controller(dictation)
        search.onPress()
        assertTrue(search.state.value.listening)
        assertEquals(R.string.ui_voice_listening, search.state.value.messageRes)
        dictation.deliver(DictationResult.Heard("카카오톡 찾아 줘"))
        assertEquals(listOf("카카오톡"), queries)
        assertFalse(search.state.value.listening)
        // The existing result count is read instead of another sentence.
        assertNull(search.state.value.messageRes)
    }

    @Test fun cancelWhileListeningKeepsTheQuery() {
        val dictation = FakeDictation()
        val (search, queries) = controller(dictation)
        search.onPress()
        search.onPress()
        assertEquals(1, dictation.cancels)
        assertFalse(search.state.value.listening)
        assertEquals(R.string.ui_voice_listen_cancelled, search.state.value.messageRes)
        dictation.deliver(DictationResult.Heard("유튜브")) // late result of the cancelled session
        assertTrue(queries.isEmpty())
    }

    @Test fun leavingTheScreenStopsQuietly() {
        val dictation = FakeDictation()
        val (search, queries) = controller(dictation)
        search.onPress()
        search.stop()
        assertEquals(1, dictation.cancels)
        assertNull(search.state.value.messageRes)
        dictation.deliver(DictationResult.Heard("유튜브"))
        assertTrue(queries.isEmpty())
    }

    @Test fun emptyOrFailedResultsSaySoAndDoNotSearch() {
        val cases = listOf(
            DictationResult.Heard("찾아 줘") to R.string.ui_voice_search_not_understood,
            DictationResult.NotUnderstood to R.string.ui_voice_search_not_understood,
            DictationResult.Silent to R.string.ui_voice_search_silent,
            DictationResult.Unavailable to R.string.ui_voice_search_unavailable,
        )
        cases.forEach { (result, message) ->
            val dictation = FakeDictation()
            val (search, queries) = controller(dictation)
            search.onPress()
            dictation.deliver(result)
            assertTrue(queries.isEmpty())
            assertFalse(search.state.value.listening)
            assertEquals(message, search.state.value.messageRes)
        }
    }

    @Test fun recognizerNotReadyDoesNotListen() {
        val dictation = FakeDictation(VoiceAvailability.MICROPHONE_PERMISSION_REQUIRED)
        val (search, _) = controller(dictation)
        search.onPress()
        assertFalse(search.state.value.listening)
        assertEquals(R.string.ui_voice_permission_hint, search.state.value.messageRes)
        dictation.availability = VoiceAvailability.ON_DEVICE_UNAVAILABLE
        search.onPress()
        assertEquals(R.string.ui_voice_search_unavailable, search.state.value.messageRes)
    }

    @Test fun hiddenButtonNeverListens() {
        val dictation = FakeDictation()
        val (search, _) = controller(dictation, SpeakButtonState.DEVICE_UNSUPPORTED)
        search.onPress()
        assertEquals(0, dictation.starts)
    }

    @Test fun buttonHiddenWhileListeningStopsIt() {
        val dictation = FakeDictation()
        val (search, queries) = controller(dictation)
        search.onPress()
        search.updateButton(SpeakButtonState.TURNED_OFF)
        assertFalse(search.state.value.listening)
        assertFalse(search.state.value.buttonVisible)
        dictation.deliver(DictationResult.Heard("유튜브"))
        assertTrue(queries.isEmpty())
    }

    @Test fun heardWordsAreNotInToString() =
        assertFalse(DictationResult.Heard("카카오톡").toString().contains("카카오톡"))
}
