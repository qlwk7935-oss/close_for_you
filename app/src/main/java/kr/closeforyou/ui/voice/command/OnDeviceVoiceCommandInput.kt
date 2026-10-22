package kr.closeforyou.ui.voice.command

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kr.closeforyou.core.contract.VoiceAvailability
import kr.closeforyou.core.contract.VoiceCommandInput
import kr.closeforyou.core.contract.VoiceEvent
import kr.closeforyou.core.model.SnapshotToken

/**
 * F13 voice input: one listening session per speak-button press, on-device recognition only
 * (API 31+ createOnDeviceSpeechRecognizer). Never a remote recognizer, never always-on.
 * The recognized sentence stays in memory for matching and is never logged or stored.
 * Call every method on the main thread (SpeechRecognizer requirement).
 *
 * The contract has no "not understood" event, so an unclear sentence ends as [VoiceEvent.Cancelled];
 * C's own prompt reads [utterances] instead, which tell "not understood", "refused" and silence apart.
 */
class OnDeviceVoiceCommandInput(context: Context) : SpeakButtonVoiceInput {
    private val appContext = context.applicationContext
    private val mutableEvents = MutableSharedFlow<VoiceEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<VoiceEvent> = mutableEvents.asSharedFlow()
    private val mutableUtterances = MutableSharedFlow<VoiceUtterance>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val utterances: Flow<VoiceUtterance> = mutableUtterances.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null

    override fun availability(): VoiceAvailability = when {
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
            VoiceAvailability.MICROPHONE_PERMISSION_REQUIRED
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> VoiceAvailability.ON_DEVICE_UNAVAILABLE
        !SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext) -> VoiceAvailability.ON_DEVICE_UNAVAILABLE
        else -> VoiceAvailability.READY
    }

    override fun startFromSpeakButton(token: SnapshotToken): VoiceAvailability {
        val availability = availability()
        if (availability != VoiceAvailability.READY || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return availability
        }
        release()
        startListening(token)
        return VoiceAvailability.READY
    }

    override fun cancel() {
        if (recognizer == null) return
        release()
        mutableEvents.tryEmit(VoiceEvent.Cancelled)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun startListening(token: SnapshotToken) {
        val created = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        recognizer = created
        created.setRecognitionListener(Listener(token, created))
        created.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE_TAG)
                .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1),
        )
    }

    private fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private inner class Listener(
        private val token: SnapshotToken,
        private val owner: SpeechRecognizer,
    ) : RecognitionListener {
        override fun onResults(results: Bundle?) {
            // Only the top result: lower-ranked alternatives are more likely to be wrong.
            val top = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            finish(KoreanVoiceCommandMatcher.classify(top))
        }

        override fun onError(error: Int) {
            finish(
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
                    -> VoiceUtterance.Unavailable
                    // Nothing was said: end quietly (error meaning from the SpeechRecognizer docs;
                    // which code each device sends is not yet checked on a real device).
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceUtterance.Silent
                    else -> VoiceUtterance.NotUnderstood
                },
            )
        }

        private fun finish(utterance: VoiceUtterance) {
            if (recognizer !== owner) return // A newer press already replaced this session.
            release()
            mutableUtterances.tryEmit(utterance)
            mutableEvents.tryEmit(utterance.toEvent(token))
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private companion object {
        const val LANGUAGE_TAG = "ko-KR"
    }
}

/**
 * C's speak-button input: the contract's [VoiceCommandInput] plus one classified
 * [VoiceUtterance] per finished session (none after [cancel]). Only C's prompt uses [utterances].
 */
interface SpeakButtonVoiceInput : VoiceCommandInput {
    val utterances: Flow<VoiceUtterance>
}
