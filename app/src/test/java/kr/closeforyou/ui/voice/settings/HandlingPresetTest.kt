package kr.closeforyou.ui.voice.settings

import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.ui.voice.settings.PopupHandlingPreference.ANNOUNCE
import kr.closeforyou.ui.voice.settings.PopupHandlingPreference.ASK
import kr.closeforyou.ui.voice.settings.PopupHandlingPreference.CLOSE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandlingPresetTest {
    /** The planned table: preset → (ad, notice, coupon). */
    private val expected = mapOf(
        HandlingPreset.CLOSE_ALL to Triple(CLOSE, CLOSE, CLOSE),
        HandlingPreset.CLOSE_ADS_AND_COUPONS to Triple(CLOSE, ANNOUNCE, CLOSE),
        HandlingPreset.CLOSE_ADS_ONLY to Triple(CLOSE, ANNOUNCE, ANNOUNCE),
    )

    @Test fun thereAreExactlyThreePresets() = assertEquals(expected.keys, HandlingPreset.entries.toSet())

    @Test fun tableByKind() {
        expected.forEach { (preset, row) ->
            assertEquals(preset.name, row.first, preset.preferenceFor(PresetPopupKind.ADVERTISEMENT))
            assertEquals(preset.name, row.second, preset.preferenceFor(PresetPopupKind.NOTICE))
            assertEquals(preset.name, row.third, preset.preferenceFor(PresetPopupKind.COUPON))
        }
    }

    @Test fun tableByClassificationMatchesKinds() {
        HandlingPreset.entries.forEach { preset ->
            assertEquals(
                preset.preferenceFor(PresetPopupKind.ADVERTISEMENT),
                preset.preferenceFor(PopupClassification.ADVERTISEMENT),
            )
            assertEquals(preset.preferenceFor(PresetPopupKind.NOTICE), preset.preferenceFor(PopupClassification.NOTICE))
        }
    }

    @Test fun importantDecisionIsAlwaysAsked() = HandlingPreset.entries.forEach {
        assertEquals(it.name, ASK, it.preferenceFor(PopupClassification.USER_DECISION))
    }

    @Test fun unknownIsAlwaysAsked() = HandlingPreset.entries.forEach {
        assertEquals(it.name, ASK, it.preferenceFor(PopupClassification.UNKNOWN))
    }

    /** Every classification B can produce is covered; nothing except decision/unknown is ASK by accident. */
    @Test fun onlyDecisionAndUnknownAreAsk() = HandlingPreset.entries.forEach { preset ->
        PopupClassification.entries.forEach { classification ->
            val ask = preset.preferenceFor(classification) == ASK
            val mustAsk = classification == PopupClassification.USER_DECISION ||
                classification == PopupClassification.UNKNOWN
            assertEquals("$preset/$classification", mustAsk, ask)
        }
    }

    @Test fun adsAreClosedInEveryPreset() = HandlingPreset.entries.forEach {
        assertEquals(CLOSE, it.preferenceFor(PresetPopupKind.ADVERTISEMENT))
    }

    @Test fun defaultIsTheNarrowestPreset() = assertEquals(HandlingPreset.CLOSE_ADS_ONLY, HandlingPreset.DEFAULT)

    /** Real clicks are not connected; the honest "지금은 닫기 전에 물어봐요" line must stay on. */
    @Test fun autoCloseIsNotLiveYet() = assertFalse(PresetAutoCloseFeature.live)

    @Test fun everyPresetHasItsTexts() {
        assertEquals(3, HandlingPreset.entries.map(PresetText::labelRes).toSet().size)
        assertEquals(3, PopupHandlingPreference.entries.map(PresetText::preferenceRes).toSet().size)
        assertNotNull(PresetText.noteRes(HandlingPreset.CLOSE_ALL))
        assertNull(PresetText.noteRes(HandlingPreset.CLOSE_ADS_ONLY))
    }

    @Test fun firstRunGate() {
        assertTrue(OnboardingGatePolicy.showOnboarding(UiVoiceSettings()))
        assertFalse(OnboardingGatePolicy.showOnboarding(UiVoiceSettings(onboardingDone = true)))
    }
}
