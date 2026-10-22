package kr.closeforyou.accessibility

import java.security.MessageDigest
import kr.closeforyou.core.model.NodeAction
import kr.closeforyou.core.model.NodeSnapshot

/** V1's structural child count, not a newly observed/guessed outline or traversal child count.
 * Inputs must already be captured by A. This value neither performs F0 nor proves completeness.
 */
internal data class SnapshotFingerprintNode(val snapshot: NodeSnapshot, val childCount: Int) {
    override fun toString(): String = "SnapshotFingerprintNode(redacted)"
}

/** Ephemeral digest. Explicit byte/hex access is for data contracts, NEVER for diagnostics. */
internal class SnapshotFingerprintDigest internal constructor(bytes: ByteArray) {
    private val value = bytes.copyOf()
    fun bytes(): ByteArray = value.copyOf()
    fun hex(): String {
        val alphabet = "0123456789abcdef"
        return CharArray(value.size * 2).also { chars ->
            value.forEachIndexed { index, byte ->
                val unsigned = byte.toInt() and 0xff
                chars[index * 2] = alphabet[unsigned ushr 4]
                chars[index * 2 + 1] = alphabet[unsigned and 0x0f]
            }
        }.concatToString()
    }
    override fun toString(): String = "SnapshotFingerprintDigest(redacted)"
}

/** Pure V1 encoding, NOT connected to AndroidSnapshotCollector.
 * The future collector owner supplies its existing 32-byte salt; never generate or export it here.
 * No token/session binding, F0/F1 checks, tree traversal, timers, logging or persistence.
 * Caller order is original preorder: never sort, drop or re-identify nodes.
 */
internal class SnapshotFingerprintCodec(collectorSalt: ByteArray) {
    private val salt: ByteArray
    init {
        require(collectorSalt.size == 32) { "FINGERPRINT_SALT_SIZE" }
        salt = collectorSalt.copyOf()
    }

    fun structure(nodes: List<SnapshotFingerprintNode>): SnapshotFingerprintDigest = digest { hash ->
        for (record in nodes) {
            val node = record.snapshot
            hash.field(node.path)
            hash.field(node.viewId)
            hash.field(node.className)
            hash.field(node.bounds.left.toString())
            hash.field(node.bounds.top.toString())
            hash.field(node.bounds.right.toString())
            hash.field(node.bounds.bottom.toString())
            hash.field(node.clickable.toString())
            hash.field(node.enabled.toString())
            hash.field(record.childCount.toString())
            hash.field(actions(node.actions))
        }
    }

    fun content(nodes: List<SnapshotFingerprintNode>): SnapshotFingerprintDigest = digest { hash ->
        for (record in nodes) {
            hash.field(record.snapshot.path)
            hash.field(record.snapshot.text)
            hash.field(record.snapshot.contentDescription)
        }
    }

    fun outline(node: NodeSnapshot): SnapshotFingerprintDigest = digest { hash ->
        hash.field(node.path)
        hash.field(node.viewId)
        hash.field(node.className)
    }

    fun application(packageName: String): SnapshotFingerprintDigest = digest { hash -> hash.field(packageName) }

    /** Reproduce the collector's CLICK-then-DISMISS construction, independent of incoming Set order.
     * Only these two actions exist in V1; enum names are the exact comma-separated representation.
     */
    private fun actions(actions: Set<NodeAction>): String = when {
        NodeAction.CLICK in actions && NodeAction.DISMISS in actions -> "CLICK,DISMISS"
        NodeAction.CLICK in actions -> "CLICK"
        NodeAction.DISMISS in actions -> "DISMISS"
        else -> ""
    }

    private inline fun digest(append: (MessageDigest) -> Unit): SnapshotFingerprintDigest {
        val hash = MessageDigest.getInstance("SHA-256")
        hash.update(salt)
        append(hash)
        return SnapshotFingerprintDigest(hash.digest())
    }

    private fun MessageDigest.field(value: String?) {
        val bytes = value?.toByteArray(Charsets.UTF_8)
        val size = bytes?.size ?: -1
        update(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        if (bytes != null) update(bytes)
    }

    override fun toString(): String = "SnapshotFingerprintCodec(V1, redacted)"
}
