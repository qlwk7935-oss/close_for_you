package kr.closeforyou.accessibility

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupSnapshot

/** A-only, ephemeral evidence. NONE means the FULL native action list was read and empty;
 * even focus/navigation/unknown action IDs count as PRESENT. No raw IDs are retained. */
internal enum class NativeActionPresence { UNKNOWN, NONE, PRESENT }

internal fun nativeActionPresence(count: Int): NativeActionPresence = when (count) {
    0 -> NativeActionPresence.NONE
    in 1..64 -> NativeActionPresence.PRESENT
    else -> NativeActionPresence.UNKNOWN
}

/** Bound by object identity to the actual original capture or fresh descriptor list.
 * A copied token/snapshot/list cannot turn missing metadata into action-absence evidence.
 * This is not a completion certificate, native-generation proof, or execution permission. */
internal class NativeNodeActions private constructor(private val owner: Any,
    private val descriptors: List<NodeSnapshot>, private val rows: List<NativeActionPresence>) {
    private var invalidated = false // A reads this ephemeral evidence on the service main thread.
    fun state(owner: Any, count: Int, index: Int): NativeActionPresence {
        if (this.owner !== owner) return NativeActionPresence.UNKNOWN
        val current = when (owner) { is PopupSnapshot -> owner.nodes; is List<*> -> owner; else -> null }
        if (invalidated || rows.size != count || descriptors.size != count || current == null || current.size != count ||
            index !in rows.indices || current[index] !== descriptors[index]) {
            invalidated = true
            return NativeActionPresence.UNKNOWN
        }
        return rows[index]
    }
    override fun toString() = "NativeNodeActions(redacted)"

    companion object {
        fun forSnapshot(snapshot: PopupSnapshot, rows: List<NativeActionPresence>): NativeNodeActions? =
            create(snapshot, snapshot.nodes, rows)
        fun forNodes(nodes: List<NodeSnapshot>, rows: List<NativeActionPresence>): NativeNodeActions? =
            create(nodes, nodes, rows)
        private fun create(owner: Any, nodes: List<NodeSnapshot>, rows: List<NativeActionPresence>): NativeNodeActions? =
            if (nodes.size in 1..1_200 && rows.size == nodes.size) NativeNodeActions(owner, nodes.toList(), rows.toList()) else null
    }
}
