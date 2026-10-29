package kr.closeforyou.ui.voice

import android.content.Context
import kr.closeforyou.R
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.ui.voice.settings.SpeechRates

/**
 * C/F10 announcer: speaks the caller's sentence with on-device TTS and keeps it for "다시 듣기".
 *
 * TTS_COMPLETED only means playback ended, never that the person heard or understood it.
 * The contract has no failure state, so anything short of full playback reports NOT_IMPLEMENTED
 * (no delivery is claimed). No focus is moved and no text is logged.
 */
class TtsPopupAnnouncer(
    private val engine: SpeechEngine,
    private val nothingToRepeatText: String,
    private val history: AnnouncementHistory = AnnouncementHistory(),
) : PopupAnnouncer {

    /** For B's DecisionContext.announcementAvailable: true only while our own TTS can speak. */
    val announcementAvailable: Boolean
        get() = engine.readiness.value == SpeechReadiness.READY

    override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
        if (request.text.isBlank()) return notDelivered()
        history.record(request)
        return speak(request.text)
    }

    override suspend fun repeatLast(): AnnouncementReceipt {
        val last = history.last()
        if (last == null) {
            // Tell the person why nothing is repeated, but do not claim a repeat happened.
            speak(nothingToRepeatText)
            return notDelivered()
        }
        return speak(last.text)
    }

    override fun stop() = engine.stop()

    /** Called when the owning service ends: forget announcements and release the engine. */
    fun close() {
        history.clear()
        engine.shutdown()
    }

    private suspend fun speak(text: String): AnnouncementReceipt =
        if (announcementAvailable && engine.speak(text)) {
            AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
        } else {
            notDelivered()
        }

    private fun notDelivered() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)

    companion object {
        fun create(context: Context): TtsPopupAnnouncer = TtsPopupAnnouncer(
            engine = AndroidTtsEngine(context, SpeechRates.provider(context)),
            nothingToRepeatText = context.getString(R.string.ui_announce_nothing_to_repeat),
        )
    }
}
