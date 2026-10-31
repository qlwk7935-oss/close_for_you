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
    ): PopupRoot? {
        val visible = nodes.filter { isVisible(it, screen) }
        dialogRoot(visible)?.let { return it }
        if (activeWindowBounded) {
            val root = visible.firstOrNull { it.path == "0" } ?: visible.firstOrNull() ?: return null
            return PopupRoot(root.path, root.bounds, PopupRootKind.DIALOG_WINDOW)
        }
        dimOverlayRoot(visible, screen)?.let { return it }
        if (!frontLayers) return null
        return frontLayerRoot(visible, screen) ?: modalCardRoot(visible, screen)
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
