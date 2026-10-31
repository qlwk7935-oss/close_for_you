package kr.closeforyou.simpleclose

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
) {
    fun matches(text: String?, description: String?, nodeBounds: ScreenBounds): Boolean =
        sameTextOrPlace(text, description, nodeBounds) || overlapsArea(nodeBounds)

    /** Same words or exactly the same place as one of the popup's nodes. */
    fun sameTextOrPlace(text: String?, description: String?, nodeBounds: ScreenBounds): Boolean =
        (text != null && text in texts) || (description != null && description in texts) || nodeBounds in bounds

    fun overlapsArea(nodeBounds: ScreenBounds): Boolean = area != null && overlaps(area, nodeBounds)

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
            )
        }

        /** Strict overlap (touching edges do not count). */
        fun overlaps(a: ScreenBounds, b: ScreenBounds): Boolean =
            a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
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
 * Where TalkBack's focus goes after a close (F4/F9, LG 10/31: it read the closed popup again; at
 * 22:29 every candidate overlapped the old popup, so nothing moved). In order:
 * 1. a page item that is not a popup leftover, the highest on the screen (tree order breaks ties);
 * 2. ([fallback]) an item wholly above the old popup, even if it shares words with it ([FocusTargetKind.ABOVE_OLD_POPUP]);
 * 3. ([fallback]) the WebView itself, named or not;
 * 4. ([fallback]) the first named item outside the WebView: Chrome's toolbar ([Pick.toolbar], the caller searches).
 * Without [fallback] only 1 is used.
 *
 * [beforeClick] (F12, LG 10/31 23:0x: with the person's press, TalkBack still began reading the popup
 * again after the close): right before the press, while the popup is still there, the focus goes
 * straight to a place that will survive the close and is surely not the popup: an item wholly above
 * it, else the WebView itself, else the toolbar. Page items beside or below the popup are skipped then.
 * Pure; JVM tested.
 */
object PageFocusRules {
    data class Pick(val index: Int?, val kind: FocusTargetKind, val webViewRoot: Boolean = false, val toolbar: Boolean = false)

    fun pick(candidates: List<FocusCandidate>, webViewPresent: Boolean, fallback: Boolean = true, beforeClick: Boolean = false): Pick {
        if (beforeClick) {
            val above = candidates.withIndex().filter { it.value.above }.minByOrNull { it.value.bounds.top }
            return when {
                above != null -> Pick(above.index, FocusTargetKind.ABOVE_OLD_POPUP)
                webViewPresent -> Pick(null, FocusTargetKind.WEBVIEW_ROOT, webViewRoot = true)
                else -> Pick(null, FocusTargetKind.TOOLBAR_ITEM, toolbar = true)
            }
        }
        val best = candidates.withIndex().filter { !it.value.leftover }.minByOrNull { it.value.bounds.top }
        if (best != null) return Pick(best.index, if (best.value.heading) FocusTargetKind.PAGE_HEADING else FocusTargetKind.PAGE_ITEM)
        if (!fallback) return Pick(null, if (candidates.isEmpty()) FocusTargetKind.NONE else FocusTargetKind.OVERLAPS_OLD_POPUP)
        val above = candidates.withIndex().firstOrNull { it.value.above }
        return when {
            above != null -> Pick(above.index, FocusTargetKind.ABOVE_OLD_POPUP)
            webViewPresent -> Pick(null, FocusTargetKind.WEBVIEW_ROOT, webViewRoot = true)
            else -> Pick(null, FocusTargetKind.TOOLBAR_ITEM, toolbar = true)
        }
    }
}
