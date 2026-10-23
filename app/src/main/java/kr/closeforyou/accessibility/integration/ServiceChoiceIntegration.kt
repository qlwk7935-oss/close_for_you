package kr.closeforyou.accessibility.integration

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import kr.closeforyou.R
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.ui.voice.*
import kr.closeforyou.ui.voice.command.SpeakButtonState
import kr.closeforyou.ui.voice.integration.UiText
import kr.closeforyou.ui.voice.overlay.*
import kr.closeforyou.ui.voice.settings.UiVoicePreferences

/** A's local, non-executing assembly. It does not instantiate microphone or rule storage ports. */
internal class ServiceChoiceIntegration private constructor(
    private val service: AccessibilityService,
    private val scope: CoroutineScope,
    private val userInteracting: () -> Boolean,
    private val history: AnnouncementHistory,
    private val tts: TtsPopupAnnouncer,
    private val requestObservation: () -> Unit,
    private val verifyPresentation: suspend (SnapshotToken) -> Boolean,
) {
    private val diagnostic = ChoiceFlowLog.create()
    private val originDiagnostic = ChoiceFlowLog.overlayOrigins()
    private val subscription = ChoiceSubscription()
    private val settings = UiVoicePreferences.get(service)
    private val announcer = CurrentChoiceAnnouncer(tts,
        { if (closed || failed || presentationHealth.failed) null else AccessibilityRuntime.currentToken() }, history::clear)
    private val cleanup = OverlayCleanupBarrier(onUncertain = ::failPresentation, clock = SystemClock::uptimeMillis,
        diagnostic = originDiagnostic)
    private val tracker = OwnedOverlayTracker(service, ChoiceFlowLog.overlayEvents(originDiagnostic), cleanup, originDiagnostic)
    private val listenerJob = SupervisorJob(scope.coroutineContext[Job])
    private val listenerScope = CoroutineScope(scope.coroutineContext + listenerJob + choiceFailureHandler {
        failed = true
        close()
    })
    private val controlsAnnouncer = FailureBoundChoiceControls(announcer) {
        failed = true
        close()
    }
    private val promptListener = PromptAnnouncementListener(service, controlsAnnouncer, listenerScope,
        mode = { settings.current().promptAnnounceMode }, detail = { settings.current().detail })
    // Keeper calls onDismissed before remove, including direct terminal choices. Recording intent
    // here must not revoke that choice: the existing guard validates/delivers it normally.
    private val overlay = AccessibilityChoiceOverlay(service, CleanupOverlayListener(promptListener, tracker::beginCleanup),
        // Repeat/stop only. Do not create OnDeviceVoiceCommandInput or request a permission.
        PromptControlsSupport(controlsAnnouncer, voiceInput = null) { SpeakButtonState.TURNED_OFF })
    private var failed = presentationHealth.failed
    private var closed = false
    private var job: Job? = null
    private val presenter = object : ChoicePromptPresenter {
        override val isShowing get() = !closed && !failed && !presentationHealth.failed && overlay.isShowing
        override val windowState get() = overlay.windowState
        override fun present(prompt: kr.closeforyou.ui.voice.choice.ChoicePrompt,
            onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            if (closed || failed || presentationHealth.failed) return PromptShowResult.BLOCKED
            check(cleanup.ready && overlay.windowState.status == OverlayWindowStatus.HIDDEN) { "CHOICE_CLEANUP_NOT_READY" }
            val before = tracker.beforeShow()
            return try {
                val result = overlay.present(prompt) { answer ->
                    if (cleanup.pending) cleanup.removalReturned(overlay.windowState.status == OverlayWindowStatus.HIDDEN)
                    onAnswer(answer)
                }
                if (result == PromptShowResult.SHOWN) tracker.afterShow(before, prompt.token, overlay.windowState.generation)
                else { failed = true; failPresentation() }
                result
            } catch (cancelled: CancellationException) {
                failed = true; failPresentation()
                throw cancelled
            } catch (_: Exception) {
                failed = true; failPresentation()
                throw IllegalStateException("CHOICE_PRESENTATION_FAILED")
            }
        }
        override fun dismiss() {
            try {
                overlay.dismiss()
                if (cleanup.pending) cleanup.removalReturned(overlay.windowState.status == OverlayWindowStatus.HIDDEN)
                if (overlay.windowState.status != OverlayWindowStatus.HIDDEN) {
                    failed = true; failPresentation()
                    throw IllegalStateException("CHOICE_DISMISS_FAILED")
                }
            } catch (cancelled: CancellationException) {
                failed = true; failPresentation()
                throw cancelled
            } catch (_: Exception) {
                failed = true; failPresentation()
                throw IllegalStateException("CHOICE_DISMISS_FAILED")
            } finally { tracker.forget() }
        }
    }
    private val resources = ChoiceResources(listOf(
        { subscription.close() }, { listenerJob.cancel() }, { presenter.dismiss() }, { announcer.dispose() },
        { tracker.forget() }, { tts.close() },
    ))
    private val source = object : SnapshotSource {
        override val events = AccessibilityRuntime.integrationEvents(subscription::subscribed)
        override fun currentToken() = if (closed || failed || presentationHealth.failed) null else AccessibilityRuntime.currentToken()
    }
    private val session = NonExecutingChoiceSession(source, AccessibilityRuntime, presenter, controlsAnnouncer,
        userInteracting, { tts.announcementAvailable }, SystemClock::uptimeMillis,
        UiText(service::getString), { PromptSpeech.content(service, it) }, resources, presentationHealth, diagnostic,
        awaitPresentationReady = { token ->
            cleanup.awaitPresentation() && !closed && !failed && !presentationHealth.failed &&
                verifyPresentation(token) && !closed && !failed && !presentationHealth.failed && cleanup.ready &&
                AccessibilityRuntime.currentToken() == token
        })

    fun start() {
        check(job == null) { "CHOICE_SESSION_ALREADY_STARTED" }
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { session.run() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally { withContext(NonCancellable) { close() } }
        }
    }
    suspend fun awaitSubscription() {
        subscription.await()
        if (closed || failed || presentationHealth.failed || job?.isActive != true) throw CancellationException("CHOICE_SESSION_UNAVAILABLE")
    }
    suspend fun awaitObservationPermit(): Any {
        awaitSubscription()
        val permit = cleanup.awaitObservationPermit()
        if (!permitsObservation(permit)) throw CancellationException("CHOICE_COLLECTION_UNAVAILABLE")
        return permit
    }
    fun permitsObservation(permit: Any): Boolean = !closed && !failed && !presentationHealth.failed && cleanup.permits(permit)

    fun observeCleanupEvent(event: AccessibilityEvent): OverlayCleanupBarrier.Receipt? {
        if (!cleanup.pending) return null
        return try {
            val windowsChanged = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
            cleanup.observe(event.windowId, windowsChanged,
                windowsChanged && event.windowChanges == AccessibilityEvent.WINDOWS_CHANGE_REMOVED)
        } catch (_: Exception) { cleanup.fail(); null }
    }
    fun cleanupEventFailed() { cleanup.fail() }
    fun cleanupEventProcessed(receipt: OverlayCleanupBarrier.Receipt?) {
        if (receipt == null) return
        // A fresh main-thread turn, after the original handler and its invalidation returned.
        // This is NOT a claim that Android has no more queued window events.
        scope.launch {
            try {
                yield()
                if (closed || failed || presentationHealth.failed) { cleanup.fail(); return@launch }
                cleanup.processed(receipt, overlay.windowState.status == OverlayWindowStatus.HIDDEN,
                    beforeRelease = requestObservation, presence = tracker::presence)
            } catch (cancelled: CancellationException) { cleanup.fail(); throw cancelled }
            catch (_: Exception) { cleanup.fail() }
        }
    }
    fun invalidate(cause: ChoiceInvalidationCause = ChoiceInvalidationCause.UNKNOWN) {
        diagnostic?.record(ChoiceFlowPoint.INVALIDATED, ChoiceFlowDetail.SERVICE_BOUNDARY, cause)
        // Retire callbacks before C dismiss/stop can reenter, even before Runtime is invalidated.
        session.invalidate()
        val clean = ChoiceResources(listOf({ presenter.dismiss() }, { announcer.stop() })).close()
        if (!clean) { failed = true; failPresentation(); close() }
    }
    fun shouldIgnore(event: AccessibilityEvent): Boolean = !closed && !failed && !presentationHealth.failed &&
        OwnedOverlayEventPolicy.mayTrack(overlay.windowState) && tracker.shouldIgnore(event, overlay.windowState)
    fun close(cause: ChoiceInvalidationCause = ChoiceInvalidationCause.UNKNOWN) {
        if (closed) return
        diagnostic?.record(ChoiceFlowPoint.INVALIDATED, ChoiceFlowDetail.SERVICE_BOUNDARY, cause)
        closed = true
        originDiagnostic?.uncertain()
        job?.cancel()
        session.close()
        cleanup.close()
        if (resources.failed) { failed = true; failPresentation() }
    }
    private fun failPresentation() {
        originDiagnostic?.uncertain()
        presentationHealth.fail()
    }
    override fun toString() = "ServiceChoiceIntegration(redacted)"
    companion object {
        // Reconnection is not evidence that an uncertain C window/engine was released.
        private val presentationHealth = ChoicePresentationHealth()
        fun create(service: AccessibilityService, scope: CoroutineScope, userInteracting: () -> Boolean,
            requestObservation: () -> Unit = {},
            verifyPresentation: suspend (SnapshotToken) -> Boolean = { false }): ServiceChoiceIntegration {
            val history = AnnouncementHistory(1)
            var speechGuard: () -> Boolean = { false }
            val tts = TtsPopupAnnouncer(OfflineChoiceSpeechEngine(service) { speechGuard() },
                service.getString(R.string.ui_announce_nothing_to_repeat), history)
            try {
                val integration = ServiceChoiceIntegration(service, scope, userInteracting, history, tts, requestObservation,
                    verifyPresentation)
                speechGuard = integration.announcer::permitsSpeech
                return integration
            }
            catch (failure: Throwable) {
                try { tts.close() } catch (_: Exception) { /* Preserve initialization failure. */ }
                throw failure
            }
        }
    }
}

/** Only a newly created, live, owned C overlay can be bound. Package identity alone is insufficient. */
private class OwnedOverlayTracker(private val service: AccessibilityService,
    private val diagnostic: OverlayEventDiagnostic?, private val cleanup: OverlayCleanupBarrier,
    private val origin: OverlayEventOriginDiagnostic?) {
    private val binding = OwnedOverlayBinding()
    private var unhealthy = false
    private var generation = 0L
    private var targetWindow: Int? = null
    fun beforeShow(): Set<Int>? { forget(); origin?.beforeShow(); return readOwnedIds() }
    fun afterShow(before: Set<Int>?, token: SnapshotToken, generation: Long) {
        binding.expect(before, token, generation)
        this.generation = generation; targetWindow = token.windowId
        origin?.shown(generation, token.windowId, before)
        diagnostic?.arm()
    }
    fun beginCleanup() {
        cleanup.retiring(binding.boundWindowFor(generation), generation, targetWindow)
        forget()
    }
    fun forget() { binding.clear(); diagnostic?.disarm(); generation = 0; targetWindow = null }
    @Suppress("DEPRECATION")
    fun presence(window: Int): CleanupWindowPresence =
        if (unhealthy) CleanupWindowPresence.UNKNOWN else
            cleanupWindowPresence(window, { service.windows }, { it.id }, { it.recycle() })
    fun shouldIgnore(event: AccessibilityEvent, state: OverlayWindowState): Boolean = inspectOwnedOverlayEvent(
        state, { event.eventType }, { event.windowChanges }, { event.packageName?.toString() },
        { AccessibilityRuntime.currentToken() },
        { current, observation -> binding.matches(event.windowId, readOwnedIds(observation), current, state.generation, observation) },
        { service.packageName }, diagnostic,
        // Runs only after a first rejected decision. Never mutates binding/unhealthy or retains event/token.
        { classifyWindow(event) }, { event.eventTime })
    @Suppress("DEPRECATION")
    private fun classifyWindow(event: AccessibilityEvent): OverlayEventWindowClass = classifyOverlayEventWindow(
        event.windowId, AccessibilityRuntime.currentToken(), { service.windows },
        { it.id }, { it.type }, { it.root }, { it.windowId }, { it.packageName?.toString() },
        { it.recycle() }, { it.recycle() })
    @Suppress("DEPRECATION")
    private fun readOwnedIds(observation: OverlayEventDiagnostic? = null): Set<Int>? {
        if (unhealthy) {
            origin?.uncertain()
            observation?.ownedQueryUncertain(OverlayOwnedWindowDetail.UNCERTAIN)
            return null
        }
        val result = readOwnedOverlayIds(
            { service.windows },
            { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.displayId == Display.DEFAULT_DISPLAY },
            { it.root },
            { root, window -> root.packageName?.toString() == service.packageName && root.windowId == window.id },
            { it.title?.toString() == service.getString(R.string.ui_choice_pane_title) },
            { it.id }, { it.recycle() }, { it.recycle() }, { unhealthy = true }, observation)
        if (unhealthy) origin?.uncertain()
        return result
    }
}
