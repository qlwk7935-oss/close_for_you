package kr.closeforyou.accessibility

import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.WindowSnapshot
import kr.closeforyou.core.model.WindowType

internal enum class ProjectionRejection {
    PROTECTION_NOT_COMPLETE, TREE_NOT_COMPLETE, ROOT_COVERAGE_INVALID, TOPOLOGY_INCOMPLETE,
    NODE_READ_INCOMPLETE, GEOMETRY_NOT_COMPLETE, METADATA_INCOMPLETE, WINDOW_GEOMETRY_INVALID,
}

/** Not CollectionResult.Ready, not a public Candidate and not a runtime authorization. */
internal sealed interface ProjectionOutcome {
    data class Rejected(val reason: ProjectionRejection) : ProjectionOutcome
    class Complete(val snapshot: PopupSnapshot, val frame: DetectionFrame) : ProjectionOutcome {
        override fun toString(): String = "ProjectionOutcome.Complete(redacted)"
    }
}

/** Pure structural DTO gate only. No Android reads, F0 execution, detection or downstream calls.
 * Reject the ENTIRE observation before constructing either DTO; never filter or repair nodes.
 */
internal object CollectionProjection {
    fun project(evidence: CollectedTreeEvidence, metadata: CollectionProjectionMetadata): ProjectionOutcome {
        fun reject(reason: ProjectionRejection) = ProjectionOutcome.Rejected(reason)
        val roots = evidence.roots
        // Protection takes priority, including every retained hidden/background node.
        if (evidence.protection != ProtectionCompletion.COMPLETED_WITHOUT_BLOCK || roots.any { root ->
                root.protection != ProtectionCompletion.COMPLETED_WITHOUT_BLOCK ||
                    root.nodes.any { it.protection != ProtectionCompletion.COMPLETED_WITHOUT_BLOCK }
            }) return reject(ProjectionRejection.PROTECTION_NOT_COMPLETE)
        if (evidence.traversal != TreeCompletion.COMPLETE || roots.any { it.traversal != TreeCompletion.COMPLETE })
            return reject(ProjectionRejection.TREE_NOT_COMPLETE)
        val rootIds = roots.map { it.windowId }
        val target = roots.singleOrNull { it.role == EvidenceTreeRole.TARGET }
        if (roots.isEmpty() || rootIds.toSet().size != rootIds.size ||
            rootIds.toSet() != evidence.expectedProtectionWindowIds || target == null ||
            target.windowId != metadata.token.windowId)
            return reject(ProjectionRejection.ROOT_COVERAGE_INVALID)
        if (roots.any { root -> root.nodes.any { it.readCompletion != NodeReadCompletion.COMPLETE } })
            return reject(ProjectionRejection.NODE_READ_INCOMPLETE)
        if (roots.any { !completePreorder(it.nodes) }) return reject(ProjectionRejection.TOPOLOGY_INCOMPLETE)
        if (evidence.geometry != GeometryCompletion.COMPLETE)
            return reject(ProjectionRejection.GEOMETRY_NOT_COMPLETE)

        val windows = metadata.windows
        val windowIds = windows.map { it.id }
        val targetWindow = windows.singleOrNull { it.id == target.windowId }
        if (windowIds.toSet().size != windows.size || targetWindow?.type != WindowType.APPLICATION ||
            !targetWindow.active || !windowIds.containsAll(rootIds) ||
            roots.any { root -> windows.none { it.id == root.windowId && it.type == WindowType.APPLICATION } } ||
            metadata.capturedAtUptimeMillis < 0 || metadata.applicationDigest.isBlank() ||
            metadata.token.packageName.isBlank() || metadata.token.popupInstanceId.isBlank() ||
            metadata.token.structureDigest.isBlank() || metadata.token.contentDigest.isBlank() ||
            roots.any { root -> root.nodes.any { it.outlineFingerprint.isBlank() } })
            return reject(ProjectionRejection.METADATA_INCOMPLETE)
        if (!metadata.screenRect.dtoCompatible || windows.any { !it.rect.dtoCompatible })
            return reject(ProjectionRejection.WINDOW_GEOMETRY_INVALID)
        val screen = metadata.screenRect.toBounds()
        val windowBounds = targetWindow.rect.toBounds()
        if (screen.left != 0 || screen.top != 0 ||
            DetectionGeometryCheck.inspect(screen, windowBounds).result != DetectionGeometry.VALID)
            return reject(ProjectionRejection.WINDOW_GEOMETRY_INVALID)

        // All records have passed before any DTO is built. No retokenization or digest substitution.
        val nodes = target.nodes.map { node -> NodeSnapshot(
            node.path, node.viewId, node.className, node.text, node.contentDescription,
            node.clickable, node.enabled, node.rect!!.toBounds(), node.actions,
        ) }.toPersistentList()
        val outlines = target.nodes.map { node -> DetectionOutline(
            node.outlineFingerprint, node.rect!!.toBounds(), node.childCount,
            node.className?.contains("dialog", ignoreCase = true) == true || NodeAction.DISMISS in node.actions,
            node.path, node.pane, node.visible, node.scrollable,
        ) }
        val snapshot = PopupSnapshot(metadata.token, metadata.capturedAtUptimeMillis, nodes,
            windows.map { WindowSnapshot(it.id, it.type, it.rect.toBounds(), it.active, it.focused, it.layer) }
                .toPersistentList(), screen, metadata.trigger, SnapshotCompleteness.COMPLETE)
        val frame = DetectionFrame(metadata.applicationDigest, target.windowId,
            windows.filter { it.type == WindowType.APPLICATION }.mapTo(mutableSetOf()) { it.id },
            metadata.token.structureDigest, screen, outlines, metadata.trigger, windowBounds, metadata.windowAge)
        return ProjectionOutcome.Complete(snapshot, frame)
    }

    /** Exact original preorder and child counts. Missing, reordered, duplicate or orphan nodes fail.
     * Bound expansion by the supplied node count, so an invalid child count cannot allocate arbitrarily.
     */
    private fun completePreorder(nodes: List<CollectedNodeEvidence>): Boolean {
        if (nodes.isEmpty()) return false
        data class Expected(val path: String, val parent: String?, val ordinal: Int) {
            override fun toString(): String = "Expected(redacted)"
        }
        val pending = ArrayDeque<Expected>()
        pending.addLast(Expected("0", null, 0))
        for (node in nodes) {
            val expected = pending.removeLastOrNull() ?: return false
            if (node.path != expected.path || node.parentPath != expected.parent ||
                node.siblingOrdinal != expected.ordinal || node.childCount !in 0 until nodes.size)
                return false
            for (index in node.childCount - 1 downTo 0)
                pending.addLast(Expected("${node.path}/$index", node.path, index))
            if (pending.size > nodes.size) return false
        }
        return pending.isEmpty()
    }

    /** Only called after ALL raw rectangles are checked. No require messages or normalization. */
    private fun CollectedRect.toBounds() = ScreenBounds(left, top, right, bottom)
}
