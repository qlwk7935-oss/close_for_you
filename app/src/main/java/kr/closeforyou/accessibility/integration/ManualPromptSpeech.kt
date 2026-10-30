package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kr.closeforyou.core.contract.AnnouncementReceipt
import kr.closeforyou.core.contract.AnnouncementState
import kr.closeforyou.ui.voice.SpeechReadiness

internal enum class ManualSpeechState {
    MODE_TTS, MODE_PANE_ONLY, REQUESTED, WAITING_FOR_ENGINE, READY, UNAVAILABLE,
    STALE, COMPLETED, NOT_DELIVERED, CANCELLED, FAILED, STOP_REQUESTED,
}

/** Engine initialization may complete after Stop or a newer content/result request. No older
 * waiter may flush that newer speech. Identity is local and is never logged. */
internal class ManualSpeechRequests {
    private var active: Any? = null
    fun replace(): Any = Any().also { active = it }
    fun permits(request: Any): Boolean = active === request
    fun stop() { active = null }
}

/** The actual A announcer boundary. Wait for the existing engine's initialization only while
 * C owns this cancellable announcement job. No retry/speak after dismiss, new token or shutdown.
 * Display intent, NOT Runtime's old 5 s execution evidence, determines whether speech is current.
 * COMPLETED is an engine receipt, never proof the person heard the sentence. */
internal suspend fun announceManualPrompt(readiness: StateFlow<SpeechReadiness>, current: () -> Boolean,
    record: (ManualSpeechState) -> Unit, deliver: suspend () -> AnnouncementReceipt): AnnouncementReceipt {
    fun code(state: ManualSpeechState) = kr.closeforyou.accessibility.diagnosticWrite { record(state) }
    fun stale(): AnnouncementReceipt { code(ManualSpeechState.STALE); return AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED) }
    try {
        currentCoroutineContext().ensureActive()
        code(ManualSpeechState.REQUESTED)
        if (!current()) return stale()
        if (readiness.value == SpeechReadiness.INITIALIZING) {
            code(ManualSpeechState.WAITING_FOR_ENGINE)
            readiness.first { it != SpeechReadiness.INITIALIZING }
        }
        currentCoroutineContext().ensureActive()
        if (!current()) return stale()
        code(if (readiness.value == SpeechReadiness.READY) ManualSpeechState.READY else ManualSpeechState.UNAVAILABLE)
        val result = deliver()
        currentCoroutineContext().ensureActive()
        // A late backend completion must not belong to an expired/replaced display or request.
        // This checks the existing display-only lease; it never extends the execution TTL.
        if (!current()) return stale()
        code(if (result.state == AnnouncementState.TTS_COMPLETED) ManualSpeechState.COMPLETED else ManualSpeechState.NOT_DELIVERED)
        return result
    } catch (cancelled: CancellationException) { code(ManualSpeechState.CANCELLED); throw cancelled }
    catch (_: Exception) {
        // A speech backend failure is undelivered speech, never a service crash/success receipt.
        // Do not let framework exception messages escape to the coroutine's general Logcat sink.
        code(ManualSpeechState.FAILED)
        return AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
    }
}
