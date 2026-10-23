package kr.closeforyou.ui.voice.debug

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.R
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OwnOverlayWindowTracker
import kr.closeforyou.ui.voice.overlay.WindowFacts

/**
 * Debug-only diagnosis for team issue #29 (C's prompt disappears ~0.2 s after showing in the A→B→C run).
 *
 * While on, the test service temporarily takes A's event types and flags (flagRetrieveInteractiveWindows,
 * notificationTimeout 0, every package) and, for [WATCH_MILLIS] after a show, logs one line per event under
 * [TAG]: elapsed ms, event type, event.windowId, whether the event package is ours, every window of
 * getWindows() (id, type, layer, ours, title-matches) and what [OwnOverlayWindowTracker] answers when
 * updated A's way (after show and on TYPE_WINDOWS_CHANGED only) and when updated at that event.
 *
 * Numbers, enums and booleans only: no node text, window title, package name or content description is
 * ever logged. Only turned on by the adb extra in ChoicePreviewActivity; the service info is restored after.
 */
internal class C29OverlayDiagnostics(
    private val service: AccessibilityService,
    private val handler: Handler,
    private val overlayState: () -> OverlayWindowState,
) {
    // Lazy: the service is created as a field initializer, before its Context is attached.
    private val ownPackage by lazy { service.packageName }
    private val ownTitle by lazy { service.getString(R.string.ui_choice_pane_title) }

    /** Updated as the contract tells A: right after show and on TYPE_WINDOWS_CHANGED. */
    private lateinit var aStyle: OwnOverlayWindowTracker

    /** Updated with a fresh getWindows() at every event (proposal 1 in issue29-diagnosis.md). */
    private lateinit var fresh: OwnOverlayWindowTracker

    private var original: AccessibilityServiceInfo? = null
    private var run = 0
    private var shownAt = NOT_SHOWN

    /** Takes A's event types and flags so getWindows() lists every interactive window. */
    fun arm() {
        val info = service.serviceInfo ?: return
        if (original == null) original = copyOf(info)
        info.eventTypes = A_EVENT_TYPES
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        info.notificationTimeout = 0
        info.packageNames = null
        service.serviceInfo = info
        Log.i(TAG, "armed")
    }

    /** [shownCode]: PromptShowResult ordinal. Call right after AccessibilityChoiceOverlay.present returns. */
    fun onShown(startedAt: Long, shownCode: Int) {
        run += 1
        shownAt = startedAt
        aStyle = OwnOverlayWindowTracker(ownPackage, ownTitle)
        fresh = OwnOverlayWindowTracker(ownPackage, ownTitle)
        seenIds.clear()
        val windows = readWindows()
        aStyle.update(windows.map { it.facts }, overlayState())
        Log.i(TAG, "run=$run SHOW addMs=${SystemClock.uptimeMillis() - startedAt} result=$shownCode state=${stateText()} " +
            "aId=${aId()} wins=${windowsText(windows)}")
        handler.postDelayed({ snapshot("END") }, WATCH_MILLIS)
        handler.postDelayed({
            snapshot("STILL")
            disarm()
        }, STILL_MILLIS)
    }

    fun onEvent(event: AccessibilityEvent) {
        if (shownAt == NOT_SHOWN) return
        val now = SystemClock.uptimeMillis()
        if (now - shownAt > WATCH_MILLIS) return
        val windows = readWindows()
        val facts = windows.map { it.facts }
        val state = overlayState()
        val ownA = aStyle.isOwnOverlayWindowEvent(event.windowId, state)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) aStyle.update(facts, state)
        fresh.update(facts, state)
        val ownFresh = fresh.isOwnOverlayWindowEvent(event.windowId, state)
        Log.i(
            TAG,
            "run=$run th=${now - shownAt} te=${event.eventTime - shownAt} ev=${AccessibilityEvent.eventTypeToString(event.eventType)} " +
                "wid=${event.windowId} evOwnPkg=${event.packageName?.toString() == ownPackage} " +
                "chg=${event.contentChangeTypes} wchg=${event.windowChanges} " +
                "ownA=$ownA aIdAfter=${aId()} ownFresh=$ownFresh freshId=${freshId(facts, state)} " +
                "state=${stateText()} wins=${windowsText(windows)}",
        )
    }

    fun disarm() {
        shownAt = NOT_SHOWN
        val info = original ?: return
        service.serviceInfo = copyOf(info)
        Log.i(TAG, "disarmed")
    }

    private fun snapshot(label: String) {
        val windows = readWindows()
        Log.i(TAG, "run=$run $label th=${SystemClock.uptimeMillis() - shownAt} state=${stateText()} wins=${windowsText(windows)}")
    }

    private fun aId(): Int = aStyle.boundIdForDiagnosis()

    private fun freshId(facts: List<WindowFacts>, state: OverlayWindowState): Int =
        OwnOverlayWindowTracker(ownPackage, ownTitle).also { it.update(facts, state) }.boundIdForDiagnosis()

    /**
     * The tracker keeps its id private; probe it with the same public call A makes, over every window id
     * seen in this run (so an id bound earlier that has since left getWindows() still shows).
     */
    private fun OwnOverlayWindowTracker.boundIdForDiagnosis(): Int {
        val state = overlayState()
        return seenIds.firstOrNull { isOwnOverlayWindowEvent(it, state) } ?: -1
    }

    private val seenIds = linkedSetOf<Int>()

    private class Seen(val facts: WindowFacts, val layer: Int, val titleNull: Boolean)

    private fun readWindows(): List<Seen> {
        val seen = try {
            service.windows.orEmpty().map { window ->
                val title = window.title
                Seen(WindowFacts(window.id, window.type, title, rootPackage(window)), window.layer, title == null)
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "getWindows failed: ${e.javaClass.simpleName}")
            emptyList()
        }
        seen.forEach { seenIds += it.facts.id }
        return seen
    }

    private fun rootPackage(window: AccessibilityWindowInfo): CharSequence? = try {
        window.root?.packageName
    } catch (e: RuntimeException) {
        null
    }

    /** id:type/Llayer/own=0|1/title=match|other|null. The title itself is never printed. */
    private fun windowsText(windows: List<Seen>): String = windows.joinToString(prefix = "[", postfix = "]") {
        val title = when {
            it.titleNull -> "null"
            it.facts.title?.toString() == ownTitle -> "match"
            else -> "other"
        }
        "${it.facts.id}:${typeName(it.facts.type)}/L${it.layer}/own=${if (it.facts.rootPackageName?.toString() == ownPackage) 1 else 0}/title=$title"
    }

    private fun stateText(): String = overlayState().let { "${it.status}#${it.generation}" }

    private fun typeName(type: Int): String = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APP"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYS"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "DIVIDER"
        AccessibilityWindowInfo.TYPE_MAGNIFICATION_OVERLAY -> "MAGNIFIER"
        else -> "T$type"
    }

    private fun copyOf(info: AccessibilityServiceInfo) = AccessibilityServiceInfo().also {
        it.eventTypes = info.eventTypes
        it.flags = info.flags
        it.feedbackType = info.feedbackType
        it.notificationTimeout = info.notificationTimeout
        it.packageNames = info.packageNames
    }

    companion object {
        const val TAG = "C29Diag"
        private const val NOT_SHOWN = -1L
        private const val WATCH_MILLIS = 1_000L
        private const val STILL_MILLIS = 3_000L

        /** Same as A's accessibility_service_config.xml (read-only copy for the diagnosis). */
        private const val A_EVENT_TYPES = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
            AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START or
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START or
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED
    }
}
