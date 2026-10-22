package kr.closeforyou.servicetest

import org.junit.Assert.*
import org.junit.Test

class BaselineIsolationTest {
    private val ready = "1:ASSESSED:NO_POPUP:BASELINE_ONLY:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
    private val outside = "2:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:RIGHT,BOTTOM:WITHIN_ONE_SECOND"
    private val malformed = "3:BLOCKED:UNREADABLE_TREE:SNAPSHOT_PASS:TREE_PROTECTION:TARGET:NONE:MALFORMED_NODE_BOUNDS:NONE:VERIFIED:NODE_BOUNDS_VALIDATE:NONE:REVERSED_Y:NOT_VISIBLE:SCROLL_ANCESTOR:DESCENDANT:OLDER:CONTENT_CHANGED"
    private val missing = "4:BLOCKED:UNREADABLE_TREE:WINDOWS:TARGET_MISSING:NONE:NONE:WINDOW_GUARD:NONE:UNAVAILABLE:NONE:NONE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED:WINDOW_CHANGED"
    @Test fun `node rejection and independent F1 geometry are never conflated`() {
        val evidence = IsolationEvidence.from(listOf(outside, malformed), false)
        assertEquals(IsolationCollection.MIXED, evidence.collection)
        assertEquals(IsolationFrame.OUTSIDE_ONLY, evidence.frame)
        assertTrue(evidence.sawReady)
        assertTrue(evidence.sawMalformed)
        assertEquals(IsolationContrast.MALFORMED_ISOLATED_TO_SCROLL, isolationContrast(evidence, IsolationEvidence.from(listOf(ready), false)))
    }
    @Test fun `unobserved invalid overflow and other blocking reasons cannot establish a clean control`() {
        val scroll = IsolationEvidence.from(listOf(malformed), false)
        assertEquals(IsolationCollection.OTHER_BLOCK, IsolationEvidence.from(listOf(missing), false).collection)
        for (control in listOf(IsolationEvidence.from(emptyList(), false), IsolationEvidence.from(listOf("private"), false),
            IsolationEvidence.from(listOf(ready), true), IsolationEvidence.from(listOf(missing), false),
            IsolationEvidence.from(listOf(ready, missing), false))) {
            assertEquals(IsolationContrast.INCONCLUSIVE, isolationContrast(scroll, control))
        }
        assertEquals(IsolationContrast.MALFORMED_ON_BOTH, isolationContrast(scroll, scroll))
        assertEquals(IsolationFrame.MIXED, IsolationEvidence.from(listOf(ready, outside), false).frame)
    }
    @Test fun `malformed in an unchanged stable sample is never turned into ready`() {
        val evidence = IsolationEvidence.from(listOf(malformed), false)
        assertEquals(IsolationCollection.MALFORMED_ONLY, evidence.collection)
        assertEquals(IsolationFrame.UNOBSERVED, evidence.frame)
        assertFalse(evidence.sawReady)
        assertEquals(IsolationContrast.INCONCLUSIVE, isolationContrast(null, evidence))
    }
    @Test fun `raw rectangle relations preserve edge directions without tolerance or repair`() {
        val frame = IsolationRect(0, 0, 100, 200)
        assertEquals(RectRelation.EQUAL, frame.relation(frame))
        assertEquals(RectRelation.WITHIN, IsolationRect(1, 1, 99, 199).relation(frame))
        assertEquals(RectRelation.CONTAINS, IsolationRect(0, 0, 101, 201).relation(frame))
        assertEquals("RIGHT,BOTTOM", IsolationRect(0, 0, 101, 201).outside(frame))
        assertEquals(RectRelation.OVERLAPS, IsolationRect(-1, 0, 99, 199).relation(frame))
        assertEquals(RectRelation.DISJOINT, IsolationRect(101, 0, 200, 100).relation(frame))
        assertEquals(RectRelation.EMPTY_OR_REVERSED, IsolationRect(0, 9, 2, 1).relation(frame))
        assertEquals("IsolationRect(redacted)", frame.toString())
    }
    @Test fun `elapsed time and equal enum relations alone cannot establish stability`() {
        val gate = ObservedGeometryStability<IsolationRect>()
        val frame = IsolationRect(0, 0, 100, 200)
        val first = IsolationRect(0, 0, 101, 201)
        val changed = IsolationRect(0, 0, 102, 202)
        assertEquals(first.relation(frame), changed.relation(frame)) // Same code, different exact values.
        assertFalse(gate.observe(first, true, 0))
        assertFalse(gate.observe(first, true, 2_000)) // Two samples are insufficient.
        assertFalse(gate.observe(changed, true, 3_000)) // Exact movement resets the window.
        assertFalse(gate.observe(changed, true, 3_500))
        assertTrue(gate.observe(changed, true, 4_000)) // Observation only, despite invalid frame relation.
        assertFalse(gate.observe(changed, false, 5_000)) // Pending layout invalidates it.
        assertFalse(gate.observe(changed, true, 6_000))
    }
}
