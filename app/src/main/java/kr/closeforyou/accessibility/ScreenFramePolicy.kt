package kr.closeforyou.accessibility

import kr.closeforyou.core.model.ScreenBounds

/** Validation only: never expands, intersects, offsets, scales or otherwise repairs coordinates. */
internal enum class ScreenFrameStatus { UNAVAILABLE, VERIFIED, EMPTY, OFFSET, SIZE_MISMATCH, DISPLAY_CHANGED }
internal enum class FrameRelation { UNKNOWN, UNAVAILABLE, EMPTY, SAME, DIFFERENT }
internal enum class OutsideEdge { LEFT, TOP, RIGHT, BOTTOM }
internal enum class SyntheticWindowRole { UNKNOWN, SYNTHETIC_DIALOG, SYNTHETIC_ACTIVITY }

internal object ScreenFramePolicy {
    fun validate(frame: ScreenBounds, logicalWidth: Int, logicalHeight: Int): ScreenFrameStatus = when {
        frame.width <= 0 || frame.height <= 0 || logicalWidth <= 0 || logicalHeight <= 0 -> ScreenFrameStatus.EMPTY
        frame.left != 0 || frame.top != 0 -> ScreenFrameStatus.OFFSET
        frame.width != logicalWidth || frame.height != logicalHeight -> ScreenFrameStatus.SIZE_MISMATCH
        else -> ScreenFrameStatus.VERIFIED
    }
    fun compare(old: ScreenBounds?, frame: ScreenBounds): FrameRelation = when {
        old == null -> FrameRelation.UNAVAILABLE
        old.width <= 0 || old.height <= 0 -> FrameRelation.EMPTY
        old == frame -> FrameRelation.SAME
        else -> FrameRelation.DIFFERENT
    }
    fun outside(inner: ScreenBounds, outer: ScreenBounds): Set<OutsideEdge> = buildSet {
        if (inner.left < outer.left) add(OutsideEdge.LEFT)
        if (inner.top < outer.top) add(OutsideEdge.TOP)
        if (inner.right > outer.right) add(OutsideEdge.RIGHT)
        if (inner.bottom > outer.bottom) add(OutsideEdge.BOTTOM)
    }
    // Fixed synthetic titles are diagnostics only. Neither role grants capture authorization.
    fun syntheticRole(title: CharSequence?): SyntheticWindowRole = when (title?.toString()) {
        "합성 이미지 창" -> SyntheticWindowRole.SYNTHETIC_DIALOG
        "합성 배경 창" -> SyntheticWindowRole.SYNTHETIC_ACTIVITY
        else -> SyntheticWindowRole.UNKNOWN
    }
}
