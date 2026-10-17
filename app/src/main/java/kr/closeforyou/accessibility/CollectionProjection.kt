package kr.closeforyou.accessibility

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
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
    NODE_COUNT_LIMIT, DEPTH_LIMIT, CHILD_COUNT_LIMIT, FIELD_LENGTH_LIMIT, TEXT_LENGTH_LIMIT,
    WINDOW_COUNT_LIMIT, PATH_LENGTH_LIMIT,
}

/** Mirrors only bounds applicable to retained records, not Android's shared visit/time budget.
 * Keep the collector unchanged. Any future reader must still enforce both passes' own budget.
 */
internal object CollectionProjectionLimits {
    const val MAX_RETAINED_NODES = 2_400
    const val MAX_DEPTH = 60
    const val MAX_CHILDREN = 1_200
    const val MAX_WINDOWS = 32
    const val MAX_FIELD_CHARACTERS = 4_096
    const val MAX_TARGET_TEXT_CHARACTERS = 32_768
    // Root "0", then at most 60 slash + four-digit ordinals (0..1199).
    const val MAX_PATH_CHARACTERS = 1 + MAX_DEPTH * 5
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
        // Check cardinalities before any input-sized scans, derived collections or DTO allocation.
        if (roots.size > CollectionProjectionLimits.MAX_WINDOWS ||
            evidence.expectedProtectionWindowIds.size > CollectionProjectionLimits.MAX_WINDOWS ||
            metadata.windows.size > CollectionProjectionLimits.MAX_WINDOWS)
            return reject(ProjectionRejection.WINDOW_COUNT_LIMIT)
        var retainedNodes = 0
        for (root in roots) {
            if (root.nodes.size > CollectionProjectionLimits.MAX_RETAINED_NODES - retainedNodes)
                return reject(ProjectionRejection.NODE_COUNT_LIMIT)
            retainedNodes += root.nodes.size
        }
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
        // Only four existing protected text fields are retained here; the reader must check the rest.
        var targetTextCharacters = 0L
        for (root in roots) for (node in root.nodes) {
            if ((node.text?.length ?: 0) > CollectionProjectionLimits.MAX_FIELD_CHARACTERS ||
                (node.contentDescription?.length ?: 0) > CollectionProjectionLimits.MAX_FIELD_CHARACTERS ||
                (node.viewId?.length ?: 0) > CollectionProjectionLimits.MAX_FIELD_CHARACTERS ||
                (node.className?.length ?: 0) > CollectionProjectionLimits.MAX_FIELD_CHARACTERS)
                return reject(ProjectionRejection.FIELD_LENGTH_LIMIT)
            if (node.path.length > CollectionProjectionLimits.MAX_PATH_CHARACTERS ||
                (node.parentPath?.length ?: 0) > CollectionProjectionLimits.MAX_PATH_CHARACTERS)
                return reject(ProjectionRejection.PATH_LENGTH_LIMIT)
            if (node.childCount > CollectionProjectionLimits.MAX_CHILDREN)
                return reject(ProjectionRejection.CHILD_COUNT_LIMIT)
            if (root.role == EvidenceTreeRole.TARGET) {
                targetTextCharacters += (node.text?.length ?: 0).toLong() + (node.contentDescription?.length ?: 0)
                if (targetTextCharacters > CollectionProjectionLimits.MAX_TARGET_TEXT_CHARACTERS)
                    return reject(ProjectionRejection.TEXT_LENGTH_LIMIT)
            }
        }
        for (root in roots) completePreorder(root.nodes)?.let { return reject(it) }
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
        val nodes = persistentListOf<NodeSnapshot>().builder()
        val outlines = persistentListOf<DetectionOutline>().builder()
        for (node in target.nodes) {
            val bounds = node.rect!!.toBounds()
            nodes.add(NodeSnapshot(node.path, node.viewId, node.className, node.text, node.contentDescription,
                node.clickable, node.enabled, bounds, node.actions))
            outlines.add(DetectionOutline(node.outlineFingerprint, bounds, node.childCount,
                node.className?.contains("dialog", ignoreCase = true) == true || NodeAction.DISMISS in node.actions,
                node.path, node.pane, node.visible, node.scrollable))
        }
        val projectedWindows = persistentListOf<WindowSnapshot>().builder()
        val applicationIds = persistentSetOf<Int>().builder()
        for (window in windows) {
            projectedWindows.add(WindowSnapshot(window.id, window.type, window.rect.toBounds(),
                window.active, window.focused, window.layer))
            if (window.type == WindowType.APPLICATION) applicationIds.add(window.id)
        }
        val snapshot = PopupSnapshot(metadata.token, metadata.capturedAtUptimeMillis, nodes.build(),
            projectedWindows.build(), screen, metadata.trigger, SnapshotCompleteness.COMPLETE)
        val frame = DetectionFrame(metadata.applicationDigest, target.windowId,
            applicationIds.build(), metadata.token.structureDigest, screen, outlines.build(), metadata.trigger,
            windowBounds, metadata.windowAge)
        return ProjectionOutcome.Complete(snapshot, frame)
    }

    /** Exact original preorder and child counts. O(depth) pending parents, no child-path expansion.
     * Compare path components in place; do not create substrings or concatenate full expected paths.
     */
    private fun completePreorder(nodes: List<CollectedNodeEvidence>): ProjectionRejection? {
        if (nodes.isEmpty()) return ProjectionRejection.TOPOLOGY_INCOMPLETE
        class Parent(val node: CollectedNodeEvidence, var nextChild: Int = 0) {
            override fun toString(): String = "Parent(redacted)"
        }
        val parents = ArrayDeque<Parent>()
        for ((index, node) in nodes.withIndex()) {
            while (parents.lastOrNull()?.let { it.nextChild == it.node.childCount } == true) parents.removeLast()
            if (parents.size > CollectionProjectionLimits.MAX_DEPTH) return ProjectionRejection.DEPTH_LIMIT
            if (node.childCount !in 0 until nodes.size) return ProjectionRejection.TOPOLOGY_INCOMPLETE
            if (index == 0) {
                if (node.path != "0" || node.parentPath != null || node.siblingOrdinal != 0)
                    return ProjectionRejection.TOPOLOGY_INCOMPLETE
            } else {
                val parent = parents.lastOrNull() ?: return ProjectionRejection.TOPOLOGY_INCOMPLETE
                val ordinal = parent.nextChild++
                if (node.parentPath != parent.node.path || node.siblingOrdinal != ordinal ||
                    !childPathMatches(node.path, parent.node.path, ordinal))
                    return ProjectionRejection.TOPOLOGY_INCOMPLETE
            }
            if (node.childCount > 0) parents.addLast(Parent(node))
        }
        while (parents.lastOrNull()?.let { it.nextChild == it.node.childCount } == true) parents.removeLast()
        return if (parents.isEmpty()) null else ProjectionRejection.TOPOLOGY_INCOMPLETE
    }

    private fun childPathMatches(path: String, parent: String, ordinal: Int): Boolean {
        val start = parent.length + 1
        val digits = path.length - start
        if (digits !in 1..4 || !path.startsWith(parent) || path[parent.length] != '/' ||
            (digits > 1 && path[start] == '0')) return false
        var parsed = 0
        for (index in start until path.length) {
            val digit = path[index] - '0'
            if (digit !in 0..9) return false
            parsed = parsed * 10 + digit
        }
        return parsed == ordinal
    }

    /** Only called after ALL raw rectangles are checked. No require messages or normalization. */
    private fun CollectedRect.toBounds() = ScreenBounds(left, top, right, bottom)
}
