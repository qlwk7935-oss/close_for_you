package kr.closeforyou.accessibility

import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

class BoundsDiagnosticsTest {
    @Test fun `each inverted axis is rejected unchanged even when invisible under a scroller`() {
        for ((rect, expected) in listOf(
            listOf(9, 0, 2, 8) to NodeRectShape.REVERSED_X,
            listOf(0, 9, 8, 2) to NodeRectShape.REVERSED_Y,
            listOf(9, 9, 2, 2) to NodeRectShape.REVERSED_BOTH,
        )) for (visibility in listOf(NodeVisibility.VISIBLE, NodeVisibility.NOT_VISIBLE, NodeVisibility.READ_FAILED)) {
            val failure = assertThrows(CollectionGuardFailure::class.java) {
                NodeBoundsPolicy.convert(rect[0], rect[1], rect[2], rect[3]) {
                    NodeBoundsDiagnostic(it, visibility, NodeAncestry.SCROLL_ANCESTOR, NodeRole.DESCENDANT)
                }
            }
            assertEquals(PrivacyBlockReason.UNREADABLE_TREE, failure.reason)
            assertEquals(CollectionFinding.MALFORMED_NODE_BOUNDS, failure.finding)
            assertEquals(expected, failure.nodeBounds?.shape)
            assertEquals(visibility, failure.nodeBounds?.visibility)
            assertNull(failure.message)
        }
    }
    @Test fun `ordered and empty rectangles keep exact original coordinates without invoking failure diagnostics`() {
        for (rect in listOf(ScreenBounds(-9, -8, -2, -1), ScreenBounds(1, 2, 9, 8),
            ScreenBounds(1, 2, 1, 8), ScreenBounds(1, 2, 9, 2), ScreenBounds(1, 2, 1, 2))) {
            assertEquals(rect, NodeBoundsPolicy.convert(rect.left, rect.top, rect.right, rect.bottom) { error("UNEXPECTED_CONTEXT_READ") })
        }
        assertEquals(NodeRectShape.EMPTY_X, NodeBoundsPolicy.shape(1, 2, 1, 8))
        assertEquals(NodeRectShape.EMPTY_Y, NodeBoundsPolicy.shape(1, 2, 9, 2))
        assertEquals(NodeRectShape.EMPTY_BOTH, NodeBoundsPolicy.shape(1, 2, 1, 2))
    }
    @Test fun `AOSP parent clamp reproduces reversed scroll child without changing stored coordinates`() {
        // Scalar reproduction of View.mapRectFromViewToWindowCoords(clipToParent=true), not a device run.
        val childTop = 200
        val childBottom = 230
        val viewportHeight = 100
        val clippedTop = maxOf(childTop, 0)
        val clippedBottom = minOf(childBottom, viewportHeight)
        assertEquals(NodeRectShape.REVERSED_Y, NodeBoundsPolicy.shape(0, clippedTop, 30, clippedBottom))
        assertThrows(CollectionGuardFailure::class.java) {
            NodeBoundsPolicy.convert(0, clippedTop, 30, clippedBottom) { NodeBoundsDiagnostic(it) }
        }
    }
    @Test fun `geometry diagnostics preserve old rejection predicate and all boundary directions`() {
        val rectangles = listOf(ScreenBounds(0, 0, 100, 200), ScreenBounds(10, 20, 90, 180),
            ScreenBounds(-1, 0, 100, 200), ScreenBounds(0, -1, 100, 200), ScreenBounds(0, 0, 101, 200),
            ScreenBounds(0, 0, 100, 201), ScreenBounds(0, 0, 0, 200), ScreenBounds(0, 0, 100, 0),
            ScreenBounds(0, 0, 0, 0), ScreenBounds(Int.MIN_VALUE, 0, Int.MAX_VALUE, 200))
        for (screen in rectangles) for (window in rectangles) {
            val previousReject = screen.width <= 0 || screen.height <= 0 ||
                !(window.left >= screen.left && window.top >= screen.top && window.right <= screen.right && window.bottom <= screen.bottom) ||
                window.width <= 0 || window.height <= 0
            val diagnostic = DetectionGeometryCheck.inspect(screen, window)
            assertEquals(previousReject, diagnostic.result != DetectionGeometry.VALID)
        }
        val screen = rectangles[0]
        for ((window, edge) in listOf(rectangles[2] to OutsideEdge.LEFT, rectangles[3] to OutsideEdge.TOP,
            rectangles[4] to OutsideEdge.RIGHT, rectangles[5] to OutsideEdge.BOTTOM)) {
            val diagnostic = DetectionGeometryCheck.inspect(screen, window)
            assertEquals(DetectionGeometry.WINDOW_OUTSIDE_FRAME, diagnostic.result)
            assertEquals(setOf(edge), diagnostic.outside)
        }
    }
    @Test fun `nonpositive frame and window diagnostics retain original priority and discard baseline`() {
        val screen = ScreenBounds(0, 0, 100, 200)
        assertEquals(DetectionGeometry.NONPOSITIVE_FRAME, DetectionGeometryCheck.inspect(ScreenBounds(0, 0, 0, 0), screen).result)
        assertEquals(DetectionGeometry.NONPOSITIVE_WINDOW, DetectionGeometryCheck.inspect(screen, ScreenBounds(0, 0, 0, 100)).result)
        assertEquals(BoundsExtent.NONPOSITIVE_HEIGHT, DetectionGeometryCheck.inspect(screen, ScreenBounds(0, 0, 50, 0)).window)
        val detector = PopupCandidateDetector()
        fun frame(window: ScreenBounds) = DetectionFrame("synthetic", 1, setOf(1), "fixed", screen, emptyList(), PopupTrigger.CONTENT_CHANGED, window)
        detector.assess(frame(screen))
        val invalid = detector.assess(frame(ScreenBounds(-1, 0, 100, 200)))
        assertEquals(DetectionReason.INVALID_GEOMETRY, invalid.reason)
        assertEquals(DetectionGeometry.WINDOW_OUTSIDE_FRAME, invalid.geometry?.result)
        assertEquals(DetectionReason.BASELINE_ONLY, detector.assess(frame(screen)).reason)
    }
    @Test fun `window bounds age buckets are observational and never authorize candidates`() {
        assertEquals(WindowBoundsAge.UNKNOWN, WindowBoundsTiming.classify(0, 2_000))
        assertEquals(WindowBoundsAge.UNKNOWN, WindowBoundsTiming.classify(3_000, 2_000))
        assertEquals(WindowBoundsAge.WITHIN_ONE_SECOND, WindowBoundsTiming.classify(1_000, 2_000))
        assertEquals(WindowBoundsAge.OLDER, WindowBoundsTiming.classify(999, 2_000))
        val screen = ScreenBounds(0, 0, 100, 200)
        for (age in WindowBoundsAge.entries) {
            val frame = DetectionFrame("synthetic", 1, setOf(1), "fixed", screen, emptyList(), PopupTrigger.WINDOW_CHANGED,
                ScreenBounds(-1, 0, 100, 200), age)
            val assessment = PopupCandidateDetector().assess(frame)
            assertEquals(DetectionDisposition.UNCERTAIN, assessment.disposition)
            assertEquals(age, assessment.windowAge)
        }
    }
}
