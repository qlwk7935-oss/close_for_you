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
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.textRes

/**
 * How a newly shown prompt is announced. The overlay always carries a paneTitle; whether TalkBack
 * reads it when the window appears is not yet verified on real devices, so our own TTS can be added.
 * Overlap between our TTS and TalkBack is also unverified (docs/experiments/overlay-talkback.md).
 */
enum class PromptAnnounceMode { PANE_TITLE_ONLY, PANE_TITLE_AND_TTS }

/** Speaks the prompt summary once when it appears and stops speaking when it is dismissed. */
class PromptAnnouncementListener(
    private val context: Context,
    private val announcer: PopupAnnouncer,
    private val scope: CoroutineScope,
    private val mode: () -> PromptAnnounceMode,
    private val ttsDelayMillis: Long = DEFAULT_TTS_DELAY_MILLIS,
) : OverlayListener {
    private var speaking: Job? = null

    override fun onShown(prompt: ChoicePrompt) {
        speaking?.cancel()
        if (mode() != PromptAnnounceMode.PANE_TITLE_AND_TTS) return
        val text = PromptSpeech.sentence(context, prompt)
        speaking = scope.launch {
            // Lets TalkBack finish its own window-change speech first (delay value unverified).
            delay(ttsDelayMillis)
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

/** Spoken text for prompts and content. Screen text is spoken only, never logged. */
object PromptSpeech {
    fun sentence(context: Context, prompt: ChoicePrompt): String = buildList {
        add(context.getString(prompt.summary.textRes()))
        if (prompt.cannotPressForUser) add(context.getString(R.string.ui_choice_cannot_press))
        add(context.getString(R.string.ui_choice_where))
    }.joinToString(" ")

    /** [내용 다시 읽기]: the popup's extracted text in reading order, or a "nothing to read" line. */
    fun content(context: Context, analysis: PopupAnalysis): String =
        contentOrNull(analysis) ?: context.getString(R.string.ui_content_none)

    internal fun contentOrNull(analysis: PopupAnalysis): String? =
        analysis.texts.map { it.text.trim() }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }
}
