package kr.closeforyou.accessibility.integration

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kr.closeforyou.R
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.*
import kr.closeforyou.ui.voice.settings.UiVoicePreferences

/** One service session / B policy / native C manual flow / result owner. Automatic execution OFF. */
internal class F9ServiceConnection(
    service: AccessibilityService, private val scope: CoroutineScope,
    connected: () -> Boolean, userInteracting: () -> Boolean,
    private val requestObservation: () -> Unit, private val policyChanged: () -> Unit,
    epoch: () -> Long, verifyPresentation: (PopupSnapshot) -> Boolean,
    collectForChoice: (PopupSnapshot, () -> Unit) -> PopupSnapshot?,
    awaitInteractionEnd: suspend () -> Unit, intentInteracting: () -> Boolean,
) {
    private val trace = ManualPromptLog.create()
    private val core = CorePreferences.get(service)
    private val ui = UiVoicePreferences.get(service)
    private val clock = F9Clock(SystemClock::uptimeMillis)
    private val raw = F9CollectedSource(clock::nowMillis)
    private val screen = AndroidF9ScreenPort(service) { app ->
        val a = core.currentSettings()
        connected() && a.consentGranted && !a.paused && app in a.allowedPackages && app !in ui.current().helpAppsExcluded
    }
    private val history = AnnouncementHistory()
    private val speechEngine = AndroidTtsEngine(service)
    private val tts = TtsPopupAnnouncer(speechEngine, service.getString(R.string.ui_announce_nothing_to_repeat), history)
    private val format = UiFormat { res, args -> service.getString(res, *args) }
    val batcher = AnnouncementBatcher(scope, SpeechHandlingListener.batchFlush(tts, format))
    private val feedback = F9FeedbackRouter(tts, batcher, format)
    private val cleanup = OverlayCleanupBarrier(clock = SystemClock::uptimeMillis, onUncertain = presentationHealth::fail,
        failureDiagnostic = {
            trace?.record(ManualPromptPoint.CLEANUP_FAILED, ManualPromptCause.CLEANUP_FAILED)
            AccessibilityRuntime.cleanupFailure(it.name)
        }, failureAttemptDiagnostic = { reason, native ->
            trace?.cleanup?.record(ManualCleanupSite.BARRIER, ManualCleanupIssue.BARRIER_REJECTED, reason, native)
        })
    private var closed = false
    private val pending = Channel<PopupEvent.Candidate>(Channel.CONFLATED)
    private var worker: Job? = null
    private var subscriber: Job? = null
    private var consentObserver: Job? = null
    private var popupIdentity: Triple<String, Int, String>? = null
    private val session = F9ServiceSession(raw, screen, clock, access = { app ->
        val a = core.currentSettings(); val c = ui.current()
        F9SessionAccess(connected(), a.consentGranted, app in a.allowedPackages && app !in c.helpAppsExcluded,
            a.paused, userInteracting(), ui.autoCloseConsentSource.current(), tts.announcementAvailable)
    }, cleanupReady = { observationReady }, onReport = { report ->
        scope.launch {
            if (closed || (report.result?.attempted != true && raw.currentToken() != report.token)) return@launch
            try { feedback.deliver(report) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* No other automatic result voice. */ }
        }
    })
    private val manual = ManualPopupConnection(service, scope, session, cleanup, presentationHealth, tts, batcher,
        history::clear, userInteracting, epoch, verifyPresentation, requestObservation,
        collectForChoice, awaitInteractionEnd, intentInteracting, speechEngine.readiness, screen::verifyDisplayedPopup, trace)
    val observationReady: Boolean get() = !closed && manual.canObserve()
    fun hasPrompt() = manual.hasPrompt()
    fun hasPendingPresentation() = manual.hasPendingPresentation()
    fun focusReview(requested: Boolean) = trace?.record(ManualPromptPoint.FOCUS_REVIEW,
        if (requested) ManualPromptCause.FOCUS_INVALIDATED_REVIEW_REQUESTED else ManualPromptCause.FOCUS_INVALIDATED_NO_REVIEW)
    fun reviewRejected(cause: ManualPromptCause) = trace?.record(ManualPromptPoint.REVIEW_REJECTED, cause)
    fun shouldIgnore(event: AccessibilityEvent) = manual.shouldIgnore(event)
    fun compatibleRetirementEvent(event: AccessibilityEvent) = manual.compatibleRetirementEvent(event)
    fun nativeCleanupRetirementEvent(event: AccessibilityEvent) = manual.nativeCleanupRetirementEvent(event)
    fun reviewDisplayedEvent(type: Int, changes: Int, eventWindow: Int, retire: () -> Long) =
        manual.reviewDisplayedEvent(type, changes, eventWindow, retire)
    fun isOwnedRetirement(event: AccessibilityEvent) = manual.isOwnedRetirement(event)
    suspend fun awaitObservationPermit(): Any {
        val permit = cleanup.awaitObservationPermit()
        if (!observationReady) throw CancellationException("OBSERVATION_UNAVAILABLE")
        return permit
    }
    fun permitsObservation(permit: Any) = observationReady && cleanup.permits(permit)

    fun start() {
        check(worker == null && !closed)
        manual.start(); feedback.onServiceReconnected()
        subscriber = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.wiring.source.events.collect { if (it is PopupEvent.Candidate) pending.trySend(it) }
        }
        worker = scope.launch {
            for (event in pending) {
                if (!observationReady) continue
                try {
                    val handled = session.handle(event)
                    handled.decision?.let { AccessibilityRuntime.recordF9Decision(it.next, handled.result?.attempted == true, it.reasons.firstOrNull()) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    trace?.cleanup?.record(ManualCleanupSite.WORKER, ManualCleanupIssue.EXCEPTION)
                    raw.invalidate(event.snapshot.token.screenVersion); cleanup.fail()
                }
            }
        }
        consentObserver = scope.launch { ui.state.drop(1).collect { policyChanged() } }
    }
    fun collected(snapshot: PopupSnapshot, detection: PopupDetection, display: PopupSnapshot, analysis: PopupAnalysis) {
        if (!observationReady) return
        val key = Triple(snapshot.token.packageName, snapshot.token.windowId, snapshot.token.structureDigest)
        if (PopupSignal.NEW_WINDOW in detection.signals && popupIdentity != key) feedback.onNewPopup()
        popupIdentity = key
        raw.publish(snapshot, detection)
        manual.offer(snapshot, detection, display, analysis)
    }
    fun observePage() { if (observationReady) session.observePage() }
    fun invalidate(version: Long, permissionOrInteraction: Boolean, ttl: Boolean = false, ownedRetirement: Boolean = false, preserveManual: Boolean = false,
        cause: ManualPromptCause = if (ttl) ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED else
            if (preserveManual) ManualPromptCause.UNATTRIBUTED_TOUCH else ManualPromptCause.UNKNOWN) {
        manual.invalidate(version, ttl, ownedRetirement, preserveManual, cause)
        session.invalidate(version, permissionOrInteraction && !ownedRetirement)
    }
    fun observeEvent(event: AccessibilityEvent, nativeTransientConfirmed: Boolean = false): OverlayCleanupBarrier.Receipt? {
        if (closed) return null
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges == AccessibilityEvent.WINDOWS_CHANGE_REMOVED &&
            popupIdentity?.second == event.windowId) { feedback.onPopupRemoved(); popupIdentity = null }
        return cleanup.observeWindowEvent(event.windowId, event.eventType,
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) event.windowChanges else 0, nativeTransientConfirmed)
    }
    fun processed(receipt: OverlayCleanupBarrier.Receipt?) = manual.processed(receipt)
    fun cleanupFailed() {
        trace?.cleanup?.record(ManualCleanupSite.EVENT_HANDLER, ManualCleanupIssue.HANDLER_NOT_COMPLETED)
        cleanup.fail()
    }
    fun close(version: Long) {
        if (closed) return
        session.close(version); closed = true
        subscriber?.cancel(); worker?.cancel(); consentObserver?.cancel(); pending.close()
        kr.closeforyou.accessibility.CollectionResourceSafety.run(body = { manual.close() }, cleanup = {
            val batch = kr.closeforyou.accessibility.CollectionReleaseBatch()
            batch.attempt { manualCleanupCall(ManualCleanupSite.SERVICE_CLOSE, trace?.cleanup, cleanup) { cleanup.close() } }
            batch.attempt { manualCleanupCall(ManualCleanupSite.SERVICE_CLOSE, trace?.cleanup, cleanup) { feedback.close() } }
            batch.attempt { manualCleanupCall(ManualCleanupSite.SERVICE_CLOSE, trace?.cleanup, cleanup) { batcher.cancel() } }
            batch.attempt { manualCleanupCall(ManualCleanupSite.TTS_CLOSE, trace?.cleanup, cleanup) { tts.close() } }
            batch.finish()
        }, cleanupFailure = { throw kr.closeforyou.accessibility.CollectionCleanupFailure() })
    }
    private companion object { val presentationHealth = ChoicePresentationHealth() }
}
