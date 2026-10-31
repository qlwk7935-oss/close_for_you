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
)

/**
 * Reads `rootInActiveWindow` into [NodeSnapshot]s with B's path form ("0/i/j"), visible nodes only
 * (`isVisibleToUser`; a zero-size node is kept out by [PopupRootFinder.isVisible] as well). The
 * walk stops at [SimpleCloseEngineFeature.MAX_NODES]; Chrome pages are large, but a popup's dialog
 * node is a child of the WebView near the top of its subtree. Nothing is logged here.
 */
class SimpleTreeCollector(private val service: AccessibilityService) {
    fun read(): TreeRead? {
        val root = service.rootInActiveWindow ?: return null
        val windows = mutableListOf<AccessibilityWindowInfo>()
        try {
            val packageName = root.packageName?.toString() ?: return null
            val screen = screenBounds()
            windows += service.windows.orEmpty()
            val nodes = ArrayList<NodeSnapshot>(256)
            walk(root, "0", 0, nodes)
            return TreeRead(packageName, root.windowId, nodes, screen, activeWindowBounded(windows, root.windowId, packageName, screen))
        } catch (e: RuntimeException) {
            // A stale node or a binder failure: this read is simply unavailable.
            return null
        } finally {
            windows.forEach(::release)
            release(root)
        }
    }

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
        val node = nodeAt(root.path) ?: return false
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
    fun targetMatches(candidate: CloseCandidate): Boolean {
        val path = candidate.nodePath ?: return false
        val node = nodeAt(path) ?: return false
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
     * Moves accessibility (TalkBack) focus to the first visible, named item of the page: inside the
     * first WebView when there is one (so the URL bar is skipped), else the first named node. A node
     * that was part of the closed popup ([exclude]: its texts and bounds, memory only) is skipped,
     * because a closed web popup can stay in the DOM with the same words (LG 10/31). Nothing is
     * clicked. Device unverified.
     */
    fun focusFirstPageItem(exclude: PopupMemory): Boolean {
        val root = service.rootInActiveWindow ?: return false
        try {
            val predicate = { node: AccessibilityNodeInfo ->
                val rect = Rect().also(node::getBoundsInScreen)
                val bounds = ScreenBounds(rect.left, rect.top, maxOf(rect.right, rect.left), maxOf(rect.bottom, rect.top))
                isNamedVisible(node) && !exclude.matches(node.text?.toString(), node.contentDescription?.toString(), bounds)
            }
            val webView = firstMatch(root, 0) { it.className?.contains("WebView", ignoreCase = true) == true }
            val target = (webView?.let { firstMatch(it, 0, predicate) ?: run { release(it); null } }
                ?: firstMatch(root, 0, predicate)) ?: return false
            try {
                return target.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
            } finally {
                release(target)
            }
        } catch (e: RuntimeException) {
            return false
        } finally {
            release(root)
        }
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

    /** Follows a path from the active root to the live node, or null when the tree changed. Caller releases. */
    fun nodeAt(path: String): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow ?: return null
        val indices = path.split('/').drop(1).map { it.toIntOrNull() ?: return null }
        var node = root
        for (index in indices) {
            val child = try { node.getChild(index) } catch (e: RuntimeException) { null }
            release(node)
            node = child ?: return null
        }
        return node
    }

    private fun walk(node: AccessibilityNodeInfo, path: String, depth: Int, out: MutableList<NodeSnapshot>) {
        if (out.size >= SimpleCloseEngineFeature.MAX_NODES || depth > SimpleCloseEngineFeature.MAX_DEPTH) return
        if (node.isVisibleToUser) {
            val bounds = Rect().also(node::getBoundsInScreen)
            val ids = node.actionList.map { it.id }
            out += NodeSnapshot(
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
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                walk(child, "$path/$index", depth + 1, out)
            } finally {
                release(child)
            }
        }
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
    }

    @Suppress("DEPRECATION")
    private fun release(window: AccessibilityWindowInfo) { window.recycle() }
}
