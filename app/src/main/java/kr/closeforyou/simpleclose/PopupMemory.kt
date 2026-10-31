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
        (text != null && text in texts) || (description != null && description in texts) || nodeBounds in bounds ||
            (area != null && overlaps(area, nodeBounds))

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
enum class FocusTargetKind { OVERLAPS_OLD_POPUP, WEBVIEW_ROOT, PAGE_HEADING, PAGE_ITEM, NONE }

/** One visible, named node that could take the focus, in tree order. No live node, no text kept. */
data class FocusCandidate(val bounds: ScreenBounds, val leftover: Boolean, val heading: Boolean)

/**
 * Where TalkBack's focus goes after a close (F4, LG 10/31 21:00: it still read the popup again).
 * Popup leftovers ([PopupMemory.matches]) are skipped; among the rest the one highest on the screen
 * wins (tree order breaks ties), so the focus lands above or beside the old popup, not inside it.
 * With no page item, the WebView itself when it has a name. Pure; JVM tested.
 */
object PageFocusRules {
    data class Pick(val index: Int?, val kind: FocusTargetKind, val webViewRoot: Boolean = false)

    fun pick(candidates: List<FocusCandidate>, webViewNamed: Boolean): Pick {
        val best = candidates.withIndex().filter { !it.value.leftover }.minByOrNull { it.value.bounds.top }
        return when {
            best != null -> Pick(best.index, if (best.value.heading) FocusTargetKind.PAGE_HEADING else FocusTargetKind.PAGE_ITEM)
            webViewNamed -> Pick(null, FocusTargetKind.WEBVIEW_ROOT, webViewRoot = true)
            candidates.isNotEmpty() -> Pick(null, FocusTargetKind.OVERLAPS_OLD_POPUP)
            else -> Pick(null, FocusTargetKind.NONE)
        }
    }
}
