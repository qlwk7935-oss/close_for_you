package kr.closeforyou.ui.voice.settings

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PresetStorageAndTextTest {
    @Test fun nothingStoredMeansFirstRunAndDefaultPreset() {
        val read = UiVoicePreferences.readFrom(MapPreferences())
        assertFalse(read.onboardingDone)
        assertEquals(HandlingPreset.DEFAULT, read.handlingPreset)
    }

    @Test fun presetAndFirstRunAreSavedAndRestored() = HandlingPreset.entries.forEach { preset ->
        val store = MapPreferences()
        val saved = UiVoiceSettings(handlingPreset = preset, onboardingDone = true, autoClosePackages = setOf("kr.a"))
        store.edit().also { UiVoicePreferences.writeTo(it, saved) }.apply()
        assertEquals(saved, UiVoicePreferences.readFrom(store))
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
        assertFalse(settings.autoCloseAllowed("kr.a", setOf("kr.a")))
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

    @Test fun decisionLineSaysImportantPopupsAreAlwaysChecked() {
        val line = text("ui_preset_decision_fixed")
        assertTrue(line.contains("결제") && line.contains("동의") && line.contains("항상"))
    }

    /** Coupons are not told apart yet; the text must not say they are handled as ads. */
    @Test fun couponLineDoesNotClaimCouponsAreAds() {
        val line = text("ui_preset_coupon_pending")
        assertTrue(line.contains("준비 중"))
        assertFalse(line.contains("광고"))
    }

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
            values.putAll(pending)
        }
    }
}
