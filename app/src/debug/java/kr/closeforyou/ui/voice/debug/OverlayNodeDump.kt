package kr.closeforyou.ui.voice.debug

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.R
import kr.closeforyou.ui.voice.overlay.ChoiceOverlayIdentity
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.WindowFacts

/**
 * Debug-only: logs the accessibility node tree of C's own prompt window under [TAG], to find which
 * node TalkBack lands on without speaking (LG TalkBack check, 2026-10-23).
 *
 * One line per node: depth, class (simple name), flags (focusable, clickable, scrollable, important,
 * visible, screen-reader-focusable), text/contentDescription/paneTitle/stateDescription as presence and
 * length only, and bounds height. Never the words themselves, never a window id. Besides C's window it
 * walks only the synthetic test app's dialog and small system windows next to the prompt (e.g. a floating
 * button). Turns on flagRetrieveInteractiveWindows only while reading, then restores the service info.
 *
 * Focus watch (PR #34 review): read-only by default. It logs focus events, and once per shown prompt it
 * dumps the nodes [FOLLOW_UP_DELAY_MILLIS] after the show. It moves TalkBack's focus onto C's first node
 * only with the explicit adb extra, and only if [FocusWatchSchedule.check] still finds the same watch,
 * service session and window generation. Every pending callback lives on this class's own Handler and is
 * cancelled at the deadline, on a new request, when the prompt goes, and on interrupt/unbind/destroy.
 * No node is kept between calls. Owned by one [ChoiceOverlayTestService]; main thread only.
 */
internal class OverlayNodeDump(
    private val service: AccessibilityService,
    /** The service's current connection number; [NO_SESSION] after unbind/destroy. */
    private val serviceSession: () -> Int,
    private val overlayState: () -> OverlayWindowState,
) {
    enum class StopReason { TIMEOUT, NEW_REQUEST, OTHER_DIAGNOSIS, INTERRUPT, UNBIND, DESTROY }

    private val handler = Handler(Looper.getMainLooper())
    private val watchToken = Any()
    private val followUpToken = Any()
    private val schedule = FocusWatchSchedule()
    private var original: AccessibilityServiceInfo? = null

    /** Read-only: logs the window list and C's prompt nodes once. */
    fun dump() {
        withInteractiveWindows { dumpWindows() }
    }

    /**
     * For [millis], logs every accessibility-focus event (TalkBack swipes): ours or not, and the node's flags.
     * [moveFocus] (explicit adb extra only) also moves TalkBack's focus once per shown prompt.
     */
    fun watchFocus(millis: Long, moveFocus: Boolean) {
        stopWatch(StopReason.NEW_REQUEST)
        val info = service.serviceInfo ?: return
        original = copyOf(info)
        info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        info.packageNames = null
        service.serviceInfo = info
        val watch = schedule.start(SystemClock.uptimeMillis(), millis, moveFocus, serviceSession())
        // Ends the watch on time even when no focus event comes after the deadline.
        handler.postAtTime({ if (schedule.watch?.id == watch.id) stopWatch(StopReason.TIMEOUT) }, watchToken, watch.deadline)
        Log.i(TAG, "focus watch ms=$millis move=$moveFocus")
    }

    /** The test service just showed C's prompt. Replaces any follow-up still waiting for an older show. */
    fun onPromptShown() {
        handler.removeCallbacksAndMessages(followUpToken)
        val followUp = schedule.onPromptShown(overlayState()) ?: return
        handler.postAtTime({ runFollowUp(followUp) }, followUpToken, SystemClock.uptimeMillis() + FOLLOW_UP_DELAY_MILLIS)
    }

    /** C's prompt was answered or is being removed: nothing scheduled for it may run. */
    fun onPromptGone() {
        handler.removeCallbacksAndMessages(followUpToken)
        schedule.followUp?.let(schedule::finish)
    }

    fun onEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED || schedule.watch == null) return
        if (schedule.isExpired(SystemClock.uptimeMillis())) {
            stopWatch(StopReason.TIMEOUT)
            return
        }
        val ours = event.packageName?.toString() == service.packageName
        val source = event.source
        if (!ours || source == null) {
            Log.i(TAG, "FOCUS ours=$ours")
            return
        }
        Log.i(TAG, "FOCUS ours=true " + describe(source, 0))
    }

    /** Cancels everything pending and restores the service info. Safe to call any number of times. */
    fun stopWatch(reason: StopReason) {
        handler.removeCallbacksAndMessages(null)
        val wasRunning = schedule.stop()
        original?.let { service.serviceInfo = copyOf(it) }
        original = null
        if (wasRunning) Log.i(TAG, "focus watch off reason=$reason")
    }

    private fun runFollowUp(followUp: FocusWatchSchedule.FollowUp) {
        val verdict = schedule.check(followUp, serviceSession(), overlayState())
        schedule.finish(followUp)
        if (verdict != FocusWatchSchedule.Verdict.RUN) {
            Log.i(TAG, "follow-up skipped reason=$verdict")
            return
        }
        withInteractiveWindows {
            dumpWindows()
            if (followUp.moveFocus) moveFocusToFirstOwnNode() else Log.i(TAG, "first focus off (read-only)")
        }
    }

    /**
     * Looks the target up fresh in the one current C window (no node kept from earlier) and moves focus there,
     * unless TalkBack's focus is already in that window (the user's focus stays where they put it).
     */
    private fun moveFocusToFirstOwnNode() {
        val state = overlayState()
        val ownTitle = service.getString(R.string.ui_choice_pane_title)
        val candidates = service.windows.orEmpty().filter {
            ChoiceOverlayIdentity.isOwnOverlayWindow(WindowFacts(it.id, it.type, it.title, rootPackage(it)), service.packageName, ownTitle, state)
        }
        val window = candidates.singleOrNull()
        if (window == null) {
            Log.i(TAG, "first focus skipped ownWindows=${candidates.size}")
            return
        }
        val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focused != null && focused.windowId == window.id) {
            Log.i(TAG, "first focus skipped alreadyInOwnWindow=true")
            return
        }
        val first = window.root?.let(::firstWithText)
        // Still in the current C window right before the action (refresh fails once the node is gone).
        val stillThere = first?.let { it.refresh() && it.windowId == window.id && it.isVisibleToUser } == true
        val done = stillThere && first?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) == true
        Log.i(TAG, "first focus found=${first != null} stillThere=$stillThere done=$done")
    }

    private fun dumpWindows() {
        val windows = service.windows.orEmpty()
        val ours = windows.filter {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && rootPackage(it)?.toString() == service.packageName
        }
        Log.i(TAG, "windows=${windows.size} ownOverlays=${ours.size}")
        // Every window in TalkBack's list: type, layer, ours, bounds; root flags only (no words).
        windows.forEach { w ->
            val b = Rect().also(w::getBoundsInScreen)
            val root = w.root
            Log.i(TAG, "win type=${w.type} layer=${w.layer} ours=${root?.packageName?.toString() == service.packageName} " +
                "title=${w.title?.length ?: -1} b=${b.left},${b.top},${b.right},${b.bottom} " +
                "rootTxt=${root?.text?.length ?: -1} rootCd=${root?.contentDescription?.length ?: -1} rootKids=${root?.childCount}")
            // Small system windows next to the prompt (e.g. a floating button): flags and lengths only.
            if (w.type == AccessibilityWindowInfo.TYPE_SYSTEM && b.height() < 400 && b.top > 200) root?.let { walk(it, 1) }
            // The synthetic test app's dialog (team test fixture, not real content): flags and lengths only.
            if (root?.packageName?.toString() == TEST_APP_PACKAGE) walk(root, 1)
        }
        ours.forEach { window -> window.root?.let { walk(it, 0) } }
    }

    private inline fun withInteractiveWindows(block: () -> Unit) {
        val info = service.serviceInfo ?: return
        val flagsBefore = info.flags
        info.flags = flagsBefore or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        service.serviceInfo = info
        try {
            block()
        } finally {
            info.flags = flagsBefore
            service.serviceInfo = info
        }
    }

    private fun rootPackage(window: AccessibilityWindowInfo): CharSequence? = try {
        window.root?.packageName
    } catch (e: RuntimeException) {
        null
    }

    private fun firstWithText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!node.text.isNullOrEmpty()) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let(::firstWithText)?.let { return it }
        return null
    }

    private fun walk(node: AccessibilityNodeInfo, depth: Int) {
        Log.i(TAG, describe(node, depth))
        for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it, depth + 1) }
    }

    private fun describe(node: AccessibilityNodeInfo, depth: Int): String {
        val bounds = Rect().also(node::getBoundsInScreen)
        return "d=$depth cls=${node.className?.toString()?.substringAfterLast('.')} " +
                "foc=${node.isFocusable} srf=${node.isScreenReaderFocusable} clk=${node.isClickable} " +
                "scr=${node.isScrollable} imp=${node.isImportantForAccessibility} vis=${node.isVisibleToUser} " +
                "txt=${node.text?.length ?: -1} cd=${node.contentDescription?.length ?: -1} " +
                "pane=${node.paneTitle?.length ?: -1} sd=${node.stateDescription?.length ?: -1} " +
                "live=${node.liveRegion} kids=${node.childCount} h=${bounds.height()} y=${bounds.top} " +
                "acts=${node.actionList.joinToString(",") { it.id.toString() }} " +
                "before=${node.traversalBefore != null} after=${node.traversalAfter != null}"
    }

    private fun copyOf(info: AccessibilityServiceInfo) = AccessibilityServiceInfo().also {
        it.eventTypes = info.eventTypes
        it.feedbackType = info.feedbackType
        it.flags = info.flags
        it.notificationTimeout = info.notificationTimeout
        it.packageNames = info.packageNames
    }

    companion object {
        const val NO_SESSION = FocusWatchSchedule.NO_SESSION
        private const val TAG = "C34Nodes"
        private const val TEST_APP_PACKAGE = "kr.closeforyou.popuptest"

        /** After the prompt shows (5 s after the scenario extra): same 9 s total as the first version. */
        private const val FOLLOW_UP_DELAY_MILLIS = 4_000L
    }
}
