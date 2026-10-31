package kr.closeforyou.ui.voice.settings

import android.content.SharedPreferences
import kr.closeforyou.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetStorageAndTextTest {
    /** LG 10/30: a phone with old saved values showed a preset chosen. A real first run has none. */
    @Test fun nothingStoredOpensSetupWithNothingChosen() {
        val draft = OnboardingGatePolicy.initialDraft(UiVoicePreferences.readFrom(MapPreferences()))
        assertEquals(OnboardingStep.WELCOME, draft.step)
        assertEquals(null, draft.preset)
        assertFalse(draft.autoCloseConsent)
        assertEquals(null, draft.helpApps.mode)
    }

    /** [처음 설정 다시 하기] and the debug entry also start with nothing chosen, even with values saved. */
    @Test fun restartedSetupOpensWithNothingChosen() {
        val store = MapPreferences()
        val saved = UiVoiceSettings(handlingPreset = HandlingPreset.CLOSE_ALL, onboardingDone = true).withAutoCloseConsent(true, 1L)
        store.edit().also { UiVoicePreferences.writeTo(it, saved) }.apply()
        val restarted = UiVoicePreferences.readFrom(store).copy(onboardingDone = false)
        assertEquals(null, OnboardingGatePolicy.initialDraft(restarted).preset)
        assertFalse(OnboardingGatePolicy.initialDraft(restarted).autoCloseConsent)
        assertEquals(null, OnboardingGatePolicy.firstRunDraft(UiVoicePreferences.readFrom(store)).preset)
    }

    @Test fun nothingStoredMeansFirstRunAndDefaultPreset() {
        val read = UiVoicePreferences.readFrom(MapPreferences())
        assertFalse(read.onboardingDone)
        assertEquals(HandlingPreset.DEFAULT, read.handlingPreset)
    }

    @Test fun takeoverAndSpeechRateAreSavedAndRestored() = SpeechRateChoice.entries.forEach { rate ->
        val store = MapPreferences()
        val saved = UiVoiceSettings(talkBackTakeover = !kr.closeforyou.ui.voice.overlay.TalkBackTakeoverFeature.DEFAULT_ON, speechRate = rate)
        store.edit().also { UiVoicePreferences.writeTo(it, saved) }.apply()
        assertEquals(saved, UiVoicePreferences.readFrom(store))
    }

    @Test fun nothingStoredMeansTakeoverDefaultAndSystemRate() {
        val read = UiVoicePreferences.readFrom(MapPreferences())
        assertEquals(kr.closeforyou.ui.voice.overlay.TalkBackTakeoverFeature.DEFAULT_ON, read.talkBackTakeover)
        assertEquals(SpeechRateChoice.FOLLOW_SYSTEM, read.speechRate)
    }

    @Test fun presetAndFirstRunAreSavedAndRestored() = HandlingPreset.entries.forEach { preset ->
        val store = MapPreferences()
        val saved = UiVoiceSettings(handlingPreset = preset, onboardingDone = true, autoClosePackages = setOf("kr.a"))
        store.edit().also { UiVoicePreferences.writeTo(it, saved) }.apply()
        assertEquals(saved, UiVoicePreferences.readFrom(store))
    }

    @Test fun nothingStoredMeansNoAutoCloseConsent() {
        val read = UiVoicePreferences.readFrom(MapPreferences())
        assertFalse(read.autoCloseConsent)
        assertEquals(null, read.autoCloseConsentPreset)
        assertEquals(null, read.autoCloseConsentChangedAt)
    }

    @Test fun autoCloseConsentIsSavedAndRestored() = HandlingPreset.entries.forEach { preset ->
        val on = UiVoiceSettings(handlingPreset = preset, onboardingDone = true).withAutoCloseConsent(true, 1234L)
        val off = on.withAutoCloseConsent(false, 5678L)
        listOf(on, off, UiVoiceSettings()).forEach { saved ->
            val store = MapPreferences()
            store.edit().also { UiVoicePreferences.writeTo(it, saved) }.apply()
            assertEquals(saved, UiVoicePreferences.readFrom(store))
        }
    }

    /** Turning consent off after it was on: the stored range and time are rewritten, not left over. */
    @Test fun consentOffOverwritesTheStoredRange() {
        val store = MapPreferences()
        val on = UiVoiceSettings(handlingPreset = HandlingPreset.CLOSE_ALL, onboardingDone = true).withAutoCloseConsent(true, 1L)
        store.edit().also { UiVoicePreferences.writeTo(it, on) }.apply()
        store.edit().also { UiVoicePreferences.writeTo(it, on.withAutoCloseConsent(false, 2L)) }.apply()
        val read = UiVoicePreferences.readFrom(store)
        assertFalse(read.autoCloseConsent)
        assertEquals(null, read.autoCloseConsentPreset)
        assertTrue(read.autoCloseKinds(live = true).isEmpty())
    }

    /** A stored range this build does not know means no confirmed range: nothing is exported. */
    @Test fun unknownStoredConsentPresetExportsNothing() {
        val store = MapPreferences()
        store.edit().putBoolean("auto_close_consent_v1", true).putBoolean("onboarding_done_v1", true)
            .putString("auto_close_consent_preset_v1", "CLOSE_EVERYTHING_NOW").apply()
        val read = UiVoicePreferences.readFrom(store)
        assertTrue(read.autoCloseConsent)
        assertTrue(read.autoCloseKinds(live = true).isEmpty())
    }

    @Test fun unknownStoredPresetFallsBackToDefault() {
        val store = MapPreferences()
        store.edit().putString("handling_preset_v1", "CLOSE_EVERYTHING_NOW").apply()
        assertEquals(HandlingPreset.DEFAULT, UiVoicePreferences.readFrom(store).handlingPreset)
    }

    /** Presets do not touch the per-app F12 switches (and the other way round). */
    @Test fun presetAndPerAppAutoCloseStaySeparate() {
        val settings = UiVoiceSettings().copy(handlingPreset = HandlingPreset.CLOSE_ALL)
        assertTrue(settings.autoClosePackages.isEmpty())
        assertFalse(settings.autoCloseAllowed("kr.a", setOf("kr.a"), perAppGate = true))
        assertEquals(HandlingPreset.CLOSE_ALL, settings.withAutoClose("kr.a", true).handlingPreset)
    }

    private val strings = java.io.File("src/main/res/values/ui_voice_strings.xml").readText()
    private fun text(name: String) =
        Regex("""<string name="$name">([^<]*)</string>""").find(strings)!!.groupValues[1]
    private val presetTexts = Regex("""<string name="(ui_preset_[a-z_]+|ui_onboarding_[a-z_]+)">""")
        .findAll(strings).map { it.groupValues[1] }.toList()

    @Test fun presetAndFirstRunTextsNeverClaimSomethingWasClosed() {
        assertTrue(presetTexts.size >= 15)
        presetTexts.forEach { name ->
            val line = text(name)
            listOf("닫았어요", "닫혔어요", "닫아 드렸어요", "차단했어요").forEach {
                assertFalse("$name: $line", line.contains(it))
            }
        }
    }

    @Test fun closeAllGetsItsOwnUnknownLineOnlyWithTheDemoSwitch() {
        assertEquals(R.string.ui_preset_decision_fixed_close_all, PresetText.decisionLineRes(HandlingPreset.CLOSE_ALL, unknownAutoClose = true))
        assertEquals(R.string.ui_preset_decision_fixed, PresetText.decisionLineRes(HandlingPreset.CLOSE_ALL, unknownAutoClose = false))
        assertEquals(R.string.ui_preset_decision_fixed, PresetText.decisionLineRes(HandlingPreset.CLOSE_ADS_ONLY, unknownAutoClose = true))
        assertEquals(R.string.ui_preset_decision_fixed, PresetText.decisionLineRes(null, unknownAutoClose = true))
        val line = text("ui_preset_decision_fixed_close_all")
        assertTrue(line.contains("결제") && line.contains("동의") && line.contains("항상") && line.contains("버튼이 하나뿐"))
        assertTrue(R.string.ui_preset_consent_unknown_all in ConsentText.bodyRes(HandlingPreset.CLOSE_ALL, live = true, unknownAutoClose = true))
        assertFalse(R.string.ui_preset_consent_unknown_all in ConsentText.bodyRes(HandlingPreset.CLOSE_ADS_AND_COUPONS, live = true, unknownAutoClose = true))
    }

    @Test fun decisionLineSaysImportantPopupsAreAlwaysChecked() {
        val line = text("ui_preset_decision_fixed")
        assertTrue(line.contains("결제") && line.contains("동의") && line.contains("항상"))
    }

    /** Contract v2 COUPON is connected: the "준비 중" line is gone. */
    @Test fun couponPendingLineIsGone() = assertFalse(strings.contains("ui_preset_coupon_pending"))

    @Test fun notLiveLineSaysItAsksFirst() = assertTrue(text("ui_preset_not_live").contains("물어봐요"))
}

/** Minimal in-memory SharedPreferences for JVM tests (no Android runtime). */
private class MapPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()
    override fun getAll(): Map<String, *> = values.toMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { pending[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { values.clear() }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            // Like the real one: a removed (null) key is gone, not stored as null.
            pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
