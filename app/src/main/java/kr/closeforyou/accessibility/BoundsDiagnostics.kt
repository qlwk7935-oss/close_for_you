package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason
import kr.closeforyou.core.model.ScreenBounds

/** Only relations leave the collector. No Rect, coordinate, path, text or node is retained here. */
internal enum class NodeRectShape { UNOBSERVED, ORDERED, EMPTY_X, EMPTY_Y, EMPTY_BOTH, REVERSED_X, REVERSED_Y, REVERSED_BOTH }
internal enum class NodeVisibility { UNOBSERVED, VISIBLE, NOT_VISIBLE, READ_FAILED }
internal enum class NodeAncestry { UNOBSERVED, SCROLL_ANCESTOR, NO_SCROLL_ANCESTOR }
internal enum class NodeRole { UNOBSERVED, ROOT, DESCENDANT }
internal data class NodeBoundsDiagnostic(val shape: NodeRectShape = NodeRectShape.UNOBSERVED,
    val visibility: NodeVisibility = NodeVisibility.UNOBSERVED, val ancestry: NodeAncestry = NodeAncestry.UNOBSERVED,
    val role: NodeRole = NodeRole.UNOBSERVED) {
    fun encoded() = listOf(shape.name, visibility.name, ancestry.name, role.name).joinToString(":")
}
internal object NodeBoundsPolicy {
    fun shape(left: Int, top: Int, right: Int, bottom: Int): NodeRectShape = when {
        right < left && bottom < top -> NodeRectShape.REVERSED_BOTH
        right < left -> NodeRectShape.REVERSED_X
        bottom < top -> NodeRectShape.REVERSED_Y
        right == left && bottom == top -> NodeRectShape.EMPTY_BOTH
        right == left -> NodeRectShape.EMPTY_X
        bottom == top -> NodeRectShape.EMPTY_Y
        else -> NodeRectShape.ORDERED
    }
    /** Called after the whole protection pass and repeated node inspect. Never repair or skip a subtree. */
    fun convert(left: Int, top: Int, right: Int, bottom: Int,
        describe: (NodeRectShape) -> NodeBoundsDiagnostic): ScreenBounds {
        val shape = shape(left, top, right, bottom)
        if (right < left || bottom < top) {
            throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE,
                finding = CollectionFinding.MALFORMED_NODE_BOUNDS, nodeBounds = describe(shape))
        }
        return ScreenBounds(left, top, right, bottom)
    }
}

/** Public window transition time means last bounds change, NOT proof that animation is running. */
internal enum class WindowBoundsAge { UNOBSERVED, UNSUPPORTED, UNKNOWN, WITHIN_ONE_SECOND, OLDER, READ_FAILED }
internal object WindowBoundsTiming {
    fun classify(changedAt: Long, now: Long): WindowBoundsAge = when {
        changedAt <= 0 || changedAt > now -> WindowBoundsAge.UNKNOWN
        now - changedAt <= 1_000 -> WindowBoundsAge.WITHIN_ONE_SECOND
        else -> WindowBoundsAge.OLDER
    }
}
internal enum class DetectionGeometry { VALID, NONPOSITIVE_FRAME, WINDOW_OUTSIDE_FRAME, NONPOSITIVE_WINDOW }
internal enum class BoundsExtent { POSITIVE, NONPOSITIVE_WIDTH, NONPOSITIVE_HEIGHT, NONPOSITIVE_BOTH }
internal enum class WindowFrameRelation { WITHIN_FRAME, WINDOW_OUTSIDE_FRAME }
internal data class DetectionGeometryDiagnostic(val result: DetectionGeometry, val frame: BoundsExtent,
    val window: BoundsExtent, val relation: WindowFrameRelation, val outside: Set<OutsideEdge>) {
    fun encoded() = listOf(result.name, frame.name, window.name, relation.name,
        outside.sortedBy { it.ordinal }.joinToString(",") { it.name }.ifEmpty { "NONE" }).joinToString(":")
}
internal object DetectionGeometryCheck {
    private fun extent(bounds: ScreenBounds): BoundsExtent = when {
        bounds.width <= 0 && bounds.height <= 0 -> BoundsExtent.NONPOSITIVE_BOTH
        bounds.width <= 0 -> BoundsExtent.NONPOSITIVE_WIDTH
        bounds.height <= 0 -> BoundsExtent.NONPOSITIVE_HEIGHT
        else -> BoundsExtent.POSITIVE
    }
    fun inspect(frame: ScreenBounds, window: ScreenBounds): DetectionGeometryDiagnostic {
        val frameExtent = extent(frame)
        val windowExtent = extent(window)
        val outside = ScreenFramePolicy.outside(window, frame)
        val relation = if (outside.isEmpty()) WindowFrameRelation.WITHIN_FRAME else WindowFrameRelation.WINDOW_OUTSIDE_FRAME
        // Preserve exactly the previous predicate and short-circuit priority, including Int extent semantics.
        val result = when {
            frameExtent != BoundsExtent.POSITIVE -> DetectionGeometry.NONPOSITIVE_FRAME
            outside.isNotEmpty() -> DetectionGeometry.WINDOW_OUTSIDE_FRAME
            windowExtent != BoundsExtent.POSITIVE -> DetectionGeometry.NONPOSITIVE_WINDOW
            else -> DetectionGeometry.VALID
        }
        return DetectionGeometryDiagnostic(result, frameExtent, windowExtent, relation, outside)
    }
}
