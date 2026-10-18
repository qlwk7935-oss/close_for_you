package kr.closeforyou.servicetest

import kr.closeforyou.capturetest.PriorityFixture
import java.util.IdentityHashMap
import java.util.concurrent.CancellationException

internal enum class PriorityVerdict { EXPECTED_BLOCK, UNOBSERVED, INVALID_DIAGNOSTIC, UNEXPECTED_ATTEMPT, OVERFLOW, RESPONSE_REJECTED }
internal enum class PriorityMarker { OTHER, FIRST, LATER }
internal enum class PriorityField { NONE, PASSWORD, EDITABLE, SENSITIVE_TEXT, UNEXPECTED }
internal data class PriorityProbeNode(val marker: PriorityMarker, val underScroll: Boolean, val visible: Boolean,
    val reversedY: Boolean, val field: PriorityField) {
    override fun toString() = "PriorityProbeNode(codes_only)"
}

/** Input order must come from real getChild preorder, never View insertion order. No F0 receipt. */
internal fun priorityTreeEstablished(fixture: PriorityFixture, nodes: List<PriorityProbeNode>): Boolean {
    if (nodes.isEmpty() || nodes.size > 256 || nodes.any { it.field == PriorityField.UNEXPECTED }) return false
    val first = nodes.withIndex().filter { it.value.marker == PriorityMarker.FIRST }.singleOrNull() ?: return false
    val later = nodes.withIndex().filter { it.value.marker == PriorityMarker.LATER }.singleOrNull() ?: return false
    val expected = when (fixture) {
        PriorityFixture.CONTROL -> PriorityField.NONE
        PriorityFixture.PASSWORD -> PriorityField.PASSWORD
        PriorityFixture.EDITABLE -> PriorityField.EDITABLE
        PriorityFixture.SENSITIVE_TEXT -> PriorityField.SENSITIVE_TEXT
    }
    return first.index < later.index && first.value.underScroll && later.value.underScroll &&
        !first.value.visible && !later.value.visible && first.value.reversedY &&
        later.value.field == expected && nodes.withIndex().all { (index, node) -> index == later.index || node.field == PriorityField.NONE }
}

/** Every required fact is in ONE validated service tuple. Native/UiAutomation samples are never merged here. */
internal object ProtectionPriorityContract {
    /** A single RESULT snapshot only; this does not close/drain the service session. */
    fun assessResponse(fixture: PriorityFixture, code: String?, attempts: List<String>, overflow: Boolean): PriorityVerdict =
        if (code != "OBSERVED") PriorityVerdict.RESPONSE_REJECTED else assess(fixture, attempts, overflow)

    fun assess(fixture: PriorityFixture, attempts: List<String>, overflow: Boolean): PriorityVerdict {
        if (overflow || attempts.size > 256) return PriorityVerdict.OVERFLOW
        if (attempts.isEmpty()) return PriorityVerdict.UNOBSERVED
        val ordinals = mutableSetOf<String>()
        for (encoded in attempts) {
            if (encoded.length > 2_048) return PriorityVerdict.INVALID_DIAGNOSTIC
            val formatted = DetectionAttemptOutput.format(encoded)
            if (formatted == "ATTEMPT_DIAGNOSTIC_INVALID") return PriorityVerdict.INVALID_DIAGNOSTIC
            val fields = formatted.split(';').map { it.split('=', limit = 2) }
            if (fields.any { it.size != 2 } || fields.map { it[0] }.toSet().size != fields.size)
                return PriorityVerdict.INVALID_DIAGNOSTIC
            val row = fields.associate { it[0] to it[1] }
            if (!ordinals.add(row.getValue("ATTEMPT"))) return PriorityVerdict.INVALID_DIAGNOSTIC
            val expected = mutableMapOf("RESULT" to "BLOCKED", "TREE" to "TARGET", "DETAIL" to "TREE_PROTECTION",
                "RELATED" to "NONE", "FRAME" to "VERIFIED", "EXCEPTION" to "NONE")
            if (fixture == PriorityFixture.CONTROL) {
                expected += mapOf("BLOCK" to "UNREADABLE_TREE", "STEP" to "SNAPSHOT_PASS", "FIELD" to "NONE",
                    "CAUSE" to "MALFORMED_NODE_BOUNDS", "OP" to "NODE_BOUNDS_VALIDATE", "NODE_RECT" to "REVERSED_Y",
                    "NODE_VISIBILITY" to "NOT_VISIBLE", "NODE_ANCESTRY" to "SCROLL_ANCESTOR", "NODE_ROLE" to "DESCENDANT")
            } else {
                expected += mapOf("STEP" to "PROTECTION_PASS", "NODE_RECT" to "UNOBSERVED", "NODE_VISIBILITY" to "UNOBSERVED",
                    "NODE_ANCESTRY" to "UNOBSERVED", "NODE_ROLE" to "UNOBSERVED")
                expected += when (fixture) {
                    PriorityFixture.PASSWORD -> mapOf("BLOCK" to "PASSWORD", "FIELD" to "PASSWORD_FLAG", "CAUSE" to "PASSWORD_FLAG", "OP" to "NODE_FIELD_CHECK")
                    PriorityFixture.EDITABLE -> mapOf("BLOCK" to "EDITABLE_FIELD", "FIELD" to "EDITABLE_FLAG", "CAUSE" to "EDITABLE_FLAG", "OP" to "NODE_FIELD_CHECK")
                    else -> mapOf("BLOCK" to "SENSITIVE_CONTENT", "FIELD" to "TEXT", "CAUSE" to "SENSITIVE_TEXT", "OP" to "NODE_TEXT_CHECK")
                }
            }
            if (expected.any { (key, value) -> row[key] != value }) return PriorityVerdict.UNEXPECTED_ATTEMPT
        }
        return PriorityVerdict.EXPECTED_BLOCK
    }
}

internal enum class PriorityReleaseFailure { NONE, FRAMEWORK_RELEASE_FAILED, RELEASE_CANCELLED }
internal class PriorityReleaseException : RuntimeException("FRAMEWORK_RELEASE_FAILED")

/** Test-only cleanup. Stores codes only, never a resource, callback or exception.
 * Ordinary release exceptions do not replace a body failure/cancellation (or fatal Error),
 * skip subsequent releases or turn a normally completed body into success.
 */
internal class PriorityReleaseScope {
    var failure = PriorityReleaseFailure.NONE
        private set
    private var cancelled = false

    fun release(action: () -> Unit) {
        try { action() }
        catch (_: CancellationException) {
            cancelled = true
            if (failure == PriorityReleaseFailure.NONE) failure = PriorityReleaseFailure.RELEASE_CANCELLED
        } catch (_: Exception) {
            if (failure == PriorityReleaseFailure.NONE) failure = PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED
        }
    }

    /** Window-list identity only. Equal-but-distinct leases must both be released.
     * Do not deduplicate node leases across traversal: recycled instances may be reused by Android.
     */
    fun <T : Any> releaseAll(resources: List<T>, action: (T) -> Unit) {
        val seen = IdentityHashMap<T, Boolean>()
        try {
            resources.forEach { resource -> if (seen.put(resource, true) == null) release { action(resource) } }
        } finally { seen.clear() }
    }

    fun <T> protect(body: (PriorityReleaseScope) -> T): T {
        var returnedNormally = false
        try {
            val result = body(this)
            returnedNormally = true
            return result
        } finally {
            // No catch(Throwable), no retained/suppressed causes, and no replacement of a body failure.
            if (returnedNormally) {
                if (cancelled) throw CancellationException("PRIORITY_RELEASE_CANCELLED")
                if (failure != PriorityReleaseFailure.NONE) throw PriorityReleaseException()
            }
        }
    }

    override fun toString() = "PriorityReleaseScope(codes_only)"
}
