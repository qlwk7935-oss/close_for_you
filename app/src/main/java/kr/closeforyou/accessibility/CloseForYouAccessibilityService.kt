package kr.closeforyou.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.res.Configuration
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.model.*

/** A owns nodes, privacy preflight and candidate detection; no external action is performed. */
class CloseForYouAccessibilityService : AccessibilityService() {
    private var serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val freshness = ScreenFreshness()
    private val collector = AndroidSnapshotCollector()
    private val detector = PopupCandidateDetector()
    private var pendingCapture: Job? = null
    private var expiry: Job? = null
    private var settingsObserver: Job? = null
    private var touching = false
    private var connected = false
    private var inputMethodPackages: Set<String> = emptySet()
    private val preferences by lazy { CorePreferences.get(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
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
        AccessibilityRuntime.connect()
        invalidate(clearBaseline = true)
        settingsObserver = serviceScope.launch {
            preferences.state.collect {
                invalidate(clearBaseline = true)
                if (!it.consentGranted) AccessibilityRuntime.block(PrivacyBlockReason.NO_CONSENT, freshness.version)
                else if (it.paused) AccessibilityRuntime.block(PrivacyBlockReason.PAUSED, freshness.version)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected || event == null || event.eventType !in OBSERVED_EVENTS) return
        // Copy only primitive enums/IDs, never keep the event or request event.source.
        val eventType = event.eventType
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
        pendingCapture = serviceScope.launch {
            delay(DEBOUNCE_MILLIS)
            if (!freshness.isCurrent(epoch) || touching) return@launch
            capture(epoch, trigger)
        }
    }

    private fun capture(epoch: Long, trigger: PopupTrigger) {
        when (val result = collector.capture(this, epoch, trigger, ::packageBlock)) {
            is CollectionResult.Blocked -> {
                detector.clear()
                AccessibilityRuntime.block(result.reason, epoch)
            }
            is CollectionResult.Ready -> {
                if (!freshness.isCurrent(epoch) || touching) return
                val signals = detector.detect(result.frame)
                if (signals.isEmpty()) return
                AccessibilityRuntime.publish(result.snapshot,
                    PopupDetection(result.snapshot.token, signals.toPersistentSet(), requiresUserReview = true))
                // Replace the transient event with Invalidated even if a collector stalled.
                expiry = serviceScope.launch {
                    delay(SNAPSHOT_TTL_MILLIS)
                    if (freshness.isCurrent(epoch)) invalidate(clearBaseline = false)
                }
            }
        }
    }

    private fun packageBlock(targetPackage: String): PrivacyBlockReason? = preferences.currentSettings().let {
        PrivacyPolicy.packageBlock(targetPackage, packageName, it.consentGranted, it.paused,
            it.allowedPackages, inputMethodPackages)
    }

    private fun invalidate(clearBaseline: Boolean): Long {
        pendingCapture?.cancel()
        pendingCapture = null
        expiry?.cancel()
        expiry = null
        if (clearBaseline) detector.clear()
        return freshness.invalidate().also(AccessibilityRuntime::invalidate)
    }

    override fun onInterrupt() { invalidate(clearBaseline = true) }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        invalidate(clearBaseline = true)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnect()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }

    private fun disconnect() {
        connected = false
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
