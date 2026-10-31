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
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.accessibility.capture.AndroidScreenshotManager
import kr.closeforyou.accessibility.capture.CaptureOcrPipeline
import kr.closeforyou.accessibility.capture.KoreanMlKitOcr
import kr.closeforyou.accessibility.capture.EvidenceOutcome
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.model.*

/** A owns nodes, privacy preflight and candidate detection; no external action is performed. */
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
    private var touching = false
    private var connected = false
    private var inputMethodPackages: Set<String> = emptySet()
    private val preferences by lazy { CorePreferences.get(this) }
    private val engineExclusion by lazy {
        AEngineExclusion(AndroidEnabledEngineSource(contentResolver), Dispatchers.IO) {
            collector.resetGeometry()
            invalidate(clearBaseline = true)
        }
    }

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
        engineExclusion.stop()
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
        val screenshots = AndroidScreenshotManager(this, preferences, ::isCurrent) { collectionAllowed() }
        AccessibilityRuntime.attachCaptureCapability(screenshots)
        pipeline = CaptureOcrPipeline(screenshots, KoreanMlKitOcr(), ::isCurrent, { verifyScreen(it) })
        AccessibilityRuntime.connect()
        invalidate(clearBaseline = true)
        settingsObserver = serviceScope.launch {
            preferences.state.collect {
                invalidate(clearBaseline = true)
                if (!it.consentGranted) AccessibilityRuntime.block(PrivacyBlockReason.NO_CONSENT, freshness.version)
                else if (it.paused) AccessibilityRuntime.block(PrivacyBlockReason.PAUSED, freshness.version)
            }
        }
        // Register before the first asynchronous read. Until it confirms C OFF, A stays closed.
        engineExclusion.start(serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null || event.eventType !in OBSERVED_EVENTS) return
        // Copy only primitive enums/IDs, never keep the event or request event.source.
        val eventType = event.eventType
        if (!engineExclusion.engineAvailable) {
            // Keep touch state accurate across C OFF without reading an app or scheduling work.
            if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START ||
                eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START) touching = true
            if (eventType == AccessibilityEvent.TYPE_TOUCH_INTERACTION_END ||
                eventType == AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END) touching = false
            return
        }
        val epoch = invalidate(clearBaseline = false)
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
        pendingCapture = serviceScope.launch {
            delay(DEBOUNCE_MILLIS)
            if (!collectionAllowed() || !freshness.isCurrent(epoch)) return@launch
            capture(epoch, trigger, eventWindowId)
        }
    }

    private fun capture(epoch: Long, trigger: PopupTrigger, eventWindowId: Int) {
        if (!collectionAllowed() || !freshness.isCurrent(epoch)) return
        val trace = CaptureDiagnostics.trace(eventWindowId, epoch)
        val result = collector.capture(this, epoch, trigger, ::packageBlock, trace = trace)
        if (!collectionAllowed() || !freshness.isCurrent(epoch)) return
        when (result) {
            is CollectionResult.Blocked -> {
                detector.clear()
                AccessibilityRuntime.block(result.reason, epoch)
            }
            is CollectionResult.Ready -> {
                if (!collectionAllowed() || !freshness.isCurrent(epoch)) return
                val signals = detector.detect(result.frame)
                if (signals.isEmpty()) {
                    trace.detail(CaptureDetail.NO_POPUP_SIGNAL)
                    return
                }
                trace.stage(CaptureStage.POPUP_DETECTED)
                AccessibilityRuntime.begin(result.snapshot.token)
                // Replace the transient event with Invalidated even if a collector stalled.
                expiry = serviceScope.launch {
                    delay(SNAPSHOT_TTL_MILLIS)
                    if (freshness.isCurrent(epoch)) invalidate(clearBaseline = false)
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

    private fun collectionAllowed(): Boolean = connected && !touching &&
        engineExclusion.allowsCollection(preferences.currentSettings())

    private fun isCurrent(token: SnapshotToken): Boolean = collectionAllowed() &&
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
                if (!isCurrent(snapshot.token)) return PrivacyBlockReason.UNREADABLE_TREE
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

    private fun packageBlock(targetPackage: String): PrivacyBlockReason? = preferences.currentSettings().let {
        if (!connected || !engineExclusion.engineAvailable) PrivacyBlockReason.PAUSED
        else PrivacyPolicy.packageBlock(targetPackage, packageName, it.consentGranted, it.paused,
            it.allowedPackages, inputMethodPackages)
    }

    private fun invalidate(clearBaseline: Boolean): Long {
        pendingCapture?.cancel()
        pendingCapture = null
        evidenceJob?.cancel()
        evidenceJob = null
        expiry?.cancel()
        expiry = null
        if (clearBaseline) detector.clear()
        return freshness.invalidate().also(AccessibilityRuntime::invalidate)
    }

    override fun onInterrupt() { invalidate(clearBaseline = true) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        collector.resetGeometry()
        invalidate(clearBaseline = true)
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
        engineExclusion.stop()
        touching = false
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
