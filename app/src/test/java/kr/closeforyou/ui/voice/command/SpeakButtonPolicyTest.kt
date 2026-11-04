package kr.closeforyou.ui.voice.command

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakButtonPolicyTest {
    private class Mic(val granted: Boolean = true, val onDevice: Boolean = true) : MicrophoneAccess {
        override fun granted() = granted
        override fun onDeviceRecognizerAvailable() = onDevice
    }

    private fun state(mic: Mic, feature: Boolean = true, setting: Boolean = true) =
        SpeakButtonPolicy.state(feature, setting, mic)

    @Test fun shownOnlyWhenEverythingIsReady() = assertEquals(SpeakButtonState.SHOWN, state(Mic()))

    @Test fun flagOrSettingOffHidesFirst() {
        assertEquals(SpeakButtonState.TURNED_OFF, state(Mic(), feature = false))
        assertEquals(SpeakButtonState.TURNED_OFF, state(Mic(), setting = false))
    }

    /** Asking for the microphone would not help on a device without on-device recognition (LG). */
    @Test fun deviceWithoutOnDeviceRecognizer() =
        assertEquals(SpeakButtonState.DEVICE_UNSUPPORTED, state(Mic(granted = false, onDevice = false)))

    @Test fun notAllowed() = assertEquals(SpeakButtonState.PERMISSION_NEEDED, state(Mic(granted = false)))
}
