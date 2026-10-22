package kr.closeforyou.servicetest

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.TextView
import kr.closeforyou.capturetest.MinimalDialogActivity

/** One additional AndroidTest helper: MinimalGeometryReader deliberately forbids a Dialog.
 * All reads share its lifetime release-failure latch. No framework object is in a sample.
 */
internal class PreparedDialogReader(private val instrumentation: Instrumentation, private val automation: UiAutomation,
    val minimal: MinimalGeometryReader) {
    val safety get() = minimal.readSafety
    private data class NativeDialog(val background: Int, val target: Int, val frame: IsolationRect,
        val backgroundDecor: IsolationRect, val backgroundContent: IsolationRect,
        val decor: IsolationRect, val content: IsolationRect, val rotation: Int,
        val activityConfiguration: Configuration, val applicationConfiguration: Configuration) {
        override fun toString() = "PreparedNativeDialog(redacted)"
    }
    private data class RemoteTree(val target: Int, val window: IsolationRect, val root: IsolationRect,
        val nodes: List<IsolationRect>) {
        override fun toString() = "PreparedRemoteTree(redacted)"
    }
    internal class DialogSample private constructor(private val value: Any, val target: Int) {
        override fun equals(other: Any?) = other is DialogSample && value == other.value
        override fun hashCode() = value.hashCode()
        override fun toString() = "PreparedDialogSample(redacted)"
        companion object { fun key(value: Any, target: Int) = DialogSample(value, target) }
    }

    fun baseline(activity: MinimalDialogActivity, anchor: MinimalGeometrySample, stimulated: Boolean) {
        safety.requireClean()
        val before = minimal.sample(instrumentation, automation, activity)
        if (before != anchor || !before.geometry.coherent) fail(GeometryIssue.STABLE_GEOMETRY_CHANGED)
        val tree = remote(anchor.geometry.own.window, null, false, stimulated)
        if (tree.window != anchor.geometry.remoteWindow || tree.root != anchor.geometry.remoteRoot)
            fail(GeometryIssue.STABLE_GEOMETRY_CHANGED)
        val after = minimal.sample(instrumentation, automation, activity)
        if (after != anchor || !after.geometry.coherent) fail(GeometryIssue.STABLE_GEOMETRY_CHANGED)
    }

    /** A real fixture field change. No result, digest or accessibility action is injected. */
    fun stimulate(activity: MinimalDialogActivity) = MinimalGeometryReader.onMain(instrumentation) {
        safety.requireClean()
        if (activity.ownedDialog != null) fail(GeometryIssue.WINDOW_MISMATCH)
        val group = activity.findViewById<ViewGroup>(android.R.id.content) ?: fail(GeometryIssue.ROOT_UNAVAILABLE)
        if (group.childCount != 1) fail(GeometryIssue.MINIMAL_TREE_REQUIRED)
        val text = group.getChildAt(0) as? TextView ?: fail(GeometryIssue.MINIMAL_TREE_REQUIRED)
        if (text.text.toString() != MinimalDialogActivity.BASE_TEXT || text.isClickable || text.isLongClickable ||
            text.isTextSelectable || text.isFocusable || !text.isEnabled) fail(GeometryIssue.MINIMAL_TREE_REQUIRED)
        text.isEnabled = false // Collector V1 structuralFields includes snapshot.enabled.
        text.contentDescription = METADATA
    }

    fun dialog(activity: MinimalDialogActivity, baseline: MinimalGeometrySample): DialogSample {
        safety.requireClean()
        val before = nativeDialog(activity)
        val own = baseline.geometry.own
        if (before.background != own.window || before.target == own.window) fail(GeometryIssue.WINDOW_MISMATCH)
        if (before.rotation != baseline.configuration.rotation || before.activityConfiguration != baseline.configuration.activity ||
            before.applicationConfiguration != baseline.configuration.application) fail(GeometryIssue.CONFIGURATION_CHANGED)
        if (before.frame != own.logical || before.backgroundDecor != own.decor || before.backgroundContent != own.content)
            fail(GeometryIssue.STABLE_GEOMETRY_CHANGED)
        val first = remote(before.target, before.background, true, false)
        val second = remote(before.target, before.background, true, false)
        val after = nativeDialog(activity)
        if (before != after || first != second) fail(GeometryIssue.STABLE_GEOMETRY_CHANGED)
        if (!within(first.window, before.frame) || !within(first.root, first.window) ||
            !within(before.decor, first.window) || !within(before.content, first.window)) fail(GeometryIssue.FRAME_UNAVAILABLE)
        return DialogSample.key(before to first, before.target)
    }

    @Suppress("DEPRECATION")
    private fun nativeDialog(activity: MinimalDialogActivity): NativeDialog {
        var result: NativeDialog? = null
        MinimalGeometryReader.onMain(instrumentation) {
            result = safety.inspect { releases ->
                if (activity.isDestroyed || activity.isFinishing) fail(GeometryIssue.WINDOW_MISMATCH)
                val dialog = activity.ownedDialog?.takeIf { it.isShowing } ?: fail(GeometryIssue.LAYOUT_PENDING)
                val decor = dialog.window?.decorView ?: fail(GeometryIssue.ROOT_UNAVAILABLE)
                val content = decor.findViewById<View>(android.R.id.content) ?: fail(GeometryIssue.ROOT_UNAVAILABLE)
                val background = activity.window.decorView
                val baseContent = activity.findViewById<View>(android.R.id.content) ?: fail(GeometryIssue.ROOT_UNAVAILABLE)
                fun ready(view: View) = view.isAttachedToWindow && view.isLaidOut && !view.isLayoutRequested
                if (listOf(decor, content, background, baseContent).any { !ready(it) } ||
                    decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false) fail(GeometryIssue.LAYOUT_PENDING)
                val display = activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                    ?: fail(GeometryIssue.DISPLAY_CHANGED)
                if (!display.isValid) fail(GeometryIssue.DISPLAY_CHANGED)
                val size = Point().also(display::getRealSize)
                val rotation = display.rotation
                val ac = Configuration(activity.resources.configuration)
                val app = Configuration(activity.applicationContext.resources.configuration)
                fun native(view: View): Pair<Int, IsolationRect> {
                    val node = view.createAccessibilityNodeInfo()
                    try {
                        if (node.windowId < 0 || node.packageName?.toString() != HOST) fail(GeometryIssue.WINDOW_MISMATCH)
                        val bounds = Rect().also(node::getBoundsInScreen).isolated()
                        if (!bounds.positive) fail(GeometryIssue.FRAME_UNAVAILABLE)
                        return node.windowId to bounds
                    } finally { releases.release { node.recycle() } }
                }
                val base = native(background)
                val baseBody = native(baseContent)
                val top = native(decor)
                val body = native(content)
                if (base.first != baseBody.first || top.first != body.first || top.first == base.first)
                    fail(GeometryIssue.WINDOW_MISMATCH)
                if (!display.isValid || display.rotation != rotation || Point().also(display::getRealSize) != size ||
                    ac != activity.resources.configuration || app != activity.applicationContext.resources.configuration)
                    fail(GeometryIssue.CONFIGURATION_CHANGED)
                NativeDialog(base.first, top.first, IsolationRect(0, 0, size.x, size.y), base.second,
                    baseBody.second, top.second, body.second, rotation, ac, app)
            }
        }
        return result ?: fail(GeometryIssue.MAIN_FAILED)
    }

    @Suppress("DEPRECATION")
    private fun remote(target: Int, background: Int?, dialog: Boolean, stimulated: Boolean): RemoteTree = safety.inspect { releases ->
        val windows = automation.windows
        try {
            if (windows.isEmpty() || windows.size > 32) fail(GeometryIssue.REMOTE_UNAVAILABLE)
            if (windows.any { (it.isActive || it.isFocused) && it.id != target }) fail(GeometryIssue.WINDOW_MISMATCH)
            val own = windows.singleOrNull { it.id == target } ?: fail(GeometryIssue.REMOTE_UNAVAILABLE)
            if (own.type != AccessibilityWindowInfo.TYPE_APPLICATION || own.displayId != Display.DEFAULT_DISPLAY || !own.isActive)
                fail(GeometryIssue.WINDOW_MISMATCH)
            if (background != null) windows.singleOrNull { it.id == background }?.let { base ->
                if (base.type != AccessibilityWindowInfo.TYPE_APPLICATION || base.displayId != Display.DEFAULT_DISPLAY || own.layer <= base.layer)
                    fail(GeometryIssue.WINDOW_MISMATCH)
            }
            val root = own.root ?: fail(GeometryIssue.ROOT_UNAVAILABLE)
            try {
                PreparedNodeOwnership.read(target, { root.packageName?.toString() }, { root.windowId }) {
                    val windowBounds = Rect().also(own::getBoundsInScreen).isolated()
                    val rootBounds = Rect().also(root::getBoundsInScreen).isolated()
                    if (!windowBounds.positive || !rootBounds.positive) fail(GeometryIssue.FRAME_UNAVAILABLE)
                    var sawText = false
                    var sawStimulus = false
                    val nodes = mutableListOf<IsolationRect>()
                    fun visit(node: AccessibilityNodeInfo, depth: Int) {
                        if (nodes.size >= 128 || depth > 30) fail(GeometryIssue.TREE_LIMIT)
                        PreparedNodeOwnership.read(target, { node.packageName?.toString() }, { node.windowId }) {
                            if (node.isPassword || node.isEditable || node.inputType != 0 || node.isScrollable ||
                                node.className?.contains("scroll", true) == true || node.isClickable || node.isLongClickable)
                                fail(GeometryIssue.MINIMAL_TREE_REQUIRED)
                            val values = listOf(node.text, node.contentDescription, node.hintText, node.error,
                                node.paneTitle, node.tooltipText, node.stateDescription)
                            if (values.any { !it.isNullOrEmpty() && it.toString() !in setOf(MinimalDialogActivity.BASE_TEXT,
                                    MinimalDialogActivity.DIALOG_TEXT, METADATA) }) fail(GeometryIssue.MINIMAL_TREE_REQUIRED)
                            val expected = if (dialog) MinimalDialogActivity.DIALOG_TEXT else MinimalDialogActivity.BASE_TEXT
                            if (node.isVisibleToUser && node.text?.toString() == expected) sawText = true
                            if (!dialog && node.text?.toString() == expected && node.contentDescription?.toString() == METADATA &&
                                !node.isEnabled && node.isVisibleToUser) sawStimulus = true
                            val bounds = Rect().also(node::getBoundsInScreen).isolated()
                            if (bounds.right < bounds.left || bounds.bottom < bounds.top) fail(GeometryIssue.FRAME_UNAVAILABLE)
                            nodes += bounds
                            val count = node.childCount
                            if (count !in 0..128) fail(GeometryIssue.TREE_LIMIT)
                            repeat(count) { index ->
                                val child = node.getChild(index) ?: fail(GeometryIssue.MINIMAL_TREE_REQUIRED)
                                try { visit(child, depth + 1) } finally { releases.release { child.recycle() } }
                            }
                        }
                    }
                    visit(root, 0)
                    if (!sawText || (stimulated && !sawStimulus)) fail(GeometryIssue.MINIMAL_TREE_REQUIRED)
                    RemoteTree(target, windowBounds, rootBounds, nodes.toList())
                }
            } finally { releases.release { root.recycle() } }
        } finally { releases.releaseAll(windows) { it.recycle() } }
    }

    private fun within(inner: IsolationRect, outer: IsolationRect) = inner.positive && outer.positive &&
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom
    private fun Rect.isolated() = IsolationRect(left, top, right, bottom)
    private fun fail(issue: GeometryIssue): Nothing = throw GeometryDiagnosticFailure(issue)

    companion object {
        private const val HOST = "kr.closeforyou.capturetest"
        const val METADATA = "합성 단계 관측"
    }
}
