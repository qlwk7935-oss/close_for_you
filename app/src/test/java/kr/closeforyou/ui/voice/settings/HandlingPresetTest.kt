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

private val AD = PopupClassification.ADVERTISEMENT
private val NOTICE_KIND = PopupClassification.NOTICE
private val COUPON_KIND = PopupClassification.COUPON

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
            assertEquals(preset.preferenceFor(PresetPopupKind.COUPON), preset.preferenceFor(PopupClassification.COUPON))
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

    // --- contract v2 DecisionContext.autoCloseKinds ---

    @Test fun autoCloseKindsByPreset() {
        assertEquals(setOf(AD, NOTICE_KIND, COUPON_KIND), HandlingPreset.CLOSE_ALL.autoCloseKinds())
        assertEquals(setOf(AD, COUPON_KIND), HandlingPreset.CLOSE_ADS_AND_COUPONS.autoCloseKinds())
        assertEquals(setOf(AD), HandlingPreset.CLOSE_ADS_ONLY.autoCloseKinds())
    }

    @Test fun autoCloseKindsNeverHoldDecisionOrUnknown() = HandlingPreset.entries.forEach { preset ->
        listOf(true, false).forEach { done ->
            val kinds = UiVoiceSettings(handlingPreset = preset, onboardingDone = done).autoCloseKinds(live = true)
            assertFalse("$preset", PopupClassification.USER_DECISION in kinds)
            assertFalse("$preset", PopupClassification.UNKNOWN in kinds)
        }
    }

    /** A 10/23: a preset shown as chosen on the first-run screen is not consent until [시작하기]. */
    @Test fun nothingBeforeStartIsPressed() = HandlingPreset.entries.forEach { preset ->
        assertTrue("$preset", UiVoiceSettings(handlingPreset = preset).autoCloseKinds(live = true).isEmpty())
        assertTrue(UiVoiceSettings().autoCloseKinds(live = true).isEmpty())
    }

    @Test fun afterStartThePresetIsExported() = HandlingPreset.entries.forEach { preset ->
        assertEquals(preset.autoCloseKinds(), UiVoiceSettings(handlingPreset = preset, onboardingDone = true).autoCloseKinds(live = true))
    }

    /** While the screens say "지금은 닫기 전에 물어봐요", nothing is exported (default today). */
    @Test fun notLiveExportsNothing() = HandlingPreset.entries.forEach { preset ->
        assertTrue(UiVoiceSettings(handlingPreset = preset, onboardingDone = true).autoCloseKinds().isEmpty())
    }

    @Test fun firstRunGate() {
        assertTrue(OnboardingGatePolicy.showOnboarding(UiVoiceSettings()))
        assertFalse(OnboardingGatePolicy.showOnboarding(UiVoiceSettings(onboardingDone = true)))
    }
}
