package kr.closeforyou.accessibility

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic records only. These ceilings do not reproduce Android traversal timing/cancellation. */
class CollectionProjectionLimitsTest {
    private val metadata = ProjectionFixture.metadata()

    @Test fun retainedNodeCeilingDoesNotAssertTwoPassVisitBudget() {
        val result = project(forest(2400), backgroundMetadata())
        assertTrue(result is ProjectionOutcome.StructuralMatch)
        assertEquals(2400, (result as ProjectionOutcome.StructuralMatch).snapshot.nodes.size)
    }

    @Test fun targetRetainedNodeCeilingPlusOneDenies() =
        reject(forest(2401), ProjectionRejection.NODE_COUNT_LIMIT, backgroundMetadata())

    @Test fun nodeLimitCheckedBeforeLargeTopologyExpansion() = reject(
        ProjectionFixture.evidence(flat(1201).addAll(flat(1200))), ProjectionRejection.NODE_COUNT_LIMIT)

    @Test fun exactly32WindowsAndProtectionRootsAreAllowed() {
        val root = ProjectionFixture.evidence().roots.single()
        val roots = (0 until 32).map { root.copy(windowId = it,
            role = if (it == 7) EvidenceTreeRole.TARGET else EvidenceTreeRole.SAME_APP_BACKGROUND) }
        assertTrue(project(CollectedTreeEvidence(persistentSetOf<Int>().addAll((0 until 32).toList()),
            persistentListOf<ProtectionRootEvidence>().addAll(roots), ProjectionFixture.evidence().target,
            ProtectionCompletion.COMPLETED_WITHOUT_BLOCK, TreeCompletion.COMPLETE),
            metadata.copy(windows = persistentListOf<CollectedWindowEvidence>().addAll((0 until 32).map {
                metadata.windows[0].copy(id = it, active = it == 7)
            }))) is ProjectionOutcome.StructuralMatch)
    }

    @Test fun windowCount33Denies() = reject(ProjectionFixture.evidence(), ProjectionRejection.WINDOW_COUNT_LIMIT,
        metadata.copy(windows = persistentListOf<CollectedWindowEvidence>().addAll((0 until 33).map {
            metadata.windows[0].copy(id = it)
        })))

    @Test fun protectionRootRoster33DeniesBeforeMissingCoverageCheck() = reject(
        ProjectionFixture.evidence().copy(expectedProtectionWindowIds = persistentSetOf<Int>().addAll((0 until 33).toList())),
        ProjectionRejection.WINDOW_COUNT_LIMIT)

    @Test fun suppliedRoots33DenyBeforeScanningNodes() {
        val root = ProjectionFixture.evidence().roots.single()
        reject(ProjectionFixture.evidence().copy(roots = persistentListOf<ProtectionRootEvidence>().addAll(
            (0 until 33).map { root.copy(windowId = it) })), ProjectionRejection.WINDOW_COUNT_LIMIT)
    }

    @Test fun rootDepthZeroAndMaximumDepth60AreAllowed() {
        assertTrue(project(ProjectionFixture.evidence(chain(0))) is ProjectionOutcome.StructuralMatch)
        assertTrue(project(ProjectionFixture.evidence(chain(60))) is ProjectionOutcome.StructuralMatch)
    }

    @Test fun depth61DeniesEvenWithCompleteFlags() =
        reject(ProjectionFixture.evidence(chain(61)), ProjectionRejection.DEPTH_LIMIT)

    @Test fun childCount1200AndFourDigitOrdinalArePreserved() {
        val result = project(ProjectionFixture.evidence(flat(1201))) as ProjectionOutcome.StructuralMatch
        assertEquals("0/1199", result.snapshot.nodes.last().path)
        assertEquals(1200, result.frame.outlines.first().childCount)
    }

    @Test fun childCount1201DeniesBeforeTopologyExpansion() = reject(
        ProjectionFixture.evidence(flat(1201).set(0, ProjectionFixture.node().copy(childCount = 1201))),
        ProjectionRejection.CHILD_COUNT_LIMIT)

    @Test fun longPathDeniesWithoutCreatingExpectedChildPaths() = reject(
        ProjectionFixture.evidence(persistentListOf(ProjectionFixture.node().copy(
            path = "x".repeat(CollectionProjectionLimits.MAX_PATH_CHARACTERS + 1)))), ProjectionRejection.PATH_LENGTH_LIMIT)

    @Test fun longParentPathDenies() = reject(
        ProjectionFixture.evidence(persistentListOf(ProjectionFixture.node().copy(
            parentPath = "x".repeat(CollectionProjectionLimits.MAX_PATH_CHARACTERS + 1)))), ProjectionRejection.PATH_LENGTH_LIMIT)

    @Test fun nonCanonicalChildPathsDeny() {
        for (path in listOf("0/00", "0/+0", "0/-0", "0/0/0", "0/", "0x0", "0/１２", "0/10000")) {
            reject(ProjectionFixture.evidence(flat(2).set(1, flat(2)[1].copy(path = path))),
                ProjectionRejection.TOPOLOGY_INCOMPLETE)
        }
    }

    @Test fun branchCompletionRestoresCorrectParentAndOrdinal() {
        val nodes = flat(3)
        val child = nodes[1].copy(childCount = 1)
        val grandchild = nodes[1].copy(path = "0/0/0", parentPath = "0/0")
        val input = persistentListOf(nodes[0], child, grandchild, nodes[2])
        val result = project(ProjectionFixture.evidence(input)) as ProjectionOutcome.StructuralMatch
        assertEquals(input.map { it.path }, result.snapshot.nodes.map { it.path })
        assertEquals(input.map { it.childCount }, result.frame.outlines.map { it.childCount })
    }

    @Test fun missingFinalGrandchildStillDenies() = reject(
        ProjectionFixture.evidence(chain(2).removeAt(2)), ProjectionRejection.TOPOLOGY_INCOMPLETE)

    @Test fun reorderedSiblingsStillDeny() {
        val input = flat(3)
        reject(ProjectionFixture.evidence(persistentListOf(input[0], input[2], input[1])),
            ProjectionRejection.TOPOLOGY_INCOMPLETE)
    }

    @Test fun fourRetainedProtectedFieldsAllow4096CharactersEach() {
        val value = "합".repeat(4096)
        val node = ProjectionFixture.node().copy(text = value, contentDescription = value, viewId = value, className = value)
        val result = project(ProjectionFixture.evidence(persistentListOf(node))) as ProjectionOutcome.StructuralMatch
        assertSame(value, result.snapshot.nodes[0].text)
        assertSame(value, result.snapshot.nodes[0].viewId)
        assertSame(value, result.snapshot.nodes[0].className)
        assertSame(value, result.snapshot.nodes[0].contentDescription)
    }

    @Test fun eachRetainedProtectedFieldRejects4097Characters() {
        val value = "합".repeat(4097)
        val node = ProjectionFixture.node()
        for (modified in listOf(node.copy(text = value), node.copy(contentDescription = value),
            node.copy(viewId = value), node.copy(className = value)))
            reject(ProjectionFixture.evidence(persistentListOf(modified)), ProjectionRejection.FIELD_LENGTH_LIMIT)
    }

    @Test fun incompleteBackgroundProtectionStillDeniesWithoutNodePayload() {
        val evidence = forest(1)
        val background = evidence.roots[1].copy(protection = ProtectionCompletion.INCOMPLETE)
        reject(evidence.copy(roots = evidence.roots.toPersistentList().set(1, background)),
            ProjectionRejection.PROTECTION_NOT_COMPLETE, backgroundMetadata())
    }

    @Test fun targetTextAndDescriptionAllow32768Characters() {
        val value = "합".repeat(4096)
        val input = flat(4).map { it.copy(text = value, contentDescription = value) }
        assertTrue(project(ProjectionFixture.evidence(persistentListOf<CollectedNodeEvidence>().addAll(input)))
            is ProjectionOutcome.StructuralMatch)
    }

    @Test fun targetCombinedText32769Denies() {
        val value = "합".repeat(4096)
        val input = flat(5).mapIndexed { i, node -> node.copy(text = if (i < 4) value else "합",
            contentDescription = if (i < 4) value else null) }
        reject(ProjectionFixture.evidence(persistentListOf<CollectedNodeEvidence>().addAll(input)),
            ProjectionRejection.TEXT_LENGTH_LIMIT)
    }

    @Test fun backgroundRequiresNoInventedBodyGeometryActionsOrOutline() {
        val evidence = forest(1)
        assertTrue(project(evidence, backgroundMetadata()) is ProjectionOutcome.StructuralMatch)
        assertEquals(setOf("windowId", "role", "protection", "traversal"),
            ProtectionRootEvidence::class.java.declaredFields.filterNot {
                java.lang.reflect.Modifier.isStatic(it.modifiers)
            }.map { it.name }.toSet())
        // Only window/role/protection/traversal observations; no background node data is supplied.
    }

    @Test fun nullAndEmptyFieldsRemainDistinct() {
        val node = ProjectionFixture.node().copy(text = null, contentDescription = "", viewId = "", className = null)
        val result = project(ProjectionFixture.evidence(persistentListOf(node))) as ProjectionOutcome.StructuralMatch
        assertNull(result.snapshot.nodes[0].text)
        assertEquals("", result.snapshot.nodes[0].contentDescription)
        assertEquals("", result.snapshot.nodes[0].viewId)
        assertNull(result.snapshot.nodes[0].className)
    }

    @Test fun allNormalFixtureDtoFieldsRemainExactlyEqual() {
        val input = ProjectionFixture.dialogNodes()
        val result = project(ProjectionFixture.evidence(input)) as ProjectionOutcome.StructuralMatch
        val expectedNodes = input.map { n -> NodeSnapshot(n.path, n.viewId, n.className, n.text,
            n.contentDescription, n.clickable, n.enabled, n.rect!!.let { ScreenBounds(it.left, it.top, it.right, it.bottom) }, n.actions) }
        assertEquals(PopupSnapshot(metadata.token, metadata.capturedAtUptimeMillis,
            persistentListOf<NodeSnapshot>().addAll(expectedNodes), persistentListOf(
                WindowSnapshot(7, WindowType.APPLICATION, ScreenBounds(0, 0, 500, 900), true, true, 1)),
            ScreenBounds(0, 0, 500, 900), metadata.trigger, SnapshotCompleteness.COMPLETE), result.snapshot)
        assertEquals(DetectionFrame(metadata.applicationDigest, 7, setOf(7), metadata.token.structureDigest,
            result.snapshot.screenBounds, input.mapIndexed { i, n -> DetectionOutline(n.outlineFingerprint,
                expectedNodes[i].bounds, n.childCount, i == 0 || NodeAction.DISMISS in n.actions, n.path,
                n.pane, n.visible, n.scrollable) }, metadata.trigger, result.snapshot.windows[0].bounds,
            metadata.windowAge), result.frame)
    }

    @Test fun geometryAggregationKeepsIncompletePriorityWithoutFlattening() {
        val node = ProjectionFixture.node()
        val e = ProjectionFixture.evidence()
        val target = e.target.copy(nodes = persistentListOf(node.copy(rect = null),
            node.copy(rect = CollectedRect(2, 0, 1, 2))))
        assertEquals(GeometryCompletion.INCOMPLETE, e.copy(target = target).geometry)
        assertEquals(GeometryCompletion.UNKNOWN, e.copy(target = target.copy(nodes = persistentListOf())).geometry)
    }

    @Test fun projectionAndValueStringsDoNotLogOrThrowSensitiveInput() {
        val node = ProjectionFixture.node().copy(text = "PRIVATE_BODY", contentDescription = "PRIVATE_DESCRIPTION",
            viewId = "PRIVATE_ID", className = "PRIVATE_CLASS", outlineFingerprint = "PRIVATE_OUTLINE",
            rect = CollectedRect(12347, 22347, 32347, 42347))
        val evidence = ProjectionFixture.evidence(persistentListOf(node))
        val secretMetadata = metadata.copy(applicationDigest = "PRIVATE_APPLICATION",
            token = metadata.token.copy(structureDigest = "PRIVATE_STRUCTURE", contentDigest = "PRIVATE_CONTENT"))
        val output = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        val stream = PrintStream(output)
        try {
            System.setOut(stream)
            System.setErr(stream)
            val complete = project(evidence, secretMetadata)
            val oversizedRoot = evidence.target.copy(nodes = persistentListOf(node.copy(text = "PRIVATE_BODY".repeat(500))))
            val rejected = project(evidence.copy(target = oversizedRoot), secretMetadata)
            assertTrue(complete is ProjectionOutcome.StructuralMatch)
            assertEquals(ProjectionOutcome.Rejected(ProjectionRejection.FIELD_LENGTH_LIMIT), rejected)
            val printable = listOf(node.rect, node, evidence.roots[0], evidence, secretMetadata,
                secretMetadata.windows[0], complete, rejected).joinToString()
            assertFalse(printable.contains("PRIVATE_"))
            assertFalse(printable.contains("12347"))
            assertFalse(printable.contains("22347"))
            assertEquals("", output.toString())
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            stream.close()
        }
    }

    private fun project(e: CollectedTreeEvidence, m: CollectionProjectionMetadata = metadata) = CollectionProjection.project(e, m)
    private fun reject(e: CollectedTreeEvidence, reason: ProjectionRejection, m: CollectionProjectionMetadata = metadata) {
        assertEquals(ProjectionOutcome.Rejected(reason), project(e, m))
    }
    private fun flat(count: Int): PersistentList<CollectedNodeEvidence> = persistentListOf<CollectedNodeEvidence>().addAll(
        (0 until count).map { i -> ProjectionFixture.node().copy(path = if (i == 0) "0" else "0/${i - 1}",
            parentPath = if (i == 0) null else "0", siblingOrdinal = if (i == 0) 0 else i - 1,
            childCount = if (i == 0) count - 1 else 0, text = null, contentDescription = null) })
    private fun chain(depth: Int): PersistentList<CollectedNodeEvidence> {
        var path = "0"
        val nodes = persistentListOf<CollectedNodeEvidence>().builder()
        for (i in 0..depth) {
            val parent = if (i == 0) null else path
            if (i > 0) path += "/0"
            nodes.add(ProjectionFixture.node().copy(path = path, parentPath = parent,
                childCount = if (i == depth) 0 else 1))
        }
        return nodes.build()
    }
    private fun forest(target: Int): CollectedTreeEvidence {
        // A bounded two-level target tree reaches 2400 without violating 1200 children/depth60.
        val nodes = if (target <= 1201) flat(target) else {
            val first = flat(1201)
            val last = first.last().copy(childCount = target - 1201)
            first.set(first.lastIndex, last).addAll((0 until target - 1201).map { i ->
                ProjectionFixture.node().copy(path = "0/1199/$i", parentPath = "0/1199", siblingOrdinal = i,
                    text = null, contentDescription = null)
            })
        }
        val e = ProjectionFixture.evidence(nodes)
        return e.copy(expectedProtectionWindowIds = persistentSetOf(7, 8), roots = persistentListOf(
            e.roots[0], e.roots[0].copy(windowId = 8, role = EvidenceTreeRole.SAME_APP_BACKGROUND)))
    }
    private fun backgroundMetadata() = metadata.copy(windows = persistentListOf(
        metadata.windows[0], metadata.windows[0].copy(id = 8, active = false)))
}
