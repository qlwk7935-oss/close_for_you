package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/** How the popup root was recognised. Logged as a code; never with text. */
enum class PopupRootKind {
    /** A node whose class is a Dialog (Chrome exports `role="dialog"` web popups as android.app.AlertDialog). */
    ALERT_DIALOG,
    /** The active window itself is a bounded dialog window over the same app's main window. */
    DIALOG_WINDOW,
    /** An app's own overlay: a clickable near-screen dim background with a bounded card. */
    DIM_OVERLAY,
    /** F16: a large box drawn over the page (a later sibling covering an earlier one) with a named close. */
    FRONT_LAYER,
    /** F16: a full-width box rising from the bottom edge over the page, with a named close. */
    BOTTOM_SHEET,
    /** F16: an app's own popup window (Dialog, PopupWindow) above its main window, read as its own tree. */
    APP_WINDOW,
    /**
     * F22: a centred box drawn over a screen-covering layer, closed only by a small unnamed X in its corner
     * (11번가 app "앱 전용 혜택 안내"). The X is offered to the person, never pressed by itself (6-3).
     */
    MODAL_CARD,
    /**
     * F32 (LG 11/1 16:33, 11번가 app `AdsOneDayPopupActivity`: a transparent activity that dims by its window flag, so
     * no dim node; NO_POPUP_ROOT ×24): a screen-sized window whose visible content is one small centred block, the
     * rest empty, with something to close it ([PopupRootFinder.dimWindowRoot]).
     */
    DIM_WINDOW,
}

/**
 * The popup's own subtree: every node at [path] or below it. [windowId]: the window whose tree the
 * path is in when it is not the active window (F16 [PopupRootKind.APP_WINDOW]); null = the active window.
 */
data class PopupRoot(val path: String, val bounds: ScreenBounds, val kind: PopupRootKind, val windowId: Int? = null) {
    fun contains(node: NodeSnapshot): Boolean = node.path == path || node.path.startsWith("$path/")

    /** "The same popup" while it is on screen, whatever its content repaints (carousel, countdown). */
    val key: String get() = "$kind:$path:${bounds.left},${bounds.top},${bounds.right},${bounds.bottom}"
}

/**
 * Finds one popup root in a visible-node tree ([NodeSnapshot] DTOs in walk order, paths "0/i/j").
 * Pure Kotlin so the device dumps (11번가, 옥션, 올리브영) run as JVM tests.
 *
 * Order: a Dialog node (①) wins; without one, a bounded dialog window (②, decided by the collector
 * from the window list); then a dim overlay (③); then (F16, [SimpleCloseEngineFeature.FIND_FRONT_LAYERS])
 * a front layer or bottom sheet (④, [frontLayerRoot]). Nodes with empty bounds or outside the screen are
 * never part of a root and never a root, so Chrome's zero-height footer links cannot be a popup.
 */
object PopupRootFinder {
    /** An overlay background covers at least this much of the screen. */
    private const val DIM_MIN_AREA_RATIO = 0.85

    /** A card is bounded: smaller than this part of the screen (same bar as A's detector). */
    private const val CARD_MAX_AREA_RATIO = 0.96

    fun find(
        nodes: List<NodeSnapshot>,
        screen: ScreenBounds,
        activeWindowBounded: Boolean = false,
        frontLayers: Boolean = SimpleCloseEngineFeature.FIND_FRONT_LAYERS,
        /** F32 (e): the window's activity is named like a popup (*Popup*, *Dialog*, *Ads*): no close needed to count. */
        popupActivityHint: Boolean = false,
    ): PopupRoot? {
        val visible = nodes.filter { isVisible(it, screen) }
        dialogRoot(visible)?.let { return it }
        if (activeWindowBounded) {
            val root = visible.firstOrNull { it.path == "0" } ?: visible.firstOrNull() ?: return null
            return PopupRoot(root.path, root.bounds, PopupRootKind.DIALOG_WINDOW)
        }
        dimOverlayRoot(visible, screen)?.let { return it }
        if (!frontLayers) return null
        return frontLayerRoot(visible, screen) ?: modalCardRoot(visible, screen) ?: dimWindowRoot(visible, screen, popupActivityHint)
    }

    /**
     * F32 (d): a window as big as the screen whose visible content (every node smaller than the screen) fits in one
     * centred block of [DIM_WINDOW_MIN_RATIO]~[DIM_WINDOW_MAX_RATIO] of it, the rest empty: a transparent window
     * (an activity or a Dialog decor) showing a card. Also needed:
     * - at least one clickable and fewer than [MENU_MIN_CLICKABLES], no text input (a form is a screen, not a popup),
     *   no "쿠키";
     * - something that closes it: a named close, or exactly one small unnamed (or X-mark) clickable anywhere in the
     *   block (11번가 puts its round X under the card). With [hint] (the activity's own name says popup) not needed.
     * The root is the deepest node holding all the content (often a screen-sized container; its own bounds are kept
     * for the live checks). Nothing here makes the X pressable without the person (6-3).
     */
    private fun dimWindowRoot(nodes: List<NodeSnapshot>, screen: ScreenBounds, hint: Boolean): PopupRoot? {
        val screenArea = area(screen)
        if (screenArea <= 0) return null
        val top = nodes.firstOrNull { it.path == "0" } ?: return null
        if (area(top.bounds) < screenArea * DIM_WINDOW_SCREEN_RATIO) return null
        val content = nodes.filter { area(it.bounds) < screenArea * DIM_WINDOW_SCREEN_RATIO }
        if (content.isEmpty()) return null
        val block = ScreenBounds(content.minOf { it.bounds.left }, content.minOf { it.bounds.top }, content.maxOf { it.bounds.right }, content.maxOf { it.bounds.bottom })
        val ratio = area(block).toDouble() / screenArea
        if (ratio < DIM_WINDOW_MIN_RATIO || ratio > DIM_WINDOW_MAX_RATIO) return null
        if (kotlin.math.abs((block.left + block.right) / 2 - (screen.left + screen.right) / 2) > screen.width * 0.1) return null
        val clickables = content.filter { it.clickable && it.enabled }
        if (clickables.isEmpty() || clickables.size >= MENU_MIN_CLICKABLES) return null
        if (content.any { it.className?.contains("EditText", ignoreCase = true) == true } || content.any(::mentionsCookies)) return null
        if (!hint) {
            val namedClose = clickables.any { SimpleLabelRules.roleOf(CloseButtonFinder.nameOf(it, content)) == ButtonRole.CLOSE }
            val side = maxOf(120, (minOf(block.width, block.height) * 0.2).toInt())
            val xs = clickables.filter { node ->
                val name = CloseButtonFinder.nameOf(node, content)
                val n = node.bounds
                (name == null || SimpleLabelRules.isXMark(name)) && n.width in 1..side && n.height in 1..side && n.width.toDouble() / n.height in 0.5..2.0
            }
            if (!namedClose && xs.size != 1) return null
        }
        val rootNode = deepestHolding(nodes, content) ?: return null
        return PopupRoot(rootNode.path, rootNode.bounds, PopupRootKind.DIM_WINDOW)
    }

    /** The deepest node whose path is a prefix of every content node's path. */
    private fun deepestHolding(nodes: List<NodeSnapshot>, content: List<NodeSnapshot>): NodeSnapshot? {
        val byPath = nodes.associateBy { it.path }
        var common = content.first().path.split('/')
        for (node in content.drop(1)) {
            val parts = node.path.split('/')
            common = common.zip(parts).takeWhile { (a, b) -> a == b }.map { it.first }
        }
        // A content node itself may be the deepest holder only when it holds the others; else its parent chain.
        for (length in common.size downTo 1) {
            val path = common.take(length).joinToString("/")
            val node = byPath[path] ?: continue
            if (content.all { it.path == path || it.path.startsWith("$path/") }) return node
        }
        return null
    }

    private const val DIM_WINDOW_SCREEN_RATIO = 0.96
    private const val DIM_WINDOW_MIN_RATIO = 0.05
    private const val DIM_WINDOW_MAX_RATIO = 0.6

    /**
     * The window a found root is looked up in from now on (click, re-check, closed check). F16: an app popup window read
     * as its own tree ([popupWindowId]): that window, and a Dialog window found that way is an [PopupRootKind.APP_WINDOW].
     * F33 (LG 11/1 11번가 app: a [PopupRootKind.DIM_WINDOW] popup closed, then NOT_CLOSED): a popup that is the active
     * window itself (a Dialog window, a popup activity) is tied to that window ([activeWindowId]); looked up in whatever
     * window is active after the close, the app's main screen had a node at the same path and the same screen size.
     */
    fun bindWindow(found: PopupRoot, popupWindowId: Int?, activeWindowId: Int): PopupRoot = when {
        popupWindowId != null -> found.copy(windowId = popupWindowId, kind = if (found.kind == PopupRootKind.DIALOG_WINDOW) PopupRootKind.APP_WINDOW else found.kind)
        found.kind == PopupRootKind.DIM_WINDOW || found.kind == PopupRootKind.DIALOG_WINDOW -> found.copy(windowId = activeWindowId)
        else -> found
    }

    /**
     * F22 (LG 11/1 07:0x, 11번가 app: dim + white box + an X TalkBack calls only "버튼" + "혜택 받으러 가기";
     * NO_POPUP_ROOT): a box that is
     * - centred (its middle within 10% of the screen's middle) and away from both side edges, 8~70% of the screen,
     *   not clickable itself;
     * - drawn over a layer: a node earlier in reading order, not one of its ancestors, covering it and at least
     *   60% of the screen (the dim or the page under it);
     * - fewer than [MENU_MIN_CLICKABLES] clickables, no "쿠키";
     * - and with exactly one small unnamed (or X-mark) clickable in one of its top corners, or a named close.
     * The root is the box. Nothing here makes the X pressable without the person: it stays [ButtonScan.xButton].
     */
    private fun modalCardRoot(nodes: List<NodeSnapshot>, screen: ScreenBounds): PopupRoot? {
        val screenArea = area(screen)
        if (screenArea <= 0) return null
        for ((index, card) in nodes.withIndex().reversed()) {
            if (card.path == "0" || card.clickable) continue
            val b = card.bounds
            val ratio = area(b).toDouble() / screenArea
            if (ratio < MODAL_MIN_AREA_RATIO || ratio > MODAL_MAX_AREA_RATIO) continue
            val margin = screen.width * 0.02
            if (b.left <= screen.left + margin || b.right >= screen.right - margin) continue
            if (kotlin.math.abs((b.left + b.right) / 2 - (screen.left + screen.right) / 2) > screen.width * 0.1) continue
            val overLayer = nodes.subList(0, index).any { under ->
                !card.path.startsWith("${under.path}/") && area(under.bounds) >= screenArea * MODAL_LAYER_MIN_RATIO &&
                    under.bounds.left <= b.left && under.bounds.top <= b.top && under.bounds.right >= b.right && under.bounds.bottom >= b.bottom
            }
            if (!overLayer) continue
            val inside = nodes.filter { it.path.startsWith("${card.path}/") }
            if (inside.count { it.clickable } >= MENU_MIN_CLICKABLES || mentionsCookies(card) || inside.any(::mentionsCookies)) continue
            val namedClose = inside.any { it.clickable && SimpleLabelRules.roleOf(CloseButtonFinder.nameOf(it, inside)) == ButtonRole.CLOSE }
            if (namedClose || cornerXs(card, inside).size == 1) return PopupRoot(card.path, card.bounds, PopupRootKind.MODAL_CARD)
        }
        return null
    }

    /** Small, about square, unnamed (or X marks only) clickables whose middle is in the box's top corners. */
    fun cornerXs(card: NodeSnapshot, inside: List<NodeSnapshot>): List<NodeSnapshot> {
        val b = card.bounds
        val side = maxOf(120, (minOf(b.width, b.height) * 0.2).toInt())
        return inside.filter { node ->
            val n = node.bounds
            val name = CloseButtonFinder.nameOf(node, inside)
            val cx = (n.left + n.right) / 2
            val cy = (n.top + n.bottom) / 2
            node.clickable && node.enabled && (name == null || SimpleLabelRules.isXMark(name)) &&
                n.width in 1..side && n.height in 1..side && n.width.toDouble() / n.height in 0.5..2.0 &&
                cy <= b.top + b.height * 0.3 && (cx >= b.right - b.width * 0.3 || cx <= b.left + b.width * 0.3)
        }
    }

    private const val MODAL_MIN_AREA_RATIO = 0.08
    private const val MODAL_MAX_AREA_RATIO = 0.7
    private const val MODAL_LAYER_MIN_RATIO = 0.6

    /** True when the node can be seen: non-empty bounds that lie inside the screen. */
    fun isVisible(node: NodeSnapshot, screen: ScreenBounds): Boolean {
        val b = node.bounds
        return b.width > 0 && b.height > 0 && b.right > screen.left && b.left < screen.right &&
            b.bottom > screen.top && b.top < screen.bottom
    }

    private fun dialogRoot(nodes: List<NodeSnapshot>): PopupRoot? {
        val dialogs = nodes.filter { it.className?.contains("dialog", ignoreCase = true) == true }
        if (dialogs.isEmpty()) return null
        val outermost = dialogs.filter { dialog -> dialogs.none { dialog.path != it.path && dialog.path.startsWith("${it.path}/") } }
        // Several dialogs at once: the last in walk order is drawn on top (later sibling).
        val chosen = outermost.last()
        return PopupRoot(chosen.path, chosen.bounds, PopupRootKind.ALERT_DIALOG)
    }

    /**
     * An app's own overlay, recognised only when all of these hold (an ordinary web page has a
     * clickable near-screen container too, so each one is needed):
     * - the dim: clickable, covers [DIM_MIN_AREA_RATIO] of the screen, not the tree root, has no text
     *   of its own, and is either a leaf (nothing drawn inside it) or describes itself as a close
     *   ("배경을 눌러 닫기");
     * - the card: a bounded non-clickable container next to the dim (sibling) or inside it (child);
     * - a close/skip button ([SimpleLabelRules]) inside the card, or inside the dim for the child form.
     * The root is the smallest subtree holding dim and card: the dim itself, or their parent.
     */
    private fun dimOverlayRoot(nodes: List<NodeSnapshot>, screen: ScreenBounds): PopupRoot? {
        val screenArea = area(screen)
        if (screenArea <= 0) return null
        val byPath = nodes.associateBy { it.path }
        val dims = nodes.filter {
            it.path != "0" && it.clickable && it.text.isNullOrBlank() && area(it.bounds).toDouble() / screenArea >= DIM_MIN_AREA_RATIO
        }
        for (dim in dims.asReversed()) {
            val children = nodes.filter { it.path.startsWith("${dim.path}/") }
            val selfDescribed = SimpleLabelRules.roleOf(dim.contentDescription) != null
            if (!selfDescribed && children.isNotEmpty()) continue
            if (selfDescribed && children.any { isCard(it, screenArea) } && children.any(::isCloseButton)) {
                return PopupRoot(dim.path, dim.bounds, PopupRootKind.DIM_OVERLAY)
            }
            if (children.isNotEmpty()) continue
            val parentPath = dim.path.substringBeforeLast('/', missingDelimiterValue = "")
            val parent = byPath[parentPath] ?: continue
            val siblings = nodes.filter { it.path != dim.path && it.path.startsWith("$parentPath/") }
            val card = siblings.firstOrNull { isCard(it, screenArea) } ?: continue
            if (siblings.any { card.contains(it) && isCloseButton(it) }) return PopupRoot(parent.path, parent.bounds, PopupRootKind.DIM_OVERLAY)
        }
        return null
    }

    /**
     * F16: a popup with no Dialog mark and no dim node (web layers, aria-modal boxes Chrome does not mark,
     * bottom sheets). Recognised only when all of these hold, the topmost (last in reading order) first:
     * - drawn over the page: an earlier sibling (page content) covers at least half of it;
     * - large but bounded ([layerSized]): a box of [LAYER_MIN_AREA_RATIO] up to [CARD_MAX_AREA_RATIO] of
     *   the screen, or a bottom sheet (full width, touching the bottom edge) of at least [SHEET_MIN_AREA_RATIO];
     * - a named close button inside ([SimpleLabelRules]; an unnamed X alone is not enough);
     * - not a menu: no side drawer (an edge, nearly full height, not full width), no top dropdown (from
     *   the top part of the screen, not reaching the bottom), fewer than [MENU_MIN_CLICKABLES] clickables;
     * - not a cookie strip: no "쿠키"/"cookie" in it.
     * Page sections stack instead of overlapping, and sticky bars and floating buttons are too small,
     * so the page itself does not pass.
     */
    private fun frontLayerRoot(nodes: List<NodeSnapshot>, screen: ScreenBounds): PopupRoot? {
        if (area(screen) <= 0) return null
        val children = nodes.groupBy { it.path.substringBeforeLast('/', missingDelimiterValue = "") }
        for (layer in nodes.asReversed()) {
            if (layer.path == "0") continue
            val sheet = isSheetShape(layer.bounds, screen)
            if (!layerSized(layer.bounds, screen, sheet)) continue
            val index = layer.path.substringAfterLast('/').toIntOrNull() ?: continue
            val siblings = children[layer.path.substringBeforeLast('/')].orEmpty()
            val overPage = siblings.any { other ->
                val i = other.path.substringAfterLast('/').toIntOrNull()
                i != null && i < index && coversHalf(other.bounds, layer.bounds)
            }
            if (!overPage) continue
            val inside = nodes.filter { it.path.startsWith("${layer.path}/") }
            if (looksLikeMenu(layer, inside, screen) || mentionsCookies(layer) || inside.any(::mentionsCookies)) continue
            if (inside.none { it.clickable && SimpleLabelRules.roleOf(CloseButtonFinder.nameOf(it, inside)) == ButtonRole.CLOSE }) continue
            return PopupRoot(layer.path, layer.bounds, if (sheet) PopupRootKind.BOTTOM_SHEET else PopupRootKind.FRONT_LAYER)
        }
        return null
    }

    /** F16 size test of a front layer; shared with [PopupProbe], which has no labels (the finder decides). */
    fun layerSized(b: ScreenBounds, screen: ScreenBounds, sheet: Boolean = isSheetShape(b, screen)): Boolean {
        val ratio = area(b).toDouble() / area(screen).coerceAtLeast(1)
        return ratio < CARD_MAX_AREA_RATIO && ratio >= (if (sheet) SHEET_MIN_AREA_RATIO else LAYER_MIN_AREA_RATIO)
    }

    /** Full width and touching the bottom edge, not the whole height. */
    fun isSheetShape(b: ScreenBounds, screen: ScreenBounds): Boolean =
        b.width >= screen.width * 0.9 && b.bottom >= screen.bottom - screen.height * 0.03 && b.height <= screen.height * 0.9

    /** [under] (an earlier sibling) covers at least half of [layer]. */
    fun coversHalf(under: ScreenBounds, layer: ScreenBounds): Boolean {
        val w = minOf(under.right, layer.right) - maxOf(under.left, layer.left)
        val h = minOf(under.bottom, layer.bottom) - maxOf(under.top, layer.top)
        return w > 0 && h > 0 && w.toLong() * h * 2 >= area(layer)
    }

    private fun looksLikeMenu(layer: NodeSnapshot, inside: List<NodeSnapshot>, screen: ScreenBounds): Boolean {
        val b = layer.bounds
        val drawer = (b.left <= screen.left || b.right >= screen.right) && b.height >= screen.height * 0.8 && b.width < screen.width * 0.9
        val dropdown = b.top <= screen.top + screen.height * 0.15 && b.bottom < screen.bottom - screen.height * 0.05
        return drawer || dropdown || inside.count { it.clickable } >= MENU_MIN_CLICKABLES
    }

    private fun mentionsCookies(node: NodeSnapshot): Boolean =
        listOfNotNull(node.text, node.contentDescription).any { "쿠키" in it || it.contains("cookie", ignoreCase = true) }

    private const val LAYER_MIN_AREA_RATIO = 0.18
    private const val SHEET_MIN_AREA_RATIO = 0.1
    private const val MENU_MIN_CLICKABLES = 12

    /** A bounded, non-clickable container or text: the card's own buttons sit inside it. A clickable item is a list row. */
    private fun isCard(node: NodeSnapshot, screenArea: Long): Boolean {
        val a = area(node.bounds)
        return a > 0 && a.toDouble() / screenArea < CARD_MAX_AREA_RATIO && !node.clickable
    }

    private fun isCloseButton(node: NodeSnapshot): Boolean =
        node.clickable && (SimpleLabelRules.roleOf(node.text) != null || SimpleLabelRules.roleOf(node.contentDescription) != null)

    private fun NodeSnapshot.contains(other: NodeSnapshot): Boolean = other.path.startsWith("$path/")

    private fun area(b: ScreenBounds): Long = b.width.toLong() * b.height.toLong()
}

/**
 * F22 (LG 11/1 06:57~59: an app window of 5 nodes was taken for the popup, nothing to press, asked again and
 * again while the real popup sat in the main window): an app window above the main one is the popup only when
 * it is one by itself: at least [MIN_AREA_RATIO] of the screen, a popup root in it, and something to offer
 * (a named close or skip, an X-shaped button, or a risky button to point at). Otherwise the main window is read.
 */
/**
 * F30 (LG 11/1 15:37, 시험 앱 P04: a plain `Dialog`, NO_POPUP_ROOT ×192): since Android 11 a touch-modal window (a
 * Dialog that dims and takes every touch) can report the whole screen as its window bounds, while its root view is
 * the card. A window is judged by the smaller of the two, so such a Dialog is still a bounded popup window: as the
 * active window ([PopupRootKind.DIALOG_WINDOW]) and above the app's main window ([PopupRootKind.APP_WINDOW]).
 * Pure; JVM tested. Device unverified (the bounds Android reports for this Dialog were not dumped).
 */
object WindowBoundsRules {
    const val BOUNDED_MAX_RATIO = 0.96

    /** What the window really shows: its root view's bounds when they are smaller and not empty, else the window's. */
    fun shown(window: ScreenBounds, root: ScreenBounds?): ScreenBounds =
        if (root != null && area(root) > 0 && area(root) < area(window)) root else window

    fun bounded(window: ScreenBounds, root: ScreenBounds?, screen: ScreenBounds): Boolean {
        val screenArea = area(screen)
        val shownArea = area(shown(window, root))
        return screenArea > 0 && shownArea > 0 && shownArea.toDouble() / screenArea < BOUNDED_MAX_RATIO
    }

    fun area(b: ScreenBounds): Long = maxOf(0, b.width).toLong() * maxOf(0, b.height)

    /**
     * F32 (LG 11/1 16:00, 시험 앱 P04 still NO_POPUP_ROOT after F30, no WINDOW_BOUNDS_FROM_ROOT): Android drops from
     * `getWindows()` a window wholly covered by the touchable regions above it, and a touch-modal Dialog's touchable
     * region is the whole screen, so the activity window under it is not listed at all. The active window is a
     * bounded popup window when it is an application window whose shown part ([shown]) is bounded, and either
     * - it reports more than its root view shows ([TOUCH_MODAL_RATIO]: a touch-modal Dialog), or
     * - a wider application window of the same app is listed (F12: a Dialog over its activity, as before).
     * A split-screen or freeform app (window = root, nothing wider of its own) stays a normal window.
     * Null = bounded; otherwise why not (debug code only).
     */
    fun activeBounded(active: WindowShape, all: List<WindowShape>, screen: ScreenBounds): ActiveWindowReject? {
        if (active.kind != WindowKind.APPLICATION) return ActiveWindowReject.NOT_APPLICATION
        if (!bounded(active.window, active.root, screen)) return ActiveWindowReject.NOT_BOUNDED
        val root = active.root
        if (root != null && area(root) > 0 && area(active.window) > area(root) * TOUCH_MODAL_RATIO) return null
        // F33 (LG 11/1, fbc54ae: `WINDOWS app=1 [S?:wPrN A*S:wPrP] active=NO_WIDER_SAME_APP_WINDOW`): the Dialog came with
        // its own size and nothing else of the app was listed. A floating window in the middle of the screen is a dialog.
        if (floating(shown(active.window, active.root), screen)) return null
        val shownArea = area(shown(active.window, active.root))
        val wider = all.any { it.id != active.id && it.kind == WindowKind.APPLICATION && it.samePackage == true && area(it.window) > shownArea }
        return if (wider) null else ActiveWindowReject.NO_WIDER_SAME_APP_WINDOW
    }

    /**
     * F33: [FLOATING_MIN_RATIO]~[FLOATING_MAX_RATIO] of the screen, its middle within 10% (across) and 20% (down) of the
     * screen's middle. Not a split-screen half (off-centre one way), a PiP (a corner, small), a top banner or a bottom
     * sheet (off-centre down); a keyboard or a toast is not an application window at all.
     */
    fun floating(b: ScreenBounds, screen: ScreenBounds): Boolean {
        val ratio = area(b).toDouble() / maxOf(1L, area(screen))
        if (ratio < FLOATING_MIN_RATIO || ratio > FLOATING_MAX_RATIO) return false
        val dx = kotlin.math.abs((b.left + b.right) / 2 - (screen.left + screen.right) / 2)
        val dy = kotlin.math.abs((b.top + b.bottom) / 2 - (screen.top + screen.bottom) / 2)
        return dx <= screen.width * 0.1 && dy <= screen.height * 0.2
    }

    const val FLOATING_MIN_RATIO = 0.1
    const val FLOATING_MAX_RATIO = 0.85

    /** A window reporting this much more than its root view shows takes touches outside its content (touch-modal). */
    const val TOUCH_MODAL_RATIO = 1.2

    /** F32: a size class for the debug line, never the bounds themselves. */
    fun grade(b: ScreenBounds?, screen: ScreenBounds): String {
        if (b == null) return "N"
        val a = area(b)
        val s = area(screen)
        return when {
            a <= 0 || s <= 0 -> "Z"
            a.toDouble() / s >= BOUNDED_MAX_RATIO -> "F"
            else -> "P"
        }
    }

    /**
     * F32: one debug line about the windows of a read, codes only (no package, no text): how many application windows of
     * the app, then each window topmost first as kind (A application, I input method, S system, O our overlay, X other),
     * `*` for the active one, S/O/? for same app / other app / unknown, window and root size class (F full, P part,
     * Z zero, N none), then why the active window is not a popup window and why app windows above were left.
     */
    fun line(shapes: List<WindowShape>, activeId: Int, screen: ScreenBounds, activeReject: ActiveWindowReject?, appRejects: List<AppWindowReject>): String {
        val apps = shapes.count { it.kind == WindowKind.APPLICATION && it.samePackage == true }
        val windows = shapes.sortedByDescending { it.layer }.joinToString(" ") { w ->
            val same = when (w.samePackage) { true -> "S"; false -> "O"; null -> "?" }
            "${w.kind.code}${if (w.id == activeId) "*" else ""}$same:w${grade(w.window, screen)}r${grade(w.root, screen)}"
        }
        val active = activeReject?.name ?: "BOUNDED"
        val above = if (appRejects.isEmpty()) "-" else appRejects.joinToString(",") { it.name }
        return "WINDOWS app=$apps [$windows] active=$active above=$above"
    }
}

/**
 * F32 (e): an activity class named like a popup ("…AdsOneDayPopupActivity", "…DialogActivity", "…AdActivity"), from
 * TYPE_WINDOW_STATE_CHANGED's class name. Compared in memory only, never logged.
 */
object PopupActivityName {
    private val WORDS = Regex("(Popup|Dialog|Ads?)(?=[A-Z]|Activity|$)")

    fun looksLikePopup(className: String?): Boolean {
        val simple = className?.substringAfterLast('.') ?: return false
        if (!simple.endsWith("Activity")) return false
        return WORDS.containsMatchIn(simple)
    }
}

/** F32: an accessibility window's kind, as the debug line and [WindowBoundsRules.activeBounded] need it. */
enum class WindowKind(val code: String) { APPLICATION("A"), INPUT_METHOD("I"), SYSTEM("S"), ACCESSIBILITY_OVERLAY("O"), OTHER("X") }

/** F32: one window of a read: its kind, z-order, whether its root is the read app's, its bounds and its root view's. Memory only. */
data class WindowShape(val id: Int, val kind: WindowKind, val layer: Int, val samePackage: Boolean?, val window: ScreenBounds, val root: ScreenBounds?)

/** F32: why the active window is not taken as a bounded popup window (debug code only). */
enum class ActiveWindowReject { NOT_LISTED, NOT_APPLICATION, NOT_BOUNDED, NO_WIDER_SAME_APP_WINDOW }

/** F32: why an application window above the active one was not read as the popup (debug code only). */
enum class AppWindowReject { NO_ROOT, OTHER_PACKAGE, NOT_BOUNDED, NOT_POPUP, CUT }

object AppWindowRules {
    private const val MIN_AREA_RATIO = 0.1

    fun isPopup(nodes: List<NodeSnapshot>, screen: ScreenBounds): Boolean {
        val top = nodes.firstOrNull { it.path == "0" } ?: return false
        val screenArea = screen.width.toLong() * screen.height
        if (screenArea <= 0 || top.bounds.width.toLong() * top.bounds.height < screenArea * MIN_AREA_RATIO) return false
        val root = PopupRootFinder.find(nodes, screen, activeWindowBounded = true) ?: return false
        val scan = CloseButtonFinder.scan(nodes, root)
        return scan.candidates.isNotEmpty() || scan.xButton != null || scan.riskButtons.isNotEmpty()
    }
}
