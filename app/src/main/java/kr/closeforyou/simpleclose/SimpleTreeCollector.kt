package kr.closeforyou.simpleclose

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.collections.immutable.toPersistentSet
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/** One read of the active window's tree as DTOs. Framework nodes are released before it returns. */
class TreeRead(
    val packageName: String,
    val windowId: Int,
    val nodes: List<NodeSnapshot>,
    val screen: ScreenBounds,
    /** The active window is a bounded dialog window over a larger window of the same app. */
    val activeWindowBounded: Boolean,
    /** F14: live nodes fetched for this read (debug number only), and subtrees skipped as off screen. */
    val visited: Int = 0,
    val skippedSubtrees: Int = 0,
    /** F15: whether only the popup's subtree was read, and how many nodes the probe fetched (in [visited]). */
    val mode: TreeReadMode = TreeReadMode.FULL,
    val probeVisited: Int = 0,
    /** F16: the nodes are the tree of this app popup window (not the active window); its id for the live look-ups. */
    val popupWindowId: Int? = null,
    /** F20: what the probe did (why a read was full). */
    val probeOutcome: ProbeOutcome = ProbeOutcome.NOT_RUN,
)

/** F15: how a read went (debug log `READ_*`). F16: [APP_WINDOW], an app's own popup window above its main window. */
enum class TreeReadMode { FULL, POPUP_SUBTREE, FULL_AFTER_PROBE, APP_WINDOW }

/**
 * F14 (LG 11/1: one read of an 11번가 page took 1.75 s, TalkBack read the popup meanwhile): every child
 * is one binder call, and a long shopping page has most of its nodes below the screen. A subtree whose
 * root is not visible to the user **and** lies wholly outside the screen is not walked. Only visible
 * nodes were ever collected, so no node the popup analysis used before is lost, except a visible node
 * under an off-screen ancestor (a fixed-position element nested in a scrolled-away container; modal
 * libraries put dialogs at the body level). A zero-size node (a portal wrapper) is still walked.
 * Pure; JVM tested.
 */
object TreeWalkRules {
    fun descend(visibleToUser: Boolean, bounds: ScreenBounds, screen: ScreenBounds): Boolean {
        if (visibleToUser) return true
        if (bounds.width <= 0 || bounds.height <= 0) return true
        return bounds.left < screen.right && screen.left < bounds.right && bounds.top < screen.bottom && screen.top < bounds.bottom
    }
}

/**
 * Reads `rootInActiveWindow` into [NodeSnapshot]s with B's path form ("0/i/j"), visible nodes only
 * (`isVisibleToUser`; a zero-size node is kept out by [PopupRootFinder.isVisible] as well). The
 * walk stops at [SimpleCloseEngineFeature.MAX_NODES]; Chrome pages are large, but a popup's dialog
 * node is a child of the WebView near the top of its subtree. Nothing is logged here.
 */
/** What [SimpleTreeCollector.focusPageItemKeeping] focused: its kind, and a copy of the node (null = nothing took it). */
class PageFocus(val kind: FocusTargetKind, val node: AccessibilityNodeInfo?) {
    val moved: Boolean get() = node != null
}

class SimpleTreeCollector(private val service: AccessibilityService) {
    /** [probe] false (F20: the YouTube app, whose skip button sits outside any popup): always the whole tree. */
    fun read(expectedPackage: String, packageAllowed: (String) -> Boolean, probe: Boolean = true): TreeRead? =
        SimpleCollectionGuard.read(expectedPackage, { service.rootInActiveWindow },
            { it.packageName?.toString() }, packageAllowed, collect = collect@{ root, packageName ->
                val windows = mutableListOf<AccessibilityWindowInfo>()
                try {
                    val screen = screenBounds()
                    windows += service.windows.orEmpty()
                    val bounded = activeWindowBounded(windows, root.windowId, packageName, screen)
                    if (SimpleCloseEngineFeature.FIND_APP_POPUP_WINDOWS && !bounded) {
                        // F16: a PopupWindow or non-focusable Dialog of the same app sits above the active window.
                        appPopupWindow(windows, root.windowId, packageName, screen)?.let { (windowId, popupRoot) ->
                            try {
                                val nodes = ArrayList<NodeSnapshot>(64)
                                val count = WalkCount()
                                walk(popupRoot, "0", 0, nodes, screen, count)
                                if (nodes.isNotEmpty()) {
                                    return@collect TreeRead(packageName, windowId, nodes, screen, true, count.visited, count.skipped,
                                        TreeReadMode.APP_WINDOW, popupWindowId = windowId)
                                }
                            } finally {
                                release(popupRoot)
                            }
                        }
                    }
                    var mode = TreeReadMode.FULL
                    var probeVisited = 0
                    var outcome = ProbeOutcome.NOT_RUN
                    if (probe && SimpleCloseEngineFeature.READ_POPUP_SUBTREE_FIRST && !bounded) {
                        val found = PopupProbe.find(root, liveTree, screen, SimpleCloseEngineFeature.PROBE_MAX_VISITED, SimpleCloseEngineFeature.MAX_DEPTH)
                        probeVisited = found.visited
                        outcome = when (found.kind) {
                            ProbeHitKind.DIALOG -> ProbeOutcome.HIT_DIALOG
                            ProbeHitKind.DIM_PARENT -> ProbeOutcome.HIT_DIM_PARENT
                            ProbeHitKind.LAYER -> ProbeOutcome.HIT_LAYER
                            null -> if (found.capped) ProbeOutcome.MISS_CAPPED else ProbeOutcome.MISS
                        }
                        found.path?.takeIf { it != "0" }?.let { path ->
                            val count = WalkCount()
                            // F16: a front layer brings the one page node under it, so the finder sees it is over the page.
                            val nodes = subtree(root, path, screen, count)?.let { sub ->
                                val context = found.context ?: return@let sub
                                single(root, context, screen, count)?.let { listOf(it) + sub }
                            }
                            // The subtree must give a popup root by itself; otherwise the whole tree, as before.
                            if (nodes != null && PopupRootFinder.find(nodes, screen) != null) {
                                return@collect TreeRead(packageName, root.windowId, nodes, screen, false,
                                    probeVisited + count.visited, count.skipped, TreeReadMode.POPUP_SUBTREE, probeVisited, probeOutcome = outcome)
                            }
                        }
                        mode = TreeReadMode.FULL_AFTER_PROBE
                    }
                    val nodes = ArrayList<NodeSnapshot>(256)
                    val count = WalkCount()
                    walk(root, "0", 0, nodes, screen, count)
                    TreeRead(packageName, root.windowId, nodes, screen, bounded, probeVisited + count.visited, count.skipped, mode, probeVisited, probeOutcome = outcome)
                } finally {
                    windows.forEach(::release)
                }
            }, release = ::release)

    /** The package of the active window right now, or null when there is none. */
    fun foregroundPackage(): String? {
        val root = service.rootInActiveWindow ?: return null
        try {
            return root.packageName?.toString()
        } finally {
            release(root)
        }
    }

    /**
     * Whether [root] is still on screen: the live node at its path has the same bounds, is visible and
     * (for a Dialog root) still a Dialog class. A few binder calls instead of a full tree read, so the
     * click re-check and the closed check stay fast. Null when the tree is unavailable.
     */
    fun rootStillPresent(root: PopupRoot): Boolean? {
        val active = service.rootInActiveWindow ?: return null
        release(active)
        val node = nodeAt(root.path, root.windowId) ?: return false
        try {
            if (!node.isVisibleToUser) return false
            val bounds = Rect().also(node::getBoundsInScreen)
            if (ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom) != root.bounds) return false
            if (root.kind == PopupRootKind.ALERT_DIALOG && node.className?.contains("dialog", ignoreCase = true) != true) return false
            return true
        } catch (e: RuntimeException) {
            return null
        } finally {
            release(node)
        }
    }

    /** The live node at [candidate]'s path still carries the same name (own text/description or its subtree's). */
    fun targetMatches(candidate: CloseCandidate, windowId: Int? = null): Boolean {
        val path = candidate.nodePath ?: return false
        val node = nodeAt(path, windowId) ?: return false
        try {
            return node.isVisibleToUser && node.isEnabled && liveName(node) == candidate.label
        } catch (e: RuntimeException) {
            return false
        } finally {
            release(node)
        }
    }

    /** Same naming as [CloseButtonFinder.nameOf], on live nodes, with a small cap on the subtree walk. */
    private fun liveName(node: AccessibilityNodeInfo): String? {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        val parts = mutableListOf<String>()
        var visited = 0
        fun visit(n: AccessibilityNodeInfo) {
            for (i in 0 until n.childCount) {
                if (visited++ >= LIVE_NAME_MAX_NODES) return
                val child = n.getChild(i) ?: continue
                try {
                    if (!child.isClickable) {
                        val own = child.text?.toString()?.takeIf { it.isNotBlank() } ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                        if (own != null) parts += own
                    }
                    visit(child)
                } finally {
                    release(child)
                }
            }
        }
        visit(node)
        return parts.joinToString(" ").ifBlank { null }
    }

    /**
     * Moves accessibility (TalkBack) focus off the popup, by [PageFocusRules.plan]: the first target
     * in its order that accepts the focus. Page items are looked for inside the first WebView (so the
     * URL bar is skipped) and must not be part of the popup ([exclude]: its texts, bounds and area,
     * memory only); the toolbar is the first named item outside the WebView that is not part of the
     * popup either. Nothing is clicked. Main thread or worker. Returns what was focused (debug log) and
     * whether the action was accepted. Device unverified.
     */
    fun focusPageItem(exclude: PopupMemory, beforeClick: Boolean = false): Pair<FocusTargetKind, Boolean> =
        focusPageItemKeeping(exclude, beforeClick).let { it.kind to it.moved }

    /**
     * [focusPageItem] that also hands back a copy of the node that took the focus (F15: the reclaim
     * target, kept by the service for a second or two, never passed on), or null when none did.
     */
    fun focusPageItemKeeping(exclude: PopupMemory, beforeClick: Boolean = false): PageFocus {
        val root = service.rootInActiveWindow ?: return PageFocus(FocusTargetKind.NONE, null)
        val live = mutableListOf<AccessibilityNodeInfo>()
        var webView: AccessibilityNodeInfo? = null
        try {
            webView = firstMatch(root, 0, ::isWebView)
            val candidates = mutableListOf<FocusCandidate>()
            var visited = 0
            fun visit(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > SimpleCloseEngineFeature.MAX_DEPTH) return
                for (i in 0 until n.childCount) {
                    if (visited++ >= FOCUS_MAX_VISITED || live.size >= FOCUS_MAX_CANDIDATES) return
                    val child = n.getChild(i) ?: continue
                    if (isNamedVisible(child)) {
                        val bounds = boundsOf(child)
                        val leftover = exclude.matches(child.text?.toString(), child.contentDescription?.toString(), bounds)
                        candidates += FocusCandidate(bounds, leftover, child.isHeading, exclude.above(bounds))
                        live += child
                    } else {
                        try { visit(child, depth + 1) } finally { release(child) }
                    }
                }
            }
            visit(webView ?: root, 0)
            val plan = PageFocusRules.plan(candidates, webViewPresent = webView != null, beforeClick = beforeClick)
            for (pick in plan) {
                val index = pick.index
                val node = when {
                    index != null -> live[index].takeIf { it.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) }?.let(::AccessibilityNodeInfo)
                    pick.webViewRoot -> webView?.takeIf { it.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) }?.let(::AccessibilityNodeInfo)
                    else -> {
                        // Chrome's toolbar: the first named item outside the WebView, never part of the old popup.
                        val item = firstOutsideWebView(root, 0) {
                            isNamedVisible(it) && !exclude.matches(it.text?.toString(), it.contentDescription?.toString(), boundsOf(it))
                        }
                        item?.let { try { it.takeIf { n -> n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS) }?.let(::AccessibilityNodeInfo) } finally { release(it) } }
                    }
                }
                if (node != null) return PageFocus(pick.kind, node)
            }
            return PageFocus(plan.firstOrNull()?.kind ?: FocusTargetKind.NONE, null)
        } catch (e: RuntimeException) {
            return PageFocus(FocusTargetKind.NONE, null)
        } finally {
            live.forEach(::release)
            webView?.let(::release)
            release(root)
        }
    }

    /**
     * Where TalkBack's focus is: true inside [area] (the popup), false elsewhere, null when nothing
     * holds it. F13: looked up across all windows first ([AccessibilityService.findFocus]; the toolbar
     * or a page item keeps it after our window goes), then in the active window. F11: after an automatic
     * close the focus is moved only when it was in the popup.
     */
    fun accessibilityFocusInside(area: ScreenBounds): Boolean? {
        try {
            val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) ?: run {
                val root = service.rootInActiveWindow ?: return null
                try { root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY) } finally { release(root) }
            } ?: return null
            try {
                return PopupMemory.overlaps(area, boundsOf(focused))
            } finally {
                release(focused)
            }
        } catch (e: RuntimeException) {
            return null
        }
    }

    /** F15: the node an accessibility-focus event names, as [FocusReclaimRules] needs it; null when there is none. */
    fun focusedNode(event: android.view.accessibility.AccessibilityEvent): Pair<FocusedNode, AccessibilityNodeInfo>? = try {
        event.source?.let { FocusedNode(it.text?.toString(), it.contentDescription?.toString(), boundsOf(it), it.isVisibleToUser) to it }
    } catch (e: RuntimeException) {
        null
    }

    /** F15: TalkBack's focus back on [target] (a node we focused earlier). Main thread; one binder call. */
    fun refocus(target: AccessibilityNodeInfo): Boolean = try {
        target.refresh() && target.isVisibleToUser && target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    } catch (e: RuntimeException) {
        false
    }

    private fun isWebView(node: AccessibilityNodeInfo) = node.className?.contains("WebView", ignoreCase = true) == true

    private fun boundsOf(node: AccessibilityNodeInfo): ScreenBounds {
        val rect = Rect().also(node::getBoundsInScreen)
        return ScreenBounds(rect.left, rect.top, maxOf(rect.right, rect.left), maxOf(rect.bottom, rect.top))
    }

    /** [firstMatch] that never enters a WebView. */
    private fun firstOutsideWebView(node: AccessibilityNodeInfo, depth: Int, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (depth > SimpleCloseEngineFeature.MAX_DEPTH) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (isWebView(child)) {
                release(child)
                continue
            }
            if (predicate(child)) return child
            val found = try { firstOutsideWebView(child, depth + 1, predicate) } finally { release(child) }
            if (found != null) return found
        }
        return null
    }

    private fun isNamedVisible(node: AccessibilityNodeInfo): Boolean {
        if (!node.isVisibleToUser || node.className?.contains("EditText", ignoreCase = true) == true) return false
        if (node.text.isNullOrBlank() && node.contentDescription.isNullOrBlank()) return false
        val bounds = Rect().also(node::getBoundsInScreen)
        return bounds.width() > 0 && bounds.height() > 0
    }

    /** Depth-first search; the returned node is the caller's to release, every other visited node is released here. */
    private fun firstMatch(node: AccessibilityNodeInfo, depth: Int, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (depth > SimpleCloseEngineFeature.MAX_DEPTH) return null
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (predicate(child)) return child
            val found = try { firstMatch(child, depth + 1, predicate) } finally { release(child) }
            if (found != null) return found
        }
        return null
    }

    /**
     * Follows a path from the active root (or, F16, the root of window [windowId]) to the live node, or
     * null when the tree changed or the window is gone. Caller releases.
     */
    fun nodeAt(path: String, windowId: Int? = null): AccessibilityNodeInfo? {
        val root = (if (windowId == null) service.rootInActiveWindow else windowRoot(windowId)) ?: return null
        val indices = path.split('/').drop(1).map { it.toIntOrNull() ?: return null }
        var node = root
        for (index in indices) {
            val child = try { node.getChild(index) } catch (e: RuntimeException) { null }
            release(node)
            node = child ?: return null
        }
        return node
    }

    private fun windowRoot(windowId: Int): AccessibilityNodeInfo? {
        val windows = service.windows.orEmpty()
        try {
            return windows.firstOrNull { it.id == windowId }?.root
        } finally {
            windows.forEach(::release)
        }
    }

    /**
     * F16: the topmost application window of the same app above the active one, bounded (smaller than the
     * screen): a PopupWindow or a Dialog that did not take the focus. Its id and root (the caller releases).
     */
    private fun appPopupWindow(windows: List<AccessibilityWindowInfo>, activeId: Int, packageName: String, screen: ScreenBounds): Pair<Int, AccessibilityNodeInfo>? {
        val screenArea = screen.width.toLong() * screen.height
        val active = windows.firstOrNull { it.id == activeId } ?: return null
        val above = windows.filter { other ->
            val b = Rect().also(other::getBoundsInScreen)
            val a = b.width().toLong() * b.height()
            other.id != activeId && other.type == AccessibilityWindowInfo.TYPE_APPLICATION && other.layer > active.layer &&
                a > 0 && a.toDouble() / screenArea < 0.96
        }.sortedByDescending { it.layer }
        for (window in above) {
            val r = window.root ?: continue
            if (r.packageName?.toString() == packageName) return window.id to r
            release(r)
        }
        return null
    }

    /** F16: one node's snapshot at [path] (not its subtree), visible or not; null when the path is gone. */
    private fun single(root: AccessibilityNodeInfo, path: String, screen: ScreenBounds, count: WalkCount): NodeSnapshot? {
        val indices = path.split('/').drop(1).map { it.toIntOrNull() ?: return null }
        var node = root
        try {
            for (index in indices) {
                val child = node.getChild(index)
                if (node !== root) release(node)
                node = child ?: return null
            }
            count.visited += 1
            return snapshot(node, path)
        } finally {
            if (node !== root) release(node)
        }
    }

    private class WalkCount(var visited: Int = 0, var skipped: Int = 0)

    /** Live nodes for [PopupProbe]; reads class, bounds, clickable and whether there is text, nothing kept. */
    private val liveTree = object : ProbeTree<AccessibilityNodeInfo> {
        override fun info(node: AccessibilityNodeInfo) = ProbeNodeInfo(node.className?.toString(), boundsOf(node), node.isClickable, !node.text.isNullOrBlank())
        override fun childCount(node: AccessibilityNodeInfo) = node.childCount
        override fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = node.getChild(index)
        override fun release(node: AccessibilityNodeInfo) = this@SimpleTreeCollector.release(node)
    }

    /** F15: the visible nodes at [path] and below it, with their full paths; null when the path is gone. */
    private fun subtree(root: AccessibilityNodeInfo, path: String, screen: ScreenBounds, count: WalkCount): List<NodeSnapshot>? {
        val indices = path.split('/').drop(1).map { it.toIntOrNull() ?: return null }
        var node = root
        try {
            for (index in indices) {
                val child = node.getChild(index)
                if (node !== root) release(node)
                node = child ?: return null
            }
            val nodes = ArrayList<NodeSnapshot>(64)
            walk(node, path, indices.size, nodes, screen, count)
            return nodes
        } finally {
            if (node !== root) release(node)
        }
    }

    private fun walk(node: AccessibilityNodeInfo, path: String, depth: Int, out: MutableList<NodeSnapshot>, screen: ScreenBounds, count: WalkCount) {
        if (out.size >= SimpleCloseEngineFeature.MAX_NODES || depth > SimpleCloseEngineFeature.MAX_DEPTH) return
        count.visited += 1
        val visible = node.isVisibleToUser
        val bounds = Rect().also(node::getBoundsInScreen)
        val box = ScreenBounds(bounds.left, bounds.top, maxOf(bounds.right, bounds.left), maxOf(bounds.bottom, bounds.top))
        if (!TreeWalkRules.descend(visible, box, screen)) {
            count.skipped += 1
            return
        }
        if (visible) out += snapshot(node, path)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                walk(child, "$path/$index", depth + 1, out, screen, count)
            } finally {
                release(child)
            }
        }
    }

    private fun snapshot(node: AccessibilityNodeInfo, path: String): NodeSnapshot {
        val bounds = Rect().also(node::getBoundsInScreen)
        val ids = node.actionList.map { it.id }
        return NodeSnapshot(
            path = path,
            viewId = node.viewIdResourceName,
            className = node.className?.toString(),
            text = node.text?.toString(),
            contentDescription = node.contentDescription?.toString(),
            clickable = node.isClickable,
            enabled = node.isEnabled,
            bounds = ScreenBounds(bounds.left, bounds.top, maxOf(bounds.right, bounds.left), maxOf(bounds.bottom, bounds.top)),
            actions = buildSet {
                if (AccessibilityNodeInfo.ACTION_CLICK in ids) add(NodeAction.CLICK)
                if (AccessibilityNodeInfo.ACTION_DISMISS in ids) add(NodeAction.DISMISS)
            }.toPersistentSet(),
        )
    }

    private fun activeWindowBounded(windows: List<AccessibilityWindowInfo>, activeId: Int, packageName: String, screen: ScreenBounds): Boolean {
        val screenArea = screen.width.toLong() * screen.height
        if (screenArea <= 0) return false
        val active = windows.firstOrNull { it.id == activeId } ?: return false
        val activeBounds = Rect().also(active::getBoundsInScreen)
        val activeArea = activeBounds.width().toLong() * activeBounds.height()
        if (activeArea.toDouble() / screenArea >= 0.96) return false
        return windows.any { other ->
            other.id != activeId && other.type == AccessibilityWindowInfo.TYPE_APPLICATION &&
                other.root?.let { r -> val same = r.packageName?.toString() == packageName; release(r); same } == true &&
                Rect().also(other::getBoundsInScreen).let { it.width().toLong() * it.height() > activeArea }
        }
    }

    private fun screenBounds(): ScreenBounds {
        val bounds = service.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        return ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) { node.recycle() }

    private companion object {
        const val LIVE_NAME_MAX_NODES = 60
        const val FOCUS_MAX_VISITED = 400
        const val FOCUS_MAX_CANDIDATES = 40
    }

    @Suppress("DEPRECATION")
    private fun release(window: AccessibilityWindowInfo) { window.recycle() }
}
