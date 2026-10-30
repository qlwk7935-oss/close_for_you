package kr.closeforyou.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.os.Process
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kr.closeforyou.accessibility.integration.unattributedManualTouch
import kr.closeforyou.accessibility.integration.ManualTouchState
import kr.closeforyou.accessibility.integration.completeSynchronousAccessibilityCallback
import kr.closeforyou.accessibility.integration.invalidateAfterOwnedCleanup
import kr.closeforyou.accessibility.integration.ManualPromptCause
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.accessibility.capture.AndroidScreenshotManager
import kr.closeforyou.accessibility.capture.CaptureOcrPipeline
import kr.closeforyou.accessibility.capture.KoreanMlKitOcr
import kr.closeforyou.accessibility.capture.EvidenceOutcome
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.model.*
import kr.closeforyou.accessibility.integration.F9ServiceConnection

/** A owns collection, F0, cleanup and the explicit C user-choice boundary. Automatic execution OFF. */
class CloseForYouAccessibilityService : AccessibilityService() {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val freshness = ScreenFreshness()
    private val collector = AndroidSnapshotCollector()
    private val detector = PopupCandidateDetector()
    private var pendingCapture: Job? = null
    private var evidenceJob: Job? = null
    private lateinit var pipeline: CaptureOcrPipeline
    private var expiry: Job? = null
    private var settingsObserver: Job? = null
    private var f9: F9ServiceConnection? = null
    private var touching = false
    private var unattributedTouch = false
    private val touchState = MutableStateFlow(false)
    private val contacts = ManualTouchState()
    private fun resetTouching() { contacts.clear(); touching = false; unattributedTouch = false; touchState.value = false }
    private fun updateTouch(type: Int, unattributed: Boolean) {
        contacts.observe(type, unattributed)
        touching = contacts.active
        unattributedTouch = contacts.active && !contacts.intentBlocked
        touchState.value = touching
    }
    private var connected = false
    private var inputMethodPackages: Set<String> = emptySet()
    private val preferences by lazy { CorePreferences.get(this) }

    override fun onCreate() {
        super.onCreate()
        AccessibilityRuntime.serviceLifecycle.created(Process.myPid(),
            javaClass.classLoader === AccessibilityRuntime::class.java.classLoader,
            Looper.myLooper() == Looper.getMainLooper())
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityRuntime.serviceLifecycle.connecting(Looper.myLooper() == Looper.getMainLooper())
        try {
            initializeConnectedService()
            AccessibilityRuntime.serviceLifecycle.phase(ServiceLifecyclePhase.CONNECTED)
        } catch (failure: Exception) {
            disconnect()
            AccessibilityRuntime.serviceLifecycle.phase(ServiceLifecyclePhase.INITIALIZATION_FAILED)
            throw failure
        }
    }

    private fun initializeConnectedService() {
        f9?.close(freshness.version + 1); f9 = null
        collector.resetGeometry()
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0L // User interaction must invalidate tokens immediately.
        }
        connected = true
        resetTouching()
        inputMethodPackages = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .inputMethodList.mapTo(mutableSetOf()) { it.packageName }
        val screenshots = AndroidScreenshotManager(this, preferences, ::isCurrent) { connected && !touching }
        AccessibilityRuntime.attachCaptureCapability(screenshots)
        pipeline = CaptureOcrPipeline(screenshots, KoreanMlKitOcr(), ::isCurrent, { verifyScreen(it) })
        AccessibilityRuntime.connect()
        f9 = F9ServiceConnection(this, serviceScope, { connected }, { touching },
            requestObservation = ::observeAfterCleanup,
            policyChanged = { invalidate(clearBaseline = true, permissionOrInteraction = true, cause = ManualPromptCause.POLICY_CHANGED) },
            epoch = { freshness.version }, verifyPresentation = ::verifyForChoice,
            collectForChoice = ::collectForManualChoice, awaitInteractionEnd = { touchState.first { !it } },
            intentInteracting = { touching && !unattributedTouch }).also { it.start() }
        invalidate(clearBaseline = true)
        settingsObserver = serviceScope.launch {
            preferences.state.collect {
                invalidate(clearBaseline = true, permissionOrInteraction = true, cause = ManualPromptCause.POLICY_CHANGED)
                if (!it.consentGranted) AccessibilityRuntime.block(PrivacyBlockReason.NO_CONSENT, freshness.version)
                else if (it.paused) AccessibilityRuntime.block(PrivacyBlockReason.PAUSED, freshness.version)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null || event.eventType !in OBSERVED_EVENTS) return
        val owner = f9
        var receipt: kr.closeforyou.accessibility.integration.OverlayCleanupBarrier.Receipt? = null
        var processed = false
        var cleanupWaitOnly = false
        completeSynchronousAccessibilityCallback(onRejected = { issue ->
            owner?.eventRejected(issue)
            try {
                invalidate(clearBaseline = true, permissionOrInteraction = true, cause = ManualPromptCause.SCREEN_EVENT_INVALIDATED)
            } finally { owner?.cleanupFailed() }
        }) {
            // Preserve the handler's primary failure while always attempting its original
            // receipt/cleanup callback. That callback is inside the same Android error boundary.
            CollectionResourceSafety.run(body = {
                val nativeRetirement = owner?.compatibleRetirementEvent(event) == true
                cleanupWaitOnly = !nativeRetirement && owner?.nativeCleanupRetirementEvent(event) == true
                receipt = owner?.observeEvent(event, nativeRetirement || cleanupWaitOnly)
                handleAccessibilityEvent(event, nativeRetirement && f9 === owner); processed = true
            }, cleanup = { if (processed) owner?.processed(receipt, cleanupWaitOnly) else owner?.cleanupFailed() },
                cleanupFailure = { throw CollectionCleanupFailure() })
        }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent, nativeRetirement: Boolean = false) {
        if (f9?.shouldIgnore(event) == true) return
        // Retaining presentation is NOT ignoring an event: old Runtime/F9 evidence is retired
        // before an independent, read-only comparison of the entire original popup.
        if (f9?.reviewDisplayedEvent(event.eventType,
                if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) event.windowChanges else 0, event.windowId) {
                invalidate(clearBaseline = false, permissionOrInteraction = true,
                    cause = ManualPromptCause.DISPLAY_REVIEW_PENDING)
            } == true) return
        val ownedRetirement = nativeRetirement || f9?.isOwnedRetirement(event) == true
        if (f9?.hasPrompt() == true) AccessibilityRuntime.manualInvalidation(event.eventType, event.windowChanges)
        // Copy only primitive enums/IDs, never keep the event or request event.source.
        val eventType = event.eventType
        // Never classify source focus as automatic. Retire evidence first, then resume only a
        // screen review that was already running (including evidence and pre-show admission).
        val resumeObservation = pendingFocusReview(eventType, pendingCapture?.isActive == true,
            evidenceJob?.isActive == true, f9?.hasPendingPresentation() == true)
        if (isReviewFocusEvent(eventType)) f9?.focusReview(resumeObservation)
        val interaction = eventType !in SCREEN_EVENTS
        val preserveManual = (f9?.hasPrompt() == true || unattributedTouch) &&
            unattributedManualTouch(eventType, event.windowId, event.packageName)
        val cause = when {
            preserveManual -> ManualPromptCause.UNATTRIBUTED_TOUCH
            isReviewFocusEvent(eventType) -> if (resumeObservation) ManualPromptCause.FOCUS_INVALIDATED_REVIEW_REQUESTED
                else ManualPromptCause.FOCUS_INVALIDATED_NO_REVIEW
            eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
                eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> ManualPromptCause.TOUCH_STARTED
            eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
                eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> ManualPromptCause.TOUCH_ENDED
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED -> ManualPromptCause.SCROLL_INVALIDATED
            eventType == AccessibilityEvent.TYPE_VIEW_CLICKED -> ManualPromptCause.CLICK_INVALIDATED
            eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> ManualPromptCause.CONTENT_INVALIDATED
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> ManualPromptCause.WINDOW_STATE_INVALIDATED
            eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED -> ManualPromptCause.WINDOW_SET_INVALIDATED
            interaction -> ManualPromptCause.INTERACTION_INVALIDATED
            else -> ManualPromptCause.SCREEN_EVENT_INVALIDATED
        }
        // Restart F1's review baseline ONLY for an interrupted, pre-show review. No old
        // signals/candidates survive: a full new collector/F1 read must rediscover popup scope.
        val epoch = invalidate(clearBaseline = resumeObservation, permissionOrInteraction = interaction,
            ownedRetirement = ownedRetirement, preserveManual = preserveManual, cause = cause)
        if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START) {
            updateTouch(eventType, preserveManual)
            return
        }
        if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END) {
            updateTouch(eventType, preserveManual)
            return
        }
        val settings = preferences.currentSettings()
        if (!settings.consentGranted || settings.paused) {
            f9?.reviewRejected(ManualPromptCause.POLICY_BLOCKED)
            detector.clear()
            AccessibilityRuntime.block(
                if (!settings.consentGranted) PrivacyBlockReason.NO_CONSENT else PrivacyBlockReason.PAUSED, epoch,
            )
            return
        }
        // Package identity here is a quick exclusion, never authoritative permission to read.
        val eventPackage = event.packageName?.toString()
        if (eventPackage != null) {
            packageBlock(eventPackage)?.let {
                f9?.reviewRejected(ManualPromptCause.PACKAGE_BLOCKED)
                CaptureDiagnostics.excludedEvent(eventPackage)
                detector.clear()
                AccessibilityRuntime.block(it, epoch)
                return
            }
        }
        if (touching) return
        if (eventType !in SCREEN_EVENTS) {
            // Carry an interrupted review only, never analysis/permission. Standalone focus
            // (including actual user exploration) cannot start a new review by itself.
            if (resumeObservation) scheduleCapture(epoch, PopupTrigger.WINDOW_CHANGED, -1)
            return
        }
        val trigger = if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) PopupTrigger.CONTENT_CHANGED
            else PopupTrigger.WINDOW_CHANGED
        // All Android objects have been released before this delayed job can run.
        val eventWindowId = event.windowId
        scheduleCapture(epoch, trigger, eventWindowId)
    }

    private fun scheduleCapture(epoch: Long, trigger: PopupTrigger, eventWindowId: Int) {
        pendingCapture = serviceScope.launch {
            delay(DEBOUNCE_MILLIS)
            val owner = f9 ?: return@launch
            val permit = owner.awaitObservationPermit()
            if (!connected || f9 !== owner || !freshness.isCurrent(epoch) || touching || !owner.permitsObservation(permit)) return@launch
            capture(epoch, trigger, eventWindowId)
        }
    }

    private fun capture(epoch: Long, trigger: PopupTrigger, eventWindowId: Int) {
        if (f9?.observationReady != true) return
        val trace = CaptureDiagnostics.trace(eventWindowId, epoch)
        when (val result = collector.capture(this, epoch, trigger, ::packageBlock, trace = trace)) {
            is CollectionResult.Blocked -> {
                f9?.reviewRejected(ManualPromptCause.COLLECTION_BLOCKED)
                detector.clear()
                AccessibilityRuntime.block(result.reason, epoch)
            }
            is CollectionResult.Ready -> {
                if (!freshness.isCurrent(epoch) || touching) return
                f9?.observePage()
                val signals = detector.detect(result.frame)
                if (signals.isEmpty()) {
                    f9?.reviewRejected(ManualPromptCause.F1_NOT_CANDIDATE)
                    trace.detail(CaptureDetail.NO_POPUP_SIGNAL)
                    return
                }
                trace.stage(CaptureStage.POPUP_DETECTED)
                AccessibilityRuntime.begin(result.snapshot.token, result.snapshot.capturedAtUptimeMillis)
                // Replace the transient event with Invalidated even if a collector stalled.
                expiry = serviceScope.launch {
                    val remaining = result.snapshot.capturedAtUptimeMillis + SNAPSHOT_TTL_MILLIS - android.os.SystemClock.uptimeMillis()
                    if (remaining > 0) delay(remaining)
                    if (freshness.isCurrent(epoch)) invalidate(clearBaseline = false, ttl = true)
                }
                evidenceJob = serviceScope.launch {
                    try {
                        when (val evidence = pipeline.analyze(result.snapshot, trace) { verifyScreen(it, trace) }) {
                            is EvidenceOutcome.Ready -> if (isCurrent(result.snapshot.token)) {
                                trace.evidence(evidence.issue)
                                AccessibilityRuntime.recordEvidenceIssue(evidence.issue)
                                AccessibilityRuntime.publish(evidence.snapshot,
                                    PopupDetection(result.snapshot.token, signals.toPersistentSet(), requiresUserReview = true),
                                    evidence.analysis)
                                f9?.collected(result.snapshot,
                                    PopupDetection(result.snapshot.token, signals.toPersistentSet(), requiresUserReview = true),
                                    evidence.snapshot, evidence.analysis, result.nativeActions)
                                trace.stage(CaptureStage.PUBLISHED)
                            }
                            is EvidenceOutcome.Blocked -> if (isCurrent(result.snapshot.token)) {
                                f9?.reviewRejected(ManualPromptCause.EVIDENCE_BLOCKED)
                                trace.evidence(evidence.issue)
                                AccessibilityRuntime.recordEvidenceIssue(evidence.issue)
                                AccessibilityRuntime.block(evidence.reason, epoch)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        f9?.reviewRejected(ManualPromptCause.EVIDENCE_CANCELLED)
                        trace.stage(CaptureStage.CANCELLED)
                        throw cancelled
                    } catch (_: Exception) {
                        f9?.reviewRejected(ManualPromptCause.EVIDENCE_FAILED)
                        if (isCurrent(result.snapshot.token)) {
                            trace.detail(CaptureDetail.PIPELINE_FAILED)
                            trace.evidence(EvidenceIssue.PRIVACY_UNCERTAIN)
                            trace.block(PrivacyBlockReason.UNREADABLE_TREE)
                            AccessibilityRuntime.recordEvidenceIssue(EvidenceIssue.PRIVACY_UNCERTAIN)
                            AccessibilityRuntime.block(PrivacyBlockReason.UNREADABLE_TREE, epoch)
                        }
                    }
                }
            }
        }
    }

    private fun isCurrent(token: SnapshotToken): Boolean = connected && !touching &&
        freshness.isCurrent(token.screenVersion) && AccessibilityRuntime.currentToken() == token

    /** F0-A again before/after capture and after async OCR, using fresh framework nodes. */
    private fun verifyScreen(snapshot: PopupSnapshot, trace: CaptureTrace = CaptureTrace(null)): PrivacyBlockReason? {
        if (!isCurrent(snapshot.token)) return PrivacyBlockReason.UNREADABLE_TREE
        val s = preferences.currentSettings()
        val isolation = s.captureConsentGranted && snapshot.token.packageName in s.captureAllowedPackages
        return when (val fresh = collector.capture(this, snapshot.token.captureEpoch, snapshot.trigger,
            ::packageBlock, requireCaptureIsolation = isolation, trace = trace)) {
            is CollectionResult.Blocked -> fresh.reason
            is CollectionResult.Ready -> {
                val a = snapshot.token
                val b = fresh.snapshot.token
                if (a.packageName != b.packageName || a.windowId != b.windowId ||
                    a.structureDigest != b.structureDigest || a.contentDigest != b.contentDigest ||
                    snapshot.screenBounds != fresh.snapshot.screenBounds ||
                    snapshot.windows.toSet() != fresh.snapshot.windows.toSet()) {
                    trace.detail(CaptureDetail.SCREEN_CHANGED)
                    PrivacyBlockReason.UNREADABLE_TREE
                } else null
            }
        }
    }

    /** A fresh F0 read, including after native C removal; original collection age is never renewed. */
    private fun verifyForChoice(snapshot: PopupSnapshot): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (!connected || touching || now < snapshot.capturedAtUptimeMillis ||
            now - snapshot.capturedAtUptimeMillis >= SNAPSHOT_TTL_MILLIS || packageBlock(snapshot.token.packageName) != null) return false
        return when (val fresh = collector.capture(this, freshness.version, snapshot.trigger, ::packageBlock)) {
            is CollectionResult.Blocked -> false
            is CollectionResult.Ready -> {
                EvidenceSnapshotComparison.firstMismatch(snapshot, fresh.snapshot) == EvidenceMismatch.NONE
            }
        }
    }

    /** One new full collector attempt after native C teardown, never a token/time rewrite. */
    private fun collectForManualChoice(original: PopupSnapshot, checkpoint: () -> Unit): PopupSnapshot? {
        checkpoint()
        if (!connected || touching || packageBlock(original.token.packageName) != null) return null
        val epoch = invalidate(clearBaseline = false, ownedRetirement = true)
        fun checkCurrent() {
            checkpoint()
            if (!connected || touching || !freshness.isCurrent(epoch) ||
                packageBlock(original.token.packageName) != null) throw CancellationException("MANUAL_COLLECTION_STALE")
        }
        checkCurrent()
        val fresh = collector.capture(this, epoch, original.trigger, ::packageBlock)
        checkCurrent() // The existing synchronous 100 ms collector is not a cancellable Binder API.
        return when (fresh) {
            is CollectionResult.Blocked -> null
            is CollectionResult.Ready -> { checkCurrent(); fresh.snapshot }
        }
    }

    private fun packageBlock(targetPackage: String): PrivacyBlockReason? = preferences.currentSettings().let {
        PrivacyPolicy.packageBlock(targetPackage, packageName, it.consentGranted, it.paused,
            it.allowedPackages, inputMethodPackages)
    }

    private fun invalidate(clearBaseline: Boolean, permissionOrInteraction: Boolean = false,
        ttl: Boolean = false, ownedRetirement: Boolean = false, preserveManual: Boolean = false,
        cause: ManualPromptCause = if (ttl) ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED else
            if (preserveManual) ManualPromptCause.UNATTRIBUTED_TOUCH else ManualPromptCause.UNKNOWN): Long {
        pendingCapture?.cancel()
        pendingCapture = null
        evidenceJob?.cancel()
        evidenceJob = null
        expiry?.cancel()
        expiry = null
        if (clearBaseline) detector.clear()
        return freshness.invalidate().also { version ->
            invalidateAfterOwnedCleanup({ f9?.invalidate(version, permissionOrInteraction, ttl, ownedRetirement, preserveManual, cause) },
                { AccessibilityRuntime.invalidate(version) })
        }
    }

    private fun observeAfterCleanup() {
        if (!connected || touching || f9?.observationReady != true) return
        val epoch = invalidate(clearBaseline = true)
        pendingCapture = serviceScope.launch {
            delay(DEBOUNCE_MILLIS)
            if (connected && !touching && freshness.isCurrent(epoch) && f9?.observationReady == true)
                capture(epoch, PopupTrigger.WINDOW_CHANGED, -1)
        }
    }

    override fun onInterrupt() { invalidate(clearBaseline = true, permissionOrInteraction = true, cause = ManualPromptCause.SERVICE_STOPPED) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        collector.resetGeometry()
        invalidate(clearBaseline = true, permissionOrInteraction = true, cause = ManualPromptCause.CONFIGURATION_CHANGED)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        AccessibilityRuntime.serviceLifecycle.phase(ServiceLifecyclePhase.DESTROYED)
        super.onDestroy()
    }

    private fun disconnect() {
        AccessibilityRuntime.serviceLifecycle.phase(ServiceLifecyclePhase.UNBOUND)
        connected = false
        resetTouching()
        f9?.close(freshness.version + 1); f9 = null
        settingsObserver?.cancel()
        invalidate(clearBaseline = true, cause = ManualPromptCause.SERVICE_STOPPED)
        AccessibilityRuntime.disconnect(freshness.version)
        serviceScope.cancel()
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 180L
        const val SNAPSHOT_TTL_MILLIS = 5_000L
        private val SCREEN_EVENTS = setOf(
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        )
        private val OBSERVED_EVENTS = SCREEN_EVENTS + setOf(
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START,
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END,
        )
    }
}

/** #29: focus may cancel the final queued SCREEN event; it still invalidates all old evidence. */
internal fun resumePendingObservationAfterFocus(hadPending: Boolean, eventType: Int): Boolean = hadPending &&
    eventType in setOf(AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
