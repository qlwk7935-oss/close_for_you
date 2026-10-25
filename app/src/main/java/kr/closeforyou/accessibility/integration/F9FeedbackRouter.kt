package kr.closeforyou.accessibility.integration

import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.HandlingReport
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.SpeechHandlingListener
import kr.closeforyou.ui.voice.UiFormat
import kr.closeforyou.ui.voice.WaitResetReason

/** A's first #51 integration boundary. Automatic reports use C alone, with no legacy fallback.
 * The composition root must supply the very same batcher to PopupChoiceFlow. Only the owner's
 * service scope may call these methods (Main); it owns cancelling that shared batcher on shutdown.
 */
internal class F9FeedbackRouter(
    announcer: PopupAnnouncer,
    batcher: AnnouncementBatcher,
    format: UiFormat,
    // There is no agreed live identity yet. Do not inherit C's Mock window/structure default.
    waitKeyOf: (HandlingReport) -> String? = { null },
) {
    private val listener = SpeechHandlingListener(announcer, batcher, format, waitKeyOf = waitKeyOf,
        titleSpeechEnabled = { false })
    private var stopped = false

    init { listener.resetWaitMemory(WaitResetReason.SERVICE_RECONNECTED) }

    suspend fun deliver(report: HandlingReport) {
        if (stopped) return
        // HandlingReport itself rejects USER_CHOICE and mismatched tokens. First integration
        // explicitly strips B's title, even if a future caller passes one or C's flag changes.
        try { listener.onHandled(report.copy(title = null)) }
        finally {
            if (report.result?.outcome == ClickOutcome.CLOSED_CONFIRMED) onPopupRemoved()
        }
    }

    /** Call for a confirmed DIFFERENT popup, not every snapshot/new UUID of the same popup. */
    fun onNewPopup() = listener.resetWaitMemory(WaitResetReason.NEW_POPUP)
    fun onPopupRemoved() = listener.resetWaitMemory(WaitResetReason.POPUP_REMOVED)
    fun onServiceReconnected() = listener.resetWaitMemory(WaitResetReason.SERVICE_RECONNECTED)
    fun close() { stopped = true; listener.clear() }
}
