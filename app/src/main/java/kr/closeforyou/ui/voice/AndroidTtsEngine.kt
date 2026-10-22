package kr.closeforyou.ui.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Korean on-device TextToSpeech routed through the accessibility audio stream, like TalkBack.
 * Whether it overlaps TalkBack speech is unverified on real devices (docs/experiments/overlay-talkback.md).
 */
class AndroidTtsEngine(context: Context) : SpeechEngine {
    private val mutableReadiness = MutableStateFlow(SpeechReadiness.INITIALIZING)
    override val readiness: StateFlow<SpeechReadiness> = mutableReadiness.asStateFlow()

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val nextId = AtomicLong()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        mutableReadiness.value =
            if (status == TextToSpeech.SUCCESS && configure()) SpeechReadiness.READY else SpeechReadiness.UNAVAILABLE
    }

    private fun configure(): Boolean {
        val language = tts.setLanguage(Locale.KOREAN)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) return false
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId, completed = true)
            @Deprecated("Required override on older APIs")
            override fun onError(utteranceId: String) = finish(utteranceId, completed = false)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId, completed = false)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId, completed = false)
        })
        return true
    }

    override suspend fun speak(text: String): Boolean {
        if (readiness.value != SpeechReadiness.READY) return false
        val id = "ui_voice_${nextId.incrementAndGet()}"
        val result = CompletableDeferred<Boolean>()
        pending[id] = result
        // QUEUE_FLUSH: a newer popup announcement replaces an older one instead of piling up.
        if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
            finish(id, completed = false)
        }
        return try {
            result.await()
        } finally {
            pending.remove(id)
        }
    }

    override fun stop() {
        tts.stop()
        // Some engines do not report onStop for flushed utterances; release every waiter.
        pending.keys.forEach { finish(it, completed = false) }
    }

    override fun shutdown() {
        stop()
        tts.shutdown()
        mutableReadiness.value = SpeechReadiness.UNAVAILABLE
    }

    private fun finish(utteranceId: String, completed: Boolean) {
        pending[utteranceId]?.complete(completed)
    }
}
