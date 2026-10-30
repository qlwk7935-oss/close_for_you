package kr.closeforyou.ui.voice.settings

import android.content.Context
import android.provider.Settings

/**
 * How fast our TTS speaks, so it sounds like TalkBack. [FOLLOW_SYSTEM] uses the device's TTS rate
 * (Settings.Secure.TTS_DEFAULT_RATE, readable by apps, 100 = 1x), which TalkBack also uses unless the
 * person set TalkBack's own rate. TalkBack's own rate is kept inside TalkBack with no public API, so
 * then the person picks a fixed [multiplier] here instead.
 */
enum class SpeechRateChoice(val multiplier: Float?) {
    FOLLOW_SYSTEM(null),
    SLOW(0.8f),
    NORMAL(1.0f),
    FAST(1.5f),
    VERY_FAST(2.0f),
}

object SpeechRates {
    /**
     * The rate for TextToSpeech.setSpeechRate, or null = leave the engine on the system default
     * (TextToSpeechService falls back to it when no rate was set; AOSP 36 source).
     * [systemRatePercent]: TTS_DEFAULT_RATE, or null when it could not be read.
     */
    fun resolve(choice: SpeechRateChoice, systemRatePercent: Int?): Float? =
        choice.multiplier ?: systemRatePercent?.takeIf { it > 0 }?.let { it / 100f }

    fun systemRatePercent(context: Context): Int? = try {
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.TTS_DEFAULT_RATE)
    } catch (e: Settings.SettingNotFoundException) {
        // Never changed on this device: the engine's own default, which is also what we get by not setting it.
        null
    }

    /** Read on every utterance, so a change in our settings or the system applies to the next one. */
    fun provider(context: Context): () -> Float? {
        val app = context.applicationContext
        val preferences = UiVoicePreferences.get(app)
        return { resolve(preferences.current().speechRate, systemRatePercent(app)) }
    }
}
