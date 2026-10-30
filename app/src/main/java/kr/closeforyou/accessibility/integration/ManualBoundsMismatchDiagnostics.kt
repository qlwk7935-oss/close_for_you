package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.ScreenBounds

/** Geometry descriptions only, never an origin attribution, coordinate correction or permission. */
internal enum class ManualBoundsNode { ROOT, BUTTON, TEXT, CONTAINER, SCROLL_CONTAINER, IMAGE, WEB, SURFACE, OTHER, UNKNOWN }
internal enum class ManualBoundsChange { TRANSLATE_X, TRANSLATE_Y, TRANSLATE_XY, CONTRACTED, EXPANDED, RESHAPED, INVALID_RECT, UNKNOWN }
internal enum class ManualBoundsCommon { ALL_NODES, PARTIAL, NON_UNIFORM, NOT_TRANSLATION, UNALIGNED, UNKNOWN }
internal enum class ManualBoundsWindow { UNCHANGED, MATCHES_NODE_TRANSLATION, OTHER_CHANGE, UNAVAILABLE, UNKNOWN }
/** Fixed enum fields only: no node reference/index, class name, delta, coordinate or identifier. */
internal data class ManualBoundsCode(val node: ManualBoundsNode, val change: ManualBoundsChange,
    val common: ManualBoundsCommon, val window: ManualBoundsWindow) {
    companion object {
        val UNKNOWN = ManualBoundsCode(ManualBoundsNode.UNKNOWN, ManualBoundsChange.UNKNOWN,
            ManualBoundsCommon.UNKNOWN, ManualBoundsWindow.UNKNOWN)
    }
}
private fun positive(b: ScreenBounds) = b.right.toLong() > b.left.toLong() && b.bottom.toLong() > b.top.toLong()
private fun sameShift(a: ScreenBounds, b: ScreenBounds, dx: Long, dy: Long) =
    b.left.toLong() - a.left.toLong() == dx && b.right.toLong() - a.right.toLong() == dx &&
        b.top.toLong() - a.top.toLong() == dy && b.bottom.toLong() - a.bottom.toLong() == dy
private fun sameNonBounds(a: NodeSnapshot, b: NodeSnapshot) =
    a.path == b.path && a.viewId == b.viewId && a.className == b.className && a.text == b.text &&
        a.contentDescription == b.contentDescription && a.clickable == b.clickable && a.enabled == b.enabled && a.actions == b.actions

/** Uses only descriptors already read by the failed comparison. Long deltas are local, not stored
 * in the report. No rounding, offsets, scale inference, sorting, clock or platform calls. */
internal fun describeManualBoundsMismatch(first: NodeSnapshot, changed: NodeSnapshot,
    original: PopupSnapshot, fresh: F9Scene): ManualBoundsCode {
    val a = first.bounds; val b = changed.bounds
    val dx = b.left.toLong() - a.left.toLong(); val dy = b.top.toLong() - a.top.toLong()
    val valid = positive(a) && positive(b)
    val translation = valid && a != b && sameShift(a, b, dx, dy)
    val kind = when {
        first.path == "0" -> ManualBoundsNode.ROOT
        first.className in setOf("android.widget.Button", "android.widget.ImageButton") -> ManualBoundsNode.BUTTON
        first.className == "android.widget.TextView" -> ManualBoundsNode.TEXT
        first.className in setOf("android.widget.ScrollView", "android.widget.HorizontalScrollView") -> ManualBoundsNode.SCROLL_CONTAINER
        first.className in setOf("android.widget.LinearLayout", "android.widget.FrameLayout", "android.widget.RelativeLayout", "android.view.ViewGroup") -> ManualBoundsNode.CONTAINER
        first.className == "android.widget.ImageView" -> ManualBoundsNode.IMAGE
        first.className == "android.webkit.WebView" -> ManualBoundsNode.WEB
        first.className in setOf("android.view.SurfaceView", "android.view.TextureView", "android.widget.VideoView") -> ManualBoundsNode.SURFACE
        else -> ManualBoundsNode.OTHER
    }
    val shape = when {
        !valid -> ManualBoundsChange.INVALID_RECT
        a == b -> ManualBoundsChange.UNKNOWN
        translation && dy == 0L -> ManualBoundsChange.TRANSLATE_X
        translation && dx == 0L -> ManualBoundsChange.TRANSLATE_Y
        translation -> ManualBoundsChange.TRANSLATE_XY
        b.left >= a.left && b.top >= a.top && b.right <= a.right && b.bottom <= a.bottom -> ManualBoundsChange.CONTRACTED
        b.left <= a.left && b.top <= a.top && b.right >= a.right && b.bottom >= a.bottom -> ManualBoundsChange.EXPANDED
        else -> ManualBoundsChange.RESHAPED
    }
    val alignedCount = original.nodes.size == fresh.nodes.size && original.nodes.size in 1..1_200
    var aligned = alignedCount
    var allShift = alignedCount && translation
    var onlyShiftOrUnchanged = allShift
    var validNodes = alignedCount
    if (alignedCount) for (i in original.nodes.indices) {
        val old = original.nodes[i]; val new = fresh.nodes[i]
        if (!sameNonBounds(old, new)) aligned = false
        if (!positive(old.bounds) || !positive(new.bounds)) validNodes = false
        val shifted = sameShift(old.bounds, new.bounds, dx, dy)
        if (!shifted) allShift = false
        if (!shifted && old.bounds != new.bounds) onlyShiftOrUnchanged = false
    }
    val common = when {
        !aligned -> ManualBoundsCommon.UNALIGNED
        !validNodes || !valid -> ManualBoundsCommon.UNKNOWN
        !translation -> ManualBoundsCommon.NOT_TRANSLATION
        allShift -> ManualBoundsCommon.ALL_NODES
        onlyShiftOrUnchanged -> ManualBoundsCommon.PARTIAL
        else -> ManualBoundsCommon.NON_UNIFORM
    }
    val oldWindow = if (original.windows.size in 1..32) original.windows.singleOrNull { it.id == original.token.windowId } else null
    val newWindow = if (fresh.windows.size in 1..32) fresh.windows.singleOrNull { it.id == fresh.windowId } else null
    val relation = when {
        oldWindow == null || newWindow == null || original.token.windowId != fresh.windowId -> ManualBoundsWindow.UNAVAILABLE
        oldWindow.bounds == newWindow.bounds -> ManualBoundsWindow.UNCHANGED
        translation && positive(oldWindow.bounds) && positive(newWindow.bounds) && sameShift(oldWindow.bounds, newWindow.bounds, dx, dy) -> ManualBoundsWindow.MATCHES_NODE_TRANSLATION
        else -> ManualBoundsWindow.OTHER_CHANGE
    }
    return ManualBoundsCode(kind, shape, common, relation)
}
