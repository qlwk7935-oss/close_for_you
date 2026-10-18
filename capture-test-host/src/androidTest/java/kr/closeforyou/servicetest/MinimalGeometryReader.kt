package kr.closeforyou.servicetest

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ScrollView
import kotlinx.coroutines.CancellationException
import kr.closeforyou.capturetest.MinimalDialogActivity

/** Copies of configuration values only; no Context, Android node or window is retained. */
internal data class MinimalGeometryConfiguration(val rotation: Int, val activity: Configuration,
    val application: Configuration, val overlay: Configuration) {
    override fun toString() = "MinimalGeometryConfiguration(redacted)"
}
internal data class MinimalGeometrySample(val geometry: IsolationGeometrySample, val configuration: MinimalGeometryConfiguration) {
    override fun toString() = "MinimalGeometrySample(redacted)"
    fun codes(): String {
        val own = geometry.own
        return listOf("SOURCE=HOST_UIAUTOMATION_SEPARATE", "COHERENCE=${if (geometry.coherent) "UNCHANGED" else "CHANGED"}",
            "OVERLAY_VS_LOGICAL=${own.overlay.relation(own.logical).name}",
            "ACTIVITY_MAX_VS_LOGICAL=${own.maximum.relation(own.logical).name}",
            "ACTIVITY_CURRENT_VS_LOGICAL=${own.current.relation(own.logical).name}",
            "ACTIVITY_CURRENT_VS_MAX=${own.current.relation(own.maximum).name}",
            "WINDOW_VS_LOGICAL=${geometry.remoteWindow.relation(own.logical).name}",
            "OUTSIDE_EDGES=${geometry.remoteWindow.outside(own.logical)}",
            "WINDOW_VS_OVERLAY=${geometry.remoteWindow.relation(own.overlay).name}",
            "WINDOW_VS_ACTIVITY_MAX=${geometry.remoteWindow.relation(own.maximum).name}",
            "WINDOW_VS_REMOTE_ROOT=${geometry.remoteWindow.relation(geometry.remoteRoot).name}",
            "REMOTE_ROOT_VS_DECOR=${geometry.remoteRoot.relation(own.decor).name}",
            "REMOTE_ROOT_VS_CONTENT=${geometry.remoteRoot.relation(own.content).name}",
            "ATOMIC_BINDING=NONE").joinToString(";")
    }
}

/** New reader instead of changing OwnedIsolationGeometry and its existing callers.
 * Reuses their scalar geometry types, with configuration/rotation checks and isolated releases.
 */
internal class MinimalGeometryReader {
    private var overlayContext: Context? = null
    val readSafety = GeometryReadSafety()
    private data class Native(val own: OwnedLayout, val configuration: MinimalGeometryConfiguration) {
        override fun toString() = "NativeGeometry(redacted)"
    }
    private data class Remote(val bounds: IsolationRect, val root: IsolationRect, val transition: Long) {
        override fun toString() = "RemoteGeometry(redacted)"
    }

    fun sample(instrumentation: Instrumentation, automation: UiAutomation, activity: MinimalDialogActivity): MinimalGeometrySample {
        readSafety.requireClean()
        val before = native(instrumentation, activity)
        val first = remote(automation, before.own.window)
        val second = remote(automation, before.own.window)
        val after = native(instrumentation, activity)
        val coherent = before == after && first == second
        return MinimalGeometrySample(IsolationGeometrySample(before.own, first.bounds, first.root, first.transition, coherent), before.configuration)
    }

    @Suppress("DEPRECATION")
    private fun remote(automation: UiAutomation, ownedWindow: Int): Remote = readSafety.inspect { releases ->
        val windows = automation.windows
        try {
            if (windows.isEmpty() || windows.size > 32) fail(GeometryIssue.REMOTE_UNAVAILABLE)
            if (windows.any { (it.isActive || it.isFocused) && it.id != ownedWindow }) fail(GeometryIssue.WINDOW_MISMATCH)
            val own = windows.singleOrNull { it.id == ownedWindow } ?: fail(GeometryIssue.REMOTE_UNAVAILABLE)
            if (own.type != AccessibilityWindowInfo.TYPE_APPLICATION || own.displayId != Display.DEFAULT_DISPLAY || !own.isActive)
                fail(GeometryIssue.WINDOW_MISMATCH)
            val root = own.root ?: fail(GeometryIssue.ROOT_UNAVAILABLE)
            try {
                if (root.packageName?.toString() != HOST || root.windowId != ownedWindow) fail(GeometryIssue.WINDOW_MISMATCH)
                val bounds = Rect().also(own::getBoundsInScreen).isolated()
                val rootBounds = Rect().also(root::getBoundsInScreen).isolated()
                if (!bounds.positive || !rootBounds.positive) fail(GeometryIssue.FRAME_UNAVAILABLE)
                Remote(bounds, rootBounds, if (Build.VERSION.SDK_INT >= 34) own.transitionTimeMillis else 0L)
            } finally { releases.release { root.recycle() } }
        } finally { releases.releaseAll(windows) { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun native(instrumentation: Instrumentation, activity: MinimalDialogActivity): Native {
        var result: Native? = null
        onMain(instrumentation) {
            result = readSafety.inspect { releases ->
                if (activity.ownedDialog != null || activity.isFinishing || activity.isDestroyed) fail(GeometryIssue.WINDOW_MISMATCH)
                val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                    ?: fail(GeometryIssue.DISPLAY_CHANGED)
                if (!display.isValid) fail(GeometryIssue.DISPLAY_CHANGED)
                val logicalBefore = Point().also(display::getRealSize)
                val rotation = display.rotation
                val metricContext = overlayContext ?: (if (Build.VERSION.SDK_INT >= 31)
                    activity.applicationContext.createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                else activity.applicationContext.createDisplayContext(display)
                    .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)).also { overlayContext = it }
                fun config() = MinimalGeometryConfiguration(display.rotation, Configuration(activity.resources.configuration),
                    Configuration(activity.applicationContext.resources.configuration), Configuration(metricContext.resources.configuration))
                val configuration = config()
                val decor = activity.window.decorView
                val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: fail(GeometryIssue.ROOT_UNAVAILABLE)
                fun ready(view: View) = view.isAttachedToWindow && view.isLaidOut && !view.isLayoutRequested
                if (!ready(decor) || !ready(content) || decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false)
                    fail(GeometryIssue.LAYOUT_PENDING)
                val nativeRoot = decor.createAccessibilityNodeInfo()
                val window: Int
                val decorBounds: IsolationRect
                try {
                    window = nativeRoot.windowId
                    if (window < 0 || nativeRoot.packageName?.toString() != HOST) fail(GeometryIssue.WINDOW_MISMATCH)
                    decorBounds = Rect().also(nativeRoot::getBoundsInScreen).isolated()
                } finally { releases.release { nativeRoot.recycle() } }
                val contentNode = content.createAccessibilityNodeInfo()
                val contentBounds = try { Rect().also(contentNode::getBoundsInScreen).isolated() }
                    finally { releases.release { contentNode.recycle() } }
                val nodes = mutableListOf<IsolationRect>()
                var allReady = true
                var fits = true
                var scroll = false
                var malformed = false
                var protected = false
                var clipped = false
                fun visit(view: View, depth: Int, underScroll: Boolean) {
                    if (nodes.size >= 128 || depth > 30) fail(GeometryIssue.TREE_LIMIT)
                    allReady = allReady && ready(view)
                    val node = view.createAccessibilityNodeInfo()
                    try {
                        if (node.windowId != window || node.packageName?.toString() != HOST) fail(GeometryIssue.WINDOW_MISMATCH)
                        val bounds = Rect().also(node::getBoundsInScreen).isolated()
                        nodes += bounds
                        malformed = malformed || !bounds.positive
                        protected = protected || node.isPassword || node.isEditable || node.inputType != 0
                        fits = fits && node.isVisibleToUser && bounds.relation(contentBounds) in setOf(RectRelation.EQUAL, RectRelation.WITHIN)
                        scroll = scroll || view is ScrollView || node.isScrollable
                        clipped = clipped || (underScroll && !view.getGlobalVisibleRect(Rect()))
                    } finally { releases.release { node.recycle() } }
                    if (view is ViewGroup) {
                        if (view.childCount > 128) fail(GeometryIssue.TREE_LIMIT)
                        repeat(view.childCount) { visit(view.getChildAt(it), depth + 1, underScroll || view is ScrollView) }
                    }
                }
                visit(content, 0, false)
                val logical = IsolationRect(0, 0, logicalBefore.x, logicalBefore.y)
                val overlay = metricContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.isolated()
                val maximum = activity.windowManager.maximumWindowMetrics.bounds.isolated()
                val current = activity.windowManager.currentWindowMetrics.bounds.isolated()
                if (listOf(logical, overlay, maximum, current, decorBounds, contentBounds).any { !it.positive }) fail(GeometryIssue.FRAME_UNAVAILABLE)
                if (!display.isValid || display.rotation != rotation || Point().also(display::getRealSize) != logicalBefore)
                    fail(GeometryIssue.DISPLAY_CHANGED)
                if (configuration != config()) fail(GeometryIssue.CONFIGURATION_CHANGED)
                if (!allReady) fail(GeometryIssue.LAYOUT_PENDING)
                if (!fits || scroll || clipped || malformed || protected) fail(GeometryIssue.MINIMAL_TREE_REQUIRED)
                Native(OwnedLayout(window, logical, overlay, maximum, current, decorBounds, contentBounds,
                    nodes.toList(), scroll, clipped, malformed, protected, allReady, fits), configuration)
            }
        }
        return result ?: fail(GeometryIssue.MAIN_FAILED)
    }

    fun close() { overlayContext = null }
    private fun Rect.isolated() = IsolationRect(left, top, right, bottom)
    private fun fail(issue: GeometryIssue): Nothing = throw GeometryDiagnosticFailure(issue)

    companion object {
        private const val HOST = "kr.closeforyou.capturetest"
        fun onMain(instrumentation: Instrumentation, block: () -> Unit) {
            var issue = GeometryIssue.NONE
            var cancelled = false
            instrumentation.runOnMainSync {
                try { block() }
                catch (_: CancellationException) { cancelled = true }
                catch (failure: GeometryDiagnosticFailure) { issue = failure.issue }
                catch (_: PriorityReleaseException) { issue = GeometryIssue.RELEASE_FAILED }
                catch (_: Exception) { issue = GeometryIssue.MAIN_FAILED }
            }
            if (cancelled) throw CancellationException("MINIMAL_GEOMETRY_CANCELLED")
            if (issue != GeometryIssue.NONE) throw GeometryDiagnosticFailure(issue)
        }
    }
}
