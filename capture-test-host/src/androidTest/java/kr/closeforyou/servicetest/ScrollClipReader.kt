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
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CancellationException
import kr.closeforyou.capturetest.DetectionScenarioActivity
import kr.closeforyou.servicetest.ScrollClipEnvironmentField.*

internal data class ScrollClipEnvironment(val window: Int, val rotation: Int, val configuration: Configuration,
    val applicationConfiguration: Configuration, val logical: IsolationRect, val maximum: IsolationRect,
    val current: IsolationRect, val decor: IsolationRect, val viewport: IsolationRect) {
    override fun toString() = "ScrollClipEnvironment(redacted)"
}
internal data class ScrollClipNative(val environment: ScrollClipEnvironment, val path: List<Int>,
    val layout: IsolationRect, val bounds: IsolationRect, val visible: Boolean, val scrollX: Int, val scrollY: Int) {
    override fun toString() = "ScrollClipNative(redacted)"
}
internal data class ScrollClipSample(val native: ScrollClipNative, val remoteBounds: IsolationRect, val remoteVisible: Boolean,
    val remotePath: List<Int>, val windowBounds: IsolationRect, val rootBounds: IsolationRect) {
    override fun toString() = "ScrollClipSample(redacted)"
    fun codes(phase: ScrollClipPhase, source: ScrollClipSource): String {
        val own = source == ScrollClipSource.HOST_NATIVE
        return ScrollClipContract.codes(phase, source, ScrollClipContract.position(native.environment.viewport, native.layout),
            ScrollClipContract.shape(if (own) native.bounds else remoteBounds), if (own) native.visible else remoteVisible)
    }
}

/** Own View references only for this Activity lifetime. Native node leases last one sample.
 * Remote matching uses actual AccessibilityNodeInfo equality (source/window identity) AND
 * getChild preorder/parent/scroll ancestry, never matching text or assuming View insertion order.
 */
internal class ScrollClipReader(private val instrumentation: Instrumentation, private val automation: UiAutomation,
    private val activity: DetectionScenarioActivity) {
    val safety = GeometryReadSafety()
    private val diagnostics = ScrollClipReadDiagnostics()
    private var scroller: ScrollView? = null
    private var rows: LinearLayout? = null
    private var target: TextView? = null
    private var fixedPath: List<Int>? = null
    private var environment: ScrollClipEnvironment? = null
    private var remotePath: List<Int>? = null
    private var fixedWindow: IsolationRect? = null
    private var fixedRoot: IsolationRect? = null

    fun bind() = main {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        val found = mutableListOf<ScrollView>()
        var count = 0
        fun visit(view: View, depth: Int) {
            if (++count > 256 || depth > 60) bad(ScrollClipIssue.FIXTURE_INVALID)
            if (view is ScrollView) found += view
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it), depth + 1) }
        }
        visit(content, 0)
        val scroll = found.singleOrNull() ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        val group = scroll.getChildAt(0) as? LinearLayout ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        if (scroll.childCount != 1 || group.childCount != 35) bad(ScrollClipIssue.FIXTURE_INVALID)
        val child = group.getChildAt(33) as? TextView ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        if (!scroll.isLaidOut || scroll.isLayoutRequested || scroll.height <= child.height || child.height <= 0)
            throw GeometryDiagnosticFailure(GeometryIssue.LAYOUT_PENDING)
        scroller = scroll; rows = group; target = child
        fixedPath = path(child)
    }

    fun move(phase: ScrollClipPhase) = main {
        safety.requireClean()
        val scroll = scroller ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        val child = target ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        requireViewIdentity()
        val destination = if (phase == ScrollClipPhase.INSIDE)
            child.top - (scroll.height - scroll.paddingTop - scroll.paddingBottom - child.height) / 2 else 0
        if (destination < 0) bad(ScrollClipIssue.POSITION_UNCONFIRMED)
        scroll.scrollTo(0, destination) // Owned host action; never accessibility scroll/click/gesture/event injection.
    }

    fun sample(phase: ScrollClipPhase, stage: ScrollClipReadStage): ScrollClipSample = diagnostics.at(stage) {
        readSample(phase)
    }

    @Suppress("DEPRECATION")
    private fun readSample(phase: ScrollClipPhase): ScrollClipSample = safety.inspect { releases ->
        val leases = mutableListOf<AccessibilityNodeInfo>()
        try {
            lateinit var targetNode: AccessibilityNodeInfo
            lateinit var scrollNode: AccessibilityNodeInfo
            lateinit var groupNode: AccessibilityNodeInfo
            val before = main {
                val data = native(leases)
                targetNode = leases[1]; scrollNode = leases[2]; groupNode = leases[3]
                data
            }
            val expected = before.environment.window
            val windows = automation.windows
            try {
                val window = windows.singleOrNull { it.id == expected } ?: bad(ScrollClipIssue.OWNERSHIP_REJECTED)
                if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION || window.displayId != Display.DEFAULT_DISPLAY ||
                    !window.isActive || windows.any { it.id != expected && (it.isActive || it.isFocused) })
                    bad(ScrollClipIssue.OWNERSHIP_REJECTED)
                val root = window.root ?: throw GeometryDiagnosticFailure(GeometryIssue.ROOT_UNAVAILABLE)
                try {
                    var matches = 0
                    var parentMatches = false
                    var scrollMatches = false
                    var matchedBounds: IsolationRect? = null
                    var matchedVisible = false
                    var matchedPath: List<Int>? = null
                    var visited = 0
                    fun visit(node: AccessibilityNodeInfo, parent: AccessibilityNodeInfo?, steps: List<Int>, underScroll: Boolean) {
                        if (++visited > 256 || steps.size > 60) bad(ScrollClipIssue.FIXTURE_INVALID)
                        ScrollClipContract.owned(expected, { node.packageName?.toString() }, { node.windowId }) {
                            if (node.isPassword || node.isEditable || node.inputType != 0) bad(ScrollClipIssue.FIXTURE_INVALID)
                            val isOwnScroll = node == scrollNode
                            if (isOwnScroll && !node.isScrollable) bad(ScrollClipIssue.IDENTITY_UNCONFIRMED)
                            if (node == targetNode) {
                                matches++
                                parentMatches = parent == groupNode
                                scrollMatches = underScroll
                                matchedBounds = Rect().also(node::getBoundsInScreen).isolated()
                                matchedVisible = node.isVisibleToUser
                                matchedPath = steps
                            }
                            val count = node.childCount
                            if (count !in 0..256) bad(ScrollClipIssue.FIXTURE_INVALID)
                            repeat(count) { index ->
                                val child = node.getChild(index) ?: bad(ScrollClipIssue.IDENTITY_UNCONFIRMED)
                                try { visit(child, node, steps + index, underScroll || isOwnScroll) }
                                finally { releases.release { child.recycle() } }
                            }
                        }
                    }
                    val rootBounds = ScrollClipContract.owned(expected, { root.packageName?.toString() }, { root.windowId }) {
                        Rect().also(root::getBoundsInScreen).isolated()
                    }
                    visit(root, null, emptyList(), false)
                    ScrollClipContract.requireIdentity(true, matches, parentMatches, scrollMatches)
                    val after = main { native(leases) }
                    if (before != after) bad(ScrollClipIssue.UNSTABLE)
                    val sample = ScrollClipSample(before, matchedBounds ?: bad(ScrollClipIssue.IDENTITY_UNCONFIRMED),
                        matchedVisible, matchedPath ?: bad(ScrollClipIssue.IDENTITY_UNCONFIRMED),
                        Rect().also(window::getBoundsInScreen).isolated(), rootBounds)
                    diagnostics.positive(REMOTE_WINDOW, sample.windowBounds)
                    diagnostics.positive(REMOTE_ROOT, rootBounds)
                    ScrollClipContract.requirePlacement(phase, ScrollClipContract.position(before.environment.viewport, before.layout),
                        before.visible, matchedVisible)
                    // Same field order as ScrollClipEnvironment's generated equals, followed by
                    // the original remote path/window/root comparisons. No Android getters here.
                    environment?.let { saved ->
                        val current = before.environment
                        diagnostics.same(WINDOW_ID, saved.window, current.window)
                        diagnostics.same(ROTATION, saved.rotation, current.rotation)
                        diagnostics.same(CONFIGURATION, saved.configuration, current.configuration)
                        diagnostics.same(APPLICATION_CONFIGURATION, saved.applicationConfiguration, current.applicationConfiguration)
                        diagnostics.same(LOGICAL, saved.logical, current.logical)
                        diagnostics.same(MAXIMUM, saved.maximum, current.maximum)
                        diagnostics.same(CURRENT, saved.current, current.current)
                        diagnostics.same(DECOR, saved.decor, current.decor)
                        diagnostics.same(VIEWPORT, saved.viewport, current.viewport)
                    }
                    diagnostics.same(REMOTE_PATH, remotePath, sample.remotePath)
                    diagnostics.same(REMOTE_WINDOW, fixedWindow, sample.windowBounds)
                    diagnostics.same(REMOTE_ROOT, fixedRoot, rootBounds)
                    environment = before.environment; remotePath = sample.remotePath
                    fixedWindow = sample.windowBounds; fixedRoot = rootBounds
                    sample
                } finally { releases.release { root.recycle() } }
            } finally { releases.releaseAll(windows) { it.recycle() } }
        } finally { releases.releaseAll(leases) { it.recycle() } }
    }

    @Suppress("DEPRECATION")
    private fun native(leases: MutableList<AccessibilityNodeInfo>): ScrollClipNative {
        requireViewIdentity()
        val scroll = scroller ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        val group = rows ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        val child = target ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        val decor = activity.window.decorView
        for (view in listOf(decor, scroll, group, child)) {
            if (!view.isAttachedToWindow || !view.isLaidOut || view.isLayoutRequested)
                throw GeometryDiagnosticFailure(GeometryIssue.LAYOUT_PENDING)
        }
        // The first four leases have a fixed owner-only role order; no IDs are exported.
        val root = decor.createAccessibilityNodeInfo().also(leases::add)
        if (root.packageName?.toString() != "kr.closeforyou.capturetest") bad(ScrollClipIssue.OWNERSHIP_REJECTED)
        val window = root.windowId
        val targetNode = child.createAccessibilityNodeInfo().also(leases::add)
        val scrollNode = scroll.createAccessibilityNodeInfo().also(leases::add)
        val groupNode = group.createAccessibilityNodeInfo().also(leases::add)
        for (node in listOf(root, targetNode, scrollNode, groupNode))
            ScrollClipContract.requireOwner(window, node.packageName?.toString(), node.windowId)
        val display = diagnostics.display(activity.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        diagnostics.displayValid(display.isValid)
        val size = Point().also(display::getRealSize)
        val location = IntArray(2).also(scroll::getLocationOnScreen)
        val viewport = IsolationRect(diagnostics.sum(location[0], scroll.paddingLeft, VIEWPORT), diagnostics.sum(location[1], scroll.paddingTop, VIEWPORT),
            diagnostics.sum(location[0], scroll.width - scroll.paddingRight, VIEWPORT), diagnostics.sum(location[1], scroll.height - scroll.paddingBottom, VIEWPORT))
        val env = ScrollClipEnvironment(window, display.rotation, Configuration(activity.resources.configuration),
            Configuration(activity.applicationContext.resources.configuration), IsolationRect(0, 0, size.x, size.y),
            activity.windowManager.maximumWindowMetrics.bounds.isolated(), activity.windowManager.currentWindowMetrics.bounds.isolated(),
            layoutBounds(decor, DECOR), viewport)
        diagnostics.positive(LOGICAL, env.logical)
        diagnostics.positive(MAXIMUM, env.maximum)
        diagnostics.positive(CURRENT, env.current)
        diagnostics.positive(DECOR, env.decor)
        diagnostics.positive(VIEWPORT, viewport)
        return ScrollClipNative(env, path(child), layoutBounds(child, TARGET_LAYOUT), Rect().also(targetNode::getBoundsInScreen).isolated(),
            targetNode.isVisibleToUser, scroll.scrollX, scroll.scrollY)
    }
    private fun requireViewIdentity() {
        val scroll = scroller ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        val group = rows ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        val child = target ?: bad(ScrollClipIssue.FIXTURE_INVALID)
        ScrollClipContract.requireIdentity(!activity.isFinishing && !activity.isDestroyed && group.parent === scroll &&
            child.parent === group && scroll.childCount == 1 && scroll.getChildAt(0) === group &&
            group.childCount == 35 && group.getChildAt(33) === child && fixedPath == path(child), 1, true, true)
    }
    private fun path(view: View): List<Int> {
        val result = mutableListOf<Int>()
        var current = view
        while (current !== activity.window.decorView) {
            if (result.size >= 60) bad(ScrollClipIssue.IDENTITY_UNCONFIRMED)
            val parent = current.parent as? ViewGroup ?: bad(ScrollClipIssue.IDENTITY_UNCONFIRMED)
            val index = parent.indexOfChild(current)
            if (index < 0) bad(ScrollClipIssue.IDENTITY_UNCONFIRMED)
            result += index; current = parent
        }
        return result.asReversed().toList()
    }
    private fun layoutBounds(view: View, field: ScrollClipEnvironmentField): IsolationRect {
        val location = IntArray(2).also(view::getLocationOnScreen)
        return IsolationRect(location[0], location[1], diagnostics.sum(location[0], view.width, field), diagnostics.sum(location[1], view.height, field))
    }
    private fun Rect.isolated() = IsolationRect(left, top, right, bottom)
    private fun <T : Any> main(block: () -> T): T {
        var result: T? = null
        var issue: ScrollClipIssue? = null
        var geometry: GeometryIssue? = null
        var cancelled = false
        instrumentation.runOnMainSync {
            try { result = block() }
            catch (_: CancellationException) { cancelled = true }
            catch (failure: ScrollClipFailure) { issue = failure.issue }
            catch (failure: GeometryDiagnosticFailure) { geometry = failure.issue }
            catch (_: Exception) { geometry = GeometryIssue.MAIN_FAILED }
        }
        if (cancelled) throw CancellationException("SCROLL_CLIP_CANCELLED")
        issue?.let { bad(it) }; geometry?.let { throw GeometryDiagnosticFailure(it) }
        return result ?: bad(ScrollClipIssue.FIXTURE_INVALID)
    }
    fun close() { target = null; rows = null; scroller = null; fixedPath = null; environment = null; remotePath = null; fixedWindow = null; fixedRoot = null }
    private fun bad(issue: ScrollClipIssue): Nothing = throw diagnostics.exception(issue)
}
