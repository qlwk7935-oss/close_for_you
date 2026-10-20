package kr.closeforyou.servicetest

internal enum class ScrollClipPhase { OUTSIDE_BEFORE, INSIDE, OUTSIDE_AFTER }
internal enum class ScrollClipShape { ORDERED, EMPTY, REVERSED_X, REVERSED_Y, REVERSED_BOTH }
internal enum class ScrollClipPosition { INSIDE, OUTSIDE, PARTIAL, INVALID }
internal enum class ScrollClipIssue {
    FIXTURE_INVALID, IDENTITY_UNCONFIRMED, OWNERSHIP_REJECTED, POSITION_UNCONFIRMED,
    ENVIRONMENT_CHANGED, UNSTABLE, RESPONSE_REJECTED, INVALID_DIAGNOSTIC, OVERFLOW,
    UNOBSERVED, UNEXPECTED_BLOCK, SNAPSHOT_REGRESSED, PHASE_ORDER, FINAL_MISSING, TIMEOUT,
}
internal class ScrollClipFailure(val issue: ScrollClipIssue) : RuntimeException(issue.name)
internal enum class ScrollClipService { UNOBSERVED, MALFORMED_ONLY, ASSESSED_ONLY, MIXED }
internal enum class ScrollClipSource { HOST_NATIVE, UIAUTOMATION_SEPARATE }
internal enum class ScrollClipContrast { REVERSED_Y_OUTSIDE_ONLY, OTHER_PATTERN, INCONCLUSIVE }

/** Pure test checks only: native/remote identity facts must be read by the Android owner.
 * No screen coordinates are corrected, nor is a protection receipt or F1 approval issued. */
internal object ScrollClipContract {
    fun contrast(shapes: List<ScrollClipShape>): ScrollClipContrast = when {
        shapes.size != 3 -> ScrollClipContrast.INCONCLUSIVE
        shapes[0] == ScrollClipShape.REVERSED_Y && shapes[1] == ScrollClipShape.ORDERED && shapes[2] == ScrollClipShape.REVERSED_Y ->
            ScrollClipContrast.REVERSED_Y_OUTSIDE_ONLY
        else -> ScrollClipContrast.OTHER_PATTERN
    }
    fun codes(phase: ScrollClipPhase, source: ScrollClipSource, position: ScrollClipPosition,
        shape: ScrollClipShape, visible: Boolean) =
        "F1_SCROLL_CLIP_SAMPLE;PHASE=${phase.name};SOURCE=${source.name};IDENTITY=CONFIRMED;POSITION=${position.name};" +
            "NODE_RECT=${shape.name};NODE_VISIBILITY=${if (visible) "VISIBLE" else "NOT_VISIBLE"};NODE_ANCESTRY=SCROLL_ANCESTOR;ATOMIC_BINDING=NONE"
    fun requireIdentity(sameViewAndPath: Boolean, matches: Int, parentMatches: Boolean, scrollAncestorMatches: Boolean) {
        if (!sameViewAndPath || matches != 1 || !parentMatches || !scrollAncestorMatches)
            throw ScrollClipFailure(ScrollClipIssue.IDENTITY_UNCONFIRMED)
    }
    fun requireOwner(expected: Int, packageName: String?, actual: Int) {
        if (expected < 0 || packageName != "kr.closeforyou.capturetest" || actual != expected)
            throw ScrollClipFailure(ScrollClipIssue.OWNERSHIP_REJECTED)
    }
    fun <T> owned(expected: Int, packageName: () -> String?, actual: () -> Int, data: () -> T): T {
        requireOwner(expected, packageName(), actual())
        return data()
    }
    fun shape(rect: IsolationRect): ScrollClipShape = when {
        rect.right < rect.left && rect.bottom < rect.top -> ScrollClipShape.REVERSED_BOTH
        rect.right < rect.left -> ScrollClipShape.REVERSED_X
        rect.bottom < rect.top -> ScrollClipShape.REVERSED_Y
        rect.right == rect.left || rect.bottom == rect.top -> ScrollClipShape.EMPTY
        else -> ScrollClipShape.ORDERED
    }
    /** Unclipped host layout bounds classify placement; accessibility bounds are separate evidence. */
    fun position(viewport: IsolationRect, childLayout: IsolationRect): ScrollClipPosition = when {
        !viewport.positive || !childLayout.positive -> ScrollClipPosition.INVALID
        childLayout.relation(viewport) in setOf(RectRelation.EQUAL, RectRelation.WITHIN) -> ScrollClipPosition.INSIDE
        childLayout.relation(viewport) == RectRelation.DISJOINT -> ScrollClipPosition.OUTSIDE
        else -> ScrollClipPosition.PARTIAL
    }
    fun requirePlacement(phase: ScrollClipPhase, position: ScrollClipPosition, nativeVisible: Boolean, remoteVisible: Boolean) {
        val inside = phase == ScrollClipPhase.INSIDE
        if (position != (if (inside) ScrollClipPosition.INSIDE else ScrollClipPosition.OUTSIDE) ||
            nativeVisible != inside || remoteVisible != inside)
            throw ScrollClipFailure(ScrollClipIssue.POSITION_UNCONFIRMED)
    }

    /** Full per-attempt tuples only, bounded and allowlisted. Blocks remain BLOCKED, never F1 success. */
    fun assess(code: String?, attempts: List<String>?, overflow: Any?, floor: Int): ScrollClipReport {
        fun reject(issue: ScrollClipIssue): Nothing = throw ScrollClipFailure(issue)
        if (code != "OBSERVED") reject(ScrollClipIssue.RESPONSE_REJECTED)
        if (attempts == null || overflow !is Boolean) reject(ScrollClipIssue.INVALID_DIAGNOSTIC)
        if (overflow || attempts.size > 256) reject(ScrollClipIssue.OVERFLOW)
        val rows = linkedMapOf<Int, String>()
        var malformed = false
        var assessed = false
        for (encoded in attempts) {
            if (encoded.length > 2_048) reject(ScrollClipIssue.INVALID_DIAGNOSTIC)
            val safe = DetectionAttemptOutput.format(encoded)
            if (safe == "ATTEMPT_DIAGNOSTIC_INVALID") reject(ScrollClipIssue.INVALID_DIAGNOSTIC)
            val fields = safe.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }
            val ordinal = fields.getValue("ATTEMPT").toInt()
            if (ordinal <= floor || rows.put(ordinal, safe) != null) reject(ScrollClipIssue.INVALID_DIAGNOSTIC)
            if (fields["RESULT"] == "BLOCKED") {
                val required = mapOf("BLOCK" to "UNREADABLE_TREE", "STEP" to "SNAPSHOT_PASS", "DETAIL" to "TREE_PROTECTION",
                    "TREE" to "TARGET", "FIELD" to "NONE", "CAUSE" to "MALFORMED_NODE_BOUNDS", "RELATED" to "NONE",
                    "FRAME" to "VERIFIED", "OP" to "NODE_BOUNDS_VALIDATE", "EXCEPTION" to "NONE", "NODE_ROLE" to "DESCENDANT")
                if (required.any { (k, v) -> fields[k] != v } || fields["NODE_RECT"] !in setOf("REVERSED_X", "REVERSED_Y", "REVERSED_BOTH") ||
                    fields["NODE_VISIBILITY"] !in setOf("VISIBLE", "NOT_VISIBLE") || fields["NODE_ANCESTRY"] != "SCROLL_ANCESTOR")
                    reject(ScrollClipIssue.UNEXPECTED_BLOCK)
                malformed = true
            } else {
                // Reuse the exact single-row consistency checks; this is observation, not candidate approval.
                val result = MinimalGeometryContract.assess("OBSERVED", listOf(encoded), false)
                if (result.issue != GeometryIssue.NONE) reject(ScrollClipIssue.INVALID_DIAGNOSTIC)
                assessed = true
            }
        }
        val kind = when {
            malformed && assessed -> ScrollClipService.MIXED
            malformed -> ScrollClipService.MALFORMED_ONLY
            assessed -> ScrollClipService.ASSESSED_ONLY
            else -> ScrollClipService.UNOBSERVED
        }
        return ScrollClipReport(kind, rows)
    }
}

/** Actual driver and JVM share reset/host-action ordering; first phase retains its own launch records. */
internal object ScrollClipTransition {
    suspend fun run(phase: ScrollClipPhase, reset: suspend () -> Unit, active: suspend () -> Unit, move: () -> Unit) {
        if (phase != ScrollClipPhase.OUTSIDE_BEFORE) reset()
        active()
        move()
    }
}
internal class ScrollClipReport(val kind: ScrollClipService, val rows: Map<Int, String>) {
    override fun toString() = "ScrollClipReport(kind=${kind.name})"
}

/** Same loop used by Android and JVM. Late successful reads cannot escape the deadline. */
internal object ScrollClipObservation {
    suspend fun await(now: () -> Long, read: suspend () -> ScrollClipReport, pause: suspend (Long) -> Unit) {
        val start = now()
        while (true) {
            if (now() - start >= 3_000) throw ScrollClipFailure(ScrollClipIssue.TIMEOUT)
            val result = read()
            val elapsed = now() - start
            if (elapsed >= 3_000) throw ScrollClipFailure(ScrollClipIssue.TIMEOUT)
            if (result.kind != ScrollClipService.UNOBSERVED && elapsed >= 2_000) return
            pause(200)
        }
    }
}

/** Ordered observation completeness only. No F0/F1/currentness/TTL proof or executable token. */
internal class ScrollClipProgress {
    class Scope internal constructor(val phase: ScrollClipPhase, internal val floor: Int) {
        internal var previous = emptyMap<Int, String>()
        internal var final: PreparedFinalDeadline? = null
        override fun toString() = "ScrollClipScope(phase=${phase.name})"
    }
    private var next = 0
    private var floor = 0
    private var active: Scope? = null
    private var failed = false
    suspend fun phase(phase: ScrollClipPhase, checks: suspend (Scope) -> Unit) {
        if (failed || active != null || phase.ordinal != next) stop(ScrollClipIssue.PHASE_ORDER)
        val scope = Scope(phase, floor).also { active = it }
        try {
            checks(scope)
            if (failed || active !== scope) stop(ScrollClipIssue.PHASE_ORDER)
            val deadline = scope.final ?: stop(ScrollClipIssue.FINAL_MISSING)
            deadline.check()
            floor = scope.previous.keys.maxOrNull() ?: stop(ScrollClipIssue.UNOBSERVED)
            scope.previous = emptyMap()
            active = null
            next++
            deadline.check()
        } catch (failure: Exception) { abort(); throw failure }
    }
    fun assess(scope: Scope, code: String?, attempts: List<String>?, overflow: Any?, final: PreparedFinalDeadline? = null): ScrollClipReport {
        if (failed || active !== scope || scope.final != null) stop(ScrollClipIssue.PHASE_ORDER)
        val result = ScrollClipContract.assess(code, attempts, overflow, scope.floor)
        if (scope.previous.any { (id, row) -> result.rows[id] != row }) stop(ScrollClipIssue.SNAPSHOT_REGRESSED)
        if (final != null && result.rows.isEmpty()) stop(ScrollClipIssue.UNOBSERVED)
        scope.previous = result.rows
        if (final != null) { final.check(); scope.final = final; final.check() }
        return result
    }
    fun abort() { failed = true; active?.previous = emptyMap(); active?.final = null; active = null }
    fun outcome(cleanup: Boolean, readsClean: Boolean) =
        if (!failed && next == 3 && cleanup && readsClean) "OBSERVATIONS_COMPLETE" else "INCONCLUSIVE"
    private fun stop(issue: ScrollClipIssue): Nothing { abort(); throw ScrollClipFailure(issue) }
    override fun toString() = "ScrollClipProgress(codes_only)"
}
