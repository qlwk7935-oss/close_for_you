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

/**
 * Debug-only: logs the accessibility node tree of C's own prompt window under [TAG], to find which
 * node TalkBack lands on without speaking (LG TalkBack check, 2026-10-23).
 *
 * One line per node: depth, class (simple name), flags (focusable, clickable, scrollable, important,
 * visible, screen-reader-focusable), text/contentDescription/paneTitle/stateDescription as presence and
 * length only, and bounds height. Never the words themselves. Besides C's window it walks only the
 * synthetic test app's dialog and small system windows next to the prompt (e.g. a floating button).
 * Turns on flagRetrieveInteractiveWindows only while reading, then restores the service info.
 */
internal object OverlayNodeDump {
    private const val TAG = "C34Nodes"
    private const val TEST_APP_PACKAGE = "kr.closeforyou.popuptest"
    private const val FIRST_FOCUS_DELAY_MILLIS = 9_000L

    fun dump(service: AccessibilityService) {
        val info = service.serviceInfo ?: return
        val originalFlags = info.flags
        info.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        service.serviceInfo = info
        try {
            val ours = service.windows.filter {
                it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                    it.root?.packageName?.toString() == service.packageName
            }
            Log.i(TAG, "windows=${service.windows.size} ownOverlays=${ours.size}")
            // Every window in TalkBack's list: type, layer, ours, bounds; root flags only (no words).
            service.windows.forEach { w ->
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
        } finally {
            info.flags = originalFlags
            service.serviceInfo = info
        }
    }

    private var original: AccessibilityServiceInfo? = null
    private var until = 0L

    /** For [millis], logs every accessibility-focus event (TalkBack swipes): ours or not, and the node's flags. */
    fun watchFocus(service: AccessibilityService, millis: Long) {
        val info = service.serviceInfo ?: return
        if (original == null) original = AccessibilityServiceInfo().also {
            it.eventTypes = info.eventTypes
            it.feedbackType = info.feedbackType
            it.flags = info.flags
            it.notificationTimeout = info.notificationTimeout
            it.packageNames = info.packageNames
        }
        info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        info.packageNames = null
        service.serviceInfo = info
        until = SystemClock.uptimeMillis() + millis
        Log.i(TAG, "focus watch ms=$millis")
        // Debug run only: after the prompt is up (5 s show delay), put TalkBack's focus on our first
        // node once, so TalkBack's own next/previous (keyboard Alt+arrows) walks our window.
        Handler(Looper.getMainLooper()).postDelayed({ focusFirstOwnNode(service) }, FIRST_FOCUS_DELAY_MILLIS)
    }

    private fun focusFirstOwnNode(service: AccessibilityService) {
        dump(service)
        val info = service.serviceInfo ?: return
        val originalFlags = info.flags
        info.flags = originalFlags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        service.serviceInfo = info
        val root = service.windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
                it.root?.packageName?.toString() == service.packageName
        }?.root
        val first = root?.let(::firstWithText)
        Log.i(TAG, "first focus found=${first != null} done=${first?.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)}")
        info.flags = originalFlags
        service.serviceInfo = info
    }

    private fun firstWithText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (!node.text.isNullOrEmpty()) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let(::firstWithText)?.let { return it }
        return null
    }

    fun onEvent(service: AccessibilityService, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED || until == 0L) return
        if (SystemClock.uptimeMillis() > until) {
            stopWatch(service)
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

    fun stopWatch(service: AccessibilityService) {
        until = 0L
        val saved = original ?: return
        original = null
        service.serviceInfo = saved
        Log.i(TAG, "focus watch off")
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
}
