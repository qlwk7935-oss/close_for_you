package kr.closeforyou.servicetest

internal enum class LayoutPhase { PREPARATION, BASELINE, LAYOUT_CHANGE;
    // Reuse ordinary-screen validation/polling, never the positive Dialog-candidate branch.
    val observationPhase get() = if (this == PREPARATION) PreparedPhase.PREPARATION else PreparedPhase.BASELINE
}
internal enum class LayoutStage { START, PREPARATION_VERIFIED, BASELINE_VERIFIED, LAYOUT_CHANGED, CHANGE_VERIFIED, ABORTED }
internal enum class LayoutIssue { FIXTURE_INVALID, LAYOUT_NOT_CHANGED, ENVIRONMENT_CHANGED }
internal class LayoutFailure(val issue: LayoutIssue) : RuntimeException(issue.name)

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
