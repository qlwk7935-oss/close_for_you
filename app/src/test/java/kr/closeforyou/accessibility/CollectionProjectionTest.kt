package kr.closeforyou.accessibility

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

class CollectionProjectionTest {
    @Test fun completeSyntheticTreePreservesSnapshotAndFrameFields() {
        val evidence = ProjectionFixture.evidence()
        val metadata = ProjectionFixture.metadata()
        val result = complete(evidence, metadata)
        val actual = result.snapshot
        val original = evidence.roots.single().nodes.single()
        assertSame(metadata.token, actual.token)
        assertEquals(79L, actual.capturedAtUptimeMillis)
        assertEquals(SnapshotCompleteness.COMPLETE, actual.completeness)
        assertEquals(NodeSnapshot(original.path, original.viewId, original.className, original.text,
            original.contentDescription, original.clickable, original.enabled,
            ScreenBounds(0, 0, 500, 900), original.actions), actual.nodes.single())
        assertEquals(metadata.token.structureDigest, result.frame.structureDigest)
        assertEquals(metadata.token.contentDigest, actual.token.contentDigest)
        assertEquals(metadata.applicationDigest, result.frame.applicationDigest)
        assertEquals(metadata.trigger, result.frame.trigger)
        assertEquals(metadata.windowAge, result.frame.windowAge)
        assertEquals(setOf(7), result.frame.applicationWindowIds)
        assertEquals(actual.screenBounds, result.frame.screenBounds)
        assertEquals(actual.windows.single().bounds, result.frame.windowBounds)
        assertEquals(original.outlineFingerprint, result.frame.outlines.single().fingerprint)
    }

    @Test fun validDialogPreservesEveryHiddenNodeActionAndOutline() {
        val nodes = ProjectionFixture.dialogNodes()
        val result = complete(ProjectionFixture.evidence(nodes))
        assertEquals(listOf("0", "0/0"), result.snapshot.nodes.map { it.path })
        assertEquals(2, result.frame.outlines.size)
        assertTrue(result.frame.outlines[0].semanticOverlay)
        assertEquals(1, result.frame.outlines[0].childCount)
        assertFalse(result.frame.outlines[1].visible)
        assertTrue(result.frame.outlines[1].pane)
        assertTrue(result.frame.outlines[1].scrollable)
        assertEquals(nodes[1].actions, result.snapshot.nodes[1].actions)
        assertEquals(nodes[1].text, result.snapshot.nodes[1].text)
        assertEquals(nodes[1].outlineFingerprint, result.frame.outlines[1].fingerprint)
    }

    @Test fun everyInvertedAxisIsRejectedWithoutThrowingOrRepairing() {
        val rects = listOf(CollectedRect(2, 0, 1, 4), CollectedRect(0, 4, 2, 1), CollectedRect(2, 4, 1, 1))
        rects.forEach { rect ->
            listOf(false, true).forEach { visible ->
                val node = ProjectionFixture.node().copy(rect = rect, visible = visible)
                reject(ProjectionFixture.evidence(persistentListOf(node)), ProjectionRejection.GEOMETRY_NOT_COMPLETE)
                assertSame(rect, node.rect)
            }
        }
    }

    @Test fun unknownProtectionDeniesEvenWhenGeometryIsComplete() =
        reject(ProjectionFixture.evidence().copy(protection = ProtectionCompletion.UNKNOWN),
            ProjectionRejection.PROTECTION_NOT_COMPLETE)

    @Test fun incompleteProtectionDeniesEvenWhenGeometryIsComplete() =
        reject(ProjectionFixture.evidence().copy(protection = ProtectionCompletion.INCOMPLETE),
            ProjectionRejection.PROTECTION_NOT_COMPLETE)

    @Test fun blockedProtectionDeniesEvenWhenGeometryIsComplete() =
        reject(ProjectionFixture.evidence().copy(protection = ProtectionCompletion.BLOCKED),
            ProjectionRejection.PROTECTION_NOT_COMPLETE)

    @Test fun hiddenProtectedChildCannotBeOverriddenByGlobalCompletion() {
        val nodes = ProjectionFixture.dialogNodes()
        reject(ProjectionFixture.evidence(nodes.set(1, nodes[1].copy(protection = ProtectionCompletion.BLOCKED))),
            ProjectionRejection.PROTECTION_NOT_COMPLETE)
    }

    @Test fun unknownHiddenChildProtectionDeniesBeforeMalformedGeometry() {
        val node = ProjectionFixture.node().copy(visible = false, rect = CollectedRect(2, 0, 1, 2),
            protection = ProtectionCompletion.UNKNOWN)
        reject(ProjectionFixture.evidence(persistentListOf(node)), ProjectionRejection.PROTECTION_NOT_COMPLETE)
    }

    @Test fun completedProtectionCannotOverrideMissingGeometry() {
        reject(ProjectionFixture.evidence(persistentListOf(ProjectionFixture.node().copy(rect = null))),
            ProjectionRejection.GEOMETRY_NOT_COMPLETE)
    }

    @Test fun normalDialogMixedWithMalformedNodeDeniesEntireProjection() {
        val nodes = ProjectionFixture.dialogNodes()
        reject(ProjectionFixture.evidence(nodes.set(1, nodes[1].copy(rect = CollectedRect(2, 4, 1, 1)))),
            ProjectionRejection.GEOMETRY_NOT_COMPLETE)
    }

    @Test fun malformedParentWithValidChildDeniesEntireProjection() {
        val nodes = ProjectionFixture.dialogNodes()
        reject(ProjectionFixture.evidence(nodes.set(0, nodes[0].copy(rect = CollectedRect(2, 4, 1, 1)))),
            ProjectionRejection.GEOMETRY_NOT_COMPLETE)
    }

    @Test fun midNodeFailureCannotBePromotedByCompleteTraversalFlag() {
        val nodes = ProjectionFixture.dialogNodes()
        reject(ProjectionFixture.evidence(nodes.set(1, nodes[1].copy(readCompletion = NodeReadCompletion.FAILED))),
            ProjectionRejection.NODE_READ_INCOMPLETE)
    }

    @Test fun unknownNodeReadIsNotComplete() = reject(ProjectionFixture.evidence(persistentListOf(
        ProjectionFixture.node().copy(readCompletion = NodeReadCompletion.UNKNOWN))),
        ProjectionRejection.NODE_READ_INCOMPLETE)

    @Test fun unknownTraversalIsNotComplete() = reject(
        ProjectionFixture.evidence().copy(traversal = TreeCompletion.UNKNOWN), ProjectionRejection.TREE_NOT_COMPLETE)

    @Test fun missingChildTraversalIsNotComplete() = reject(
        ProjectionFixture.evidence().copy(traversal = TreeCompletion.MISSING_CHILD), ProjectionRejection.TREE_NOT_COMPLETE)

    @Test fun timeLimitCannotBePromotedWithValidNodes() = reject(
        ProjectionFixture.evidence().copy(traversal = TreeCompletion.TIME_LIMIT), ProjectionRejection.TREE_NOT_COMPLETE)

    @Test fun allOtherTraversalFailureCodesDeny() {
        listOf(TreeCompletion.NODE_FAILURE, TreeCompletion.NODE_LIMIT, TreeCompletion.DEPTH_LIMIT,
            TreeCompletion.CHILD_LIMIT, TreeCompletion.CANCELLED).forEach {
            reject(ProjectionFixture.evidence().copy(traversal = it), ProjectionRejection.TREE_NOT_COMPLETE)
        }
    }

    @Test fun rootFailureCannotBeOverriddenByGlobalCompletion() {
        val evidence = ProjectionFixture.evidence()
        reject(evidence.copy(roots = persistentListOf(evidence.roots.single().copy(traversal = TreeCompletion.TIME_LIMIT))),
            ProjectionRejection.TREE_NOT_COMPLETE)
        reject(evidence.copy(roots = persistentListOf(evidence.roots.single().copy(protection = ProtectionCompletion.UNKNOWN))),
            ProjectionRejection.PROTECTION_NOT_COMPLETE)
    }

    @Test fun backgroundProtectionMustBeCompleteEvenWithCompleteTarget() {
        val evidence = withBackground()
        val background = evidence.roots[1].copy(nodes = persistentListOf(
            ProjectionFixture.node().copy(visible = false, protection = ProtectionCompletion.BLOCKED)))
        reject(evidence.copy(roots = evidence.roots.toPersistentList().set(1, background)), ProjectionRejection.PROTECTION_NOT_COMPLETE,
            backgroundMetadata())
    }

    @Test fun completeBackgroundIsProtectedButNeverMergedIntoTargetNodes() {
        val result = complete(withBackground(), backgroundMetadata())
        assertEquals(1, result.snapshot.nodes.size)
        assertEquals(1, result.frame.outlines.size)
        assertEquals(2, result.snapshot.windows.size)
    }

    @Test fun missingBackgroundRootCannotProject() {
        reject(ProjectionFixture.evidence().copy(expectedProtectionWindowIds = persistentSetOf(7, 8)),
            ProjectionRejection.ROOT_COVERAGE_INVALID, backgroundMetadata())
    }

    @Test fun emptyDuplicateAndExtraRootsDeny() {
        val evidence = ProjectionFixture.evidence()
        reject(evidence.copy(roots = persistentListOf()), ProjectionRejection.ROOT_COVERAGE_INVALID)
        reject(evidence.copy(roots = persistentListOf(evidence.roots[0], evidence.roots[0])),
            ProjectionRejection.ROOT_COVERAGE_INVALID)
        reject(withBackground().copy(expectedProtectionWindowIds = persistentSetOf(7)),
            ProjectionRejection.ROOT_COVERAGE_INVALID)
    }

    @Test fun droppingChildCannotSatisfyCompleteFlags() {
        reject(ProjectionFixture.evidence(persistentListOf(ProjectionFixture.dialogNodes()[0])),
            ProjectionRejection.TOPOLOGY_INCOMPLETE)
    }

    @Test fun emptyTreeCannotProject() {
        reject(ProjectionFixture.evidence(persistentListOf()), ProjectionRejection.TOPOLOGY_INCOMPLETE)
    }

    @Test fun wrongParentOrdinalDuplicateOrReorderedNodesDeny() {
        val nodes = ProjectionFixture.dialogNodes()
        listOf(nodes.set(1, nodes[1].copy(parentPath = null)), nodes.set(1, nodes[1].copy(siblingOrdinal = 1)),
            nodes.set(1, nodes[1].copy(path = "0")), persistentListOf(nodes[1], nodes[0])).forEach {
            reject(ProjectionFixture.evidence(it), ProjectionRejection.TOPOLOGY_INCOMPLETE)
        }
    }

    @Test fun invalidChildCountsDenyWithoutExpansionOrExceptions() {
        listOf(-1, Int.MAX_VALUE).forEach { count ->
            reject(ProjectionFixture.evidence(persistentListOf(ProjectionFixture.node().copy(childCount = count))),
                if (count > CollectionProjectionLimits.MAX_CHILDREN) ProjectionRejection.CHILD_COUNT_LIMIT
                else ProjectionRejection.TOPOLOGY_INCOMPLETE)
        }
    }

    @Test fun tokenWindowMismatchDenies() {
        reject(ProjectionFixture.evidence(), ProjectionRejection.ROOT_COVERAGE_INVALID,
            ProjectionFixture.metadata().copy(token = ProjectionFixture.metadata().token.copy(windowId = 99)))
    }

    @Test fun outsideWindowCannotProjectEvenWithCompleteTree() {
        val metadata = ProjectionFixture.metadata()
        reject(ProjectionFixture.evidence(), ProjectionRejection.WINDOW_GEOMETRY_INVALID,
            metadata.copy(windows = persistentListOf(metadata.windows.single().copy(rect = CollectedRect(0, 0, 501, 900)))))
    }

    @Test fun reversedOrEmptyFrameAndOverflowAreRejectedWithoutDtoExceptions() {
        listOf(CollectedRect(5, 0, 1, 900), CollectedRect(0, 0, 0, 900),
            CollectedRect(Int.MIN_VALUE, 0, Int.MAX_VALUE, 900)).forEach { rect ->
            reject(ProjectionFixture.evidence(), ProjectionRejection.WINDOW_GEOMETRY_INVALID,
                ProjectionFixture.metadata().copy(screenRect = rect))
        }
    }

    @Test fun missingMetadataOrOutlineFingerprintDenies() {
        reject(ProjectionFixture.evidence(), ProjectionRejection.METADATA_INCOMPLETE,
            ProjectionFixture.metadata().copy(applicationDigest = ""))
        reject(ProjectionFixture.evidence(persistentListOf(ProjectionFixture.node().copy(outlineFingerprint = ""))),
            ProjectionRejection.METADATA_INCOMPLETE)
    }

    @Test fun resultRepresentationsNeverPrintDtoContentsAndBadInputDoesNotThrow() {
        val result = complete(ProjectionFixture.evidence())
        assertEquals("ProjectionOutcome.Complete(redacted)", result.toString())
        val outcome = CollectionProjection.project(ProjectionFixture.evidence(persistentListOf(
            ProjectionFixture.node().copy(path = "PRIVATE_PATH", text = "PRIVATE_BODY",
                rect = CollectedRect(184763, 284763, 3, 4)))), ProjectionFixture.metadata())
        assertTrue(outcome is ProjectionOutcome.Rejected)
        assertEquals("Rejected(reason=TOPOLOGY_INCOMPLETE)", outcome.toString())
    }

    private fun withBackground(): CollectedTreeEvidence {
        val evidence = ProjectionFixture.evidence()
        return evidence.copy(expectedProtectionWindowIds = persistentSetOf(7, 8), roots = evidence.roots.toPersistentList().add(
            evidence.roots.single().copy(windowId = 8, role = EvidenceTreeRole.SAME_APP_BACKGROUND)))
    }
    private fun backgroundMetadata(): CollectionProjectionMetadata {
        val metadata = ProjectionFixture.metadata()
        return metadata.copy(windows = metadata.windows.toPersistentList().add(metadata.windows.single().copy(id = 8, active = false)))
    }
    private fun complete(evidence: CollectedTreeEvidence, metadata: CollectionProjectionMetadata = ProjectionFixture.metadata()) =
        CollectionProjection.project(evidence, metadata).also { assertTrue(it is ProjectionOutcome.Complete) } as ProjectionOutcome.Complete
    private fun reject(evidence: CollectedTreeEvidence, reason: ProjectionRejection,
        metadata: CollectionProjectionMetadata = ProjectionFixture.metadata()) {
        assertEquals(ProjectionOutcome.Rejected(reason), CollectionProjection.project(evidence, metadata))
    }
}

/** Synthetic RAM-only records, never an Android reader or proof of the current runtime F0 pass. */
internal object ProjectionFixture {
    fun node() = CollectedNodeEvidence("0", null, 0, 0, "synthetic:id", "SyntheticView", "합성 공지",
        "합성 설명", false, true, CollectedRect(0, 0, 500, 900), "outline-secret", false, true, false,
        protection = ProtectionCompletion.COMPLETED_WITHOUT_BLOCK, readCompletion = NodeReadCompletion.COMPLETE)
    fun dialogNodes() = persistentListOf(
        node().copy(childCount = 1, className = "SyntheticDialog", rect = CollectedRect(50, 100, 450, 800)),
        node().copy(path = "0/0", parentPath = "0", visible = false, pane = true, scrollable = true,
            text = "닫기", clickable = true, rect = CollectedRect(100, 200, 200, 250),
            outlineFingerprint = "child-secret", actions = persistentSetOf(NodeAction.DISMISS, NodeAction.CLICK)))
    fun evidence(nodes: ImmutableList<CollectedNodeEvidence> = persistentListOf(node())) = CollectedTreeEvidence(
        persistentSetOf(7), persistentListOf(CollectedRootEvidence(7, EvidenceTreeRole.TARGET, nodes,
            ProtectionCompletion.COMPLETED_WITHOUT_BLOCK, TreeCompletion.COMPLETE)),
        ProtectionCompletion.COMPLETED_WITHOUT_BLOCK, TreeCompletion.COMPLETE)
    fun metadata() = CollectionProjectionMetadata(
        SnapshotToken(1, 7, "synthetic.test", "synthetic-popup", "structure-secret", "content-secret", 1), 79,
        "application-secret", CollectedRect(0, 0, 500, 900), persistentListOf(
            CollectedWindowEvidence(7, WindowType.APPLICATION, CollectedRect(0, 0, 500, 900), true, true, 1)),
        PopupTrigger.CONTENT_CHANGED, WindowBoundsAge.OLDER)
}
