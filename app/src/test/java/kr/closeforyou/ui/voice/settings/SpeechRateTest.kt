package kr.closeforyou.ui.voice.settings

import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverFeature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechRateTest {
    @Test fun followSystemUsesTheDeviceTtsRate() {
        assertEquals(1.5f, SpeechRates.resolve(SpeechRateChoice.FOLLOW_SYSTEM, 150)!!, 0.001f)
    }

    @Test fun followSystemUnreadableLeavesTheEngineDefault() {
        assertNull(SpeechRates.resolve(SpeechRateChoice.FOLLOW_SYSTEM, null))
        assertNull(SpeechRates.resolve(SpeechRateChoice.FOLLOW_SYSTEM, 0))
    }

    @Test fun aFixedChoiceIgnoresTheSystemRate() {
        assertEquals(2.0f, SpeechRates.resolve(SpeechRateChoice.VERY_FAST, 80)!!, 0.001f)
        assertEquals(0.8f, SpeechRates.resolve(SpeechRateChoice.SLOW, null)!!, 0.001f)
    }

    @Test fun defaultsAreTheDemoConstantAndTheSystemRate() {
        val settings = UiVoiceSettings()
        assertEquals(TalkBackTakeoverFeature.DEFAULT_ON, settings.talkBackTakeover)
        assertEquals(SpeechRateChoice.FOLLOW_SYSTEM, settings.speechRate)
    }
}
