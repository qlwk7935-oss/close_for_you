package com.team.popup.experiment

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * 안내 방식 (c) 자체 TTS.
 *
 * 오디오 용도를 '접근성 보조'로 지정해서 TalkBack과 같은 접근성 음량을 쓰게 한다.
 * TalkBack 낭독과 겹치는지는 실기기에서 확인해야 한다.
 */
class Speaker(context: Context) {
    private var ready = false
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS && configureKorean()
    }

    private fun configureKorean(): Boolean {
        val result = tts.setLanguage(Locale.KOREAN)
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        return result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    /** 준비가 안 됐거나 한국어가 없으면 false. */
    fun speak(text: String): Boolean {
        if (!ready) return false
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        return true
    }

    fun stop() {
        tts.stop()
    }

    fun shutdown() {
        tts.shutdown()
    }

    private companion object {
        const val UTTERANCE_ID = "overlay_experiment"
    }
}
