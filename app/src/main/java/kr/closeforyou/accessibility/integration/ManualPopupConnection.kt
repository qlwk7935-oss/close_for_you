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
    private val collectForChoice: (PopupSnapshot, () -> Unit) -> PopupSnapshot?,
    private val awaitInteractionEnd: suspend () -> Unit, private val intentInteracting: () -> Boolean,
    private val speechReadiness: StateFlow<SpeechReadiness>,
    private val verifyDisplay: (PopupSnapshot, ManualDisplayIdentity, () -> Unit, ManualDisplayProbe) -> ManualDisplayVerdict,
    private val trace: ManualPromptTrace? = null,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val promptLifetime = ManualPromptLifetime(SystemClock::uptimeMillis)
    private val binding = OwnedOverlayBinding()
    private val displayRegistration = ManualDisplayRegistration()
    private val nativeOwner = Any()
    private var native: OverlayNativeWindowLifetime? = null
    private var retiredId: Int? = null
    private var retiredNative: OverlayNativeWindowLifetime? = null
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
    private var selectedAction: ManualActionTrace? = null
    private var endCause = ManualPromptCause.UNKNOWN
    private var completedKey: Triple<String, Int, String>? = null
    private val reviews = ChoiceReviewBudget()
    private val cleanupReviews = ManualCleanupReview()
    private val speechRequests = ManualSpeechRequests()
    private val eventDiagnostic = OverlayEventDiagnostic { category, reason, detail, _, _, _, _, _, scan, relation, _ ->
        AccessibilityRuntime.ownedEventRejection("${category.name};${reason.name};${detail.name};${scan.name};${relation.name}")
    }
    private val events = MutableSharedFlow<PopupEvent>(extraBufferCapacity = 1)
    private fun flowToken(): SnapshotToken? = if (closed || health.failed) null else
        selected?.takeIf { selectedValid } ?: promptLifetime.current() ?: AccessibilityRuntime.currentToken()
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
            val requestOwner = speechRequests.replace()
            if (request.kind == AnnouncementKind.ACTION_RESULT) return tts.announce(request.copy(token = null))
            val reportSpeech = trace?.speechReporter()
            return announceManualPrompt(speechReadiness,
                current = { speechRequests.permits(requestOwner) && !closed && request.token != null && request.token == flowToken() },
                record = { reportSpeech?.invoke(it) }) { tts.announce(request) }
        }
        override suspend fun repeatLast() = if (flowToken() != null) tts.repeatLast()
            else AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
        override fun stop() {
            speechRequests.stop()
            trace?.speech(ManualSpeechState.STOP_REQUESTED)
            manualCleanupCall(ManualCleanupSite.TTS_STOP, trace?.cleanup, cleanup) { tts.stop() }
        } // TTL no longer cancels this manual flow.
    }
    private val settings = UiVoicePreferences.get(service)
    private val promptListener = PromptAnnouncementListener(service, announcer, scope,
        mode = { settings.current().promptAnnounceMode.also {
            trace?.speech(if (it == PromptAnnounceMode.PANE_TITLE_AND_TTS) ManualSpeechState.MODE_TTS else ManualSpeechState.MODE_PANE_ONLY)
        } }, detail = { settings.current().detail })
    val overlay: AccessibilityChoiceOverlay = AccessibilityChoiceOverlay(service, object : OverlayListener {
        override fun onShown(prompt: ChoicePrompt, speakPressed: () -> Boolean) =
            manualCleanupCall(ManualCleanupSite.CALLBACK_SHOWN, trace?.cleanup, cleanup) {
                promptListener.onShown(prompt, speakPressed)
            }
        override fun onDismissed() {
            // C invokes this listener BEFORE removeView. It is not removal confirmation.
            trace?.record(ManualPromptPoint.OVERLAY_DISMISS_STARTED, endCause)
            manualCleanupCall(ManualCleanupSite.CALLBACK_DISMISSED, trace?.cleanup, cleanup) {
                val n = native
                retiredNative = n
                retiredId = n?.retirementWindow(nativeOwner, n.generation)
                cleanup.retiring(binding.boundWindowFor(n?.generation ?: 0), n?.generation ?: 0, target, n, nativeOwner)
                binding.clear(); promptListener.onDismissed()
            }
        }
    }, PromptControlsSupport(tts, null) { kr.closeforyou.ui.voice.command.SpeakButtonState.TURNED_OFF }).also { view ->
        view.observeNativeWindows(object : OverlayNativeWindowObserver {
            override fun created(generation: Long) = OverlayNativeWindowLifetime(generation, nativeOwner).also {
                displayRegistration.created(generation); promptLifetime.nativeCreating(generation); native = it
            }
            override fun changed(lifetime: OverlayNativeWindowLifetime) {
                handler.post { manualCleanupCall(ManualCleanupSite.NATIVE_CALLBACK, trace?.cleanup, cleanup) { nativeProcessed(lifetime) } }
            }
        })
    }
    private val gate = ChoicePresentationGate(SystemClock::uptimeMillis,
        current = { !closed && !health.failed && !interacting() && AccessibilityRuntime.currentToken() == it },
        cleanupCurrent = cleanup::permits,
        nextTurn = { awaitChoicePresentationTurn(handler::post, handler::removeCallbacks) },
        verify = { baseline, checkpoint -> checkpoint(); verify(baseline).also { checkpoint() } },
        reject = {
            trace?.record(ManualPromptPoint.ADMISSION_REJECTED, ManualPromptCause.PRESENTATION_GATE_REJECTED)
            AccessibilityRuntime.manualBoundary("ADMISSION_REJECTED"); review()
        })
    private fun recordPresentation(point: ManualReviewPoint, prompt: ChoicePrompt) {
        val t = trace ?: return
        t.review(manualReviewPresentationCode(point, original?.token, prompt, reviews))
    }
    private val delegate = object : ChoicePromptPresenter {
        override val isShowing get() = overlay.isShowing
        override val windowState get() = overlay.windowState
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            trace?.record(ManualPromptPoint.PRESENT_REQUESTED)
            recordPresentation(ManualReviewPoint.PRESENT_REQUESTED, prompt)
            if (!cleanup.ready || overlay.windowState.status != OverlayWindowStatus.HIDDEN || health.failed) {
                trace?.record(ManualPromptPoint.PRESENT_REJECTED, if (health.failed) ManualPromptCause.HEALTH_FAILED else ManualPromptCause.CLEANUP_FAILED)
                return PromptShowResult.BLOCKED
            }
            before = readOwned(); target = prompt.token.windowId
            eventDiagnostic.arm()
            val result = overlay.present(prompt, onAnswer)
            AccessibilityRuntime.manualBoundary("PRESENT_" + result.name + ";SUMMARY=" + prompt.summary.name +
                ";PRESS=" + prompt.options.count { it.kind == kr.closeforyou.ui.voice.choice.PromptOptionKind.PRESS_CANDIDATE })
            if (result == PromptShowResult.SHOWN) {
                binding.expect(before, prompt.token, overlay.windowState.generation)
                promptLifetime.shown(prompt.token, overlay.windowState)
                trace?.record(ManualPromptPoint.SHOWN)
                recordPresentation(ManualReviewPoint.SHOWN, prompt)
                displayTimer?.cancel()
                displayTimer = scope.launch {
                    delay(ChoiceDisplayLease.MAX_DISPLAY_MILLIS)
                    invalidate(epoch(), ttl = false, ownedRetirement = false, cause = ManualPromptCause.DISPLAY_LEASE_EXPIRED)
                }
                AccessibilityRuntime.recordManualPrompt()
            } else trace?.record(ManualPromptPoint.PRESENT_REJECTED, ManualPromptCause.UNKNOWN)
            return result
        }
        override fun dismiss() {
            trace?.record(ManualPromptPoint.DISMISS_REQUESTED, endCause)
            dismissOverlay()
        }
    }
    private val guarded = GuardedChoicePresenter(delegate, ::flowToken, intentInteracting, health,
        onFailure = {
            trace?.cleanup?.record(ManualCleanupSite.GUARDED_PRESENTER, ManualCleanupIssue.GUARD_REJECTED)
            trace?.record(ManualPromptPoint.CLEANUP_FAILED, ManualPromptCause.HEALTH_FAILED); cleanup.fail()
        })
    private val presenter = object : ChoicePromptPresenter {
        override val isShowing get() = guarded.isShowing
        override val windowState get() = guarded.windowState
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit) = guarded.present(prompt) { answer ->
            // Register the already generation-checked answer synchronously, before resuming C's
            // suspended flow: native removal events may be next on Main's queue.
            if (answer is PromptAnswer.Picked && answer.choice.kind == ChoiceKind.CLOSE_ONCE) {
                selected = answer.choice.token; selectedValid = true
                selectedAction = trace?.action().also { it?.record(ManualActionStep.CHOICE_ACCEPTED, ManualActionState.PASSED) }
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
            trace?.record(ManualPromptPoint.ADMISSION_REQUESTED)
            try {
                if (!gate.awaitReady(snapshot.token)) {
                    trace?.record(ManualPromptPoint.ADMISSION_REJECTED, ManualPromptCause.PRESENTATION_GATE_REJECTED)
                    throw CancellationException("MANUAL_PRESENTATION_STALE")
                }
                trace?.record(ManualPromptPoint.ADMISSION_ACCEPTED)
                return analysis
            } catch (cancelled: CancellationException) {
                trace?.record(ManualPromptPoint.FLOW_CANCELLED, ManualPromptCause.FLOW_CANCELLED)
                throw cancelled
            }
        }
    }
    private val flow = PopupChoiceFlow(wiring.source, admittedExtractor, DecisionPopupClassifier(), presenter, announcer, batcher,
        ChoiceHandler { choice, analysis -> handleChoice(choice, analysis) }, UiText(service::getString),
        contentText = { PromptSpeech.content(service, it) }, moveToButtonEnabled = false,
        rememberChoicesEnabled = false, revalidatingEnabled = false)

    fun start() {
        check(job == null)
        job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { flow.run() } catch (cancelled: CancellationException) {
                trace?.record(ManualPromptPoint.FLOW_CANCELLED, ManualPromptCause.FLOW_CANCELLED); throw cancelled
            } catch (_: Exception) {
                trace?.cleanup?.record(ManualCleanupSite.FLOW, ManualCleanupIssue.EXCEPTION)
                trace?.record(ManualPromptPoint.FLOW_FAILED, ManualPromptCause.HEALTH_FAILED)
                AccessibilityRuntime.manualBoundary("FLOW_FAILED"); health.fail(); close()
            }
        }
    }
    fun offer(snapshot: PopupSnapshot, detection: PopupDetection, display: PopupSnapshot, analysis: PopupAnalysis) {
        val key = Triple(snapshot.token.packageName, snapshot.token.windowId, snapshot.token.contentDigest)
        val rejection = manualOfferRejection(closed, { health.failed }, { completedKey == key },
            { cleanup.ready }, { overlay.isShowing }, { selected != null })
        if (rejection != null) {
            trace?.record(ManualPromptPoint.OFFER_REJECTED, rejection)
            AccessibilityRuntime.manualBoundary("OFFER_UNAVAILABLE"); return
        }
        if (detection.signals.none { it == PopupSignal.NEW_WINDOW || it == PopupSignal.SAME_WINDOW_OVERLAY }) {
            AccessibilityRuntime.manualBoundary("NO_POPUP_SCOPE"); return
        }
        if (!reviews.admit(snapshot.token)) {
            trace?.record(ManualPromptPoint.OFFER_REJECTED, ManualPromptCause.REVIEW_BUDGET_EXHAUSTED)
            AccessibilityRuntime.manualBoundary("REVIEW_BUDGET_EXHAUSTED"); return
        }
        val permit = cleanup.permit() ?: return
        trace?.begin(); endCause = ManualPromptCause.UNKNOWN
        trace?.record(ManualPromptPoint.REVIEW_ADMISSION, when (reviews.lastAdmission) {
            ManualReviewAdmission.FIRST_OFFER -> ManualPromptCause.REVIEW_FIRST
            ManualReviewAdmission.UNCHANGED_RECOVERY -> ManualPromptCause.REVIEW_UNCHANGED_RECOVERY
            ManualReviewAdmission.IDENTITY_CHANGED -> ManualPromptCause.REVIEW_IDENTITY_CHANGED
            ManualReviewAdmission.EXHAUSTED -> ManualPromptCause.REVIEW_BUDGET_EXHAUSTED
        })
        trace?.review(ManualReviewCode(ManualReviewPoint.ADMITTED, reviews.lastAdmission, reviews.lastChange))
        original = snapshot; shownAnalysis = analysis; shownDetection = detection
        gate.prepare(snapshot, permit)
        AccessibilityRuntime.manualBoundary("OFFERED")
        events.tryEmit(PopupEvent.Candidate(display, detection))
    }
    private suspend fun handleChoice(choice: UserChoice, analysis: PopupAnalysis): ActionResult? {
        val action = selectedAction
        val old = original?.takeIf { it.token == choice.token } ?: run {
            action?.record(ManualActionStep.MANUAL_END, ManualActionState.REJECTED, ManualActionReason.ORIGINAL_UNAVAILABLE)
            return null
        }
        val detected = shownDetection?.takeIf { it.token == old.token } ?: run {
            action?.record(ManualActionStep.MANUAL_END, ManualActionState.REJECTED, ManualActionReason.DETECTION_UNAVAILABLE)
            return null
        }
        completedKey = Triple(old.token.packageName, old.token.windowId, old.token.contentDigest)
        if (choice.kind == ChoiceKind.KEEP_OPEN) {
            original = null; shownAnalysis = null; shownDetection = null; promptLifetime.clear(); return null
        }
        if (choice.kind != ChoiceKind.CLOSE_ONCE || !choice.buttonMeaningConfirmed) {
            promptLifetime.clear(); return null
        }
        if (selected != choice.token) {
            action?.record(ManualActionStep.MANUAL_END, ManualActionState.REJECTED, ManualActionReason.SELECTION_STALE)
            return ActionResult(choice.token, ActionStatus.STALE, SafetyReason.STALE_SCREEN)
        }
        fun complete(result: ActionResult): ActionResult = result.also {
            action?.record(ManualActionStep.MANUAL_END, ManualActionState.COMPLETED, safety = it.reason, outcome = it.outcome)
            AccessibilityRuntime.recordManualResult(it)
            if (it.attempted) AccessibilityRuntime.recordF9Decision(NextStep.EXECUTE, true)
        }
        try {
            cleanup.removalReturned(overlay.windowState.status == OverlayWindowStatus.HIDDEN)
            currentCoroutineContext().ensureActive()
            fun intentCurrent() = selectedValid && !closed && !health.failed && !interacting() && cleanup.ready
            val refreshed = ManualChoiceRefresh.afterRemoval(old, analysis, choice, detected, SystemClock::uptimeMillis,
                ::intentCurrent, awaitRemoval = { cleanup.awaitObservationPermit(); Unit }, awaitQuiet = awaitInteractionEnd, trace = action
            ) { checkpoint -> collectForChoice(old, checkpoint) }
                ?: run {
                    action?.record(ManualActionStep.CLICK_ATTEMPT, ManualActionState.NOT_ATTEMPTED)
                    return complete(ActionResult(old.token, ActionStatus.STALE, SafetyReason.STALE_SCREEN))
                }
            val version = refreshed.snapshot.token.screenVersion
            return session.executeUser(refreshed.snapshot, refreshed.analysis, refreshed.choice, refreshed.detection, trace = action) {
                intentCurrent() && epoch() == version
            }.let(::complete)
        } catch (cancelled: CancellationException) {
            action?.record(ManualActionStep.MANUAL_END, ManualActionState.CANCELLED)
            if (!currentCoroutineContext().isActive) throw cancelled
            return complete(ActionResult(old.token, ActionStatus.NOT_EXECUTED, SafetyReason.UNVERIFIED_TARGET))
        } catch (failure: Exception) {
            action?.record(ManualActionStep.MANUAL_END, ManualActionState.FAILED)
            throw failure
        } finally {
            if (selectedAction === action) selectedAction = null
            selectedValid = false; selected = null; original = null; shownAnalysis = null; shownDetection = null; gate.clear(); promptLifetime.clear()
            retiredNative = null; retiredId = null
            // A next popup may have arrived while this result was being observed. Never replay
            // it: explicitly ask F0/F1 for a new read after the accepted choice finishes.
            handler.post { if (!closed && !health.failed && cleanup.ready) review() }
        }
    }
    fun invalidate(version: Long, ttl: Boolean, ownedRetirement: Boolean, preserveManual: Boolean = false,
        cause: ManualPromptCause = if (ttl) ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED else
            if (preserveManual) ManualPromptCause.UNATTRIBUTED_TOUCH else ManualPromptCause.UNKNOWN) {
        // Admission is never consulted again after SHOWN. Clearing it cannot expire a C lease.
        gate.clear()
        trace?.record(ManualPromptPoint.INVALIDATED, cause)
        val retained = applyManualInvalidation(cause, promptLifetime.current() != null, !health.failed,
            selected != null, ownedRetirement && selectedValid, revoke = {
                endCause = cause
                selectedValid = false; guarded.revoke(); promptLifetime.clear(); displayTimer?.cancel()
            }, remove = {
                original = null; shownAnalysis = null; shownDetection = null; clearHistory()
                events.tryEmit(PopupEvent.Invalidated(version))
                val batch = kr.closeforyou.accessibility.CollectionReleaseBatch()
                batch.attempt { dismissOverlay() }; batch.attempt { announcer.stop() }
                try { batch.finish() } catch (failure: Exception) {
                    trace?.record(ManualPromptPoint.CLEANUP_FAILED, ManualPromptCause.CLEANUP_FAILED)
                    throw failure
                }
            })
        if (retained) trace?.record(if (selected == null) ManualPromptPoint.DISPLAY_RETAINED
            else ManualPromptPoint.RESULT_RETAINED, cause)
    }
    fun hasPendingPresentation(): Boolean = !closed && !health.failed && !overlay.isShowing &&
        selected == null && gate.hasCurrentObservation()
    /** Foreign/old event metadata is not a target-change proof. Retire ALL execution evidence
     * first; compare the original entire popup under THIS native C window synchronously. */
    fun reviewDisplayedEvent(type: Int, changes: Int = 0, eventWindow: Int = -1, retire: () -> Long): Boolean {
        if (!manualDisplayReviewEvent(type, changes, eventWindow, original?.token?.windowId) ||
            closed || health.failed || interacting() || selected != null ||
            !overlay.isShowing || promptLifetime.current() == null ||
            shownDetection?.signals?.contains(PopupSignal.NEW_WINDOW) != true) return false
        val source = original ?: return false
        var version = epoch()
        val probe = ManualDisplayProbe(nodeDiagnostics = trace != null)
        trace?.record(ManualPromptPoint.DISPLAY_REVIEW_STARTED)
        val result = reviewManualDisplay(retire = { version = retire() }, verify = {
            val started = SystemClock.uptimeMillis()
            val generation = overlay.windowState.generation
            fun checkpoint() {
                manualDisplayCheckpoint(current = { !closed && !health.failed && !interacting() && selected == null &&
                    overlay.windowState.status == OverlayWindowStatus.SHOWING && overlay.windowState.generation == generation &&
                    promptLifetime.current() == source.token && epoch() == version }, SystemClock::uptimeMillis, started, probe)
            }
            fun owned(): ManualDisplayIdentity? = displayRegistration.read(generation, { readManualNativeWindow(native, nativeOwner, generation, null) },
                { before?.contains(it) }, {
                    val read = ManualOwnedReadProbe() // policy facts must be identical in Debug and Release
                    try { readOwned(read) } finally { probe.ownedRead = read.code() }
                }, probe)
            verifyRegisteredManualDisplay(probe, ::checkpoint, ::owned) { identity ->
                verifyDisplay(source, identity, ::checkpoint, probe)
            }
        }, remove = {
            invalidate(version, ttl = false, ownedRetirement = false, cause =
                when (type) {
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> ManualPromptCause.CONTENT_INVALIDATED
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED -> ManualPromptCause.WINDOW_SET_INVALIDATED
                    else -> ManualPromptCause.WINDOW_STATE_INVALIDATED
                })
        }, onChecked = { verdict ->
            if (verdict == ManualDisplayVerdict.UNAVAILABLE && probe.detail == ManualDisplayDetail.NONE) probe.reject(ManualDisplayDetail.UNKNOWN)
            trace?.displayReview(ManualDisplayReviewCode(when (type) {
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> ManualEventKind.CONTENT
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> ManualEventKind.WINDOW_SET
                else -> ManualEventKind.WINDOW_STATE
            }, if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) manualWindowMask(changes) else ManualWindowMask.NOT_APPLICABLE,
                verdict, probe.failurePoint, probe.detail, probe.failureOwned, probe.failureListing, probe.failureNodes))
        })
        val cause = when (result) {
            ManualDisplayVerdict.SAME -> ManualPromptCause.TARGET_RECONFIRMED
            ManualDisplayVerdict.CHANGED -> ManualPromptCause.TARGET_CHANGED
            ManualDisplayVerdict.UNAVAILABLE -> ManualPromptCause.TARGET_REVIEW_UNAVAILABLE
        }
        trace?.record(ManualPromptPoint.DISPLAY_REVIEW_FINISHED, cause)
        if (result == ManualDisplayVerdict.SAME) trace?.record(ManualPromptPoint.DISPLAY_RETAINED, cause)
        return true
    }
    fun shouldIgnore(event: AccessibilityEvent): Boolean {
        val probe = if (trace != null && (manualControlEvent(event.eventType) || manualWindowEvent(event.eventType)))
            ManualOwnershipProbe() else null
        try {
            if (closed) return probe?.reject(OwnershipCheck.CLOSED) ?: false
            if (health.failed) return probe?.reject(OwnershipCheck.HEALTH) ?: false
            val state = overlay.windowState
            val ignored = inspectManualEvent(event.eventType, nativeControl = {
                inspectManualOwnedInteraction(event.eventType, state, promptLifetime, { event.eventTime },
                    { event.packageName?.toString() }, service.packageName, { event.windowId },
                    { readManualNativeWindow(native, nativeOwner, state.generation, probe) }, before, { readOwned() }, probe)
            }, otherEvent = {
                inspectOwnedOverlayEvent(overlay.windowState, { event.eventType }, { event.windowChanges },
                    { event.packageName?.toString() }, ::flowToken,
                    { token, diagnostic -> binding.matches(event.windowId, readOwned(), token, overlay.windowState.generation, diagnostic) },
                    { service.packageName }, eventDiagnostic)
            }, nativeWindowEvent = {
                inspectManualOwnedWindowEvent(event.eventType, { event.windowChanges }, state, promptLifetime,
                    { event.eventTime }, { event.packageName?.toString() }, service.packageName, { event.windowId },
                    { readManualNativeWindow(native, nativeOwner, state.generation, probe) }, before, { readOwned() }, probe)
            })
            if (ignored) displayRegistration.confirmed(state.generation)
            if (ignored && event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                trace?.record(ManualPromptPoint.DISPLAY_RETAINED, ManualPromptCause.OWNED_CONTENT_CONFIRMED)
            if (ignored && manualWindowEvent(event.eventType))
                trace?.record(ManualPromptPoint.DISPLAY_RETAINED, ManualPromptCause.OWNED_WINDOW_CONFIRMED)
            return ignored
        } finally {
            if (probe != null) kr.closeforyou.accessibility.diagnosticWrite {
                trace?.ownership(probe.code())
                trace?.windowEvent(manualEventCode(event.eventType, probe.check, probe.result, event.windowId,
                    native?.liveWindow(nativeOwner, overlay.windowState.generation), original?.token?.windowId,
                    event.packageName?.toString(), service.packageName, original?.token?.packageName,
                    if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) event.windowChanges else 0))
            }
        }
    }
    fun isOwnedRetirement(event: AccessibilityEvent): Boolean = selectedValid && event.windowId == retiredId &&
        (event.packageName == null || event.packageName?.toString() == service.packageName) &&
        event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && event.windowChanges == AccessibilityEvent.WINDOWS_CHANGE_REMOVED
    /** The primitive verdict is captured once BEFORE barrier observation/ordinary invalidation.
     * It can keep a pending C choice alive, never complete cleanup or reuse execution evidence. */
    fun compatibleRetirementEvent(event: AccessibilityEvent): Boolean {
        val n = retiredNative ?: return false
        val id = retiredId ?: return false
        if (event.windowId != id) return false
        val code = inspectManualRetirementEvent(event.eventType,
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) event.windowChanges else 0,
            choice = { !closed && !health.failed && selectedValid && selected != null && selected == original?.token },
            hidden = { overlay.windowState.status == OverlayWindowStatus.HIDDEN },
            packageMatches = {
                val pkg = event.packageName?.toString()
                retirementPackageConfirmed(event.eventType, pkg == service.packageName, pkg == null) {
                    n.removalComplete(nativeOwner, n.generation, id) && presence(id) == CleanupWindowPresence.ABSENT
                }
            },
            timeMatches = { promptLifetime.retirementEventWithinLease(n.generation, event.eventTime) },
            nativeRegistered = {
                !n.identityUnavailable && n.registrationConfirmed(nativeOwner, n.generation) &&
                    n.retirementWindow(nativeOwner, n.generation) == id && before?.contains(id) == false &&
                    original?.token?.windowId != id
            }, absenceIfReleased = {
                if (cleanup.pending) true // blocked until nativeProcessed proves completion + absence
                else cleanup.ready && n.removalComplete(nativeOwner, n.generation, id) &&
                    presence(id) == CleanupWindowPresence.ABSENT
            })
        trace?.retirement(code)
        return code.check == ManualRetirementCheck.COMPATIBLE
    }
    /** Cleanup-only: also applies to removal caused by invalidation (no selected choice).
     * It never preserves a callback or token. Only actual native completion + absence can
     * release the barrier. The normal service handler still invalidates this event. */
    fun nativeCleanupRetirementEvent(event: AccessibilityEvent): Boolean {
        if (closed || !cleanup.pending) return false
        val n = retiredNative ?: return false
        val id = retiredId ?: return false
        if (event.windowId != id) return false
        val code = inspectNativeCleanupRetirement(event.eventType,
            if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) event.windowChanges else 0,
            hidden = { overlay.windowState.status == OverlayWindowStatus.HIDDEN },
            packageMatches = {
                val pkg = event.packageName?.toString()
                retirementPackageConfirmed(event.eventType, pkg == service.packageName, pkg == null) {
                    n.removalComplete(nativeOwner, n.generation, id) && presence(id) == CleanupWindowPresence.ABSENT
                }
            }, nativeRegistered = {
                !n.identityUnavailable && n.registrationConfirmed(nativeOwner, n.generation) &&
                    n.retirementWindow(nativeOwner, n.generation) == id && before?.contains(id) == false &&
                    target != null && target != id
            }, nativeRemovalComplete = { n.removalComplete(nativeOwner, n.generation, id) })
        trace?.retirement(code)
        return code.check == ManualRetirementCheck.CLEANUP_WAIT
    }
    fun hasPrompt() = overlay.isShowing
    fun canObserve() = !closed && !health.failed && cleanup.ready && !overlay.isShowing
    private fun nativeProcessed(n: OverlayNativeWindowLifetime) {
        if (closed || !cleanup.pending) return
        val hidden = overlay.windowState.status == OverlayWindowStatus.HIDDEN
        cleanup.removalReturned(hidden)
        if (cleanup.nativeProcessed(n, hidden, presence = ::presence)) {
            trace?.record(ManualPromptPoint.OVERLAY_REMOVED, endCause)
            native = null; target = null; reviewAfterCleanup()
        }
    }
    private fun dismissOverlay() = dismissManualOverlay({ overlay.dismiss() },
        { overlay.windowState.status }, cleanup, trace?.cleanup)
    @Suppress("DEPRECATION") private fun presence(id: Int) = cleanupWindowPresence(id, { service.windows }, { it.id }, { it.recycle() })
    @Suppress("DEPRECATION") private fun readOwned(probe: ManualOwnedReadProbe? = null): Set<Int>? = readOwnedOverlayIds({ service.windows },
        { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && it.displayId == 0 }, { it.root },
        { root, w -> root.packageName?.toString() == service.packageName && root.windowId == w.id },
        { it.title?.toString() == service.getString(R.string.ui_choice_pane_title) }, { it.id }, { it.recycle() }, { it.recycle() },
        { manualOwnedReadFailed(health, trace?.cleanup) }, eventDiagnostic, probe)
    fun processed(receipt: OverlayCleanupBarrier.Receipt?, nativeCleanupWait: Boolean = false) {
        if (closed) return
        if (receipt == null) {
            // A queued mixed removal is not a receipt. After NORMAL handler invalidation,
            // recheck this exact View's actual detach + full-list absence under the old deadline.
            if (nativeCleanupWait) (retiredNative ?: native)?.let(::nativeProcessed)
            return
        }
        val wasPending = cleanup.pending
        val hidden = overlay.windowState.status == OverlayWindowStatus.HIDDEN
        cleanup.processed(receipt, hidden, presence = ::presence)
        completeManualCleanupReview(wasPending, cleanup.ready, hidden) {
            trace?.record(ManualPromptPoint.OVERLAY_REMOVED, endCause)
            native = null; target = null; reviewAfterCleanup()
        }
    }
    private fun reviewAfterCleanup() {
        val permit = cleanup.permit()
        if (!cleanupReviews.take(permit, closed || health.failed, selected != null)) return
        trace?.record(ManualPromptPoint.REVIEW_REQUESTED, ManualPromptCause.CLEANUP_REVIEW)
        // Preserve the native-completion path's synchronous invalidation of old observations;
        // do not insert another turn in which a resumed old waiter could admit a prompt.
        if (!closed && !health.failed && selected == null && cleanup.permits(requireNotNull(permit))) review()
    }
    fun close() {
        if (closed) return
        trace?.record(ManualPromptPoint.SERVICE_CLOSED, ManualPromptCause.SERVICE_STOPPED)
        endCause = ManualPromptCause.SERVICE_STOPPED
        closed = true; selectedValid = false; gate.clear(); promptLifetime.clear(); guarded.revoke(); displayTimer?.cancel()
        job?.cancel(); original = null; shownAnalysis = null; shownDetection = null; clearHistory(); eventDiagnostic.disarm()
        retiredNative = null; retiredId = null
        val batch = kr.closeforyou.accessibility.CollectionReleaseBatch()
        batch.attempt { dismissOverlay() }
        batch.attempt { manualCleanupCall(ManualCleanupSite.CALLBACK_DISMISSED, trace?.cleanup, cleanup) { promptListener.onDismissed() } }
        batch.attempt { manualCleanupCall(ManualCleanupSite.SERVICE_CLOSE, trace?.cleanup, cleanup) { handler.removeCallbacksAndMessages(null) } }
        batch.finish()
    }
}
