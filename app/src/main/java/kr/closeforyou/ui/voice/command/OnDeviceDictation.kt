package kr.closeforyou.ui.voice.command

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kr.closeforyou.core.contract.VoiceAvailability

/** One finished dictation. [Heard] keeps the sentence in memory only; it is never logged or stored. */
sealed interface DictationResult {
    class Heard(val text: String) : DictationResult {
        // Keeps the heard words out of any accidental log or crash report.
        override fun toString() = "Heard(…)"
    }

    data object NotUnderstood : DictationResult
    data object Silent : DictationResult
    data object Unavailable : DictationResult
}

/**
 * Free text from one button press (the "도와줄 앱" search), not a command. Behind an interface so
 * [kr.closeforyou.ui.voice.settings.VoiceSearchController] is JVM tested.
 */
interface VoiceDictation {
    /** Starts one session; [onResult] runs once on the main thread unless [cancel] comes first. */
    fun start(onResult: (DictationResult) -> Unit): VoiceAvailability

    /** Stops listening; no result is delivered for that session. */
    fun cancel()
}

/**
 * [VoiceDictation] with the same on-device recognizer as [OnDeviceVoiceCommandInput]
 * (API 31+ only, never a remote one). Call every method on the main thread.
 */
class OnDeviceDictation(context: Context) : VoiceDictation {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null

    override fun start(onResult: (DictationResult) -> Unit): VoiceAvailability {
        val availability = OnDeviceRecognition.availability(appContext)
        if (availability != VoiceAvailability.READY || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return availability
        }
        release()
        startListening(onResult)
        return VoiceAvailability.READY
    }

    override fun cancel() = release()

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startListening(onResult: (DictationResult) -> Unit) {
        val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        recognizer = created
        created.setRecognitionListener(Listener(created, onResult))
        created.startListening(OnDeviceRecognition.intent())
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private inner class Listener(
        private val owner: SpeechRecognizer,
        private val onResult: (DictationResult) -> Unit,
    ) : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val top = OnDeviceRecognition.topResult(results)
            finish(if (top.isNullOrBlank()) DictationResult.NotUnderstood else DictationResult.Heard(top))
        }

        override fun onError(error: Int) {
            finish(
                when (OnDeviceRecognition.failure(error)) {
                    VoiceUtterance.Silent -> DictationResult.Silent
                    VoiceUtterance.Unavailable -> DictationResult.Unavailable
                    else -> DictationResult.NotUnderstood
                },
            )
        }

        private fun finish(result: DictationResult) {
            if (recognizer !== owner) return // Cancelled, or a newer press replaced this session.
            release()
            onResult(result)
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
