package kr.closeforyou.accessibility.integration

import android.content.Context
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kr.closeforyou.ui.voice.SpeechEngine
import kr.closeforyou.ui.voice.SpeechReadiness
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Android's voice metadata is the supported boundary, not proof against a malicious TTS provider. */
internal object OfflineVoiceCheck {
    fun usable(languageMatches: Boolean, networkRequired: Boolean?, installed: Boolean, sameSelection: Boolean) =
        languageMatches && networkRequired == false && installed && sameSelection
}

/** A's privacy adapter for C's SpeechEngine port. No remote/default-voice fallback or text logging. */
internal class OfflineChoiceSpeechEngine(context: Context, private val maySpeak: () -> Boolean = { false }) : SpeechEngine {
    override val readiness = MutableStateFlow(SpeechReadiness.INITIALIZING)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val next = AtomicLong()
    private val operations = SpeechOperationOwner()
    private var selected: Voice? = null
    private var closed = false
    private val main = Handler(Looper.getMainLooper())
    private val tts: TextToSpeech

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) { "SPEECH_MAIN_THREAD_REQUIRED" }
        // Defer even a synchronous init callback until the instance has been assigned.
        tts = TextToSpeech(context.applicationContext) { status -> main.post { initialize(status) } }
    }
    private fun initialize(status: Int) {
        if (closed) return
        try {
            if (status != TextToSpeech.SUCCESS) { readiness.value = SpeechReadiness.UNAVAILABLE; return }
            val voice = tts.voices?.firstOrNull {
                OfflineVoiceCheck.usable(it.locale.language == Locale.KOREAN.language,
                    it.isNetworkConnectionRequired, TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty(), true)
            }
            if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                readiness.value = SpeechReadiness.UNAVAILABLE; return
            }
            selected = voice
            if (tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) != TextToSpeech.SUCCESS) {
                readiness.value = SpeechReadiness.UNAVAILABLE; return
            }
            val listenerStatus = tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = Unit
                override fun onDone(id: String) { finish(id, true) }
                @Deprecated("Required Android callback") override fun onError(id: String) { finish(id, false) }
                override fun onError(id: String, errorCode: Int) { finish(id, false) }
                override fun onStop(id: String, interrupted: Boolean) { finish(id, false) }
            })
            readiness.value = if (listenerStatus == TextToSpeech.SUCCESS && localSelection())
                SpeechReadiness.READY else SpeechReadiness.UNAVAILABLE
        } catch (_: Exception) { readiness.value = SpeechReadiness.UNAVAILABLE }
    }
    private fun localSelection(): Boolean {
        if (closed || selected == null) return false
        val current = tts.voice ?: return false
        return OfflineVoiceCheck.usable(current.locale.language == Locale.KOREAN.language,
            current.isNetworkConnectionRequired, TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in current.features.orEmpty(),
            current == selected)
    }
    override suspend fun speak(text: String): Boolean {
        currentCoroutineContext().ensureActive()
        if (closed || readiness.value != SpeechReadiness.READY) return false
        val local = try { localSelection() } catch (_: Exception) { false }
        if (!local) { readiness.value = SpeechReadiness.UNAVAILABLE; stop(); return false }
        val id = "a_local_speech_${next.incrementAndGet()}"
        val completion = CompletableDeferred<Boolean>()
        val operation = operations.begin()
        pending[id] = completion
        try {
            currentCoroutineContext().ensureActive()
            if (!maySpeak()) return false // Recheck the original A token/TTL immediately before enqueue.
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id, false)
            val result = completion.await()
            currentCoroutineContext().ensureActive()
            return result && !closed && operations.owns(operation)
        } catch (cancelled: CancellationException) {
            try { if (operations.owns(operation)) stop() } catch (_: Exception) { /* Preserve cancellation. */ }
            throw cancelled
        } catch (_: Exception) {
            try { if (operations.owns(operation)) stop() } catch (_: Exception) { /* Not delivered; no message/stack output. */ }
            return false
        }
        finally { pending.remove(id); operations.clear(operation) }
    }
    override fun stop() {
        operations.clear()
        try { if (!closed) tts.stop() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { throw IllegalStateException("SPEECH_STOP_FAILED") }
        finally { pending.values.forEach { it.complete(false) }; pending.clear() }
    }
    override fun shutdown() {
        if (closed) return
        var failed = false
        try { stop() } catch (_: Exception) { failed = true }
        closed = true; selected = null; readiness.value = SpeechReadiness.UNAVAILABLE
        try { tts.shutdown() } catch (_: Exception) { failed = true }
        if (failed) throw IllegalStateException("SPEECH_CLEANUP_FAILED")
    }
    private fun finish(id: String, result: Boolean) { pending.remove(id)?.complete(result) }
    override fun toString() = "OfflineChoiceSpeechEngine(redacted)"
}
