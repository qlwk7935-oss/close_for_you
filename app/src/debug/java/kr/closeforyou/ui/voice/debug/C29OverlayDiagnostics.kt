package kr.closeforyou.ui.voice.debug

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.R
import kr.closeforyou.ui.voice.overlay.ChoiceOverlayIdentity
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import kr.closeforyou.ui.voice.overlay.OwnOverlayWindowTracker
import kr.closeforyou.ui.voice.overlay.WindowFacts

/**
 * Debug-only diagnosis for team issue #29 (C's prompt disappears ~0.2 s after showing in the A→B→C run).
 *
 * While on, the test service temporarily takes A's event types and flags (flagRetrieveInteractiveWindows,
 * notificationTimeout 0, every package) and logs one line per event under [TAG]: elapsed ms, event type,
 * the event window, whether the event package is ours, and every window of getWindows() (type, layer, ours,
 * title-matches). Two modes:
 * - show: after the test service's own prompt, for [WATCH_MILLIS]; also what [OwnOverlayWindowTracker]
 *   answers when updated A's contract way (after show and on TYPE_WINDOWS_CHANGED) and when updated at
 *   that event.
 * - watch: no prompt of its own; every event for a while, to see what A's real service receives around
 *   the prompt A shows (same process, same event types).
 *
 * Numbers, enums and booleans only. Window ids are never printed: each run numbers the windows it sees
 * w1, w2, … in order of appearance (comparable within one run only). No node text, window title, package
 * name or content description is ever logged. Only turned on by adb extras in ChoicePreviewActivity; the
 * service info is restored after.
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
    private var startedAt = NOT_STARTED
    private var watching = false
    private var watchMillis = 0L

    /** Window id → run-local ordinal. Cleared every run; never printed as the raw id. */
    private val ordinals = LinkedHashMap<Int, Int>()

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
        // Elapsed times count from when addView returned; addMs shows how long the add itself took.
        newRun(SystemClock.uptimeMillis())
        watching = false
        aStyle = OwnOverlayWindowTracker(ownPackage, ownTitle)
        fresh = OwnOverlayWindowTracker(ownPackage, ownTitle)
        val windows = readWindows()
        aStyle.update(windows.map { it.facts }, overlayState())
        Log.i(TAG, "run=$run SHOW addMs=${SystemClock.uptimeMillis() - startedAt} result=$shownCode state=${stateText()} " +
            "aKnows=${bound(aStyle) != null} wins=${windowsText(windows)}")
        handler.postDelayed({ snapshot("END") }, WATCH_MILLIS)
        handler.postDelayed({
            snapshot("STILL")
            disarm()
        }, STILL_MILLIS)
    }

    /** Watch mode: arm now and log every event for [millis] without showing anything. */
    fun watch(millis: Long) {
        arm()
        newRun(SystemClock.uptimeMillis())
        watching = true
        watchMillis = millis
        Log.i(TAG, "run=$run WATCH ms=$millis wins=${windowsText(readWindows())}")
        handler.postDelayed({
            snapshot("WATCH_END")
            disarm()
        }, millis)
    }

    fun onEvent(event: AccessibilityEvent) {
        if (startedAt == NOT_STARTED) return
        val now = SystemClock.uptimeMillis()
        if (now - startedAt > if (watching) watchMillis else WATCH_MILLIS) return
        val windows = readWindows()
        val head = "run=$run th=${now - startedAt} te=${event.eventTime - startedAt} " +
            "ev=${AccessibilityEvent.eventTypeToString(event.eventType)} win=${ordinal(event.windowId)} " +
            "evOwnPkg=${event.packageName?.toString() == ownPackage} chg=${event.contentChangeTypes} wchg=${event.windowChanges}"
        if (watching) {
            // A's prompt: what the C rules would call C's window right now (overlay state assumed showing).
            val prompts = windows.filter { isPromptLike(it.facts) }
            val inPrompt = prompts.any { it.facts.id == event.windowId && event.windowId >= 0 }
            Log.i(TAG, "$head prompts=${prompts.size} evInPrompt=$inPrompt wins=${windowsText(windows)}")
            return
        }
        val facts = windows.map { it.facts }
        val state = overlayState()
        val ownA = aStyle.isOwnOverlayWindowEvent(event.windowId, state)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) aStyle.update(facts, state)
        fresh.update(facts, state)
        val ownFresh = fresh.isOwnOverlayWindowEvent(event.windowId, state)
        Log.i(
            TAG,
            "$head ownA=$ownA aAfter=${relation(bound(aStyle), event.windowId)} ownFresh=$ownFresh " +
                "freshW=${bound(fresh)?.let(::ordinal) ?: "?"} state=${stateText()} wins=${windowsText(windows)}",
        )
    }

    fun disarm() {
        startedAt = NOT_STARTED
        watching = false
        val info = original ?: return
        service.serviceInfo = copyOf(info)
        Log.i(TAG, "disarmed")
    }

    private fun newRun(at: Long) {
        run += 1
        startedAt = at
        ordinals.clear()
    }

    private fun snapshot(label: String) {
        val windows = readWindows()
        Log.i(TAG, "run=$run $label th=${SystemClock.uptimeMillis() - startedAt} state=${stateText()} wins=${windowsText(windows)}")
    }

    /** "same" / "diff" / "unknown": the tracker's window against the event's, without printing either id. */
    private fun relation(boundId: Int?, eventWindowId: Int): String = when {
        boundId == null || eventWindowId < 0 -> "unknown"
        boundId == eventWindowId -> "same"
        else -> "diff"
    }

    /**
     * The tracker keeps its id private; probe it with the same public call A makes, over every window id
     * seen in this run (so an id bound earlier that has since left getWindows() still shows).
     */
    private fun bound(tracker: OwnOverlayWindowTracker): Int? {
        val state = overlayState()
        return ordinals.keys.firstOrNull { tracker.isOwnOverlayWindowEvent(it, state) }
    }

    private fun isPromptLike(window: WindowFacts): Boolean = ChoiceOverlayIdentity.isOwnOverlayWindow(
        window, ownPackage, ownTitle, OverlayWindowState(OverlayWindowStatus.SHOWING, 1),
    )

    private fun ordinal(windowId: Int): String =
        if (windowId < 0) "none" else "w${ordinals.getOrPut(windowId) { ordinals.size + 1 }}"

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
        seen.forEach { ordinal(it.facts.id) }
        return seen
    }

    private fun rootPackage(window: AccessibilityWindowInfo): CharSequence? = try {
        window.root?.packageName
    } catch (e: RuntimeException) {
        null
    }

    /** wN:type/Llayer/own=0|1/title=match|other|null. Neither the id nor the title itself is printed. */
    private fun windowsText(windows: List<Seen>): String = windows.joinToString(prefix = "[", postfix = "]") {
        val title = when {
            it.titleNull -> "null"
            it.facts.title?.toString() == ownTitle -> "match"
            else -> "other"
        }
        "${ordinal(it.facts.id)}:${typeName(it.facts.type)}/L${it.layer}/own=${if (it.facts.rootPackageName?.toString() == ownPackage) 1 else 0}/title=$title"
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
        private const val NOT_STARTED = -1L
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
