package kr.closeforyou.ui.voice.overlay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.ui.voice.PressResultSpeech
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import kr.closeforyou.ui.voice.settings.AnnouncementDetail
import kr.closeforyou.ui.voice.choice.textRes

/**
 * How a newly shown prompt is announced. The overlay always carries a paneTitle; whether TalkBack
 * reads it when the window appears is not yet verified on real devices, so our own TTS can be added.
 * Overlap between our TTS and TalkBack is also unverified (docs/experiments/overlay-talkback.md).
 */
enum class PromptAnnounceMode { PANE_TITLE_ONLY, PANE_TITLE_AND_TTS }

/**
 * Speaks the prompt summary once when it appears and stops speaking when it is dismissed.
 * If the person pressed [말하기] before the first announcement, it is skipped and not spoken later:
 * our TTS must not reach the microphone, and the person has already acted.
 */
class PromptAnnouncementListener internal constructor(
    private val announcer: PopupAnnouncer,
    private val scope: CoroutineScope,
    private val mode: () -> PromptAnnounceMode,
    private val ttsDelayMillis: Long,
    private val detail: () -> AnnouncementDetail,
    private val sentence: (ChoicePrompt, AnnouncementDetail) -> String,
    /** C's fixed sentences by resource id (REVALIDATING close); blank = say nothing. */
    private val text: (Int) -> String = { "" },
    /** C's fixed sentences with arguments, for the takeover's safety lines. */
    private val formatted: (Int, List<Any>) -> String = { _, _ -> "" },
    private val takeoverTiming: TakeoverTiming = TakeoverTiming(),
) : OverlayListener {
    constructor(
        context: Context,
        announcer: PopupAnnouncer,
        scope: CoroutineScope,
        mode: () -> PromptAnnounceMode,
        ttsDelayMillis: Long = DEFAULT_TTS_DELAY_MILLIS,
        detail: () -> AnnouncementDetail = { AnnouncementDetail.SHORT },
    ) : this(
        announcer, scope, mode, ttsDelayMillis, detail,
        { prompt, level -> PromptSpeech.sentence(context, prompt, level) },
        { context.getString(it) },
        { res, args -> context.getString(res, *args.toTypedArray()) },
    )

    private var speaking: Job? = null

    override fun onShown(prompt: ChoicePrompt, speakPressed: () -> Boolean, focus: PromptFocus) {
        speaking?.cancel()
        if (focus.enabled) {
            takeOver(prompt, speakPressed, focus)
            return
        }
        if (mode() != PromptAnnounceMode.PANE_TITLE_AND_TTS) return
        // A follow-up after a failed press: its result was just spoken by the caller.
        if (prompt.pressFailure != null) return
        val text = sentence(prompt, detail())
        speaking = scope.launch {
            // Lets TalkBack finish its own window-change speech first (delay value unverified).
            delay(ttsDelayMillis)
            if (speakPressed()) return@launch
            announcer.announce(AnnouncementRequest(prompt.token, AnnouncementKind.POPUP_FOUND, text))
        }
    }

    /**
     * TalkBack takeover ([TalkBackTakeoverFeature]): first item → the popup's own text → the button.
     * Our speech is announced whatever [mode] says. Stopped speech ([그만 말하기], [말하기], dismiss,
     * [onUserInteraction]) leaves focus where it is: only a fully played reading moves it.
     */
    private fun takeOver(prompt: ChoicePrompt, speakPressed: () -> Boolean, focus: PromptFocus) {
        // A failed-press follow-up: its result is being spoken by the caller; focus and speech stay as before.
        val words = TalkBackTakeoverPlan.speech(prompt, formatted) { sentence(prompt, detail()) } ?: return
        speaking = scope.launch {
            for (attempt in 1..takeoverTiming.firstFocusAttempts) {
                if (focus.moveTo(TakeoverFocusTarget.FIRST_ITEM)) break
                if (attempt < takeoverTiming.firstFocusAttempts) delay(takeoverTiming.firstFocusRetryMillis)
            }
            delay(takeoverTiming.speechDelayMillis)
            if (speakPressed()) return@launch
            val receipt = announcer.announce(AnnouncementRequest(prompt.token, AnnouncementKind.POPUP_FOUND, words))
            if (receipt.state == AnnouncementState.TTS_COMPLETED && !speakPressed()) {
                focus.moveTo(TakeoverFocusTarget.CHOICE_BUTTON)
            }
        }
    }

    /**
     * A calls this on TYPE_TOUCH_INTERACTION_START while C's prompt is up: the person took over, so
     * our reading stops (as TalkBack's own does) and the later focus move is dropped.
     */
    fun onUserInteraction() {
        val job = speaking ?: return
        if (!job.isActive) return
        job.cancel()
        speaking = null
        announcer.stop()
    }

    override fun onDismissed() {
        speaking?.cancel()
        speaking = null
        announcer.stop()
    }

    /** Our TTS must not talk over the re-check; a first announcement still waiting is dropped. */
    override fun onRevalidating() = onDismissed()

    /**
     * Same popup confirmed: no TTS. The window's own status line says "다시 확인했어요." once
     * (liveRegion), and the person has heard this popup already, so it is not read in full again.
     */
    override fun onRevalidated(prompt: ChoicePrompt) {
        speaking?.cancel()
        speaking = null
    }

    /**
     * The window was closed by the re-check: one sentence, whatever the announce mode, because the
     * window is gone and its liveRegion can no longer speak (same rule as the press-failure result).
     */
    override fun onRevalidationClosed(prompt: ChoicePrompt, end: RevalidationEnd) {
        speaking?.cancel()
        val res = RevalidationSpeech.closedRes(end) ?: return
        val sentence = text(res).ifBlank { return }
        speaking = scope.launch {
            announcer.announce(AnnouncementRequest(prompt.token, AnnouncementKind.ACTION_RESULT, sentence))
        }
    }

    companion object {
        const val DEFAULT_TTS_DELAY_MILLIS = 700L
    }
}

/** Takeover waits; tests shorten them. */
data class TakeoverTiming(
    val firstFocusAttempts: Int = TalkBackTakeoverFeature.FIRST_FOCUS_ATTEMPTS,
    val firstFocusRetryMillis: Long = TalkBackTakeoverFeature.FIRST_FOCUS_RETRY_MILLIS,
    val speechDelayMillis: Long = TalkBackTakeoverFeature.SPEECH_DELAY_MILLIS,
)

/** One piece of spoken text: a string resource and its format arguments. Tests compare these. */
data class SpeechPart(val resId: Int, val args: List<Any> = emptyList())

/** Spoken text for prompts and content. Screen text is spoken only, never logged. */
object PromptSpeech {
    fun sentence(context: Context, prompt: ChoicePrompt, detail: AnnouncementDetail = AnnouncementDetail.SHORT): String =
        parts(prompt, detail).joinToString(" ") { context.getString(it.resId, *it.args.toTypedArray()) }

    /**
     * SHORT: summary (+ "대신 닫을 수 없어요" or a video ad's countdown wait / failed-press result)
     * + where to choose.
     * DETAILED adds only facts C already has, never a classification reason: the names of the
     * buttons offered and how many choices the prompt has. Wording stays hedged.
     */
    fun parts(prompt: ChoicePrompt, detail: AnnouncementDetail): List<SpeechPart> = buildList {
        val failure = prompt.pressFailure
        if (failure != null) {
            // Follow-up after a failed press: the result first, never "닫았어요".
            add(SpeechPart(PressResultSpeech.reasonRes(failure)))
            add(SpeechPart(PressResultSpeech.nextRes(failure, prompt.hasMoveOption())))
        } else {
            add(SpeechPart(prompt.summary.textRes()))
            val countdown = prompt.countdownSeconds
            if (countdown != null) {
                // B's read value only; C does not wait and press when it ends.
                add(SpeechPart(R.string.ui_choice_video_ad_wait, listOf(countdown)))
            } else if (prompt.cannotPressForUser) {
                add(SpeechPart(R.string.ui_choice_cannot_press))
            }
        }
        if (detail == AnnouncementDetail.DETAILED) {
            prompt.labelsOf(PromptOptionKind.PRESS_CANDIDATE)?.let {
                add(SpeechPart(R.string.ui_choice_detail_press_buttons, listOf(it)))
            }
            prompt.labelsOf(PromptOptionKind.MOVE_TO_BUTTON)?.let {
                add(SpeechPart(R.string.ui_choice_detail_move_buttons, listOf(it)))
            }
            add(SpeechPart(R.string.ui_choice_detail_option_count, listOf(prompt.options.size)))
        }
        add(SpeechPart(R.string.ui_choice_where))
    }

    /** Button names joined in on-screen order; null when there are none of that kind. */
    private fun ChoicePrompt.labelsOf(kind: PromptOptionKind): String? =
        options.filter { it.kind == kind }.mapNotNull { it.buttonLabel?.takeIf(String::isNotBlank) }
            .joinToString(LABEL_SEPARATOR) { "'$it'" }.ifEmpty { null }

    private fun ChoicePrompt.hasMoveOption() = options.any { it.kind == PromptOptionKind.MOVE_TO_BUTTON }

    private const val LABEL_SEPARATOR = ", "

    /** [내용 다시 읽기]: the popup's extracted text in reading order, or a "nothing to read" line. */
    fun content(context: Context, analysis: PopupAnalysis): String =
        contentOrNull(analysis) ?: context.getString(R.string.ui_content_none)

    internal fun contentOrNull(analysis: PopupAnalysis): String? =
        analysis.texts.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }
}
