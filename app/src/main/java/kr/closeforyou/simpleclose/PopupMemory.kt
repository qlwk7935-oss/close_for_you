package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/**
 * What a closed popup looked like, kept in memory only for the moment after the close: the texts and
 * bounds of its nodes, and the area it covered. A live node with the same words or the same place is
 * treated as the popup's leftover (a web popup often stays in the DOM after closing), so TalkBack focus
 * is not sent there. Pure Kotlin; never logged or stored; dropped with the popup.
 */
class PopupMemory(
    private val texts: Set<String>,
    private val bounds: Set<ScreenBounds>,
    /**
     * The popup root's area, or null when it covered most of the screen (a dim overlay): then every
     * page item would "overlap" and only [texts]/[bounds] are used (LG 10/31 21:00, F4).
     */
    private val area: ScreenBounds? = null,
    /** F29: the popup root's own bounds when it was found (null in old callers: never "in place"). */
    private val rootBounds: ScreenBounds? = null,
) {
    fun matches(text: String?, description: String?, nodeBounds: ScreenBounds): Boolean =
        sameTextOrPlace(text, description, nodeBounds) || overlapsArea(nodeBounds)

    /** Same words or exactly the same place as one of the popup's nodes. */
    fun sameTextOrPlace(text: String?, description: String?, nodeBounds: ScreenBounds): Boolean =
        (text != null && text in texts) || (description != null && description in texts) || nodeBounds in bounds

    fun overlapsArea(nodeBounds: ScreenBounds): Boolean = area != null && overlaps(area, nodeBounds)

    /**
     * F20 (LG 11/1 11번가: "closed" 0.27 s after the click, the same sheet found again 0.4 s later): a popup root
     * found after the close is this popup again when it is in the same place (its area, when known) and one of
     * its named nodes has the same words or exactly the same place as one of ours.
     */
    fun reappeared(root: PopupRoot, nodes: List<NodeSnapshot>, screen: ScreenBounds? = null): Boolean = reappearance(root, nodes, screen) != null

    /**
     * F29 (LG 11/1 11번가 웹: the sheet closed, but 220 ms later it was still sliding out and counted as back): where
     * the popup found again is, or null when it is not this popup. Only nodes on screen with a size count ([screen]).
     * [Reappearance.inPlace]: its root and every matched node sit exactly where they were before the press.
     */
    fun reappearance(root: PopupRoot, nodes: List<NodeSnapshot>, screen: ScreenBounds? = null): Reappearance? {
        if (area != null && !overlaps(area, root.bounds)) return null
        if (screen != null && !(root.bounds.width > 0 && root.bounds.height > 0 && overlaps(root.bounds, screen))) return null
        val matched = nodes.filter { node ->
            root.contains(node) && (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank()) &&
                (screen == null || PopupRootFinder.isVisible(node, screen)) &&
                sameTextOrPlace(node.text, node.contentDescription, node.bounds)
        }
        if (matched.isEmpty()) return null
        val places = matched.map { it.bounds }
        return Reappearance(
            inPlace = rootBounds != null && root.bounds == rootBounds && places.all { it in bounds },
            signature = listOf(root.bounds) + places.sortedWith(compareBy({ it.top }, { it.left }, { it.bottom }, { it.right })),
        )
    }

    /** Wholly above the popup's area (F9 fallback ①); false when the area is unknown. */
    fun above(nodeBounds: ScreenBounds): Boolean = area != null && nodeBounds.bottom <= area.top

    companion object {
        /** A root covering at least this share of the screen is not used as an area to avoid. */
        private const val FULL_SCREEN_SHARE = 0.7

        fun of(popup: SimplePopup): PopupMemory {
            val inside = popup.snapshot.nodes.filter(popup.root::contains)
            val screen = popup.snapshot.screenBounds
            val screenArea = screen.width.toLong() * screen.height
            val rootArea = popup.root.bounds.width.toLong() * popup.root.bounds.height
            return PopupMemory(
                inside.flatMap { listOfNotNull(it.text, it.contentDescription) }.filter { it.isNotBlank() }.toSet(),
                inside.map { it.bounds }.toSet(),
                popup.root.bounds.takeIf { screenArea > 0 && rootArea < screenArea * FULL_SCREEN_SHARE },
                popup.root.bounds,
            )
        }

        /** Strict overlap (touching edges do not count). */
        fun overlaps(a: ScreenBounds, b: ScreenBounds): Boolean =
            a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }
}

/** F29: the closed popup seen again: exactly where it was ([inPlace]), and where it is now ([signature]: root, then matched nodes). */
data class Reappearance(val inPlace: Boolean, val signature: List<ScreenBounds>)

/** F29: one strict re-check read's verdict. */
enum class RecheckVerdict { CLOSED, BACK, MOVING }

/**
 * F29 (LG 11/1 11번가 웹, c750177: CLOSE_REAPPEARED on a sheet that did close): a popup that is leaving slides or
 * shrinks, so where it is changes from read to read; a popup that is really back sits still. Not found = closed.
 * Found exactly where it was before the press = back (the F20 11번가 failure, an 옥션 popup shown again). Found
 * elsewhere twice at the same place = back (it settled there). Found elsewhere and still moving = read again; the
 * caller counts it as back when its time runs out (never a false "closed"). Pure; JVM tested.
 */
object RecheckRules {
    fun verdict(found: Reappearance?, previous: Reappearance?): RecheckVerdict = when {
        found == null -> RecheckVerdict.CLOSED
        found.inPlace -> RecheckVerdict.BACK
        previous != null && previous.signature == found.signature -> RecheckVerdict.BACK
        else -> RecheckVerdict.MOVING
    }
}

/** What the engine put TalkBack's focus on after a close (debug log `FOCUS_TARGET_*`, no text). */
enum class FocusTargetKind { OVERLAPS_OLD_POPUP, WEBVIEW_ROOT, PAGE_HEADING, PAGE_ITEM, ABOVE_OLD_POPUP, TOOLBAR_ITEM, NONE }

/**
 * One visible, named node that could take the focus, in tree order. No live node, no text kept.
 * [leftover]: part of the closed popup by words, place or area ([PopupMemory.matches]); [above]:
 * wholly above the popup's area ([PopupMemory.above]).
 */
data class FocusCandidate(val bounds: ScreenBounds, val leftover: Boolean, val heading: Boolean, val above: Boolean = false)

/**
 * Where TalkBack's focus goes, as an ordered list of targets: the caller tries each until one takes
 * the focus. F13 (LG 11/1 11번가: the focus was moved before the press and again after the close, and
 * TalkBack read the popup text twice in between): one move per handling, onto something that is
 * still there after the close.
 *
 * [beforeClick] (right before our window goes away and the press, popup still on screen):
 * 1. Chrome's toolbar ([Pick.toolbar], only with a WebView; native, survives any DOM change);
 * 2. the highest heading wholly above the popup that is not part of it;
 * 3. the highest item wholly above the popup that is not part of it ([FocusTargetKind.ABOVE_OLD_POPUP]).
 * Never the WebView itself (TalkBack reads the whole page, the popup's DOM included) and never a
 * leftover of the popup (its words, place or area, the dim and the sheet).
 *
 * After the close, only when the focus was lost or is still in the old popup:
 * 1. the highest page item that is not a leftover (the top of the page);
 * 2. the toolbar (with a WebView);
 * 3. the WebView itself, last.
 * Pure; JVM tested.
 */
object PageFocusRules {
    data class Pick(val index: Int?, val kind: FocusTargetKind, val webViewRoot: Boolean = false, val toolbar: Boolean = false)

    private val TOOLBAR = Pick(null, FocusTargetKind.TOOLBAR_ITEM, toolbar = true)
    private val WEBVIEW = Pick(null, FocusTargetKind.WEBVIEW_ROOT, webViewRoot = true)

    fun plan(candidates: List<FocusCandidate>, webViewPresent: Boolean, beforeClick: Boolean = false): List<Pick> {
        val page = candidates.withIndex().filter { !it.value.leftover }
        return if (beforeClick) {
            val above = page.filter { it.value.above }
            listOfNotNull(
                TOOLBAR.takeIf { webViewPresent },
                above.filter { it.value.heading }.minByOrNull { it.value.bounds.top }?.let { Pick(it.index, FocusTargetKind.PAGE_HEADING) },
                above.minByOrNull { it.value.bounds.top }?.let { Pick(it.index, FocusTargetKind.ABOVE_OLD_POPUP) },
            ).distinct()
        } else {
            listOfNotNull(
                page.minByOrNull { it.value.bounds.top }?.let { Pick(it.index, if (it.value.heading) FocusTargetKind.PAGE_HEADING else FocusTargetKind.PAGE_ITEM) },
                TOOLBAR.takeIf { webViewPresent },
                WEBVIEW.takeIf { webViewPresent },
            )
        }
    }
}

/** F15: the node TalkBack's focus just went to, as the reclaim rule needs it (memory only, never logged). */
class FocusedNode(val text: String?, val description: String?, val bounds: ScreenBounds, val visible: Boolean)

/**
 * F15: what to do with a focus move we did not make, while the watch after a press is on.
 * F39: [RECLAIM_TO_CARD] = a move elsewhere on the page while our result card holds the focus: back to the card.
 */
enum class ReclaimVerdict { OUR_OWN, LEAVE, RECLAIM, RECLAIM_TO_CARD, LIMIT }

/**
 * F15 (demo mode, AGENTS 6-0; LG 11/1 02:55: `FOCUS_MOVES_BY_OTHERS=2`, TalkBack read the closed
 * 11번가 popup again): the page restores its own focus as its popup closes and TalkBack follows it. A
 * move into the old popup (its words, place or area, [PopupMemory.matches]) or onto a node that is gone
 * (no node, not visible, no size) is taken back at once, at most `max` times; a move anywhere else on
 * the page is left alone, and so is our own move. Pure; JVM tested.
 */
object FocusReclaimRules {
    /**
     * F39 (삼성, TalkBack, a voice "닫아"): with nothing touched since the choice, a move onto the page's own
     * text while our result card holds the focus is TalkBack or the app restoring a focus, not the person:
     * [cardHoldsFocus] takes it back to the card too (the watch ends at the person's touch or gesture).
     */
    fun decide(focused: FocusedNode?, ours: Boolean, memory: PopupMemory, reclaimed: Int, max: Int, cardHoldsFocus: Boolean = false): ReclaimVerdict {
        if (ours) return ReclaimVerdict.OUR_OWN
        val intoPopup = intoPopup(focused, memory)
        return when {
            !intoPopup && !cardHoldsFocus -> ReclaimVerdict.LEAVE
            reclaimed >= max -> ReclaimVerdict.LIMIT
            !intoPopup -> ReclaimVerdict.RECLAIM_TO_CARD
            else -> ReclaimVerdict.RECLAIM
        }
    }

    fun intoPopup(focused: FocusedNode?, memory: PopupMemory): Boolean =
        focused == null || !focused.visible || focused.bounds.width <= 0 || focused.bounds.height <= 0 ||
            memory.matches(focused.text, focused.description, focused.bounds)
}
