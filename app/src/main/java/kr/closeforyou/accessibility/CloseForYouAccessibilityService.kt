package kr.closeforyou.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.accessibility.capture.AndroidScreenshotManager
import kr.closeforyou.accessibility.capture.CaptureOcrPipeline
import kr.closeforyou.accessibility.capture.KoreanMlKitOcr
import kr.closeforyou.accessibility.capture.EvidenceOutcome
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.accessibility.integration.ChoiceFlowLog
import kr.closeforyou.accessibility.integration.ChoiceInvalidationCause
import kr.closeforyou.core.model.*

/** A owns nodes, privacy preflight and candidate detection; no external action is performed. */
class CloseForYouAccessibilityService : AccessibilityService() {
    private val ruleCleanup = kr.closeforyou.accessibility.integration.ServiceRuleCleanup()
    private var choiceIntegration: kr.closeforyou.accessibility.integration.ServiceChoiceIntegration? = null
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val freshness = ScreenFreshness()
    private val collector = AndroidSnapshotCollector()
    private val detector = PopupCandidateDetector()
    private var pendingCapture: Job? = null
    private var evidenceJob: Job? = null
    private lateinit var pipeline: CaptureOcrPipeline
    private var expiry: Job? = null
    private var settingsObserver: Job? = null
    private var touching = false
    private var connected = false
    private var inputMethodPackages: Set<String> = emptySet()
    private val preferences by lazy { CorePreferences.get(this) }
    private val presentationHandler = Handler(Looper.getMainLooper())
    private val presentationGate = kr.closeforyou.accessibility.integration.ChoicePresentationGate(
        SystemClock::uptimeMillis, ::isCurrent,
        { choiceIntegration?.permitsObservation(it) == true },
        { kr.closeforyou.accessibility.integration.awaitChoicePresentationTurn(
            { presentationHandler.post(it) }, { presentationHandler.removeCallbacks(it) }) },
        { snapshot, check -> verifyScreen(snapshot, checkActive = check) == null },
        { token -> if (isCurrent(token)) invalidate(clearBaseline = false, cause = ChoiceInvalidationCause.EVENT) },
    )

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
            EvidenceBlockLog.connected()
            ChoiceFlowLog.connected()
        } catch (failure: Exception) {
            disconnect()
            AccessibilityRuntime.serviceLifecycle.phase(ServiceLifecyclePhase.INITIALIZATION_FAILED)
            throw failure
        }
    }

    private fun initializeConnectedService() {
        presentationGate.clear()
        ruleCleanup.close()
        choiceIntegration?.close(ChoiceInvalidationCause.SERVICE)
        choiceIntegration = null
        collector.resetGeometry()
        serviceScope.cancel()
        serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 0L // User interaction must invalidate tokens immediately.
        }
        connected = true
        touching = false
        inputMethodPackages = (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .inputMethodList.mapTo(mutableSetOf()) { it.packageName }
        val screenshots = AndroidScreenshotManager(this, preferences, ::isCurrent) { connected && !touching }
        AccessibilityRuntime.attachCaptureCapability(screenshots)
        pipeline = CaptureOcrPipeline(screenshots, KoreanMlKitOcr(), ::isCurrent, { verifyScreen(it) })
        AccessibilityRuntime.connect(::isPolicyCurrent)
        choiceIntegration = kr.closeforyou.accessibility.integration.ServiceChoiceIntegration.create(this, serviceScope,
            userInteracting = { touching || !connected }, requestObservation = ::observeAfterCleanup,
            verifyPresentation = presentationGate::awaitReady)
        choiceIntegration?.start()
        invalidate(clearBaseline = true, cause = ChoiceInvalidationCause.SERVICE)
        val application = applicationContext
        ruleCleanup.start(serviceScope) { kr.closeforyou.data.RuleStorage.cleanUp(application); Unit }
        settingsObserver = serviceScope.launch {
            preferences.state.collect {
                DetectionObservationHook.policyChanged()
                invalidate(clearBaseline = true, cause = ChoiceInvalidationCause.POLICY)
                if (!it.consentGranted) AccessibilityRuntime.block(PrivacyBlockReason.NO_CONSENT, freshness.version)
                else if (it.paused) AccessibilityRuntime.block(PrivacyBlockReason.PAUSED, freshness.version)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null) return
        // Toast/notification payloads are never read and do not start tree/OCR collection.
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            DetectionObservationHook.event(event.packageName?.toString(), event.windowId, DetectionInput.NOTIFICATION)
            return
        }
        if (event.eventType !in OBSERVED_EVENTS) return
        val integration = choiceIntegration
        val receipt = integration?.observeCleanupEvent(event)
        var processed = false
        try {
            handleObservedEvent(event)
            processed = true
        } finally {
            if (processed) integration?.cleanupEventProcessed(receipt)
            else integration?.cleanupEventFailed()
        }
    }

    private fun handleObservedEvent(event: AccessibilityEvent) {
        // A-owned new-window binding + live ownership, never an app-wide package exclusion.
        if (choiceIntegration?.shouldIgnore(event) == true) return
        // Copy only primitive enums/IDs, never keep the event or request event.source.
        val eventType = event.eventType
        DetectionObservationHook.event(event.packageName?.toString(), event.windowId, when (eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> DetectionInput.SCROLL
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> DetectionInput.CONTENT
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> DetectionInput.WINDOW
            else -> DetectionInput.INTERACTION
        })
        val epoch = invalidate(clearBaseline = false, cause = ChoiceInvalidationCause.EVENT)
        if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
            eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START) {
            touching = true
            return
        }
        if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
            eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END) {
            touching = false
            return
        }
        val settings = preferences.currentSettings()
        if (!settings.consentGranted || settings.paused) {
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
                CaptureDiagnostics.excludedEvent(eventPackage)
                detector.clear()
                AccessibilityRuntime.block(it, epoch)
                return
            }
        }
        if (touching || eventType !in SCREEN_EVENTS) return
        val trigger = if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED) PopupTrigger.CONTENT_CHANGED
            else PopupTrigger.WINDOW_CHANGED
        // All Android objects have been released before this delayed job can run.
        val eventWindowId = event.windowId
        scheduleCapture(epoch, trigger, eventWindowId)
    }

    /** No Candidate is replayed. The original removal may have had our package and been excluded
     * from detection, so explicitly arrange a fresh F0 observation after confirmed cleanup. */
    private fun observeAfterCleanup() {
        if (!connected || touching) return
        val settings = preferences.currentSettings()
        if (!settings.consentGranted || settings.paused) return
        pendingCapture?.cancel()
        scheduleCapture(freshness.version, PopupTrigger.WINDOW_CHANGED, -1)
    }

    private fun scheduleCapture(epoch: Long, trigger: PopupTrigger, eventWindowId: Int) {
        val captureDiagnostic = EvidenceBlockLog.beginWork(ServiceWorkKind.CAPTURE)
        pendingCapture = serviceScope.launchDiagnosed(captureDiagnostic) {
            captureDiagnostic?.at(ServiceWorkStage.DEBOUNCE)
            delay(DEBOUNCE_MILLIS)
            captureDiagnostic?.at(ServiceWorkStage.SUBSCRIPTION)
            val integration = choiceIntegration
            if (integration == null) {
                captureDiagnostic?.outcome(ServiceWorkEnd.INTEGRATION_UNAVAILABLE)
                return@launchDiagnosed
            }
            val cleanupPermit = integration.awaitObservationPermit()
            if (!freshness.isCurrent(epoch) || touching) {
                captureDiagnostic?.outcome(ServiceWorkEnd.CAPTURE_STALE)
                return@launchDiagnosed
            }
            val owner = currentCoroutineContext()[Job] ?: throw CancellationException("NO_COLLECTION_OWNER")
            capture(epoch, trigger, eventWindowId, captureDiagnostic, integration, cleanupPermit) {
                freshness.checkActive(epoch, owner) { connected && !touching && integration.permitsObservation(cleanupPermit) }
            }
        }
    }

    private fun capture(epoch: Long, trigger: PopupTrigger, eventWindowId: Int,
        diagnostic: ServiceWorkDiagnostic?, integration: kr.closeforyou.accessibility.integration.ServiceChoiceIntegration,
        cleanupPermit: Any, checkActive: () -> Unit) {
        val trace = CaptureDiagnostics.trace(eventWindowId, epoch)
        val observation = DetectionObservationHook.beginAttempt()
        diagnostic?.at(ServiceWorkStage.CAPTURE)
        when (val result = collector.capture(this, epoch, trigger, ::packageBlock, trace = trace, checkActive = checkActive)) {
            is CollectionResult.Blocked -> {
                diagnostic?.captureBlocked(result.reason, result.diagnostic)
                checkActive()
                detector.clear()
                DetectionObservationHook.blocked(result.targetPackage, result.windowId, result.reason, result.diagnostic, observation)
                AccessibilityRuntime.block(result.reason, epoch)
            }
            is CollectionResult.Ready -> {
                if (!freshness.isCurrent(epoch) || touching) {
                    diagnostic?.outcome(ServiceWorkEnd.CAPTURE_STALE)
                    return
                }
                diagnostic?.at(ServiceWorkStage.F1)
                val assessment = detector.assess(result.frame)
                diagnostic?.assessed(assessment)
                DetectionObservationHook.assessed(result.snapshot.token.packageName, result.snapshot.token.windowId, assessment, observation)
                if (assessment.disposition != DetectionDisposition.POPUP_CANDIDATE) {
                    // Preserve unknown transition/structure evidence in A diagnostics. Do not
                    // count it as a confirmed popup or capture merely because a screen changed.
                    trace.detail(CaptureDetail.NO_POPUP_SIGNAL)
                    diagnostic?.outcome(ServiceWorkEnd.F1_NOT_CANDIDATE)
                    return
                }
                val signals = assessment.signals
                trace.stage(CaptureStage.POPUP_DETECTED)
                // Replace the transient event with Invalidated even if a collector stalled.
                expiry = serviceScope.launch {
                    delay((result.snapshot.capturedAtUptimeMillis + SNAPSHOT_TTL_MILLIS -
                        SystemClock.uptimeMillis()).coerceAtLeast(0))
                    if (freshness.isCurrent(epoch)) invalidate(clearBaseline = false, cause = ChoiceInvalidationCause.TTL)
                }
                val evidenceDiagnostic = EvidenceBlockLog.beginWork(ServiceWorkKind.EVIDENCE)
                evidenceJob = serviceScope.launchDiagnosed(evidenceDiagnostic) {
                    evidenceDiagnostic?.at(ServiceWorkStage.BEGIN)
                    val owner = currentCoroutineContext()[Job] ?: throw CancellationException("NO_EVIDENCE_OWNER")
                    freshness.checkActive(epoch, owner) { integration.permitsObservation(cleanupPermit) && connected && !touching }
                    val operation = AccessibilityRuntime.begin(result.snapshot, owner)
                    if (operation == null) {
                        evidenceDiagnostic?.outcome(ServiceWorkEnd.BEGIN_REJECTED)
                        return@launchDiagnosed
                    }
                    val blockDiagnostic = evidenceDiagnostic?.block
                    var published = false
                    var presentationTicket: kr.closeforyou.accessibility.integration.ChoicePresentationGate.Ticket? = null
                    try {
                        val checkEvidence = {
                            freshness.checkActive(epoch, owner) { isCurrent(result.snapshot.token) && integration.permitsObservation(cleanupPermit) }
                        }
                        evidenceDiagnostic?.at(ServiceWorkStage.PIPELINE)
                        when (val evidence = pipeline.analyze(result.snapshot, trace, detectionFrame = result.frame,
                            detectionAssessment = assessment, blockDiagnostic = blockDiagnostic) {
                            verifyScreen(it, trace, checkEvidence, blockDiagnostic)
                        }) {
                            is EvidenceOutcome.Ready -> {
                                evidenceDiagnostic?.at(ServiceWorkStage.READY)
                                if (isCurrent(result.snapshot.token)) {
                                    trace.evidence(evidence.issue)
                                    checkEvidence()
                                    evidenceDiagnostic?.at(ServiceWorkStage.PUBLISH)
                                    presentationTicket = presentationGate.prepare(evidence.snapshot, cleanupPermit)
                                    published = AccessibilityRuntime.publish(operation, evidence.snapshot,
                                        PopupDetection(result.snapshot.token, signals.toPersistentSet(), requiresUserReview = true),
                                        evidence.analysis, evidence.issue)
                                    evidenceDiagnostic?.outcome(if (published) ServiceWorkEnd.PUBLISHED else ServiceWorkEnd.PUBLISH_REJECTED)
                                    if (published) trace.stage(CaptureStage.PUBLISHED)
                                } else evidenceDiagnostic?.outcome(ServiceWorkEnd.READY_STALE)
                            }
                            is EvidenceOutcome.Blocked -> {
                                blockDiagnostic?.blocked(evidence.reason, evidence.issue)
                                evidenceDiagnostic?.outcome(ServiceWorkEnd.EVIDENCE_BLOCKED)
                                if (isCurrent(result.snapshot.token)) {
                                    trace.evidence(evidence.issue)
                                    AccessibilityRuntime.recordEvidenceIssue(evidence.issue)
                                    AccessibilityRuntime.block(evidence.reason, epoch, operation)
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        published = false
                        trace.stage(CaptureStage.CANCELLED)
                        throw cancelled
                    } catch (_: Exception) {
                        evidenceDiagnostic?.exceptionHandled()
                        published = false
                        if (isCurrent(result.snapshot.token)) {
                            trace.detail(CaptureDetail.PIPELINE_FAILED)
                            trace.evidence(EvidenceIssue.PRIVACY_UNCERTAIN)
                            trace.block(PrivacyBlockReason.UNREADABLE_TREE)
                            AccessibilityRuntime.recordEvidenceIssue(EvidenceIssue.PRIVACY_UNCERTAIN)
                            AccessibilityRuntime.block(PrivacyBlockReason.UNREADABLE_TREE, epoch, operation)
                        }
                    } finally {
                        diagnosedCleanup(evidenceDiagnostic) {
                            if (!published || !isActive) presentationTicket?.let(presentationGate::abandon)
                            if (!published || !isActive) AccessibilityRuntime.abandon(operation, epoch)
                        }
                    }
                }
                diagnostic?.outcome(ServiceWorkEnd.EVIDENCE_SCHEDULED)
            }
        }
    }

    private fun isPolicyCurrent(token: SnapshotToken): Boolean = connected && !touching &&
        freshness.isCurrent(token.screenVersion) && packageBlock(token.packageName) == null

    private fun isCurrent(token: SnapshotToken): Boolean = isPolicyCurrent(token) && AccessibilityRuntime.currentToken() == token

    /** F0-A again before/after capture and after async OCR, using fresh framework nodes. */
    private fun verifyScreen(snapshot: PopupSnapshot, trace: CaptureTrace = CaptureTrace(null),
        checkActive: () -> Unit = {}, blockDiagnostic: EvidenceBlockDiagnostic? = null): PrivacyBlockReason? {
        checkActive()
        if (!isCurrent(snapshot.token)) {
            blockDiagnostic?.rejected(EvidenceBlockSource.CURRENT_SCREEN_UNAVAILABLE)
            return PrivacyBlockReason.UNREADABLE_TREE
        }
        val s = preferences.currentSettings()
        val isolation = s.captureConsentGranted && snapshot.token.packageName in s.captureAllowedPackages
        return when (val fresh = collector.capture(this, snapshot.token.captureEpoch, snapshot.trigger,
            ::packageBlock, requireCaptureIsolation = isolation, trace = trace, checkActive = checkActive)) {
            is CollectionResult.Blocked -> {
                blockDiagnostic?.rejected(EvidenceBlockSource.RECOLLECTION_FAILED, collection = fresh.diagnostic)
                fresh.reason
            }
            is CollectionResult.Ready -> {
                val mismatch = EvidenceSnapshotComparison.firstMismatch(snapshot, fresh.snapshot)
                if (mismatch != EvidenceMismatch.NONE) {
                    blockDiagnostic?.rejected(EvidenceBlockSource.SNAPSHOT_MISMATCH, mismatch)
                    trace.detail(CaptureDetail.SCREEN_CHANGED)
                    PrivacyBlockReason.UNREADABLE_TREE
                } else null
            }
        }
    }

    private fun packageBlock(targetPackage: String): PrivacyBlockReason? = preferences.currentSettings().let {
        PrivacyPolicy.packageBlock(targetPackage, packageName, it.consentGranted, it.paused,
            it.allowedPackages, inputMethodPackages)
    }

    private fun invalidate(clearBaseline: Boolean, cause: ChoiceInvalidationCause = ChoiceInvalidationCause.UNKNOWN): Long {
        presentationGate.clear()
        pendingCapture?.cancel()
        pendingCapture = null
        evidenceJob?.cancel()
        evidenceJob = null
        expiry?.cancel()
        expiry = null
        if (clearBaseline) detector.clear()
        return freshness.invalidate().also {
            choiceIntegration?.invalidate(cause)
            AccessibilityRuntime.invalidate(it)
        }
    }

    override fun onInterrupt() {
        DetectionObservationHook.policyChanged()
        invalidate(clearBaseline = true, cause = ChoiceInvalidationCause.SERVICE)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        DetectionObservationHook.policyChanged()
        collector.resetGeometry()
        invalidate(clearBaseline = true, cause = ChoiceInvalidationCause.SERVICE)
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
        ruleCleanup.close()
        DetectionObservationHook.policyChanged()
        AccessibilityRuntime.serviceLifecycle.phase(ServiceLifecyclePhase.UNBOUND)
        connected = false
        touching = false
        choiceIntegration?.close(ChoiceInvalidationCause.SERVICE)
        choiceIntegration = null
        settingsObserver?.cancel()
        invalidate(clearBaseline = true, cause = ChoiceInvalidationCause.SERVICE)
        AccessibilityRuntime.disconnect(freshness.version)
        serviceScope.cancel()
    }

    companion object {
        private const val DEBOUNCE_MILLIS = 180L
        const val SNAPSHOT_TTL_MILLIS = LiveEvidenceState.TTL_MILLIS
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
