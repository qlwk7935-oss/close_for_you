package kr.closeforyou.simpleclose

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
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
    /** F22: an app window above the main one was read and not taken (too small, nothing to press). */
    val appWindowIgnored: Boolean = false,
    /** F25: the whole-tree walk stopped at its node or time cap ([WholeReadCap]); [nodes] are the part read. */
    val wholeAborted: Boolean = false,
    /** F27: paths where the walk stopped at [SimpleCloseEngineFeature.MAX_DEPTH] (a few at most). */
    val depthCut: List<String> = emptyList(),
    /**
     * F28 (B_DEMO_RULES.md 2-6): a visible password field (`isPassword`, size > 0, on screen) was walked. Only the
     * flag: what is typed in it is never read.
     */
    val passwordSeen: Boolean = false,
    /**
     * F28: what a browser's address bar shows, for [BrowserSensitivity] only (compared in memory, never logged or
     * kept). Looked up by its view id when the read itself did not reach the bar (a popup subtree, an app window).
     */
    val address: String? = null,
    /**
     * F30: the popup window reported (nearly) the whole screen as its bounds and only its root view showed it is a
     * card (a touch-modal Dialog): the device evidence for [WindowBoundsRules].
     */
    val boundsFromRoot: Boolean = false,
    /** F32: the windows of this read as one debug line of codes ([WindowBoundsRules.line]); logged when it changes. */
    val windowsLine: String? = null,
    /** F43: the active window was judged by the bottom sheet rule (debug `BOTTOM_SHEET_*`). */
    val bottomSheet: BottomSheetVerdict? = null,
    /** F51: the read from the end after a capped whole walk ([TailRead]); null when it did not run. */
    val tail: TailReadInfo? = null,
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

/** F28: browsers whose address bar [BrowserSensitivity] reads (Chrome and its channels, Samsung Internet, Whale, the Naver app). */
private val BROWSERS = setOf(
    "com.android.chrome", "com.chrome.beta", "com.chrome.dev", "com.chrome.canary",
    "com.sec.android.app.sbrowser", "com.sec.android.app.sbrowser.beta", "com.naver.whale", "com.nhn.android.search",
)

class SimpleTreeCollector(private val service: AccessibilityService) {
    /** [probe] false (F20: the YouTube app, whose skip button sits outside any popup): always the whole tree. */
    /**
     * [wholeIfProbeMisses] false (F21, the strict close re-check): when the probe finds no popup, no whole-tree read
     * (2 s on 11번가): the read comes back with no nodes, which means "no popup there".
     */
    /**
     * [capMillis] (F26): one deadline for every walk of this read (app windows, the popup subtree, the whole tree),
     * counted from the start of the read, probe included (LG 11/1: G마켓 1044 ms past a 600 ms walk cap, an app read
     * of 2193 ms not capped at all). The strict close re-check passes a shorter one.
     */
    fun read(
        expectedPackage: String,
        packageAllowed: (String) -> Boolean,
        probe: Boolean = true,
        wholeIfProbeMisses: Boolean = true,
        capMillis: Long = WholeReadCap.MAX_MILLIS,
        /** F27: false (the strict re-check of a popup that was not an app window): the windows above are not walked. */
        appWindows: Boolean = true,
        /** F28: false (the strict re-check): the address bar is not looked up apart (its cost stays out of the re-check). */
        lookUpAddress: Boolean = true,
    ): TreeRead? =
        SimpleCollectionGuard.read(expectedPackage, { service.rootInActiveWindow },
            { it.packageName?.toString() }, packageAllowed, collect = collect@{ root, packageName ->
                val windows = mutableListOf<AccessibilityWindowInfo>()
                val deadline = SystemClock.uptimeMillis() + capMillis
                var aborted = false
                try {
                    val screen = screenBounds()
                    windows += service.windows.orEmpty()
                    // F32: every window as a shape (its root's package compared in memory only), the active one with its root view.
                    val shapes = windows.map { shapeOf(it, packageName) }.map { if (it.id == root.windowId) it.copy(root = boundsOf(root)) else it }
                    val active = shapes.firstOrNull { it.id == root.windowId }
                    val judgement = if (active == null) ActiveJudgement(ActiveWindowReject.NOT_LISTED) else WindowBoundsRules.activeJudgement(active, shapes, screen)
                    val activeReject = judgement.reject
                    val bounded = activeReject == null
                    val activeByRoot = bounded && active != null && !WindowBoundsRules.bounded(active.window, null, screen)
                    val appRejects = mutableListOf<AppWindowReject>()
                    val line = { WindowBoundsRules.line(shapes, root.windowId, screen, activeReject, appRejects) }
                    var appWindowIgnored = false
                    if (SimpleCloseEngineFeature.FIND_APP_POPUP_WINDOWS && appWindows && !bounded) {
                        // F16: a PopupWindow or non-focusable Dialog of the same app sits above the active window.
                        // F22 (LG 11/1 06:57: a 5-node window was taken for the popup, nothing to press, the real popup in
                        // the main window never read): only a window that is a popup by itself ([AppWindowRules]).
                        for (window in appPopupWindows(windows, root.windowId, screen)) {
                            val popupRoot = window.root
                            if (popupRoot == null) {
                                appRejects += AppWindowReject.NO_ROOT
                                continue
                            }
                            try {
                                if (popupRoot.packageName?.toString() != packageName) {
                                    appRejects += AppWindowReject.OTHER_PACKAGE
                                    continue
                                }
                                // F30: a touch-modal Dialog may report the whole screen as its window; its root view is the card.
                                if (!WindowBoundsRules.bounded(boundsOf(window), boundsOf(popupRoot), screen)) {
                                    appRejects += AppWindowReject.NOT_BOUNDED
                                    continue
                                }
                                val windowByRoot = !WindowBoundsRules.bounded(boundsOf(window), null, screen)
                                val nodes = ArrayList<NodeSnapshot>(64)
                                val count = WalkCount(maxVisited = WholeReadCap.MAX_NODES, deadline = deadline)
                                walk(popupRoot, "0", 0, nodes, screen, count)
                                aborted = aborted || count.aborted
                                if (!count.aborted && AppWindowRules.isPopup(nodes, screen)) {
                                    return@collect TreeRead(packageName, window.id, nodes, screen, true, count.visited, count.skipped,
                                        TreeReadMode.APP_WINDOW, popupWindowId = window.id, depthCut = count.depthCut,
                                        passwordSeen = count.password, address = addressOf(root, packageName, nodes, lookUpAddress),
                                        boundsFromRoot = windowByRoot, windowsLine = line(), bottomSheet = judgement.sheet)
                                }
                                appRejects += if (count.aborted) AppWindowReject.CUT else AppWindowReject.NOT_POPUP
                                appWindowIgnored = true
                            } finally {
                                release(popupRoot)
                            }
                        }
                    }
                    var mode = TreeReadMode.FULL
                    var probeVisited = 0
                    var outcome = ProbeOutcome.NOT_RUN
                    if (probe && SimpleCloseEngineFeature.READ_POPUP_SUBTREE_FIRST && !bounded) {
                        // F27 (LG 11/1 올리브영 앱: CLOSE_RECHECK_MS 734 with a 250 ms read cap): the probe keeps the read's deadline too.
                        val found = PopupProbe.find(root, liveTree(deadline), screen, SimpleCloseEngineFeature.PROBE_MAX_VISITED, SimpleCloseEngineFeature.MAX_DEPTH)
                        probeVisited = found.visited
                        outcome = when (found.kind) {
                            ProbeHitKind.DIALOG -> ProbeOutcome.HIT_DIALOG
                            ProbeHitKind.DIM_PARENT -> ProbeOutcome.HIT_DIM_PARENT
                            ProbeHitKind.LAYER -> ProbeOutcome.HIT_LAYER
                            null -> if (found.capped) ProbeOutcome.MISS_CAPPED else ProbeOutcome.MISS
                        }
                        found.path?.takeIf { it != "0" }?.let { path ->
                            // A "popup" subtree that runs past the deadline is the page, not a popup: whole tree below.
                            val count = WalkCount(maxVisited = WholeReadCap.MAX_NODES, deadline = deadline)
                            // F16: a front layer brings the one page node under it, so the finder sees it is over the page.
                            val nodes = subtree(root, path, screen, count)?.let { sub ->
                                val context = found.context ?: return@let sub
                                single(root, context, screen, count)?.let { listOf(it) + sub }
                            }
                            // The subtree must give a popup root by itself; otherwise the whole tree, as before.
                            aborted = aborted || count.aborted
                            if (nodes != null && !count.aborted && PopupRootFinder.find(nodes, screen) != null) {
                                return@collect TreeRead(packageName, root.windowId, nodes, screen, false,
                                    probeVisited + count.visited, count.skipped, TreeReadMode.POPUP_SUBTREE, probeVisited, probeOutcome = outcome,
                                    appWindowIgnored = appWindowIgnored, depthCut = count.depthCut,
                                    passwordSeen = count.password, address = addressOf(root, packageName, nodes, lookUpAddress),
                                    windowsLine = line(), bottomSheet = judgement.sheet)
                            }
                        }
                        mode = TreeReadMode.FULL_AFTER_PROBE
                        if (!wholeIfProbeMisses) {
                            return@collect TreeRead(packageName, root.windowId, emptyList(), screen, false, probeVisited, 0, mode, probeVisited,
                                probeOutcome = outcome, appWindowIgnored = appWindowIgnored, wholeAborted = aborted, windowsLine = line(), bottomSheet = judgement.sheet)
                        }
                    }
                    val nodes = ArrayList<NodeSnapshot>(256)
                    // F25 (LG 11/1 G마켓: one whole read took 6.1 s): the walk stops at [WholeReadCap]; the probe above
                    // already looked at the end of the page (last child first), this walk covers its beginning.
                    val count = WalkCount(maxVisited = WholeReadCap.MAX_NODES, deadline = deadline)
                    walk(root, "0", 0, nodes, screen, count)
                    // F25 (safety): a popup cut by the cap could be missing a 결제·동의 button that must keep it asked:
                    // the popup found in the part read is read again whole, its own subtree only.
                    var complete = if (!count.aborted) nodes else WholeReadCap.complete(nodes, screen, bounded) { path ->
                        val popupCount = WalkCount()
                        subtree(root, path, screen, popupCount).also { count.visited += popupCount.visited }
                    }
                    // F51 (예스24 웹, the popup about the 2,500th of 2,600 nodes): no popup in the part read, one short read from the end.
                    var tail: TailReadInfo? = null
                    if (count.aborted && TailRead.needed(true, complete, screen, bounded)) {
                        val tailStart = SystemClock.uptimeMillis()
                        val tailDeadline = tailStart + TailRead.MAX_MILLIS
                        val walked = TailRead.walk(root, liveTail(screen), TailRead.MAX_NODES, SimpleCloseEngineFeature.MAX_DEPTH) { SystemClock.uptimeMillis() > tailDeadline }
                        var subtreeVisited = 0
                        val (assisted, tailOutcome) = TailRead.assist(complete, walked, screen, bounded) { path ->
                            val popupCount = WalkCount(maxVisited = WholeReadCap.MAX_NODES, deadline = SystemClock.uptimeMillis() + TailRead.SUBTREE_MAX_MILLIS)
                            subtree(root, path, screen, popupCount).also { subtreeVisited = popupCount.visited }
                                ?.takeIf { !popupCount.aborted && popupCount.depthCut.isEmpty() }
                                ?.also { count.password = count.password || popupCount.password }
                        }
                        count.visited += walked.visited + subtreeVisited
                        // A password field the tail saw on screen leaves the screen alone either way (F28).
                        count.password = count.password || walked.password
                        if (tailOutcome == TailOutcome.FOUND) {
                            complete = assisted
                            count.depthCut += walked.depthCut.filterNot { it in count.depthCut }
                        }
                        tail = TailReadInfo(tailOutcome, walked.nodes.size, walked.visited + subtreeVisited, SystemClock.uptimeMillis() - tailStart)
                    }
                    TreeRead(packageName, root.windowId, complete, screen, bounded, probeVisited + count.visited, count.skipped, mode, probeVisited,
                        probeOutcome = outcome, appWindowIgnored = appWindowIgnored, wholeAborted = aborted || count.aborted, depthCut = count.depthCut,
                        passwordSeen = count.password, address = addressOf(root, packageName, complete, lookUpAddress),
                        boundsFromRoot = activeByRoot, windowsLine = line(), bottomSheet = judgement.sheet, tail = tail)
                } finally {
                    windows.forEach(::release)
                }
            }, release = ::release)

    /**
     * F28: the address bar's text of a browser ([BROWSERS]): from the nodes read, else looked up by its view id (a
     * few binder calls, browsers only). Null when there is none. Memory only.
     */
    private fun addressOf(root: AccessibilityNodeInfo, packageName: String, nodes: List<NodeSnapshot>, lookUp: Boolean): String? {
        nodes.firstOrNull { n -> n.viewId != null && BrowserSensitivity.isAddressBarId(n.viewId) }?.let { return it.text }
        if (!lookUp || packageName !in BROWSERS) return null
        for (suffix in BrowserSensitivity.ADDRESS_BAR_IDS) {
            val found = try { root.findAccessibilityNodeInfosByViewId("$packageName$suffix").orEmpty() } catch (e: RuntimeException) { emptyList() }
            try {
                found.firstOrNull { it.isVisibleToUser }?.text?.toString()?.let { return it }
            } finally {
                found.forEach(::release)
            }
        }
        return null
    }

    /**
     * F28 (LG 11/1 14:29, a prompt stayed over our own app): the packages of the application windows on screen now
     * (our own prompt is an accessibility overlay, not one of them). Null when it cannot be told (no windows, or a
     * window whose root is not there): the caller then does nothing.
     */
    fun applicationPackages(): Set<String>? {
        val windows = service.windows.orEmpty()
        try {
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            if (apps.isEmpty()) return null
            val packages = mutableSetOf<String>()
            for (window in apps) {
                val root = window.root ?: return null
                try {
                    packages += root.packageName?.toString() ?: return null
                } finally {
                    release(root)
                }
            }
            return packages
        } finally {
            windows.forEach(::release)
        }
    }

    /**
     * F31 (B_DEMO_RULES.md 6-1): the visible nodes of [root]'s own subtree now (its window when it has one), and the
     * screen. Null when the popup is gone, the walk hit [capMillis], or a part was too deep: the caller does not press.
     * B_DEMO_RULES.md 6-3: a flat banner is read at all its [PopupRoot.rereadPaths], not only its close button.
     */
    fun readPopupAgain(root: PopupRoot, capMillis: Long): Pair<List<NodeSnapshot>, ScreenBounds>? {
        try {
            val screen = screenBounds()
            val nodes = ArrayList<NodeSnapshot>(64)
            val count = WalkCount(maxVisited = WholeReadCap.MAX_NODES, deadline = SystemClock.uptimeMillis() + capMillis)
            for (path in root.rereadPaths()) {
                val node = nodeAt(path, root.windowId) ?: return null
                try {
                    walk(node, path, path.count { it == '/' }, nodes, screen, count)
                } finally {
                    release(node)
                }
            }
            if (count.aborted || count.depthCut.isNotEmpty() || nodes.isEmpty()) return null
            return nodes.distinctBy { it.path } to screen
        } catch (e: RuntimeException) {
            return null
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
    fun rootStillPresent(root: PopupRoot): Boolean? = when (rootRecheck(root).miss) {
        null -> true
        RecheckMiss.ROOT_UNREADABLE -> null
        else -> false
    }

    /** F48: [rootStillPresent] with why not ([ClickRecheck.rootMiss]) and the live bounds when the node was found. */
    fun rootRecheck(root: PopupRoot): RootRecheck {
        val active = service.rootInActiveWindow ?: return RootRecheck(RecheckMiss.ROOT_UNREADABLE)
        release(active)
        val node = nodeAt(root.path, root.windowId) ?: return RootRecheck(RecheckMiss.ROOT_MISSING)
        try {
            val bounds = Rect().also(node::getBoundsInScreen)
            val live = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            val isDialog = node.className?.contains("dialog", ignoreCase = true) == true
            return RootRecheck(ClickRecheck.rootMiss(node.isVisibleToUser, live, root.bounds, root.kind == PopupRootKind.ALERT_DIALOG, isDialog), live)
        } catch (e: RuntimeException) {
            return RootRecheck(RecheckMiss.ROOT_UNREADABLE)
        } finally {
            release(node)
        }
    }

    /** The live node at [candidate]'s path still carries the same name (own text/description or its subtree's). */
    fun targetMatches(candidate: CloseCandidate, windowId: Int? = null): Boolean = targetMiss(candidate, windowId) == null

    /** F48: [targetMatches] with why not ([ClickRecheck.targetMiss]); null = it matches. */
    fun targetMiss(candidate: CloseCandidate, windowId: Int? = null): RecheckMiss? {
        val path = candidate.nodePath ?: return RecheckMiss.TARGET_MISSING
        val node = nodeAt(path, windowId) ?: return RecheckMiss.TARGET_MISSING
        try {
            // F27 (review #5): still clickable itself, so the click never climbs to a parent (an ad card's link).
            // The name is read only when the rest holds, as before (a few binder calls fewer).
            ClickRecheck.targetMiss(node.isVisibleToUser, node.isEnabled, node.isClickable, sameName = true)?.let { return it }
            return ClickRecheck.targetMiss(visible = true, enabled = true, clickable = true, sameName = liveName(node) == candidate.label)
        } catch (e: RuntimeException) {
            return RecheckMiss.TARGET_UNREADABLE
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

    /** F26: the share of the screen an event's source covers (one binder call), or null when there is none. */
    fun sourceAreaRatio(event: android.view.accessibility.AccessibilityEvent): Double? = try {
        event.source?.let { node ->
            try {
                val b = boundsOf(node)
                val screen = screenBounds()
                val screenArea = screen.width.toLong() * screen.height
                if (screenArea <= 0) null else b.width.toLong() * b.height / screenArea.toDouble()
            } finally {
                release(node)
            }
        }
    } catch (e: RuntimeException) {
        null
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

    /**
     * F53: what else is on screen around [root], with its own caps ([PopupStack.LOOK_MAX_NODES], [PopupStack.LOOK_MAX_MILLIS]):
     * the windows, and (a popup inside the page's window) the tree from its end, the popup's own subtree skipped. Null when
     * there is no tree. Nothing kept past the caller.
     */
    fun readStack(root: PopupRoot): StackLook? {
        val start = SystemClock.uptimeMillis()
        val active = service.rootInActiveWindow ?: return null
        val windows = mutableListOf<AccessibilityWindowInfo>()
        try {
            val screen = screenBounds()
            windows += service.windows.orEmpty()
            val packageName = active.packageName?.toString().orEmpty()
            val shapes = windows.map { shapeOf(it, packageName) }
            val base = root.windowId ?: active.windowId
            if (root.windowId != null) return StackLook(emptyList(), screen, shapes, base, false, 0, SystemClock.uptimeMillis() - start)
            val deadline = start + PopupStack.LOOK_MAX_MILLIS
            val walked = TailRead.walk(active, liveTail(screen, skip = root.path), PopupStack.LOOK_MAX_NODES, SimpleCloseEngineFeature.MAX_DEPTH) { SystemClock.uptimeMillis() > deadline }
            return StackLook(walked.nodes, screen, shapes, base, walked.aborted, walked.visited, SystemClock.uptimeMillis() - start)
        } catch (e: RuntimeException) {
            return null
        } finally {
            windows.forEach(::release)
            release(active)
        }
    }

    /** F50: the window [windowId] is still in the window list; null when the list could not be read. */
    fun windowListed(windowId: Int): Boolean? {
        val windows = try { service.windows.orEmpty() } catch (e: RuntimeException) { return null }
        try {
            return windows.any { it.id == windowId }
        } finally {
            windows.forEach(::release)
        }
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
    /** F16: application windows above the active one, bounded (smaller than the screen), topmost first. */
    private fun appPopupWindows(windows: List<AccessibilityWindowInfo>, activeId: Int, screen: ScreenBounds): List<AccessibilityWindowInfo> {
        val active = windows.firstOrNull { it.id == activeId } ?: return emptyList()
        // F30: the size test is made in the caller, on the smaller of the window and its root view ([WindowBoundsRules]).
        return windows.filter { other ->
            other.id != activeId && other.type == AccessibilityWindowInfo.TYPE_APPLICATION && other.layer > active.layer &&
                WindowBoundsRules.area(boundsOf(other)) > 0
        }.sortedByDescending { it.layer }
    }

    /** F32: a window as a [WindowShape]; its root's package only compared, then released. */
    private fun shapeOf(window: AccessibilityWindowInfo, packageName: String): WindowShape {
        val kind = when (window.type) {
            AccessibilityWindowInfo.TYPE_APPLICATION -> WindowKind.APPLICATION
            AccessibilityWindowInfo.TYPE_INPUT_METHOD -> WindowKind.INPUT_METHOD
            AccessibilityWindowInfo.TYPE_SYSTEM -> WindowKind.SYSTEM
            AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> WindowKind.ACCESSIBILITY_OVERLAY
            AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> WindowKind.SPLIT_DIVIDER
            else -> WindowKind.OTHER
        }
        // Application windows only: one binder call each (the others are never a popup of the app).
        if (kind != WindowKind.APPLICATION) return WindowShape(window.id, kind, window.layer, null, boundsOf(window), null)
        val root = try { window.root } catch (e: RuntimeException) { null }
            ?: return WindowShape(window.id, kind, window.layer, null, boundsOf(window), null)
        try {
            return WindowShape(window.id, kind, window.layer, root.packageName?.toString() == packageName, boundsOf(window), boundsOf(root))
        } finally {
            release(root)
        }
    }

    private fun boundsOf(window: AccessibilityWindowInfo): ScreenBounds {
        val b = Rect().also(window::getBoundsInScreen)
        return ScreenBounds(b.left, b.top, maxOf(b.right, b.left), maxOf(b.bottom, b.top))
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

    /** [maxVisited]/[deadline] (F25, whole reads only): past either, the walk stops and [aborted] is set. */
    private class WalkCount(
        var visited: Int = 0,
        var skipped: Int = 0,
        val maxVisited: Int = Int.MAX_VALUE,
        val deadline: Long = Long.MAX_VALUE,
        var aborted: Boolean = false,
        val depthCut: MutableList<String> = mutableListOf(),
        var password: Boolean = false,
    )

    /** Live nodes for [PopupProbe]; reads class, bounds, clickable and whether there is text, nothing kept. */
    private fun liveTree(deadline: Long) = object : ProbeTree<AccessibilityNodeInfo> {
        override fun info(node: AccessibilityNodeInfo) = ProbeNodeInfo(node.className?.toString(), boundsOf(node), node.isClickable, !node.text.isNullOrBlank())
        override fun childCount(node: AccessibilityNodeInfo) = node.childCount
        override fun child(node: AccessibilityNodeInfo, index: Int): AccessibilityNodeInfo? = node.getChild(index)
        override fun release(node: AccessibilityNodeInfo) = this@SimpleTreeCollector.release(node)
        override fun expired() = SystemClock.uptimeMillis() > deadline
    }

    /** F51: live nodes for [TailRead]: the same snapshot and walk rule as [walk] (visible nodes kept, off-screen subtrees skipped). */
    private fun liveTail(screen: ScreenBounds, skip: String? = null) = object : TailTree<AccessibilityNodeInfo> {
        override fun look(node: AccessibilityNodeInfo, path: String): TailLook {
            if (path == skip) return TailLook(null, descend = false, zeroSize = false)
            val visible = node.isVisibleToUser
            val box = boundsOf(node)
            val zero = box.width <= 0 || box.height <= 0
            val password = visible && node.isPassword && !zero &&
                box.left < screen.right && screen.left < box.right && box.top < screen.bottom && screen.top < box.bottom
            return TailLook(if (visible) snapshot(node, path) else null, TreeWalkRules.descend(visible, box, screen), zero, password)
        }
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
        if (depth > SimpleCloseEngineFeature.MAX_DEPTH) {
            // F27 (review #7): remembered, so a popup with a part this deep is never closed by itself.
            if (count.depthCut.size < 8) count.depthCut += path
            return
        }
        if (out.size >= SimpleCloseEngineFeature.MAX_NODES || count.aborted) return
        // F26: the clock every 4 nodes (G마켓 went 444 ms past the cap with every 16th).
        if (count.visited >= count.maxVisited || (count.visited and 3) == 0 && SystemClock.uptimeMillis() > count.deadline) {
            count.aborted = true
            return
        }
        count.visited += 1
        val visible = node.isVisibleToUser
        val bounds = Rect().also(node::getBoundsInScreen)
        val box = ScreenBounds(bounds.left, bounds.top, maxOf(bounds.right, bounds.left), maxOf(bounds.bottom, bounds.top))
        if (!TreeWalkRules.descend(visible, box, screen)) {
            count.skipped += 1
            return
        }
        if (visible) out += snapshot(node, path)
        // F28: a password field on screen: the screen is left alone ([TreeRead.passwordSeen]); the flag only, never its text.
        if (visible && node.isPassword && box.width > 0 && box.height > 0 &&
            box.left < screen.right && screen.left < box.right && box.top < screen.bottom && screen.top < box.bottom) {
            count.password = true
        }
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
            // B 갤럭시 11/3 쿠팡: "7일간 보지 않기" was a checkbox. Any checkable node reads as one, whatever view draws it.
            className = if (node.isCheckable) CloseButtonFinder.CHECKABLE_CLASS else node.className?.toString(),
            // F28: a password field's text is never copied (the screen is skipped on its flag anyway).
            text = if (node.isPassword) null else node.text?.toString(),
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

/**
 * F25 whole-read cap (LG 11/1: G마켓 6.1 s, 예스24 3.6 s for one whole read; TalkBack sluggish meanwhile, a popup
 * appearing then waited for the read). A whole read is a first-child-first walk: past [MAX_NODES] nodes or
 * [MAX_MILLIS] it stops (`READ_WHOLE_ABORTED`) with what it has. Why popups are still found on such pages:
 * - the probe runs first and walks last child first (popups are appended at the end of the page and drawn on top),
 *   skipping off-screen and, until last, zero-size subtrees: it finds end-of-page Dialogs and layers within 200 nodes;
 * - the capped whole walk covers the beginning of the page (a modal early in the DOM, a portal at the top);
 * - a page that aborted is walked whole at most every [ABORTED_RETRY_MILLIS], the probe on every read.
 * What both can miss: a modal deep in the middle of a huge page with no Dialog mark (`PROBE_MISS_CAPPED` and
 * `READ_WHOLE_ABORTED` together in the log).
 */
object WholeReadCap {
    const val MAX_NODES = 1_500
    const val MAX_MILLIS = 600L
    const val ABORTED_RETRY_MILLIS = 10_000L

    /**
     * Safety after a cut walk: a popup found in the part read may be missing a 결제·동의 button that must keep it
     * asked, so its own subtree is read again whole ([readSubtree]) and replaces the cut part. Pure; JVM tested.
     */
    fun complete(partial: List<NodeSnapshot>, screen: ScreenBounds, bounded: Boolean, readSubtree: (String) -> List<NodeSnapshot>?): List<NodeSnapshot> {
        val path = PopupRootFinder.find(partial, screen, bounded)?.path?.takeIf { it != "0" } ?: return partial
        val whole = readSubtree(path) ?: return partial
        return partial.filterNot { it.path == path || it.path.startsWith("$path/") } + whole
    }
}
