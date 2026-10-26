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
            policyChanged = { invalidate(clearBaseline = true, permissionOrInteraction = true) },
            epoch = { freshness.version }, verifyPresentation = ::verifyForChoice,
            collectForChoice = ::collectForManualChoice, awaitInteractionEnd = { touchState.first { !it } },
            intentInteracting = { touching && !unattributedTouch }).also { it.start() }
        invalidate(clearBaseline = true)
        settingsObserver = serviceScope.launch {
            preferences.state.collect {
                invalidate(clearBaseline = true, permissionOrInteraction = true)
                if (!it.consentGranted) AccessibilityRuntime.block(PrivacyBlockReason.NO_CONSENT, freshness.version)
                else if (it.paused) AccessibilityRuntime.block(PrivacyBlockReason.PAUSED, freshness.version)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null || event.eventType !in OBSERVED_EVENTS) return
        val owner = f9
        val receipt = owner?.observeEvent(event)
        var processed = false
        try { handleAccessibilityEvent(event); processed = true }
        finally { if (processed) owner?.processed(receipt) else owner?.cleanupFailed() }
    }

    private fun handleAccessibilityEvent(event: AccessibilityEvent) {
        if (f9?.shouldIgnore(event) == true) return
        val ownedRetirement = f9?.isOwnedRetirement(event) == true
        if (f9?.hasPrompt() == true) AccessibilityRuntime.manualInvalidation(event.eventType, event.windowChanges)
        // Copy only primitive enums/IDs, never keep the event or request event.source.
        val eventType = event.eventType
        val resumeObservation = resumePendingObservationAfterFocus(pendingCapture?.isActive == true, eventType)
        val interaction = eventType !in SCREEN_EVENTS
        val preserveManual = (f9?.hasPrompt() == true || unattributedTouch) &&
            unattributedManualTouch(eventType, event.windowId, event.packageName)
        val epoch = invalidate(clearBaseline = false, permissionOrInteraction = interaction,
            ownedRetirement = ownedRetirement, preserveManual = preserveManual)
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
        if (touching) return
        if (eventType !in SCREEN_EVENTS) {
            // The focus event invalidates the epoch. Only carry an already pending screen read,
            // never an analysis or permission; standalone user focus creates no collection.
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
                detector.clear()
                AccessibilityRuntime.block(result.reason, epoch)
            }
            is CollectionResult.Ready -> {
                if (!freshness.isCurrent(epoch) || touching) return
                f9?.observePage()
                val signals = detector.detect(result.frame)
                if (signals.isEmpty()) {
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
                                    evidence.snapshot, evidence.analysis)
                                trace.stage(CaptureStage.PUBLISHED)
                            }
                            is EvidenceOutcome.Blocked -> if (isCurrent(result.snapshot.token)) {
                                trace.evidence(evidence.issue)
                                AccessibilityRuntime.recordEvidenceIssue(evidence.issue)
                                AccessibilityRuntime.block(evidence.reason, epoch)
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        trace.stage(CaptureStage.CANCELLED)
                        throw cancelled
                    } catch (_: Exception) {
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
        ttl: Boolean = false, ownedRetirement: Boolean = false, preserveManual: Boolean = false): Long {
        pendingCapture?.cancel()
        pendingCapture = null
        evidenceJob?.cancel()
        evidenceJob = null
        expiry?.cancel()
        expiry = null
        if (clearBaseline) detector.clear()
        return freshness.invalidate().also { version ->
            f9?.invalidate(version, permissionOrInteraction, ttl, ownedRetirement, preserveManual)
            AccessibilityRuntime.invalidate(version)
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

    override fun onInterrupt() { invalidate(clearBaseline = true, permissionOrInteraction = true) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        collector.resetGeometry()
        invalidate(clearBaseline = true, permissionOrInteraction = true)
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
        invalidate(clearBaseline = true)
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
