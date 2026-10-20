package kr.closeforyou.ui.voice.settings

import kr.closeforyou.ui.voice.overlay.PromptAnnounceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UiVoiceSettingsTest {
    private val shop = "kr.example.shop"

    @Test fun defaultsAreSafe() {
        val settings = UiVoiceSettings()
        assertTrue(settings.autoClosePackages.isEmpty())
        assertFalse(settings.autoCloseAllowed(shop, setOf(shop)))
        assertEquals(AnnouncementDetail.SHORT, settings.detail)
        assertEquals(PromptAnnounceMode.PANE_TITLE_AND_TTS, settings.promptAnnounceMode)
        // [말하기] is shown by default; it still listens only when pressed.
        assertTrue(settings.voiceCommandsEnabled)
    }

    @Test fun voiceCommandsCanBeTurnedOff() =
        assertFalse(UiVoiceSettings().copy(voiceCommandsEnabled = false).voiceCommandsEnabled)

    @Test fun autoCloseIsPerAppAndCanBeTurnedOff() {
        val on = UiVoiceSettings().withAutoClose(shop, true)
        assertTrue(on.autoCloseAllowed(shop, setOf(shop, "kr.example.news")))
        assertFalse(on.autoCloseAllowed("kr.example.news", setOf(shop, "kr.example.news")))
        assertFalse(on.withAutoClose(shop, false).autoCloseAllowed(shop, setOf(shop)))
    }

    @Test fun autoCloseNeedsTheAppToStillBeAllowedByCore() {
        val on = UiVoiceSettings().withAutoClose(shop, true)
        assertFalse(on.autoCloseAllowed(shop, coreAllowedPackages = emptySet()))
    }

    @Test fun unknownStoredEnumFallsBackToDefault() {
        assertEquals(AnnouncementDetail.SHORT, UiVoicePreferences.enumOrDefault("LOUD", AnnouncementDetail.SHORT))
        assertEquals(AnnouncementDetail.SHORT, UiVoicePreferences.enumOrDefault(null, AnnouncementDetail.SHORT))
        assertEquals(
            AnnouncementDetail.DETAILED,
            UiVoicePreferences.enumOrDefault("DETAILED", AnnouncementDetail.SHORT),
        )
    }
}
