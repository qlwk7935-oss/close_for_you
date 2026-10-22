package kr.closeforyou.servicetest

import android.app.Activity
import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.ScrollView

/** All raw values are test-private RAM. Never stringify this object or include it in assertions. */
internal data class OwnedLayout(val window: Int, val logical: IsolationRect, val overlay: IsolationRect,
    val maximum: IsolationRect, val current: IsolationRect, val decor: IsolationRect, val content: IsolationRect,
    val nodeBounds: List<IsolationRect>, val hasScroll: Boolean, val hasClipped: Boolean,
    val hasMalformed: Boolean, val protectedField: Boolean, val layoutReady: Boolean, val controlFits: Boolean) {
    override fun toString() = "OwnedLayout(redacted)"
}
internal data class IsolationGeometrySample(val own: OwnedLayout, val remoteWindow: IsolationRect,
    val remoteRoot: IsolationRect, val transition: Long, val coherent: Boolean) {
    override fun toString() = "IsolationGeometrySample(redacted)"
    fun codes(): String = listOf(
        "SOURCE=UIAUTOMATION_SEPARATE_SAMPLE", "COHERENCE=${if (coherent) "UNCHANGED" else "CHANGED"}",
        "WINDOW_VS_LOGICAL=${remoteWindow.relation(own.logical).name}", "WINDOW_OUTSIDE=${remoteWindow.outside(own.logical)}",
        "OVERLAY_VS_LOGICAL=${own.overlay.relation(own.logical).name}", "ACTIVITY_MAX_VS_LOGICAL=${own.maximum.relation(own.logical).name}",
        "ACTIVITY_CURRENT_VS_MAX=${own.current.relation(own.maximum).name}", "WINDOW_VS_ACTIVITY_MAX=${remoteWindow.relation(own.maximum).name}",
        "WINDOW_VS_REMOTE_ROOT=${remoteWindow.relation(remoteRoot).name}", "REMOTE_ROOT_VS_OWN_DECOR=${remoteRoot.relation(own.decor).name}",
        "OWN_DECOR_VS_LOGICAL=${own.decor.relation(own.logical).name}", "CONTENT_VS_LOGICAL=${own.content.relation(own.logical).name}",
        "SCROLLER=${yes(own.hasScroll)}", "CLIPPED_DESCENDANT=${yes(own.hasClipped)}", "MALFORMED_NATIVE=${yes(own.hasMalformed)}",
        "PROTECTED_FIELD=${yes(own.protectedField)}", "CONTROL_FITS=${yes(own.controlFits)}",
        "LAYOUT=${if (own.layoutReady) "READY" else "PENDING"}",
    ).joinToString(";")
    private fun yes(value: Boolean) = if (value) "YES" else "NO"
}
internal class IsolationProbeFailure(val code: String) : RuntimeException()

internal class OwnedIsolationGeometry {
    private var overlayContext: Context? = null
    @Suppress("DEPRECATION")
    fun sample(instrumentation: Instrumentation, automation: UiAutomation, activity: Activity): IsolationGeometrySample {
        val before = layout(instrumentation, activity)
        val windows = automation.windows
        try {
            val window = windows.singleOrNull { it.id == before.window }
                ?: throw IsolationProbeFailure("OWN_WINDOW_UNAVAILABLE")
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.displayId != Display.DEFAULT_DISPLAY)
                throw IsolationProbeFailure("OWN_WINDOW_NOT_PRIMARY_APPLICATION")
            val node = window.root ?: throw IsolationProbeFailure("OWN_ROOT_UNAVAILABLE")
            val root = try {
                if (node.packageName?.toString() != "kr.closeforyou.capturetest") throw IsolationProbeFailure("OWN_ROOT_PACKAGE_MISMATCH")
                Rect().also(node::getBoundsInScreen).isolated()
            } finally { node.recycle() }
            val bounds = Rect().also(window::getBoundsInScreen).isolated()
            val transition = if (Build.VERSION.SDK_INT >= 34) window.transitionTimeMillis else 0L
            val after = layout(instrumentation, activity)
            return IsolationGeometrySample(before, bounds, root, transition, before == after)
        } finally { windows.forEach { it.recycle() } }
    }
    @Suppress("DEPRECATION")
    private fun layout(instrumentation: Instrumentation, activity: Activity): OwnedLayout {
        var result: OwnedLayout? = null
        var failure: String? = null
        instrumentation.runOnMainSync {
            try {
            val decor = activity.window.decorView
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: throw IsolationProbeFailure("LOGICAL_DISPLAY_UNAVAILABLE")
            val logical = Point().also(display::getRealSize)
            val metricContext = overlayContext ?: (if (Build.VERSION.SDK_INT >= 31)
                activity.applicationContext.createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
            else activity.applicationContext.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null))
                .also { overlayContext = it } // One context per test, never one context/token per sample.
            val overlay = metricContext.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds.isolated()
            val root = decor.createAccessibilityNodeInfo()
            val id: Int
            val decorBounds: IsolationRect
            try { id = root.windowId; decorBounds = Rect().also(root::getBoundsInScreen).isolated() } finally { root.recycle() }
            val rawContent = content.createAccessibilityNodeInfo()
            val contentBounds = try { Rect().also(rawContent::getBoundsInScreen).isolated() } finally { rawContent.recycle() }
            val nodes = mutableListOf<IsolationRect>()
            var hasScroll = false; var clipped = false; var malformed = false; var protected = false
            var ready = true; var fits = true
            fun visit(view: View, underScroll: Boolean) {
                if (nodes.size >= 256) throw IsolationProbeFailure("OWN_TREE_LIMIT")
                ready = ready && view.isAttachedToWindow && view.isLaidOut && !view.isLayoutRequested
                val node = view.createAccessibilityNodeInfo()
                try {
                    val rect = Rect().also(node::getBoundsInScreen).isolated()
                    nodes += rect
                    malformed = malformed || rect.right < rect.left || rect.bottom < rect.top
                    protected = protected || node.isPassword || node.isEditable || node.inputType != 0
                    fits = fits && node.isVisibleToUser && rect.relation(contentBounds) in setOf(RectRelation.EQUAL, RectRelation.WITHIN)
                    clipped = clipped || (underScroll && !view.getGlobalVisibleRect(Rect()))
                } finally { node.recycle() }
                hasScroll = hasScroll || view is ScrollView
                if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it), underScroll || view is ScrollView) }
            }
            visit(content, false)
            result = OwnedLayout(id, IsolationRect(0, 0, logical.x, logical.y), overlay,
                activity.windowManager.maximumWindowMetrics.bounds.isolated(), activity.windowManager.currentWindowMetrics.bounds.isolated(),
                decorBounds, contentBounds, nodes.toList(), hasScroll, clipped, malformed, protected, ready, fits)
            } catch (exception: IsolationProbeFailure) { failure = exception.code }
            catch (_: Exception) { failure = "OWN_LAYOUT_READ_FAILED" }
        }
        failure?.let { throw IsolationProbeFailure(it) }
        return result ?: throw IsolationProbeFailure("OWN_LAYOUT_UNAVAILABLE")
    }
    private fun Rect.isolated() = IsolationRect(left, top, right, bottom)
}
