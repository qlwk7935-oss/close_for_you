package kr.closeforyou.ui.voice.overlay

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.contract.VoiceAvailability
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.command.AndroidMicrophoneAccess
import kr.closeforyou.ui.voice.command.OnDeviceVoiceCommandInput
import kr.closeforyou.ui.voice.command.SpeakButtonPolicy
import kr.closeforyou.ui.voice.command.SpeakButtonState
import kr.closeforyou.ui.voice.command.SpeakButtonVoiceInput
import kr.closeforyou.ui.voice.command.VoiceButtonFeature
import kr.closeforyou.ui.voice.command.VoiceOutcome
import kr.closeforyou.ui.voice.command.VoicePolicy
import kr.closeforyou.ui.voice.command.VoiceUtterance
import kr.closeforyou.ui.voice.settings.UiVoicePreferences

/**
 * What the prompt shows below its choices: [말하기], [다시 듣기], [그만 말하기] and one status line.
 * Default = none of them (the prompt looks as before).
 */
data class PromptControlsState(
    val speakButton: SpeakButtonState = SpeakButtonState.TURNED_OFF,
    val listening: Boolean = false,
    /** [다시 듣기]·[그만 말하기] need C's announcer. */
    val speechButtons: Boolean = false,
    /** Fixed sentence after a voice attempt or the first status line; never the heard words. */
    @StringRes val messageRes: Int? = null,
    /**
     * F38: our TTS already said [messageRes], so the line drops its liveRegion (TalkBack would read it a
     * second time). False = the line announces itself politely (liveRegion), as before.
     */
    val messageSpoken: Boolean = false,
    /** False while REVALIDATING: the buttons stay (TalkBack reads them as unavailable) but do nothing. */
    val enabled: Boolean = true,
) {
    val speakVisible: Boolean get() = speakButton == SpeakButtonState.SHOWN

    /** One short hint only when the person can fix it in settings; otherwise the button just hides. */
    @get:StringRes
    val hintRes: Int? get() = if (speakButton == SpeakButtonState.PERMISSION_NEEDED) R.string.ui_voice_permission_hint else null
}

/**
 * What the overlay needs for the extra buttons. [NONE] keeps the old prompt (choices only).
 * A's real service can pass [create] with C's announcer; the debug test service already does.
 */
class PromptControlsSupport(
    val announcer: PopupAnnouncer?,
    val voiceInput: SpeakButtonVoiceInput?,
    /** Read on every show, so a settings or permission change applies to the next prompt. */
    val speakButtonState: () -> SpeakButtonState,
    /** F38: string resource → sentence for the announcer; null = the status line only shows (liveRegion). */
    val sentence: ((Int) -> String)? = null,
) {
    companion object {
        val NONE = PromptControlsSupport(null, null, { SpeakButtonState.TURNED_OFF })

        fun create(context: Context, announcer: PopupAnnouncer): PromptControlsSupport {
            val microphone = AndroidMicrophoneAccess(context)
            val preferences = UiVoicePreferences.get(context)
            return PromptControlsSupport(
                announcer,
                OnDeviceVoiceCommandInput(context),
                { SpeakButtonPolicy.state(VoiceButtonFeature.enabled, preferences.current().voiceCommandsEnabled, microphone) },
                context::getString,
            )
        }
    }
}

/**
 * One controller per shown prompt (main thread). Listening starts only from [onSpeak], a second
 * press cancels, and our own TTS is stopped first so it does not reach the microphone.
 * A recognized choice goes to [onVoiceOption], the same path as a tap (then B's policy and A's gate).
 * It never moves TalkBack focus and never logs what was heard.
 */
class PromptControlsController(
    private val prompt: ChoicePrompt,
    private val scope: CoroutineScope,
    private val support: PromptControlsSupport,
    /** First status line (liveRegion), e.g. "다시 확인했어요." after a same-popup refill. */
    @StringRes initialMessageRes: Int? = null,
    private val onVoiceOption: (PromptOption) -> Unit,
) {
    private val mutableState = MutableStateFlow(
        PromptControlsState(
            speakButton = if (support.voiceInput != null) support.speakButtonState() else SpeakButtonState.TURNED_OFF,
            speechButtons = support.announcer != null,
            messageRes = initialMessageRes,
        ),
    )
    val state: StateFlow<PromptControlsState> = mutableState.asStateFlow()

    /** True once listening started on this prompt; the first announcement is then skipped. */
    var speakPressed: Boolean = false
        private set

    /** Set by [dispose]: a late voice result or button press of a closed prompt is dropped. */
    private var disposed = false

    init {
        val input = support.voiceInput
        if (input != null && mutableState.value.speakVisible) {
            // UNDISPATCHED: subscribed before any press can start a session.
            scope.launch(start = CoroutineStart.UNDISPATCHED) { input.utterances.collect(::onResult) }
        }
    }

    fun onSpeak() {
        if (disposed) return
        val input = support.voiceInput ?: return
        if (!mutableState.value.speakVisible) return
        if (mutableState.value.listening) {
            input.cancel()
            say(R.string.ui_voice_listen_cancelled)
            return
        }
        support.announcer?.stop()
        val message = when (input.startFromSpeakButton(prompt.token)) {
            VoiceAvailability.READY -> {
                speakPressed = true
                mutableState.update { it.copy(listening = true, messageRes = R.string.ui_voice_listening, messageSpoken = false) }
                return
            }
            VoiceAvailability.MICROPHONE_PERMISSION_REQUIRED -> R.string.ui_voice_permission_hint
            else -> R.string.ui_voice_unavailable
        }
        say(message)
    }

    fun onRepeat() {
        if (disposed) return
        val announcer = support.announcer ?: return
        cancelListening()
        // TtsPopupAnnouncer says ui_announce_nothing_to_repeat itself when there is nothing.
        scope.launch { announcer.repeatLast() }
    }

    fun onStopSpeaking() {
        cancelListening()
        support.announcer?.stop()
    }

    /** The prompt is closing: stop listening; the scope owner cancels collection. */
    fun dispose() {
        disposed = true
        cancelListening()
    }

    private fun cancelListening() {
        if (!mutableState.value.listening) return
        support.voiceInput?.cancel()
        mutableState.update { it.copy(listening = false, messageRes = null, messageSpoken = false) }
    }

    /**
     * F38: a fixed sentence after a voice attempt is shown and also spoken by our TTS, so it is heard
     * with TalkBack off (a liveRegion alone says nothing then). Only the resource sentence is spoken,
     * never what was heard; the line then has no liveRegion, so TalkBack does not read it twice.
     */
    private fun say(@StringRes res: Int) {
        val announcer = support.announcer
        val text = announcer?.let { support.sentence?.invoke(res) }?.takeIf { it.isNotBlank() }
        mutableState.update { it.copy(listening = false, messageRes = res, messageSpoken = text != null) }
        if (announcer != null && text != null) {
            scope.launch { announcer.announce(AnnouncementRequest(prompt.token, AnnouncementKind.ACTION_RESULT, text)) }
        }
    }

    private fun onResult(utterance: VoiceUtterance) {
        // A result after cancel, after dispose or from an older prompt's session is ignored.
        if (disposed || !mutableState.value.listening) return
        when (val outcome = VoicePolicy.decide(utterance, prompt)) {
            is VoiceOutcome.Choose -> {
                mutableState.update { it.copy(listening = false, messageRes = null, messageSpoken = false) }
                onVoiceOption(outcome.option)
            }
            is VoiceOutcome.Say -> say(outcome.textRes)
            VoiceOutcome.Quiet -> mutableState.update { it.copy(listening = false, messageRes = null, messageSpoken = false) }
        }
    }
}
