package kr.closeforyou.accessibility

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.PopupTrigger
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.WindowType

/** A-private, ephemeral values. No Android handles, persistence, logging or production wiring. */
internal enum class ProtectionCompletion { UNKNOWN, INCOMPLETE, BLOCKED, COMPLETED_WITHOUT_BLOCK }
internal enum class TreeCompletion {
    UNKNOWN, COMPLETE, NODE_FAILURE, MISSING_CHILD, TIME_LIMIT, NODE_LIMIT, DEPTH_LIMIT, CHILD_LIMIT, CANCELLED,
}
internal enum class NodeReadCompletion { UNKNOWN, COMPLETE, FAILED }
internal enum class GeometryCompletion { UNKNOWN, COMPLETE, INCOMPLETE }
internal enum class EvidenceTreeRole { TARGET, SAME_APP_BACKGROUND }

/** No ordering precondition: malformed components are retained verbatim, never repaired. */
internal data class CollectedRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val shape: NodeRectShape get() = NodeBoundsPolicy.shape(left, top, right, bottom)
    // Empty ordered node bounds remain representable by V1; visibility is NOT a privacy exemption.
    val dtoCompatible: Boolean get() = right.toLong() - left in 0..Int.MAX_VALUE.toLong() &&
        bottom.toLong() - top in 0..Int.MAX_VALUE.toLong()
    override fun toString(): String = "CollectedRect(redacted)"
}

/** One original preorder record, including hidden nodes and unusable parents/descendants. */
internal data class CollectedNodeEvidence(
    val path: String,
    val parentPath: String?,
    val siblingOrdinal: Int,
    val childCount: Int,
    val viewId: String?,
    val className: String?,
    val text: String?,
    val contentDescription: String?,
    val clickable: Boolean,
    val enabled: Boolean,
    val rect: CollectedRect?,
    val outlineFingerprint: String,
    val pane: Boolean,
    val visible: Boolean,
    val scrollable: Boolean,
    val actions: ImmutableSet<NodeAction> = persistentSetOf(),
    val protection: ProtectionCompletion = ProtectionCompletion.UNKNOWN,
    val readCompletion: NodeReadCompletion = NodeReadCompletion.UNKNOWN,
) {
    val geometry: GeometryCompletion get() = when (rect?.dtoCompatible) {
        null -> GeometryCompletion.UNKNOWN
        true -> GeometryCompletion.COMPLETE
        false -> GeometryCompletion.INCOMPLETE
    }
    override fun toString(): String = "CollectedNodeEvidence(redacted)"
}

/** Each declared same-app protection root must be retained, even when its tree is incomplete. */
internal data class CollectedRootEvidence(
    val windowId: Int,
    val role: EvidenceTreeRole,
    val nodes: ImmutableList<CollectedNodeEvidence>,
    val protection: ProtectionCompletion = ProtectionCompletion.UNKNOWN,
    val traversal: TreeCompletion = TreeCompletion.UNKNOWN,
) {
    override fun toString(): String = "CollectedRootEvidence(redacted)"
}

/** Completion claims are independent; none is inferred from visibility or another pass's success.
 * A future trusted reader must establish coverage/currentness, not merely populate these enums.
 * This phase neither reads Android nor attests that F0 actually ran on a real screen.
 */
internal data class CollectedTreeEvidence(
    val expectedProtectionWindowIds: ImmutableSet<Int>,
    val roots: ImmutableList<CollectedRootEvidence>,
    val protection: ProtectionCompletion = ProtectionCompletion.UNKNOWN,
    val traversal: TreeCompletion = TreeCompletion.UNKNOWN,
) {
    val geometry: GeometryCompletion get() {
        var unknown = roots.isEmpty()
        for (root in roots) {
            if (root.nodes.isEmpty()) unknown = true
            for (node in root.nodes) when (node.geometry) {
                GeometryCompletion.INCOMPLETE -> return GeometryCompletion.INCOMPLETE
                GeometryCompletion.UNKNOWN -> unknown = true
                GeometryCompletion.COMPLETE -> Unit
            }
        }
        return if (unknown) GeometryCompletion.UNKNOWN else GeometryCompletion.COMPLETE
    }
    override fun toString(): String = "CollectedTreeEvidence(redacted)"
}

internal data class CollectedWindowEvidence(
    val id: Int,
    val type: WindowType,
    val rect: CollectedRect,
    val active: Boolean,
    val focused: Boolean,
    val layer: Int,
) {
    override fun toString(): String = "CollectedWindowEvidence(redacted)"
}

/** Supplied, transient metadata; digests are preserved, NOT recomputed or authenticated here.
 * This is not a live consent/session/window/occlusion check or an execution authorization.
 */
internal data class CollectionProjectionMetadata(
    val token: SnapshotToken,
    val capturedAtUptimeMillis: Long,
    val applicationDigest: String,
    val screenRect: CollectedRect,
    val windows: ImmutableList<CollectedWindowEvidence>,
    val trigger: PopupTrigger,
    val windowAge: WindowBoundsAge = WindowBoundsAge.UNOBSERVED,
) {
    override fun toString(): String = "CollectionProjectionMetadata(redacted)"
}
