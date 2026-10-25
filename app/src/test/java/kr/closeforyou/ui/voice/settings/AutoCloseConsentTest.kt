package kr.closeforyou.ui.voice.settings

import kr.closeforyou.R
import kr.closeforyou.core.model.PopupClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val AD = PopupClassification.ADVERTISEMENT
private val NOTICE_KIND = PopupClassification.NOTICE
private val COUPON_KIND = PopupClassification.COUPON

class AutoCloseConsentTest {
    private fun started(preset: HandlingPreset = HandlingPreset.DEFAULT) =
        UiVoiceSettings(handlingPreset = preset, onboardingDone = true)

    private fun consented(preset: HandlingPreset) = started(preset).withAutoCloseConsent(true, 10L)

    // --- default and the three gates ---

    @Test fun consentIsOffByDefault() {
        val settings = UiVoiceSettings()
        assertFalse(settings.autoCloseConsent)
        assertNull(settings.autoCloseConsentPreset)
        assertNull(settings.autoCloseConsentChangedAt)
        assertEquals(AutoCloseConsent.NONE, settings.autoCloseConsentNow(live = true))
    }

    /** A 10/25: choosing a preset (and finishing first run) is not consent. */
    @Test fun noConsentMeansEmpty() = HandlingPreset.entries.forEach { preset ->
        assertTrue("$preset", started(preset).autoCloseKinds(live = true).isEmpty())
        assertFalse("$preset", started(preset).autoCloseConsentNow(live = true).granted)
    }

    @Test fun notLiveMeansEmptyEvenWithConsent() = HandlingPreset.entries.forEach { preset ->
        assertTrue("$preset", consented(preset).autoCloseKinds(live = false).isEmpty())
        assertFalse("$preset", consented(preset).autoCloseConsentNow(live = false).granted)
        // The build default: live is false, so today nothing is exported.
        assertTrue("$preset", consented(preset).autoCloseKinds().isEmpty())
    }

    @Test fun beforeFirstRunIsFinishedMeansEmpty() = HandlingPreset.entries.forEach { preset ->
        val notStarted = UiVoiceSettings(handlingPreset = preset).withAutoCloseConsent(true, 10L)
        assertTrue("$preset", notStarted.autoCloseKinds(live = true).isEmpty())
    }

    @Test fun kindsByPresetWithConsent() {
        assertEquals(setOf(AD), consented(HandlingPreset.CLOSE_ADS_ONLY).autoCloseKinds(live = true))
        assertEquals(setOf(AD, COUPON_KIND), consented(HandlingPreset.CLOSE_ADS_AND_COUPONS).autoCloseKinds(live = true))
        assertEquals(setOf(AD, COUPON_KIND, NOTICE_KIND), consented(HandlingPreset.CLOSE_ALL).autoCloseKinds(live = true))
    }

    @Test fun decisionAndUnknownAreNeverIncluded() = HandlingPreset.entries.forEach { from ->
        HandlingPreset.entries.forEach { to ->
            listOf(false, true).forEach { confirmed ->
                val kinds = consented(from).withPreset(to, 20L, confirmed).autoCloseKinds(live = true)
                assertFalse("$from→$to", PopupClassification.USER_DECISION in kinds)
                assertFalse("$from→$to", PopupClassification.UNKNOWN in kinds)
            }
        }
    }

    @Test fun grantedMatchesExportedKinds() {
        val consent = consented(HandlingPreset.CLOSE_ADS_AND_COUPONS).autoCloseConsentNow(live = true)
        assertTrue(consent.granted)
        assertEquals(setOf(AD, COUPON_KIND), consent.autoCloseKinds)
        assertEquals(10L, consent.changedAtEpochMillis)
    }

    // --- switch off ---

    @Test fun turningOffEmptiesAtOnce() = HandlingPreset.entries.forEach { preset ->
        val off = consented(preset).withAutoCloseConsent(false, 30L)
        assertFalse(off.autoCloseConsent)
        assertNull(off.autoCloseConsentPreset)
        assertEquals(30L, off.autoCloseConsentChangedAt)
        assertTrue(off.autoCloseKinds(live = true).isEmpty())
        assertFalse(off.autoCloseConsentNow(live = true).granted)
    }

    @Test fun switchIsIndependentOfThePerAppF12Switches() {
        val settings = consented(HandlingPreset.CLOSE_ALL)
        assertTrue(settings.autoClosePackages.isEmpty())
        assertTrue(settings.withAutoClose("kr.a", true).autoCloseConsent)
        assertFalse(started().withAutoClose("kr.a", true).autoCloseConsent)
    }

    // --- preset change while on ---

    @Test fun narrowerPresetKeepsConsentAndNarrowsAtOnce() {
        val narrowed = consented(HandlingPreset.CLOSE_ALL).withPreset(HandlingPreset.CLOSE_ADS_ONLY, 40L)
        assertTrue(narrowed.autoCloseConsent)
        assertEquals(HandlingPreset.CLOSE_ADS_ONLY, narrowed.autoCloseConsentPreset)
        assertEquals(40L, narrowed.autoCloseConsentChangedAt)
        assertEquals(setOf(AD), narrowed.autoCloseKinds(live = true))
    }

    /** Wider without the dialog: the preset shows, but the exported kinds stay in the confirmed range. */
    @Test fun widerPresetWithoutConfirmDoesNotWiden() {
        val widened = consented(HandlingPreset.CLOSE_ADS_ONLY).withPreset(HandlingPreset.CLOSE_ALL, 40L)
        assertTrue(widened.autoCloseConsent)
        assertEquals(HandlingPreset.CLOSE_ALL, widened.handlingPreset)
        assertEquals(HandlingPreset.CLOSE_ADS_ONLY, widened.autoCloseConsentPreset)
        assertEquals(10L, widened.autoCloseConsentChangedAt)
        assertEquals(setOf(AD), widened.autoCloseKinds(live = true))
    }

    @Test fun widerPresetWithConfirmWidens() {
        val widened = consented(HandlingPreset.CLOSE_ADS_ONLY).withPreset(HandlingPreset.CLOSE_ALL, 40L, widerConfirmed = true)
        assertEquals(HandlingPreset.CLOSE_ALL, widened.autoCloseConsentPreset)
        assertEquals(40L, widened.autoCloseConsentChangedAt)
        assertEquals(setOf(AD, COUPON_KIND, NOTICE_KIND), widened.autoCloseKinds(live = true))
    }

    /** Narrow then back up: the old confirmation does not come back by itself. */
    @Test fun narrowThenWidenAsksAgain() {
        val back = consented(HandlingPreset.CLOSE_ALL)
            .withPreset(HandlingPreset.CLOSE_ADS_ONLY, 40L)
            .withPreset(HandlingPreset.CLOSE_ALL, 50L)
        assertEquals(setOf(AD), back.autoCloseKinds(live = true))
        assertTrue(AutoCloseConsentPolicy.needsReconfirm(true, HandlingPreset.CLOSE_ADS_ONLY, HandlingPreset.CLOSE_ALL))
    }

    @Test fun presetChangeWithoutConsentStaysWithoutConsent() {
        val changed = started().withPreset(HandlingPreset.CLOSE_ALL, 40L, widerConfirmed = true)
        assertFalse(changed.autoCloseConsent)
        assertNull(changed.autoCloseConsentPreset)
        assertTrue(changed.autoCloseKinds(live = true).isEmpty())
    }

    @Test fun reconfirmOnlyWhenOnAndWider() {
        val presets = HandlingPreset.entries
        presets.forEach { from ->
            presets.forEach { to ->
                assertFalse("$from→$to off", AutoCloseConsentPolicy.needsReconfirm(false, from, to))
                val wider = !from.autoCloseKinds().containsAll(to.autoCloseKinds())
                assertEquals("$from→$to", wider, AutoCloseConsentPolicy.needsReconfirm(true, from, to))
            }
        }
        assertTrue(AutoCloseConsentPolicy.needsReconfirm(true, HandlingPreset.CLOSE_ADS_ONLY, HandlingPreset.CLOSE_ADS_AND_COUPONS))
        assertFalse(AutoCloseConsentPolicy.needsReconfirm(true, HandlingPreset.CLOSE_ALL, HandlingPreset.CLOSE_ADS_AND_COUPONS))
    }

    // --- first run ---

    @Test fun firstRunWithSwitchOffHasNoConsent() {
        val done = UiVoiceSettings().completedOnboarding(HandlingPreset.CLOSE_ALL, autoCloseConsent = false, nowEpochMillis = 5L)
        assertTrue(done.onboardingDone)
        assertFalse(done.autoCloseConsent)
        assertTrue(done.autoCloseKinds(live = true).isEmpty())
    }

    @Test fun firstRunWithSwitchOnBindsTheChosenPreset() = HandlingPreset.entries.forEach { preset ->
        val done = UiVoiceSettings().completedOnboarding(preset, autoCloseConsent = true, nowEpochMillis = 5L)
        assertEquals(preset, done.autoCloseConsentPreset)
        assertEquals(5L, done.autoCloseConsentChangedAt)
        assertEquals(preset.autoCloseKinds(), done.autoCloseKinds(live = true))
    }

    /** Debug page reruns first run: switch shown off → consent cleared. */
    @Test fun firstRunAgainWithSwitchOffClearsConsent() {
        val done = consented(HandlingPreset.CLOSE_ALL).completedOnboarding(HandlingPreset.CLOSE_ALL, false, 6L)
        assertFalse(done.autoCloseConsent)
        assertTrue(done.autoCloseKinds(live = true).isEmpty())
    }

    @Test fun sourceNoneHasNoConsent() = assertEquals(AutoCloseConsent.NONE, AutoCloseConsentSource.NONE.current())

    // --- dialog texts ---

    private val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
    private val names = Regex("""<string name="([a-z_0-9]+)">""").findAll(strings).map { it.groupValues[1] }.toList()
    private fun text(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(strings)!!.groupValues[1]
    private fun textOf(res: Int): String {
        val name = R.string::class.java.fields.first { it.getInt(null) == res }.name
        return text(name)
    }

    @Test fun dialogKindsLineFollowsThePreset() {
        val ads = textOf(ConsentText.kindsRes(HandlingPreset.CLOSE_ADS_ONLY))
        val adsCoupons = textOf(ConsentText.kindsRes(HandlingPreset.CLOSE_ADS_AND_COUPONS))
        val all = textOf(ConsentText.kindsRes(HandlingPreset.CLOSE_ALL))
        assertTrue(ads.contains("광고") && !ads.contains("쿠폰") && !ads.contains("공지"))
        assertTrue(adsCoupons.contains("광고") && adsCoupons.contains("쿠폰"))
        assertTrue(all.contains("광고") && all.contains("쿠폰") && all.contains("공지"))
        assertEquals(3, HandlingPreset.entries.map(ConsentText::kindsRes).toSet().size)
    }

    @Test fun dialogSaysImportantPopupsAreAlwaysChecked() {
        val safety = textOf(R.string.ui_preset_consent_safety)
        assertTrue(safety.contains("결제") && safety.contains("동의") && safety.contains("항상"))
        HandlingPreset.entries.forEach { assertTrue(R.string.ui_preset_consent_safety in ConsentText.bodyRes(it, live = true)) }
    }

    @Test fun dialogSaysItStillAsksWhileNotLive() = HandlingPreset.entries.forEach { preset ->
        assertTrue(R.string.ui_preset_consent_not_live in ConsentText.bodyRes(preset, live = false))
        assertFalse(R.string.ui_preset_consent_not_live in ConsentText.bodyRes(preset, live = true))
        assertTrue(textOf(R.string.ui_preset_consent_not_live).contains("물어봐요"))
    }

    /** TalkBack may read the dialog while the mic listens; no voice command words in it. */
    @Test fun dialogAndSwitchHelpHaveNoCommandWords() {
        val forbidden = listOf("닫아", "닫기", "닫자", "넘겨", "넘어가", "건너뛰", "스킵", "읽어", "그대로", "놔둬", "뒤로", "돌아가", "버튼으로")
        val checked = names.filter { it.startsWith("ui_preset_consent_") && it != "ui_preset_consent_switch" }
        assertTrue(checked.size >= 11)
        checked.forEach { name ->
            val line = text(name)
            forbidden.forEach { assertFalse("$name: $line", line.contains(it)) }
        }
    }

    @Test fun switchHelpFollowsTheState() {
        assertTrue(textOf(ConsentText.switchHelpRes(false)).contains("물어봐요"))
        assertTrue(textOf(ConsentText.switchHelpRes(true)).contains("끌 수 있어요"))
    }

    // --- first run starts with no preset chosen (A #34 10/25) ---

    @Test fun firstRunShowsNoPresetChosen() {
        assertNull(OnboardingGatePolicy.initialPreset(UiVoiceSettings()))
        // Even a stored preset (e.g. a debug run) is not shown as chosen before setup is finished.
        HandlingPreset.entries.forEach { assertNull("$it", OnboardingGatePolicy.initialPreset(UiVoiceSettings(handlingPreset = it))) }
    }

    @Test fun nextWaitsForAPreset() {
        assertFalse(OnboardingGatePolicy.canContinue(null))
        HandlingPreset.entries.forEach { assertTrue("$it", OnboardingGatePolicy.canContinue(it)) }
        assertTrue(textOf(R.string.ui_onboarding_choose_preset_first).contains("고르면"))
    }

    @Test fun savedPresetIsKeptAfterSetup() = HandlingPreset.entries.forEach { preset ->
        assertEquals(preset, OnboardingGatePolicy.initialPreset(UiVoiceSettings(handlingPreset = preset, onboardingDone = true)))
    }

    /** No saved value: the internal safe default stays (settings screen shows it), only first run hides it. */
    @Test fun internalSafeDefaultIsKept() {
        assertEquals(HandlingPreset.CLOSE_ADS_ONLY, HandlingPreset.DEFAULT)
        assertEquals(HandlingPreset.DEFAULT, UiVoiceSettings().handlingPreset)
        assertEquals(HandlingPreset.DEFAULT, UiVoicePreferences.enumOrDefault<HandlingPreset>(null, HandlingPreset.DEFAULT))
        assertEquals(HandlingPreset.DEFAULT, OnboardingGatePolicy.initialPreset(UiVoiceSettings(onboardingDone = true)))
        assertTrue(AutoCloseConsentPolicy.switchEnabled(UiVoiceSettings(onboardingDone = true).handlingPreset))
    }

    @Test fun switchIsDisabledUntilAPresetIsChosen() {
        assertFalse(AutoCloseConsentPolicy.switchEnabled(null))
        HandlingPreset.entries.forEach { assertTrue("$it", AutoCloseConsentPolicy.switchEnabled(it)) }
        assertEquals(R.string.ui_preset_consent_switch_choose_first, ConsentText.switchHelpRes(on = false, enabled = false))
        assertTrue(textOf(R.string.ui_preset_consent_switch_choose_first).contains("고르면"))
        // Off by default, and no range is "wider" to confirm while it is off.
        assertFalse(UiVoiceSettings().autoCloseConsent)
        HandlingPreset.entries.forEach { assertFalse("$it", AutoCloseConsentPolicy.needsReconfirm(false, null, it)) }
    }

    @Test fun choosingAPresetOnFirstRunStillExportsNothing() = HandlingPreset.entries.forEach { preset ->
        val started = UiVoiceSettings().completedOnboarding(preset, autoCloseConsent = false, nowEpochMillis = 5L)
        assertEquals(preset, started.handlingPreset)
        assertTrue("$preset", started.autoCloseKinds(live = true).isEmpty())
        assertTrue("$preset", started.autoCloseKinds().isEmpty())
        // Even with the switch confirmed, the build default (not live) exports nothing.
        assertTrue("$preset", UiVoiceSettings().completedOnboarding(preset, true, 5L).autoCloseKinds().isEmpty())
    }
}
