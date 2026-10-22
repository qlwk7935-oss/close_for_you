package kr.closeforyou.ui.voice.overlay

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
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
) : OverlayListener {
    constructor(
        context: Context,
        announcer: PopupAnnouncer,
        scope: CoroutineScope,
        mode: () -> PromptAnnounceMode,
        ttsDelayMillis: Long = DEFAULT_TTS_DELAY_MILLIS,
        detail: () -> AnnouncementDetail = { AnnouncementDetail.SHORT },
    ) : this(announcer, scope, mode, ttsDelayMillis, detail, { prompt, level -> PromptSpeech.sentence(context, prompt, level) })

    private var speaking: Job? = null

    override fun onShown(prompt: ChoicePrompt, speakPressed: () -> Boolean) {
        speaking?.cancel()
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

    override fun onDismissed() {
        speaking?.cancel()
        speaking = null
        announcer.stop()
    }

    companion object {
        const val DEFAULT_TTS_DELAY_MILLIS = 700L
    }
}

/** One piece of spoken text: a string resource and its format arguments. Tests compare these. */
data class SpeechPart(val resId: Int, val args: List<Any> = emptyList())

/** Spoken text for prompts and content. Screen text is spoken only, never logged. */
object PromptSpeech {
    fun sentence(context: Context, prompt: ChoicePrompt, detail: AnnouncementDetail = AnnouncementDetail.SHORT): String =
        parts(prompt, detail).joinToString(" ") { context.getString(it.resId, *it.args.toTypedArray()) }

    /**
     * SHORT: summary (+ "대신 닫을 수 없어요" / failed-press result) + where to choose.
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
            if (prompt.cannotPressForUser) add(SpeechPart(R.string.ui_choice_cannot_press))
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
