package kr.closeforyou.accessibility.integration

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kr.closeforyou.R
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateWiring
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.ui.voice.*
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.integration.*
import kr.closeforyou.ui.voice.overlay.*
import kr.closeforyou.ui.voice.settings.UiVoicePreferences

/** The single C manual window. No Android handle or raw event is kept. Main-thread only. */
internal class ManualPopupConnection(
    private val service: AccessibilityService, private val scope: CoroutineScope,
    private val session: F9ServiceSession, private val cleanup: OverlayCleanupBarrier,
    private val health: ChoicePresentationHealth, private val tts: TtsPopupAnnouncer,
    private val batcher: AnnouncementBatcher, private val clearHistory: () -> Unit,
    private val interacting: () -> Boolean, private val epoch: () -> Long,
    private val verify: (PopupSnapshot) -> Boolean, private val review: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val lease = ChoiceDisplayLease(SystemClock::uptimeMillis)
    private val binding = OwnedOverlayBinding()
    private val nativeOwner = Any()
    private var native: OverlayNativeWindowLifetime? = null
    private var retiredId: Int? = null
    private var target: Int? = null
    private var before: Set<Int>? = null
    private var closed = false
    private var job: Job? = null
    private var displayTimer: Job? = null
    private var original: PopupSnapshot? = null
    private var shownAnalysis: PopupAnalysis? = null
    private var shownDetection: PopupDetection? = null
    private var selected: SnapshotToken? = null
    private var selectedValid = false
    private var completedKey: Triple<String, Int, String>? = null
    private val reviews = ChoiceReviewBudget()
    private val eventDiagnostic = OverlayEventDiagnostic { category, reason, detail, _, _, _, _, _, scan, relation, _ ->
        AccessibilityRuntime.ownedEventRejection("${category.name};${reason.name};${detail.name};${scan.name};${relation.name}")
    }
    private val events = MutableSharedFlow<PopupEvent>(extraBufferCapacity = 1)
    private fun flowToken(): SnapshotToken? = if (closed || health.failed) null else
        selected?.takeIf { selectedValid } ?: AccessibilityRuntime.currentToken()
    private val source = object : SnapshotSource {
        override val events = this@ManualPopupConnection.events
        override fun currentToken() = flowToken()
    }
    private val wiring = DecisionCandidateWiring(source, object : EvidenceExtractor {
        override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis =
            requireNotNull(shownAnalysis?.takeIf { it.token == snapshot.token && source.currentToken() == snapshot.token })
    })
    private val announcer: PopupAnnouncer = object : PopupAnnouncer {
        override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
            if (closed) return AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            if (request.kind == AnnouncementKind.ACTION_RESULT) return tts.announce(request.copy(token = null))
            if (request.token != AccessibilityRuntime.currentToken()) return AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            return tts.announce(request)
        }
        override suspend fun repeatLast() = if (AccessibilityRuntime.currentToken() != null) tts.repeatLast()
            else AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
        override fun stop() { if (!lease.retains(overlay.windowState)) tts.stop() }
    }
    private val settings = UiVoicePreferences.get(service)
    private val promptListener = PromptAnnouncementListener(service, announcer, scope,
        mode = { settings.current().promptAnnounceMode }, detail = { settings.current().detail })
    val overlay: AccessibilityChoiceOverlay = AccessibilityChoiceOverlay(service, object : OverlayListener {
        override fun onShown(prompt: ChoicePrompt, speakPressed: () -> Boolean) = promptListener.onShown(prompt, speakPressed)
        override fun onDismissed() {
            val n = native
            retiredId = n?.retirementWindow(nativeOwner, n.generation)
            cleanup.retiring(binding.boundWindowFor(n?.generation ?: 0), n?.generation ?: 0, target, n, nativeOwner)
            binding.clear(); promptListener.onDismissed()
        }
    }, PromptControlsSupport(tts, null) { kr.closeforyou.ui.voice.command.SpeakButtonState.TURNED_OFF }).also { view ->
        view.observeNativeWindows(object : OverlayNativeWindowObserver {
            override fun created(generation: Long) = OverlayNativeWindowLifetime(generation, nativeOwner).also { native = it }
            override fun changed(lifetime: OverlayNativeWindowLifetime) { handler.post { nativeProcessed(lifetime) } }
        })
    }
    private val gate = ChoicePresentationGate(SystemClock::uptimeMillis,
        current = { !closed && !health.failed && !interacting() && AccessibilityRuntime.currentToken() == it },
        cleanupCurrent = cleanup::permits,
        nextTurn = { awaitChoicePresentationTurn(handler::post, handler::removeCallbacks) },
        verify = { baseline, checkpoint -> checkpoint(); verify(baseline).also { checkpoint() } },
        reject = { AccessibilityRuntime.manualBoundary("ADMISSION_REJECTED"); review() })
    private val delegate = object : ChoicePromptPresenter {
        override val isShowing get() = overlay.isShowing
        override val windowState get() = overlay.windowState
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            if (!cleanup.ready || overlay.windowState.status != OverlayWindowStatus.HIDDEN || health.failed) return PromptShowResult.BLOCKED
            before = readOwned(); target = prompt.token.windowId
            eventDiagnostic.arm()
            val result = overlay.present(prompt, onAnswer)
            AccessibilityRuntime.manualBoundary("PRESENT_" + result.name + ";SUMMARY=" + prompt.summary.name +
                ";PRESS=" + prompt.options.count { it.kind == kr.closeforyou.ui.voice.choice.PromptOptionKind.PRESS_CANDIDATE })
            if (result == PromptShowResult.SHOWN) {
                binding.expect(before, prompt.token, overlay.windowState.generation)
                lease.shown(prompt.token, overlay.windowState)
                AccessibilityRuntime.recordManualPrompt()
            }
            return result
        }
        override fun dismiss() {
            overlay.dismiss()
            if (cleanup.pending) cleanup.removalReturned(overlay.windowState.status == OverlayWindowStatus.HIDDEN)
        }
    }
    private val guarded = GuardedChoicePresenter(delegate, ::flowToken, interacting, health,
        preserveDisplay = { lease.retains(overlay.windowState) }, onFailure = { cleanup.fail() })
    private val presenter = object : ChoicePromptPresenter {
        override val isShowing get() = guarded.isShowing
        override val windowState get() = guarded.windowState
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit) = guarded.present(prompt) { answer ->
            // Register the already generation-checked answer synchronously, before resuming C's
            // suspended flow: native removal events may be next on Main's queue.
            if (answer is PromptAnswer.Picked && answer.choice.kind == ChoiceKind.CLOSE_ONCE) {
                selected = answer.choice.token; selectedValid = true
                AccessibilityRuntime.manualBoundary("CHOICE_ACCEPTED")
            }
            onAnswer(answer)
        }
        override fun dismiss() = guarded.dismiss()
    }
    // C extracts/classifies first; this admission wait happens before its one real native add.
    private val admittedExtractor = object : EvidenceExtractor {
        override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
            val analysis = wiring.extractor.extract(snapshot)
            AccessibilityRuntime.manualBoundary("EXTRACTED")
            if (!gate.awaitReady(snapshot.token)) throw CancellationException("MANUAL_PRESENTATION_STALE")
            return analysis
        }
    }
    private val flow = PopupChoiceFlow(wiring.source, admittedExtractor, DecisionPopupClassifier(), presenter, announcer, batcher,
        ChoiceHandler { choice, analysis -> handleChoice(choice, analysis) }, UiText(service::getString),
        contentText = { PromptSpeech.content(service, it) }, rememberChoicesEnabled = false, revalidatingEnabled = false)

    fun start() {
        check(job == null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { flow.run() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { AccessibilityRuntime.manualBoundary("FLOW_FAILED"); health.fail(); close() }
        }
    }
    fun offer(snapshot: PopupSnapshot, detection: PopupDetection, display: PopupSnapshot, analysis: PopupAnalysis) {
        val key = Triple(snapshot.token.packageName, snapshot.token.windowId, snapshot.token.contentDigest)
        if (closed || health.failed || completedKey == key || !cleanup.ready || overlay.isShowing || selected != null) {
            AccessibilityRuntime.manualBoundary("OFFER_UNAVAILABLE"); return
        }
        if (detection.signals.none { it == PopupSignal.NEW_WINDOW || it == PopupSignal.SAME_WINDOW_OVERLAY }) {
            AccessibilityRuntime.manualBoundary("NO_POPUP_SCOPE"); return
        }
        if (!reviews.admit(snapshot.token)) { AccessibilityRuntime.manualBoundary("REVIEW_BUDGET_EXHAUSTED"); return }
        val permit = cleanup.permit() ?: return
        original = snapshot; shownAnalysis = analysis; shownDetection = detection
        gate.prepare(snapshot, permit)
        AccessibilityRuntime.manualBoundary("OFFERED")
        events.tryEmit(PopupEvent.Candidate(display, detection))
    }
    private suspend fun handleChoice(choice: UserChoice, analysis: PopupAnalysis): ActionResult? {
        val old = original?.takeIf { it.token == choice.token } ?: return null
        val detected = shownDetection?.takeIf { it.token == old.token } ?: return null
        completedKey = Triple(old.token.packageName, old.token.windowId, old.token.contentDigest)
        if (choice.kind == ChoiceKind.KEEP_OPEN) { original = null; return null }
        if (choice.kind != ChoiceKind.CLOSE_ONCE || !choice.buttonMeaningConfirmed) return null
        if (selected != choice.token) return ActionResult(choice.token, ActionStatus.STALE, SafetyReason.STALE_SCREEN)
        fun complete(result: ActionResult): ActionResult = result.also {
            AccessibilityRuntime.recordManualResult(it)
            if (it.attempted) AccessibilityRuntime.recordF9Decision(NextStep.EXECUTE, true)
        }
        try {
            cleanup.removalReturned(overlay.windowState.status == OverlayWindowStatus.HIDDEN)
            val remaining = old.capturedAtUptimeMillis + 5_000L - SystemClock.uptimeMillis()
            if (remaining <= 0 || withTimeoutOrNull(remaining) { cleanup.awaitObservationPermit() } == null)
                return complete(ActionResult(old.token, ActionStatus.STALE, SafetyReason.STALE_SCREEN))
            currentCoroutineContext().ensureActive()
            if (!selectedValid || health.failed || interacting() || !verify(old)) {
                return complete(ActionResult(old.token, ActionStatus.STALE, SafetyReason.STALE_SCREEN))
            }
            val version = epoch()
            val token = old.token.copy(screenVersion = version, captureEpoch = version)
            val refreshed = old.copy(token = token) // original collection time is NOT renewed
            return session.executeUser(refreshed, analysis.copy(token = token), choice.copy(token = token), detected.copy(token = token)) {
                selectedValid && !closed && !health.failed && epoch() == version && !interacting() && cleanup.ready
            }.let(::complete)
        } catch (cancelled: CancellationException) {
            if (!currentCoroutineContext().isActive) throw cancelled
            return complete(ActionResult(old.token, ActionStatus.NOT_EXECUTED, SafetyReason.UNVERIFIED_TARGET))
        } finally {
            selectedValid = false; selected = null; original = null; shownAnalysis = null; shownDetection = null; gate.clear()
            // A next popup may have arrived while this result was being observed. Never replay
            // it: explicitly ask F0/F1 for a new read after the accepted choice finishes.
            handler.post { if (!closed && !health.failed && cleanup.ready) review() }
        }
    }
    fun invalidate(version: Long, ttl: Boolean, ownedRetirement: Boolean) {
        gate.clear()
        if (ownedRetirement && selectedValid) return // flow lifetime only, never an action permit
        selectedValid = false; guarded.revoke()
        // A continues invalidating every target token. Once C accepted an explicit choice, its
        // coroutine owns only completion/result speech: do not cancel observation of our click.
        // manualCurrent is now false, so this cannot authorize a click that has not happened.
        if (selected != null) return
        if (ttl) {
            // Expiry is not a new review request. Native removal at the 30 s display deadline
            // must not automatically collect and show the identical popup with another 5 s TTL.
            original?.token?.let { completedKey = Triple(it.packageName, it.windowId, it.contentDigest) }
        }
        if (ttl && lease.detach(overlay.windowState, binding.boundWindowFor(overlay.windowState.generation) != null) &&
            overlay.expireEvidence(overlay.windowState.generation)) {
            clearHistory(); original = null; shownAnalysis = null; shownDetection = null
            events.tryEmit(PopupEvent.Invalidated(version))
            displayTimer?.cancel()
            displayTimer = scope.launch { delay(lease.remainingMillis(overlay.windowState).coerceAtLeast(0)); invalidate(version, false, false) }
            return
        }
        lease.clear(); displayTimer?.cancel(); original = null; shownAnalysis = null; shownDetection = null; clearHistory()
        events.tryEmit(PopupEvent.Invalidated(version)); overlay.dismiss(); announcer.stop()
    }
    fun shouldIgnore(event: AccessibilityEvent): Boolean {
        if (closed || health.failed) return false
        if (inspectExpiredOverlayUpdate(overlay.windowState, lease.retains(overlay.windowState), event.eventType,
                { event.windowChanges }, { event.packageName?.toString() }, service.packageName, { event.windowId },
                { binding.boundWindowFor(overlay.windowState.generation) }, ::readOwned)) return true
        return inspectOwnedOverlayEvent(overlay.windowState, { event.eventType }, { event.windowChanges },
            { event.packageName?.toString() }, ::flowToken,
            { token, diagnostic -> binding.matches(event.windowId, readOwned(), token, overlay.windowState.generation, diagnostic) },
            { service.packageName }, eventDiagnostic)
    }
    fun isOwnedRetirement(event: AccessibilityEvent): Boolean = selectedValid && event.windowId == retiredId &&
        (event.packageName == null || event.packageName?.toString() == service.packageName) &&
        event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges == AccessibilityEvent.WINDOWS_CHANGE_REMOVED
    fun hasPrompt() = overlay.isShowing
    fun canObserve() = !closed && !health.failed && cleanup.ready && !overlay.isShowing
    private fun nativeProcessed(n: OverlayNativeWindowLifetime) {
        if (closed || !cleanup.pending) return
        val hidden = overlay.windowState.status == OverlayWindowStatus.HIDDEN
        cleanup.removalReturned(hidden)
        if (cleanup.nativeProcessed(n, hidden, presence = ::presence)) { native = null; target = null; if (selected == null) review() }
    }
    @Suppress("DEPRECATION") private fun presence(id: Int) = cleanupWindowPresence(id, { service.windows }, { it.id }, { it.recycle() })
    @Suppress("DEPRECATION") private fun readOwned(): Set<Int>? = readOwnedOverlayIds({ service.windows },
        { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.displayId == 0 }, { it.root },
        { root, w -> root.packageName?.toString() == service.packageName && root.windowId == w.id },
        { it.title?.toString() == service.getString(R.string.ui_choice_pane_title) }, { it.id }, { it.recycle() }, { it.recycle() }, health::fail, eventDiagnostic)
    fun processed(receipt: OverlayCleanupBarrier.Receipt?) {
        if (receipt == null || closed) return
        cleanup.processed(receipt, overlay.windowState.status == OverlayWindowStatus.HIDDEN, presence = ::presence)
        if (cleanup.ready) { native = null; target = null; if (selected == null) handler.post { if (!closed) review() } }
    }
    fun close() {
        if (closed) return
        closed = true; selectedValid = false; gate.clear(); lease.clear(); guarded.revoke(); displayTimer?.cancel()
        job?.cancel(); original = null; shownAnalysis = null; shownDetection = null; clearHistory(); eventDiagnostic.disarm()
        try { overlay.dismiss() } finally { promptListener.onDismissed(); handler.removeCallbacksAndMessages(null) }
    }
}
