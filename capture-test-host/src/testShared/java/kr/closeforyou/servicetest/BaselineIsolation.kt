package kr.closeforyou.servicetest

/** Test-private raw geometry, retained only in a bounded observation in RAM. Never include it in assertions/output. */
internal data class IsolationRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val positive get() = right.toLong() > left && bottom.toLong() > top
    override fun toString() = "IsolationRect(redacted)"
    fun relation(other: IsolationRect): RectRelation = when {
        !positive || !other.positive -> RectRelation.EMPTY_OR_REVERSED
        this == other -> RectRelation.EQUAL
        left >= other.left && top >= other.top && right <= other.right && bottom <= other.bottom -> RectRelation.WITHIN
        other.left >= left && other.top >= top && other.right <= right && other.bottom <= bottom -> RectRelation.CONTAINS
        left < other.right && other.left < right && top < other.bottom && other.top < bottom -> RectRelation.OVERLAPS
        else -> RectRelation.DISJOINT
    }
    fun outside(other: IsolationRect) = buildList {
        if (left < other.left) add("LEFT")
        if (top < other.top) add("TOP")
        if (right > other.right) add("RIGHT")
        if (bottom > other.bottom) add("BOTTOM")
    }.joinToString(",").ifEmpty { "NONE" }
}
internal enum class RectRelation { EQUAL, WITHIN, CONTAINS, OVERLAPS, DISJOINT, EMPTY_OR_REVERSED }
internal enum class IsolationCollection { READY_ONLY, MALFORMED_ONLY, OTHER_BLOCK, MIXED, UNOBSERVED, INVALID }
internal enum class IsolationFrame { VALID_ONLY, OUTSIDE_ONLY, OTHER_GEOMETRY, MIXED, UNOBSERVED }
internal data class IsolationEvidence(val collection: IsolationCollection, val frame: IsolationFrame,
    val sawReady: Boolean = false, val sawMalformed: Boolean = false) {
    companion object {
        /** Parse only already allowlisted per-attempt rows; no aggregate reason is a causal tuple. */
        fun from(attempts: List<String>, overflow: Boolean): IsolationEvidence {
            if (overflow) return IsolationEvidence(IsolationCollection.INVALID, IsolationFrame.UNOBSERVED)
            val rows = attempts.map(DetectionAttemptOutput::format)
            if (rows.any { it == "ATTEMPT_DIAGNOSTIC_INVALID" }) return IsolationEvidence(IsolationCollection.INVALID, IsolationFrame.UNOBSERVED)
            val tokens = rows.map { it.split(';').toSet() }
            val ready = tokens.any { "RESULT=ASSESSED" in it }
            val malformed = tokens.any { "RESULT=BLOCKED" in it && "CAUSE=MALFORMED_NODE_BOUNDS" in it }
            val other = tokens.any { "RESULT=BLOCKED" in it && "CAUSE=MALFORMED_NODE_BOUNDS" !in it }
            val collection = when {
                listOf(ready, malformed, other).count { it } > 1 -> IsolationCollection.MIXED
                ready -> IsolationCollection.READY_ONLY
                malformed -> IsolationCollection.MALFORMED_ONLY
                other -> IsolationCollection.OTHER_BLOCK
                else -> IsolationCollection.UNOBSERVED
            }
            val geometries = tokens.filter { "RESULT=ASSESSED" in it }.map { row -> when {
                "GEOMETRY=VALID" in row -> IsolationFrame.VALID_ONLY
                "GEOMETRY=WINDOW_OUTSIDE_FRAME" in row -> IsolationFrame.OUTSIDE_ONLY
                else -> IsolationFrame.OTHER_GEOMETRY
            } }.toSet()
            return IsolationEvidence(collection, geometries.singleOrNull() ?: if (geometries.isEmpty()) IsolationFrame.UNOBSERVED else IsolationFrame.MIXED, ready, malformed)
        }
    }
}
internal enum class IsolationContrast { MALFORMED_ISOLATED_TO_SCROLL, MALFORMED_ON_BOTH, NO_MALFORMED_OBSERVED, INCONCLUSIVE }
internal fun isolationContrast(scroll: IsolationEvidence?, minimal: IsolationEvidence?): IsolationContrast = when {
    scroll == null || minimal == null -> IsolationContrast.INCONCLUSIVE
    scroll.sawMalformed && minimal.collection == IsolationCollection.READY_ONLY -> IsolationContrast.MALFORMED_ISOLATED_TO_SCROLL
    scroll.sawMalformed && minimal.sawMalformed -> IsolationContrast.MALFORMED_ON_BOTH
    scroll.collection == IsolationCollection.READY_ONLY && minimal.collection == IsolationCollection.READY_ONLY -> IsolationContrast.NO_MALFORMED_OBSERVED
    else -> IsolationContrast.INCONCLUSIVE
}

/** Observed equality, NOT validity. Time alone or matching relation enums cannot establish stability. */
internal class ObservedGeometryStability<T> {
    private var previous: T? = null
    private var since = 0L
    private var count = 0
    fun observe(key: T, layoutReady: Boolean, now: Long): Boolean {
        if (!layoutReady) { previous = null; count = 0; return false }
        if (previous != key || now < since) { previous = key; since = now; count = 1; return false }
        count++
        return count >= 3 && now - since >= 1_000
    }
}
