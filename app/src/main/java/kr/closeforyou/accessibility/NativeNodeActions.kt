package kr.closeforyou.accessibility

import android.view.accessibility.AccessibilityNodeInfo as N
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupSnapshot

/** A-only, ephemeral evidence. NONE means the FULL native action list was read and empty;
 * focus/navigation/operative actions count as PRESENT. No IDs are logged. */
internal enum class NativeActionPresence { UNKNOWN, NONE, PRESENT }

internal fun nativeActionPresence(count: Int): NativeActionPresence = when (count) {
    0 -> NativeActionPresence.NONE
    in 1..64 -> NativeActionPresence.PRESENT
    else -> NativeActionPresence.UNKNOWN
}

/** Exact A-only, short-lived IDs from ONE full native read. No IDs/labels are exposed or
 * logged. The private sorted copy is compared structurally, never by a hash or category.
 * Recognized, unlabelled standard IDs do not authenticate application handler semantics.
 * Unsupported/custom/labelled actions and presence-only PRESENT cannot certify continuity. */
internal class NativeActionSummary private constructor(val presence: NativeActionPresence,
    private val ids: List<Int>?) {
    val hasExactKnownSet: Boolean get() = ids != null
    /** Read-only diagnostic questions; neither exposes IDs nor replaces exact equality. */
    fun containsOnly(allowed: Set<Int>): Boolean = ids != null && ids.all { it in allowed }
    fun containsAnyOf(group: Set<Int>): Boolean = ids?.any { it in group } == true
    val displayRootSafe: Boolean get() = ids != null && ids.all { it in navigationIds }
    /** Capability facet only, NOT a ROOT role/identity or execution certificate. Android View
     * supplies SELECT/CLEAR_SELECTION even on ordinary containers. We never invoke these.
     * Caller must separately prove both structural ROOTs, whole-popup continuity and geometry. */
    val hasOnlyStructuralRootCapabilities: Boolean get() = ids != null && ids.all { it in structuralRootIds }
    fun matchesProjection(actions: Set<NodeAction>): Boolean = ids != null &&
        ((N.ACTION_CLICK in ids) == (NodeAction.CLICK in actions)) &&
        ((N.ACTION_DISMISS in ids) == (NodeAction.DISMISS in actions))
    override fun equals(other: Any?) = other is NativeActionSummary && presence == other.presence && ids == other.ids
    override fun hashCode() = 31 * presence.hashCode() + (ids?.hashCode() ?: 0)
    override fun toString() = "NativeActionSummary(redacted)"
    companion object {
        private val navigationIds = setOf(N.ACTION_FOCUS, N.ACTION_CLEAR_FOCUS,
            N.ACTION_ACCESSIBILITY_FOCUS, N.ACTION_CLEAR_ACCESSIBILITY_FOCUS,
            android.R.id.accessibilityActionShowOnScreen)
        private val structuralRootIds = navigationIds + setOf(N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION)
        // Deliberately bounded supported platform subset. Unrecognized newer framework IDs
        // also fail closed; never infer a custom action's meaning from its integer alone.
        private val knownIds = navigationIds + setOf(N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION,
            N.ACTION_CLICK, N.ACTION_LONG_CLICK, N.ACTION_NEXT_AT_MOVEMENT_GRANULARITY,
            N.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, N.ACTION_NEXT_HTML_ELEMENT,
            N.ACTION_PREVIOUS_HTML_ELEMENT, N.ACTION_SCROLL_FORWARD, N.ACTION_SCROLL_BACKWARD,
            N.ACTION_COPY, N.ACTION_PASTE, N.ACTION_CUT, N.ACTION_SET_SELECTION,
            N.ACTION_EXPAND, N.ACTION_COLLAPSE, N.ACTION_DISMISS, N.ACTION_SET_TEXT)
        val UNKNOWN = NativeActionSummary(NativeActionPresence.UNKNOWN, null)
        fun presenceOnly(value: NativeActionPresence): NativeActionSummary = when (value) {
            NativeActionPresence.NONE -> NativeActionSummary(value, emptyList())
            NativeActionPresence.PRESENT -> NativeActionSummary(value, null)
            NativeActionPresence.UNKNOWN -> UNKNOWN
        }
        /** Tests supply explicitly unlabelled fake IDs. Both Android producers additionally
         * inspect labels on the SAME frozen action references; no label contents are copied. */
        fun fromIds(ids: List<Int>, hasUnverifiedLabel: Boolean = false): NativeActionSummary {
            if (ids.size > 64 || hasUnverifiedLabel) return UNKNOWN
            val frozen = ids.toList()
            if (frozen.size > 64 || frozen.any { it !in knownIds } || frozen.toSet().size != frozen.size) return UNKNOWN
            return NativeActionSummary(nativeActionPresence(frozen.size), frozen.sorted())
        }
    }
}

/** Bound by object identity to the actual original capture or fresh descriptor list.
 * A copied token/snapshot/list cannot turn missing metadata into action-absence evidence.
 * This is not a completion certificate, native-generation proof, or execution permission. */
internal class NativeNodeActions private constructor(private val owner: Any,
    private val descriptors: List<NodeSnapshot>, private val rows: List<NativeActionSummary>) {
    private var invalidated = false // A reads this ephemeral evidence on the service main thread.
    fun state(owner: Any, count: Int, index: Int): NativeActionPresence = summary(owner, count, index).presence
    /** Diagnostics must not poison the policy's existing invalidation latch on a bad lookup. */
    fun observedSummary(owner: Any, count: Int, index: Int): NativeActionSummary {
        if (this.owner !== owner || invalidated) return NativeActionSummary.UNKNOWN
        val current = when (owner) { is PopupSnapshot -> owner.nodes; is List<*> -> owner; else -> null }
        if (rows.size != count || descriptors.size != count || current == null || current.size != count ||
            index !in rows.indices || current[index] !== descriptors[index]) return NativeActionSummary.UNKNOWN
        return rows[index]
    }
    fun summary(owner: Any, count: Int, index: Int): NativeActionSummary {
        if (this.owner !== owner) return NativeActionSummary.UNKNOWN
        val current = when (owner) { is PopupSnapshot -> owner.nodes; is List<*> -> owner; else -> null }
        if (invalidated || rows.size != count || descriptors.size != count || current == null || current.size != count ||
            index !in rows.indices || current[index] !== descriptors[index]) {
            invalidated = true
            return NativeActionSummary.UNKNOWN
        }
        return rows[index]
    }
    override fun toString() = "NativeNodeActions(redacted)"

    companion object {
        fun forSnapshot(snapshot: PopupSnapshot, rows: List<NativeActionPresence>): NativeNodeActions? =
            create(snapshot, snapshot.nodes, rows.map(NativeActionSummary::presenceOnly))
        fun forNodes(nodes: List<NodeSnapshot>, rows: List<NativeActionPresence>): NativeNodeActions? =
            create(nodes, nodes, rows.map(NativeActionSummary::presenceOnly))
        fun forSnapshotSummaries(snapshot: PopupSnapshot, rows: List<NativeActionSummary>): NativeNodeActions? =
            create(snapshot, snapshot.nodes, rows)
        fun forNodeSummaries(nodes: List<NodeSnapshot>, rows: List<NativeActionSummary>): NativeNodeActions? =
            create(nodes, nodes, rows)
        private fun create(owner: Any, nodes: List<NodeSnapshot>, rows: List<NativeActionSummary>): NativeNodeActions? =
            if (nodes.size in 1..1_200 && rows.size == nodes.size) NativeNodeActions(owner, nodes.toList(), rows.toList()) else null
    }
}
