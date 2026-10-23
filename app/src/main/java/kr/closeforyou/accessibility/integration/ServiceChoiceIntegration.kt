package kr.closeforyou.accessibility.integration

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import kr.closeforyou.R
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.CollectionResourceSafety
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
) {
    private val diagnostic = ChoiceFlowLog.create()
    private val subscription = ChoiceSubscription()
    private val settings = UiVoicePreferences.get(service)
    private val announcer = CurrentChoiceAnnouncer(tts,
        { if (closed || failed || presentationHealth.failed) null else AccessibilityRuntime.currentToken() }, history::clear)
    private val tracker = OwnedOverlayTracker(service)
    private val listenerJob = SupervisorJob(scope.coroutineContext[Job])
    private val listenerScope = CoroutineScope(scope.coroutineContext + listenerJob + choiceFailureHandler {
        failed = true
        close()
    })
    private val controlsAnnouncer = FailureBoundChoiceControls(announcer) {
        failed = true
        close()
    }
    private val overlay = AccessibilityChoiceOverlay(service,
        PromptAnnouncementListener(service, controlsAnnouncer, listenerScope, mode = { settings.current().promptAnnounceMode },
            detail = { settings.current().detail }),
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
            val before = tracker.beforeShow()
            return try {
                val result = overlay.present(prompt, onAnswer)
                if (result == PromptShowResult.SHOWN) tracker.afterShow(before, prompt.token, overlay.windowState.generation)
                else { failed = true; presentationHealth.fail() }
                result
            } catch (cancelled: CancellationException) {
                failed = true; presentationHealth.fail()
                throw cancelled
            } catch (_: Exception) {
                failed = true; presentationHealth.fail()
                throw IllegalStateException("CHOICE_PRESENTATION_FAILED")
            }
        }
        override fun dismiss() {
            tracker.forget()
            try {
                overlay.dismiss()
                if (overlay.windowState.status != OverlayWindowStatus.HIDDEN) {
                    failed = true; presentationHealth.fail()
                    throw IllegalStateException("CHOICE_DISMISS_FAILED")
                }
            } catch (cancelled: CancellationException) {
                failed = true; presentationHealth.fail()
                throw cancelled
            } catch (_: Exception) {
                failed = true; presentationHealth.fail()
                throw IllegalStateException("CHOICE_DISMISS_FAILED")
            }
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
        UiText(service::getString), { PromptSpeech.content(service, it) }, resources, presentationHealth, diagnostic)

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
    fun invalidate(cause: ChoiceInvalidationCause = ChoiceInvalidationCause.UNKNOWN) {
        diagnostic?.record(ChoiceFlowPoint.INVALIDATED, ChoiceFlowDetail.SERVICE_BOUNDARY, cause)
        // Retire callbacks before C dismiss/stop can reenter, even before Runtime is invalidated.
        session.invalidate()
        val clean = ChoiceResources(listOf({ presenter.dismiss() }, { announcer.stop() })).close()
        if (!clean) { failed = true; presentationHealth.fail(); close() }
    }
    fun shouldIgnore(event: AccessibilityEvent): Boolean = !closed && !failed && !presentationHealth.failed &&
        OwnedOverlayEventPolicy.mayTrack(overlay.windowState) && tracker.shouldIgnore(event, overlay.windowState)
    fun close(cause: ChoiceInvalidationCause = ChoiceInvalidationCause.UNKNOWN) {
        if (closed) return
        diagnostic?.record(ChoiceFlowPoint.INVALIDATED, ChoiceFlowDetail.SERVICE_BOUNDARY, cause)
        closed = true
        job?.cancel()
        session.close()
        if (resources.failed) { failed = true; presentationHealth.fail() }
    }
    override fun toString() = "ServiceChoiceIntegration(redacted)"
    companion object {
        // Reconnection is not evidence that an uncertain C window/engine was released.
        private val presentationHealth = ChoicePresentationHealth()
        fun create(service: AccessibilityService, scope: CoroutineScope, userInteracting: () -> Boolean): ServiceChoiceIntegration {
            val history = AnnouncementHistory(1)
            var speechGuard: () -> Boolean = { false }
            val tts = TtsPopupAnnouncer(OfflineChoiceSpeechEngine(service) { speechGuard() },
                service.getString(R.string.ui_announce_nothing_to_repeat), history)
            try {
                val integration = ServiceChoiceIntegration(service, scope, userInteracting, history, tts)
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
private class OwnedOverlayTracker(private val service: AccessibilityService) {
    private val binding = OwnedOverlayBinding()
    private var unhealthy = false
    fun beforeShow(): Set<Int>? { forget(); return readOwnedIds() }
    fun afterShow(before: Set<Int>?, token: SnapshotToken, generation: Long) {
        binding.expect(before, token, generation)
    }
    fun forget() { binding.clear() }
    fun shouldIgnore(event: AccessibilityEvent, state: OverlayWindowState): Boolean {
        if (!OwnedOverlayEventPolicy.mayTrack(state)) return false
        val kind = when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> OverlayEventKind.CONTENT
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> OverlayEventKind.WINDOW_STATE
            AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> OverlayEventKind.FOCUS
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val harmless = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_BOUNDS or
                    AccessibilityEvent.WINDOWS_CHANGE_LAYER or AccessibilityEvent.WINDOWS_CHANGE_TITLE
                if (event.windowChanges != 0 && event.windowChanges and harmless.inv() == 0)
                    OverlayEventKind.OWN_WINDOW_LAYOUT else OverlayEventKind.UNKNOWN
            }
            else -> OverlayEventKind.INTERACTION // Touch/click/scroll/unknown remain invalidating.
        }
        if (kind == OverlayEventKind.INTERACTION || kind == OverlayEventKind.UNKNOWN) return false
        val pkg = event.packageName?.toString()
        val current = AccessibilityRuntime.currentToken()
        val owns = binding.matches(event.windowId, readOwnedIds(), current, state.generation)
        return OwnedOverlayEventPolicy.mayIgnore(kind, owns, owns,
            pkg == service.packageName, pkg == null, current != null)
    }
    @Suppress("DEPRECATION")
    private fun readOwnedIds(): Set<Int>? {
        if (unhealthy) return null
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val windows = mutableListOf<AccessibilityWindowInfo>()
        return try {
            CollectionResourceSafety.run(body = {
                windows += service.windows
                if (windows.isEmpty() || windows.size > 32) return@run null
                val found = mutableSetOf<Int>()
                for (window in windows) {
                    if (window.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY ||
                        window.displayId != Display.DEFAULT_DISPLAY) continue
                    val root = window.root ?: continue
                    if (roots.none { it === root }) roots += root
                    if (root.packageName?.toString() != service.packageName || root.windowId != window.id) continue
                    if (window.title?.toString() == service.getString(R.string.ui_choice_pane_title)) found += window.id
                }
                found
            }, cleanup = {
                val batch = CollectionReleaseBatch()
                roots.forEach { root -> batch.attempt { root.recycle() } }
                val seen = java.util.IdentityHashMap<AccessibilityWindowInfo, Boolean>()
                windows.forEach { window -> if (seen.put(window, true) == null) batch.attempt { window.recycle() } }
                batch.finish()
            }, cleanupFailure = { unhealthy = true; null })
        } catch (_: Exception) { unhealthy = true; null }
        finally { roots.clear(); windows.clear() }
    }
}
