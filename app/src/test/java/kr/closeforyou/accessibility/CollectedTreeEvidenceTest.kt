package kr.closeforyou.accessibility

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.NodeAction
import org.junit.Assert.*
import org.junit.Test

class CollectedTreeEvidenceTest {
    @Test fun reversedXRetainsOriginalComponents() {
        val rect = CollectedRect(41, 2, 3, 60)
        assertEquals(NodeRectShape.REVERSED_X, rect.shape)
        assertEquals(41, rect.left)
        assertEquals(3, rect.right)
        assertFalse(rect.dtoCompatible)
    }

    @Test fun reversedYRetainsOriginalComponents() {
        val rect = CollectedRect(1, 72, 30, 4)
        assertEquals(NodeRectShape.REVERSED_Y, rect.shape)
        assertEquals(72, rect.top)
        assertEquals(4, rect.bottom)
        assertFalse(rect.dtoCompatible)
    }

    @Test fun reversedBothRetainsOriginalComponents() {
        val rect = CollectedRect(41, 72, 3, 4)
        assertEquals(NodeRectShape.REVERSED_BOTH, rect.shape)
        assertEquals(listOf(41, 72, 3, 4), listOf(rect.left, rect.top, rect.right, rect.bottom))
        assertFalse(rect.dtoCompatible)
    }

    @Test fun visibleAndHiddenMalformedNodesAreBothUnusable() {
        val node = ProjectionFixture.node().copy(rect = CollectedRect(0, 20, 10, 1))
        assertEquals(GeometryCompletion.INCOMPLETE, node.copy(visible = true).geometry)
        assertEquals(GeometryCompletion.INCOMPLETE, node.copy(visible = false).geometry)
    }

    @Test fun protectionTraversalAndGeometryAreIndependent() {
        val fixture = ProjectionFixture.evidence()
        assertEquals(GeometryCompletion.COMPLETE, fixture.copy(
            protection = ProtectionCompletion.UNKNOWN, traversal = TreeCompletion.TIME_LIMIT).geometry)
        val malformed = fixture.copy(roots = persistentListOf(fixture.roots.single().copy(
            nodes = persistentListOf(ProjectionFixture.node().copy(rect = CollectedRect(2, 0, 1, 1))))))
        assertEquals(ProtectionCompletion.COMPLETED_WITHOUT_BLOCK, malformed.protection)
        assertEquals(TreeCompletion.COMPLETE, malformed.traversal)
        assertEquals(GeometryCompletion.INCOMPLETE, malformed.geometry)
    }

    @Test fun missingRectOrEmptyTreeCannotClaimGeometryComplete() {
        val fixture = ProjectionFixture.evidence()
        assertEquals(GeometryCompletion.UNKNOWN, fixture.copy(roots = persistentListOf()).geometry)
        assertEquals(GeometryCompletion.UNKNOWN, fixture.copy(roots = persistentListOf(
            fixture.roots.single().copy(nodes = persistentListOf()))).geometry)
        assertEquals(GeometryCompletion.UNKNOWN, ProjectionFixture.node().copy(rect = null).geometry)
    }

    @Test fun emptyOrderedAndNegativeNodeRectsStayVerbatimButOverflowIsUnusable() {
        val empty = CollectedRect(-20, -10, -20, -10)
        assertEquals(NodeRectShape.EMPTY_BOTH, empty.shape)
        assertTrue(empty.dtoCompatible)
        assertEquals(-20, empty.right)
        assertFalse(CollectedRect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 1).dtoCompatible)
    }

    @Test fun malformedParentDoesNotEraseChildrenIdentityOrSemantics() {
        val nodes = ProjectionFixture.dialogNodes()
        val parent = nodes.first().copy(rect = CollectedRect(9, 0, 2, 8))
        val evidence = ProjectionFixture.evidence(persistentListOf(parent, nodes[1]))
        assertEquals(2, evidence.roots.single().nodes.size)
        val child = evidence.roots.single().nodes[1]
        assertEquals("0/0", child.path)
        assertEquals("0", child.parentPath)
        assertEquals(0, child.siblingOrdinal)
        assertEquals(nodes[1].outlineFingerprint, child.outlineFingerprint)
        assertTrue(NodeAction.CLICK in child.actions)
        assertEquals(1, parent.childCount)
        assertEquals(GeometryCompletion.INCOMPLETE, evidence.geometry)
    }

    @Test fun allNewPrivateValueRepresentationsAreRedacted() {
        val node = ProjectionFixture.node().copy(path = "PRIVATE_PATH", parentPath = "PRIVATE_PARENT",
            viewId = "PRIVATE_ID", className = "PRIVATE_CLASS", text = "PRIVATE_BODY",
            contentDescription = "PRIVATE_DESCRIPTION", outlineFingerprint = "PRIVATE_FINGERPRINT",
            rect = CollectedRect(184763, 284763, 384763, 484763))
        val evidence = ProjectionFixture.evidence(persistentListOf(node))
        val metadata = ProjectionFixture.metadata()
        val values = listOf(node.rect, node, evidence.roots.single(), evidence, metadata,
            metadata.windows.single(), metadata.screenRect)
        values.forEach { assertTrue(it.toString().endsWith("(redacted)")) }
        val printable = values.joinToString()
        listOf("PRIVATE_", "184763", "284763", "384763", "484763", "0/0", "structure-secret",
            "content-secret", "application-secret").forEach { assertFalse(printable.contains(it)) }
    }
}
