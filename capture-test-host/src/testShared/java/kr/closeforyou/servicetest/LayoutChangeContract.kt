package kr.closeforyou.servicetest

internal enum class LayoutPhase { PREPARATION, BASELINE, LAYOUT_CHANGE;
    // Reuse ordinary-screen validation/polling, never the positive Dialog-candidate branch.
    val observationPhase get() = if (this == PREPARATION) PreparedPhase.PREPARATION else PreparedPhase.BASELINE
}
internal enum class LayoutStage { START, PREPARATION_VERIFIED, BASELINE_VERIFIED, LAYOUT_CHANGED, CHANGE_VERIFIED, ABORTED }
internal enum class LayoutIssue { FIXTURE_INVALID, LAYOUT_NOT_CHANGED, ENVIRONMENT_CHANGED }
internal class LayoutFailure(val issue: LayoutIssue) : RuntimeException(issue.name)

internal enum class LayoutWaitPoint { NOT_STARTED, HOST_BEFORE_CHECK, RESULT_RECEIVED, HOST_AFTER_CHECK, ASSESSED }
internal enum class LayoutWaitReasons { NONE, UNCHANGED_ONLY, BASELINE_ONLY_ONLY, ORDINARY_CONTENT_CHANGE_SEEN, MIXED, OTHER }
internal enum class LayoutWaitFailure { OBSERVATION_TIMEOUT, ASSESSMENT_REJECTED, HOST_REJECTED, OBSERVATION_FAILED, CANCELLED }
internal enum class LayoutAttemptCount { NONE, ONE, MULTIPLE }

/** One phase, constant-size codes only. No reply, row, callback, exception or clock is retained.
 * Historical rows/expected flags describe accepted assessments, not the latest unvalidated IPC.
 * This observer never grants phase completion; the unchanged progress/deadline contracts do that.
 */
internal class LayoutWaitDiagnostics(private val phase: LayoutPhase) {
    var lastPoint = LayoutWaitPoint.NOT_STARTED
        private set
    var responseReceived = false
        private set
    var rowsSeen = false
        private set
    var reasons = LayoutWaitReasons.NONE
        private set
    var expectedSeen = false
        private set
    var finalReached = false
        private set
    var ownedAttempts = LayoutAttemptCount.NONE
        private set
    var baselineOnlyAttempts = LayoutAttemptCount.NONE
        private set

    suspend fun <T> await(now: () -> Long, anchor: () -> Unit, query: suspend () -> T,
        assess: (T) -> PreparedAssessment, pause: suspend (Long) -> Unit) =
        PreparedDialogObservation.await(phase.observationPhase, now,
            { read(anchor, query, assess) }, pause)

    /** The same wrapper is used by Android IPC and deterministic JVM callbacks. */
    suspend fun <T> read(anchor: () -> Unit, query: suspend () -> T, assess: (T) -> PreparedAssessment): PreparedAssessment {
        lastPoint = LayoutWaitPoint.HOST_BEFORE_CHECK
        anchor()
        val reply = query()
        received()
        anchor() // Failure here leaves RESULT_RECEIVED, never ASSESSED.
        lastPoint = LayoutWaitPoint.HOST_AFTER_CHECK
        return assessed { assess(reply) }
    }

    fun beginFinalResult() { finalReached = true }
    fun received() { responseReceived = true; lastPoint = LayoutWaitPoint.RESULT_RECEIVED }

    fun assessed(check: () -> PreparedAssessment): PreparedAssessment {
        val result = check() // Rejections/cancellation propagate without promoting this response.
        lastPoint = LayoutWaitPoint.ASSESSED
        if (result.rows.isNotEmpty()) rowsSeen = true
        if (result.verdict == PreparedMinimalDialogContract.expected(phase.observationPhase)) expectedSeen = true
        var unchanged = false
        var baseline = false
        var ordinary = false
        var other = false
        var baselineCount = LayoutAttemptCount.NONE
        // Recompute from this validated cumulative ordinal map, never from query count.
        ownedAttempts = when (result.rows.size) {
            0 -> LayoutAttemptCount.NONE
            1 -> LayoutAttemptCount.ONE
            else -> LayoutAttemptCount.MULTIPLE
        }
        // Canonical rows have already passed the real phase parser/validator. Do not retain them.
        for (row in result.rows.values) when {
            ";REASON=UNCHANGED;" in row -> unchanged = true
            ";REASON=BASELINE_ONLY;" in row -> {
                baseline = true
                baselineCount = if (baselineCount == LayoutAttemptCount.NONE) LayoutAttemptCount.ONE else LayoutAttemptCount.MULTIPLE
            }
            ";REASON=ORDINARY_CONTENT_CHANGE;" in row -> ordinary = true
            else -> other = true
        }
        reasons = when {
            ordinary -> LayoutWaitReasons.ORDINARY_CONTENT_CHANGE_SEEN
            other -> LayoutWaitReasons.OTHER
            unchanged && baseline -> LayoutWaitReasons.MIXED
            unchanged -> LayoutWaitReasons.UNCHANGED_ONLY
            baseline -> LayoutWaitReasons.BASELINE_ONLY_ONLY
            else -> LayoutWaitReasons.NONE
        }
        baselineOnlyAttempts = baselineCount
        return result
    }

    /** Called once on the failure path, outside polling/final acceptance. Existing cleanup
     * reporting preserves an unwinding cancellation and defers report-only cancellation.
     */
    fun reportFailure(detail: LayoutWaitFailure, cleanup: PreparedDialogCleanup, emit: (String) -> Unit) = cleanup.report {
        emit("F1_LAYOUT_WAIT;PHASE=${phase.name};LAST_POINT=${lastPoint.name};" +
            "RESULT_RECEIVED=${if (responseReceived) "YES" else "NO"};ROWS=${if (rowsSeen) "PRESENT" else "NONE"};" +
            "OWNED_ATTEMPTS=${ownedAttempts.name};BASELINE_ONLY_ATTEMPTS=${baselineOnlyAttempts.name};" +
            "REASONS=${reasons.name};EXPECTED_SEEN=${if (expectedSeen) "YES" else "NO"};" +
            "FINAL_RESULT=${if (finalReached) "REACHED" else "NOT_REACHED"};DETAIL=${detail.name}")
    }
    override fun toString() = "LayoutWaitDiagnostics(phase=${phase.name},point=${lastPoint.name})"
}

/** Exact fixture bounds only, not a correction/tolerance for observed Android bounds. */
internal object LayoutMutation {
    fun changed(before: List<IsolationRect>, after: List<IsolationRect>): Boolean {
        if (before.size != 2 || after.size != 2 || (before + after).any { !it.positive })
            throw LayoutFailure(LayoutIssue.FIXTURE_INVALID)
        val container = before[0]
        val original = before[1]
        val child = after[1]
        if (container != after[0] || original != container) throw LayoutFailure(LayoutIssue.ENVIRONMENT_CHANGED)
        if (child == original) return false // Not evidence of a completed layout mutation.
        val height = original.bottom.toLong() - original.top
        if (height < 2 || child.left != original.left || child.top != original.top || child.right != original.right ||
            child.bottom.toLong() != original.top.toLong() + height / 2 || child.relation(container) != RectRelation.WITHIN)
            throw LayoutFailure(LayoutIssue.FIXTURE_INVALID)
        return true
    }
}

/** Real service rows are still parsed/checked by the existing Prepared contract. */
internal object LayoutChangeContract {
    fun assess(phase: LayoutPhase, code: String?, attempts: List<String>, overflow: Boolean, floor: Int): PreparedAssessment {
        // Inspect all bounded, allowlisted rows for a reported candidate, even when another
        // row would make ordinary-screen validation stop first. This can only fail the test.
        if (code == "OBSERVED" && attempts.size <= 256 && attempts.any { encoded ->
                encoded.length <= 2_048 && DetectionAttemptOutput.format(encoded).let { row ->
                    ";RESULT=ASSESSED;DISPOSITION=POPUP_CANDIDATE;" in ";$row;"
                }
            }) return PreparedAssessment(PreparedVerdict.BASELINE_FALSE_CANDIDATE)
        val result = PreparedMinimalDialogContract.assess(phase.observationPhase, code, attempts, overflow, floor)
        if (phase == LayoutPhase.LAYOUT_CHANGE && result.verdict == PreparedVerdict.BASELINE_CONFIRMED &&
            result.rows.values.none { ";REASON=ORDINARY_CONTENT_CHANGE;" in it })
            return PreparedAssessment(PreparedVerdict.BASELINE_INSUFFICIENT, result.rows)
        return result
    }
    fun failureOutcome(verdict: PreparedVerdict) =
        if (verdict == PreparedVerdict.BASELINE_FALSE_CANDIDATE) "FALSE_POSITIVE" else "INCONCLUSIVE"
}

/** Test-only ordered orchestration. No Android F0/currentness/TTL proof is issued. */
internal class LayoutChangeProgress {
    class Scope internal constructor(val phase: LayoutPhase, internal val floor: Int) {
        internal var previous = emptyMap<Int, String>()
        internal var final: PreparedAssessment? = null
        internal var deadline: PreparedFinalDeadline? = null
        override fun toString() = "LayoutScope(phase=${phase.name})"
    }
    var stage = LayoutStage.START
        private set
    private var active: Scope? = null
    private var completedOrdinal = 0

    fun assess(scope: Scope, code: String?, rows: List<String>, overflow: Boolean, final: Boolean = false,
        deadline: PreparedFinalDeadline? = null): PreparedAssessment {
        if (active !== scope || scope.final != null) stop(PreparedVerdict.STAGE_ORDER_INVALID)
        val result = LayoutChangeContract.assess(scope.phase, code, rows, overflow, scope.floor)
        val waiting = PreparedMinimalDialogContract.awaitingFirstAttempt(scope.phase.observationPhase, result)
        if (waiting && scope.previous.isNotEmpty()) stop(PreparedVerdict.SNAPSHOT_REGRESSED)
        val expected = PreparedMinimalDialogContract.expected(scope.phase.observationPhase)
        if (result.verdict != expected && result.verdict != PreparedVerdict.BASELINE_INSUFFICIENT && !(waiting && !final))
            stop(result.verdict)
        if (scope.previous.any { (id, row) -> result.rows[id] != row }) stop(PreparedVerdict.SNAPSHOT_REGRESSED)
        scope.previous = result.rows
        if (final) {
            PreparedMinimalDialogContract.requireExpected(scope.phase.observationPhase, result.verdict)
            val limit = deadline ?: stop(PreparedVerdict.FINAL_RESULT_MISSING)
            limit.check()
            scope.deadline = limit
            scope.final = result
            limit.check()
        }
        return result
    }
    suspend fun preparation(checks: suspend (Scope) -> Unit) = verify(LayoutPhase.PREPARATION,
        LayoutStage.START, LayoutStage.PREPARATION_VERIFIED, checks)
    suspend fun baseline(checks: suspend (Scope) -> Unit) = verify(LayoutPhase.BASELINE,
        LayoutStage.PREPARATION_VERIFIED, LayoutStage.BASELINE_VERIFIED, checks)
    suspend fun change(checks: suspend (Scope) -> Unit) = verify(LayoutPhase.LAYOUT_CHANGE,
        LayoutStage.LAYOUT_CHANGED, LayoutStage.CHANGE_VERIFIED, checks)

    suspend fun <T> changeAndVerifyHost(checks: suspend () -> T): T {
        if (stage != LayoutStage.BASELINE_VERIFIED) stop(PreparedVerdict.STAGE_ORDER_INVALID)
        try {
            val result = checks()
            if (stage != LayoutStage.BASELINE_VERIFIED) stop(PreparedVerdict.STAGE_ORDER_INVALID)
            stage = LayoutStage.LAYOUT_CHANGED
            return result
        } catch (failure: Exception) { abort(); throw failure }
    }
    private suspend fun verify(phase: LayoutPhase, expected: LayoutStage, next: LayoutStage,
        checks: suspend (Scope) -> Unit) {
        if (stage != expected || active != null) stop(PreparedVerdict.STAGE_ORDER_INVALID)
        val scope = Scope(phase, completedOrdinal).also { active = it }
        try {
            checks(scope)
            if (stage != expected || active !== scope) stop(PreparedVerdict.STAGE_ORDER_INVALID)
            val result = scope.final ?: stop(PreparedVerdict.FINAL_RESULT_MISSING)
            val limit = scope.deadline ?: stop(PreparedVerdict.FINAL_RESULT_MISSING)
            val maximum = result.maximumOrdinal
            limit.check()
            completedOrdinal = maximum
            scope.previous = emptyMap()
            active = null
            stage = next
            limit.check()
        } catch (failure: Exception) { abort(); throw failure }
    }
    fun abort() {
        active?.previous = emptyMap(); active?.final = null; active?.deadline = null
        active = null; stage = LayoutStage.ABORTED
    }
    fun outcome(cleanup: Boolean, readsClean: Boolean) =
        if (stage == LayoutStage.CHANGE_VERIFIED && cleanup && readsClean) "OBSERVED_NEGATIVE_CONTROL" else "INCONCLUSIVE"
    private fun stop(verdict: PreparedVerdict): Nothing { abort(); throw PreparedFailure(verdict) }
    override fun toString() = "LayoutChangeProgress(stage=${stage.name})"
}
