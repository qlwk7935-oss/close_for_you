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
import kr.closeforyou.accessibility.DetectionFrame
import kr.closeforyou.accessibility.PopupCandidateDetector
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.*
import kr.closeforyou.ui.voice.settings.UiVoicePreferences
import kr.closeforyou.ui.voice.settings.autoCloseConsentNow

/** One session / B policy / native C manual flow. Release auto OFF; explicit Debug trial only. */
internal class F9ServiceConnection(
    service: AccessibilityService, private val scope: CoroutineScope,
    private val connected: () -> Boolean, private val userInteracting: () -> Boolean,
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
    private val tts = TtsPopupAnnouncer(AndroidTtsEngine(service), service.getString(R.string.ui_announce_nothing_to_repeat), history)
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
    private var developerObserver: Job? = null
    private var waitTimer: Job? = null
    private var trialTicket: Any? = null
    private var activeTrial: Any? = null
    private class Offer(val snapshot: PopupSnapshot, val detection: PopupDetection, val display: PopupSnapshot,
        val analysis: PopupAnalysis, val frame: DetectionFrame?, val trial: Any?) {
        override fun toString() = "AutomaticOffer(redacted)"
    }
    private var latestOffer: Offer? = null
    private val waiting = DelayedPopupWatch(clock::nowMillis) { phase ->
        DebugDelayedAutoClose.report(trialTicket, phase)
        DebugDelayedAutoClose.finish(trialTicket); waitTimer?.cancel()
    }
    private var popupIdentity: Triple<String, Int, String>? = null
    private fun access(app: String): F9SessionAccess {
        val a = core.currentSettings(); val c = ui.current()
        val consent = if (DebugDelayedAutoClose.permits(app)) c.autoCloseConsentNow(live = true)
            else ui.autoCloseConsentSource.current()
        return F9SessionAccess(connected(), a.consentGranted, app in a.allowedPackages && app !in c.helpAppsExcluded,
            a.paused, userInteracting(), consent, tts.announcementAvailable)
    }
    private val session = F9ServiceSession(raw, screen, clock, access = ::access, cleanupReady = { observationReady }, onReport = { report ->
        val ticket = activeTrial
        if (report.result != null && ticket != null) {
            DebugDelayedAutoClose.report(ticket, DelayedAutoPhase.F9_RESULT, report.decision.next,
                report.result.reason, attempted = report.result.attempted, outcome = report.result.outcome)
            DebugDelayedAutoClose.finish(ticket)
            if (trialTicket === ticket) { waiting.clear(); waitTimer?.cancel() }
        }
        scope.launch {
            if (closed || (report.result?.attempted != true && raw.currentToken() != report.token)) return@launch
            try { feedback.deliver(report) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* No other automatic result voice. */ }
        }
    }, executionEnabled = { activeTrial != null },
        executionScope = { app -> DebugDelayedAutoClose.permits(app, activeTrial) },
        onPageVerified = { app -> if (DebugDelayedAutoClose.permits(app))
            DebugDelayedAutoClose.report(DebugDelayedAutoClose.ticket(), DelayedAutoPhase.PAGE_VERIFIED) },
        onDecision = { decision ->
            if (activeTrial != null) {
                DebugDelayedAutoClose.report(activeTrial, DelayedAutoPhase.B_DECISION, decision.next, decision.reasons.firstOrNull())
            }
        }, onWaiting = { snapshot, detection, fresh, analysis, page ->
            val offer = latestOffer?.takeIf { it.snapshot.token == snapshot.token && it.trial === activeTrial }
            if (activeTrial != null && DebugDelayedAutoClose.permits(snapshot.token.packageName, activeTrial) &&
                waiting.arm(snapshot, detection, offer?.frame, fresh, analysis, page)) {
                DebugDelayedAutoClose.report(activeTrial, DelayedAutoPhase.WAITING_FOR_TARGET)
                waitTimer?.cancel()
                val ticket = activeTrial
                waitTimer = scope.launch {
                    delay(waiting.remaining())
                    if (!closed && trialTicket === ticket && waiting.waiting) waiting.cancel(DelayedAutoPhase.EXPIRED)
                }
            }
        }, executionGuard = waiting::permitsExecution, onExecutionStart = waiting::executing)
    private val manual = ManualPopupConnection(service, scope, session, cleanup, presentationHealth, tts, batcher,
        history::clear, userInteracting, epoch, verifyPresentation, requestObservation,
        collectForChoice, awaitInteractionEnd, intentInteracting, trace)
    val observationReady: Boolean get() = !closed && manual.canObserve()
    fun hasPrompt() = manual.hasPrompt()
    fun hasPendingPresentation() = manual.hasPendingPresentation()
    fun focusReview(requested: Boolean) = trace?.record(ManualPromptPoint.FOCUS_REVIEW,
        if (requested) ManualPromptCause.FOCUS_INVALIDATED_REVIEW_REQUESTED else ManualPromptCause.FOCUS_INVALIDATED_NO_REVIEW)
    fun reviewRejected(cause: ManualPromptCause) {
        trace?.record(ManualPromptPoint.REVIEW_REJECTED, cause)
        if (cause in setOf(ManualPromptCause.COLLECTION_BLOCKED, ManualPromptCause.EVIDENCE_BLOCKED,
                ManualPromptCause.PACKAGE_BLOCKED, ManualPromptCause.POLICY_BLOCKED, ManualPromptCause.EVIDENCE_FAILED))
            waiting.cancel(DelayedAutoPhase.CONTINUITY_REJECTED)
    }
    fun reobserveWaiting(snapshot: PopupSnapshot, frame: DetectionFrame, detector: PopupCandidateDetector): Set<PopupSignal> {
        if (!DebugDelayedAutoClose.permits(snapshot.token.packageName, trialTicket) || !waiting.waiting) return emptySet()
        if (!waiting.reobserved(snapshot, frame)) return emptySet()
        val evidence = detector.reobserveBoundedApplicationWindow(frame)
        if (evidence.isEmpty()) waiting.cancel(DelayedAutoPhase.CONTINUITY_REJECTED)
        else DebugDelayedAutoClose.report(trialTicket, DelayedAutoPhase.REOBSERVED)
        return evidence
    }
    fun shouldIgnore(event: AccessibilityEvent) = manual.shouldIgnore(event)
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
                val offer = latestOffer?.takeIf { it.snapshot.token == event.snapshot.token } ?: continue
                val ticket = offer.trial
                if (ticket != null && !DebugDelayedAutoClose.permits(event.snapshot.token.packageName, ticket)) continue
                activeTrial = ticket
                try {
                    val handled = session.handle(event)
                    handled.decision?.let { AccessibilityRuntime.recordF9Decision(it.next, handled.result?.attempted == true, it.reasons.firstOrNull()) }
                    if (ticket != null) {
                        handled.result?.block?.let { DebugDelayedAutoClose.report(ticket, DelayedAutoPhase.F9_RESULT, block = it) }
                        val next = handled.decision?.next
                        if (next != NextStep.WAIT && next != NextStep.EXECUTE) {
                            waiting.clear(); waitTimer?.cancel(); DebugDelayedAutoClose.finish(ticket)
                            if (raw.currentToken() == offer.snapshot.token && observationReady)
                                manual.offer(offer.snapshot, offer.detection, offer.display, offer.analysis)
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    trace?.cleanup?.record(ManualCleanupSite.WORKER, ManualCleanupIssue.EXCEPTION)
                    raw.invalidate(event.snapshot.token.screenVersion); cleanup.fail()
                } finally {
                    activeTrial = null
                    if (latestOffer === offer) latestOffer = null
                }
            }
        }
        consentObserver = scope.launch { ui.state.drop(1).collect { policyChanged() } }
        developerObserver = scope.launch { DebugDelayedAutoClose.userChanges.drop(1).collect {
            waiting.clear(); waitTimer?.cancel(); latestOffer = null; trialTicket = DebugDelayedAutoClose.ticket()
            requestObservation()
        } }
    }
    fun collected(snapshot: PopupSnapshot, detection: PopupDetection, display: PopupSnapshot, analysis: PopupAnalysis,
        frame: DetectionFrame? = null) {
        if (!observationReady) return
        val key = Triple(snapshot.token.packageName, snapshot.token.windowId, snapshot.token.structureDigest)
        if (PopupSignal.NEW_WINDOW in detection.signals && popupIdentity != key) feedback.onNewPopup()
        popupIdentity = key
        val ticket = DebugDelayedAutoClose.ticket()
        val a = access(snapshot.token.packageName)
        val automatic = ticket != null && PopupSignal.NEW_WINDOW in detection.signals &&
            DebugDelayedAutoClose.permits(snapshot.token.packageName, ticket) && a.connected && a.collectionConsent &&
            a.appAllowed && !a.paused && !a.userInteracting && a.consent.granted
        if (automatic) {
            trialTicket = ticket
            if (!waiting.track(snapshot)) return
            DebugDelayedAutoClose.report(ticket, DelayedAutoPhase.CANDIDATE)
        }
        latestOffer = Offer(snapshot, detection, display, analysis, frame, ticket.takeIf { automatic })
        raw.publish(snapshot, detection)
        if (!automatic) manual.offer(snapshot, detection, display, analysis)
    }
    fun observePage() { if (observationReady) session.observePage() }
    fun invalidate(version: Long, permissionOrInteraction: Boolean, ttl: Boolean = false, ownedRetirement: Boolean = false, preserveManual: Boolean = false,
        cause: ManualPromptCause = if (ttl) ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED else
            if (preserveManual) ManualPromptCause.UNATTRIBUTED_TOUCH else ManualPromptCause.UNKNOWN,
        content: DelayedContentEvent? = null) {
        waiting.invalidated(version, permissionOrInteraction, cause, content)
        if (cause in setOf(ManualPromptCause.POLICY_CHANGED, ManualPromptCause.SERVICE_STOPPED,
                ManualPromptCause.CONFIGURATION_CHANGED, ManualPromptCause.CLEANUP_FAILED, ManualPromptCause.HEALTH_FAILED))
            DebugDelayedAutoClose.finish(DebugDelayedAutoClose.ticket())
        if (waiting.waiting && content != null) DebugDelayedAutoClose.report(trialTicket, DelayedAutoPhase.RECOLLECT_REQUESTED)
        latestOffer = null
        manual.invalidate(version, ttl, ownedRetirement, preserveManual, cause)
        session.invalidate(version, permissionOrInteraction && !ownedRetirement)
    }
    fun observeEvent(event: AccessibilityEvent): OverlayCleanupBarrier.Receipt? {
        if (closed) return null
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges == AccessibilityEvent.WINDOWS_CHANGE_REMOVED &&
            popupIdentity?.second == event.windowId) { feedback.onPopupRemoved(); popupIdentity = null }
        return cleanup.observeWindowEvent(event.windowId, event.eventType,
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) event.windowChanges else 0)
    }
    fun processed(receipt: OverlayCleanupBarrier.Receipt?) = manual.processed(receipt)
    fun cleanupFailed() {
        trace?.cleanup?.record(ManualCleanupSite.EVENT_HANDLER, ManualCleanupIssue.HANDLER_NOT_COMPLETED)
        cleanup.fail()
    }
    fun close(version: Long) {
        if (closed) return
        session.close(version); closed = true
        subscriber?.cancel(); worker?.cancel(); consentObserver?.cancel(); developerObserver?.cancel(); pending.close()
        waiting.clear(); waitTimer?.cancel(); DebugDelayedAutoClose.finish(DebugDelayedAutoClose.ticket())
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
