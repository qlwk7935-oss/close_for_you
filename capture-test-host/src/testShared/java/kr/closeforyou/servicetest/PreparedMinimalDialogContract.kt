package kr.closeforyou.servicetest

internal enum class PreparedPhase { PREPARATION, BASELINE, DIALOG }
internal enum class PreparedStage { START, PREPARATION_VERIFIED, BASELINE_VERIFIED, DIALOG_WINDOW_VERIFIED, DIALOG_VERIFIED, ABORTED }
internal enum class PreparedVerdict {
    PREPARATION_RECORDED, BASELINE_CONFIRMED, DIALOG_CONFIRMED,
    RESPONSE_REJECTED, OVERFLOW, UNOBSERVED, INVALID_DIAGNOSTIC, COLLECTION_BLOCKED,
    GEOMETRY_REJECTED, BASELINE_INSUFFICIENT, BASELINE_FALSE_CANDIDATE,
    CANDIDATE_NOT_SEEN, UNEXPECTED_ASSESSMENT, DETECTOR_UNCERTAIN,
    OBSERVATION_TIMEOUT, STAGE_ORDER_INVALID, FINAL_RESULT_MISSING,
    PHASE_ATTEMPT_REUSED, SNAPSHOT_REGRESSED,
}
internal class PreparedFailure(val verdict: PreparedVerdict) : RuntimeException(verdict.name)

/** Test-local monotonic deadline. No externally supplied timestamp or source payload. */
internal class PreparedFinalDeadline private constructor(private val now: () -> Long, private val start: Long) {
    fun check() {
        if (now() - start >= 3_000) throw PreparedFailure(PreparedVerdict.OBSERVATION_TIMEOUT)
    }
    override fun toString() = "PreparedFinalDeadline(redacted)"
    companion object { fun start(now: () -> Long) = PreparedFinalDeadline(now, now()) }
}

/** Metadata must be accepted BEFORE any geometry/text/child getter. Actual remote reader uses this. */
internal object PreparedNodeOwnership {
    fun <T> read(expectedWindow: Int, packageName: () -> String?, windowId: () -> Int, data: () -> T): T {
        if (packageName() != "kr.closeforyou.capturetest" || expectedWindow < 0 || windowId() != expectedWindow)
            throw GeometryDiagnosticFailure(GeometryIssue.WINDOW_MISMATCH)
        return data()
    }
}

internal enum class PreparedCleanupStep { SESSION_END, DIALOG_DISMISS, ACTIVITY_FINISH, FLAGS_RESTORE }

/** Shared real finally/JVM orchestration. Codes only; callbacks/exceptions are not retained.
 * Caller supplies NonCancellable on Android. A pre-existing exception/cancellation can unwind
 * through run(); a cancellation arising only in cleanup is rethrown after finally by propagateCancellation().
 */
internal class PreparedDialogCleanup {
    private val failed = linkedSetOf<PreparedCleanupStep>()
    private var cancelled = false
    private var started = false
    private var completed = false
    var reportingFailed = false
        private set
    val failures: Set<PreparedCleanupStep> get() = failed.toSet()
    val clean: Boolean get() = completed && failed.isEmpty() && !cancelled && !reportingFailed

    suspend fun run(end: suspend () -> Unit, dismiss: suspend () -> Unit,
        finish: suspend () -> Unit, restoreFlags: suspend () -> Unit) {
        check(!started) { "PREPARED_CLEANUP_ALREADY_RUN" }
        started = true
        for ((step, action) in listOf(PreparedCleanupStep.SESSION_END to end, PreparedCleanupStep.DIALOG_DISMISS to dismiss,
                PreparedCleanupStep.ACTIVITY_FINISH to finish, PreparedCleanupStep.FLAGS_RESTORE to restoreFlags)) {
            try { action() }
            catch (_: java.util.concurrent.CancellationException) { cancelled = true; failed += step }
            catch (_: Exception) { failed += step }
        }
        completed = true
    }

    fun propagateCancellation() {
        if (cancelled) throw java.util.concurrent.CancellationException("PREPARED_CLEANUP_CANCELLED")
    }
    /** Reporting IPC must not replace a body cancellation while finally is unwinding. */
    fun report(action: () -> Unit) {
        try { action() }
        catch (_: java.util.concurrent.CancellationException) { cancelled = true; reportingFailed = true }
        catch (_: Exception) { reportingFailed = true }
    }
    override fun toString() = "PreparedDialogCleanup(codes_only)"
}

/** Allowlisted codes/ordinals only. No caller-provided source payload is retained. */
internal class PreparedAssessment(val verdict: PreparedVerdict, val rows: Map<Int, String> = emptyMap()) {
    val maximumOrdinal get() = rows.keys.maxOrNull() ?: 0
    override fun toString() = "PreparedAssessment(verdict=${verdict.name})"
}

internal object PreparedMinimalDialogContract {
    fun expected(phase: PreparedPhase) = when (phase) {
        PreparedPhase.PREPARATION -> PreparedVerdict.PREPARATION_RECORDED
        PreparedPhase.BASELINE -> PreparedVerdict.BASELINE_CONFIRMED
        PreparedPhase.DIALOG -> PreparedVerdict.DIALOG_CONFIRMED
    }

    /** Scope/session/window selection must come from real IPC orchestration, not these enums. */
    fun assess(phase: PreparedPhase, code: String?, attempts: List<String>, overflow: Boolean,
        afterOrdinal: Int = 0): PreparedAssessment {
        fun reject(verdict: PreparedVerdict) = PreparedAssessment(verdict)
        if (code != "OBSERVED") return reject(PreparedVerdict.RESPONSE_REJECTED)
        if (overflow || attempts.size > 256) return reject(PreparedVerdict.OVERFLOW)
        if (attempts.isEmpty()) return reject(PreparedVerdict.UNOBSERVED)
        val rows = linkedMapOf<Int, String>()
        var evidence = false
        for (encoded in attempts) {
            if (encoded.length > 2_048) return reject(PreparedVerdict.INVALID_DIAGNOSTIC)
            val safe = DetectionAttemptOutput.format(encoded)
            if (safe == "ATTEMPT_DIAGNOSTIC_INVALID") return reject(PreparedVerdict.INVALID_DIAGNOSTIC)
            val fields = safe.split(';').map { it.split('=', limit = 2) }
            if (fields.any { it.size != 2 } || fields.map { it[0] }.toSet().size != fields.size)
                return reject(PreparedVerdict.INVALID_DIAGNOSTIC)
            val row = fields.associate { it[0] to it[1] }
            val ordinal = row.getValue("ATTEMPT").toInt()
            if (rows.put(ordinal, safe) != null) return reject(PreparedVerdict.INVALID_DIAGNOSTIC)
            if (ordinal <= afterOrdinal) return reject(PreparedVerdict.PHASE_ATTEMPT_REUSED)
            if (row["RESULT"] == "BLOCKED") return reject(PreparedVerdict.COLLECTION_BLOCKED)
            val valid = row["GEOMETRY"] == "VALID" && row["FRAME_EXTENT"] == "POSITIVE" &&
                row["WINDOW_EXTENT"] == "POSITIVE" && row["WINDOW_RELATION"] == "WITHIN_FRAME" && row["OUTSIDE_EDGES"] == "NONE"
            val preparationOutside = phase == PreparedPhase.PREPARATION && row["GEOMETRY"] == "WINDOW_OUTSIDE_FRAME" &&
                row["FRAME_EXTENT"] == "POSITIVE" && row["WINDOW_EXTENT"] == "POSITIVE" &&
                row["WINDOW_RELATION"] == "WINDOW_OUTSIDE_FRAME" && row["OUTSIDE_EDGES"] !in setOf(null, "NONE") &&
                row["DISPOSITION"] == "UNCERTAIN" && row["REASON"] == "INVALID_GEOMETRY"
            if (preparationOutside) continue // Recorded rejected geometry; never baseline evidence.
            if (!valid) return reject(PreparedVerdict.GEOMETRY_REJECTED)
            val disposition = row["DISPOSITION"]
            val reason = row["REASON"]
            if (disposition == "UNCERTAIN") return reject(PreparedVerdict.DETECTOR_UNCERTAIN)
            if (phase == PreparedPhase.DIALOG) {
                if (disposition == "POPUP_CANDIDATE" && reason == "BOUNDED_WINDOW") evidence = true
                else if (disposition != "NO_POPUP" || reason != "UNCHANGED")
                    return reject(PreparedVerdict.UNEXPECTED_ASSESSMENT)
            } else {
                if (disposition == "POPUP_CANDIDATE") return reject(PreparedVerdict.BASELINE_FALSE_CANDIDATE)
                if (disposition != "NO_POPUP" || reason !in setOf("BASELINE_ONLY", "UNCHANGED", "ORDINARY_CONTENT_CHANGE"))
                    return reject(PreparedVerdict.UNEXPECTED_ASSESSMENT)
                if (phase == PreparedPhase.BASELINE && reason in setOf("BASELINE_ONLY", "ORDINARY_CONTENT_CHANGE")) evidence = true
            }
        }
        val verdict = when {
            phase == PreparedPhase.PREPARATION -> PreparedVerdict.PREPARATION_RECORDED
            evidence -> expected(phase)
            phase == PreparedPhase.BASELINE -> PreparedVerdict.BASELINE_INSUFFICIENT
            else -> PreparedVerdict.CANDIDATE_NOT_SEEN
        }
        return PreparedAssessment(verdict, rows.toMap())
    }

    fun requireExpected(phase: PreparedPhase, verdict: PreparedVerdict) {
        if (verdict != expected(phase)) throw PreparedFailure(verdict)
    }

    fun failureOutcome(verdict: PreparedVerdict) = when (verdict) {
        PreparedVerdict.BASELINE_FALSE_CANDIDATE, PreparedVerdict.CANDIDATE_NOT_SEEN,
        PreparedVerdict.UNEXPECTED_ASSESSMENT -> "FAILURE"
        else -> "INCONCLUSIVE"
    } // No exception verdict, including a phase-success code, can return OBSERVED.
}

/** Actual instrumentation polling, also exercised with JVM fake time/IPC. */
internal object PreparedDialogObservation {
    /** One deadline spans IPC, diagnostics, validation AND marker acceptance. The marker/phase
     * also recheck this same deadline. Non-cooperative calls are not forcibly interrupted.
     */
    suspend fun <T> finalSnapshot(now: () -> Long, read: suspend () -> T, accept: (T, PreparedFinalDeadline) -> Unit) {
        val deadline = PreparedFinalDeadline.start(now)
        deadline.check()
        val result = read()
        deadline.check()
        accept(result, deadline)
        deadline.check()
    }

    suspend fun await(phase: PreparedPhase, now: () -> Long, read: suspend () -> PreparedAssessment,
        pause: suspend (Long) -> Unit) {
        val start = now()
        while (true) {
            if (now() - start >= 3_000) throw PreparedFailure(PreparedVerdict.OBSERVATION_TIMEOUT)
            val result = read()
            val elapsed = now() - start
            if (elapsed >= 3_000) throw PreparedFailure(PreparedVerdict.OBSERVATION_TIMEOUT)
            if (result.verdict != PreparedMinimalDialogContract.expected(phase) && result.verdict !in setOf(
                    PreparedVerdict.BASELINE_INSUFFICIENT, PreparedVerdict.CANDIDATE_NOT_SEEN)) throw PreparedFailure(result.verdict)
            if (result.verdict == PreparedMinimalDialogContract.expected(phase) && elapsed >= 2_000) return
            pause(200)
        }
    }
}

/** One synthetic-test orchestration owner, not Android F0/currentness/TTL evidence. */
internal class PreparedDialogProgress {
    class Scope internal constructor(internal val phase: PreparedPhase, internal val floor: Int) {
        internal var previous = emptyMap<Int, String>()
        internal var final: PreparedAssessment? = null
        internal var finalDeadline: PreparedFinalDeadline? = null
        override fun toString() = "PreparedScope(phase=${phase.name})"
    }
    var stage = PreparedStage.START
        private set
    private var active: Scope? = null
    private var completedOrdinal = 0

    fun assess(scope: Scope, code: String?, attempts: List<String>, overflow: Boolean, final: Boolean = false,
        deadline: PreparedFinalDeadline? = null): PreparedAssessment {
        if (active !== scope || scope.final != null) stop(PreparedVerdict.STAGE_ORDER_INVALID)
        val result = PreparedMinimalDialogContract.assess(scope.phase, code, attempts, overflow, scope.floor)
        val acceptable = result.verdict in setOf(PreparedMinimalDialogContract.expected(scope.phase),
            PreparedVerdict.BASELINE_INSUFFICIENT, PreparedVerdict.CANDIDATE_NOT_SEEN)
        if (!acceptable) stop(result.verdict)
        if (acceptable) {
            if (scope.previous.any { (ordinal, row) -> result.rows[ordinal] != row }) stop(PreparedVerdict.SNAPSHOT_REGRESSED)
            scope.previous = result.rows
        }
        if (final) {
            PreparedMinimalDialogContract.requireExpected(scope.phase, result.verdict)
            val finalDeadline = deadline ?: stop(PreparedVerdict.FINAL_RESULT_MISSING)
            finalDeadline.check() // No diagnostics, suspend or user callback between check and marker writes.
            scope.finalDeadline = finalDeadline
            scope.final = result
            finalDeadline.check()
        }
        return result
    }

    suspend fun verifyPreparation(checks: suspend (Scope) -> Unit) = verify(PreparedPhase.PREPARATION,
        PreparedStage.START, PreparedStage.PREPARATION_VERIFIED, checks)
    suspend fun verifyBaseline(checks: suspend (Scope) -> Unit) = verify(PreparedPhase.BASELINE,
        PreparedStage.PREPARATION_VERIFIED, PreparedStage.BASELINE_VERIFIED, checks)
    suspend fun verifyDialog(checks: suspend (Scope) -> Unit) = verify(PreparedPhase.DIALOG,
        PreparedStage.DIALOG_WINDOW_VERIFIED, PreparedStage.DIALOG_VERIFIED, checks)

    suspend fun <T> showAndVerifyDialogWindow(checks: suspend () -> T): T {
        if (stage != PreparedStage.BASELINE_VERIFIED) stop(PreparedVerdict.STAGE_ORDER_INVALID)
        try {
            val result = checks()
            if (stage != PreparedStage.BASELINE_VERIFIED) stop(PreparedVerdict.STAGE_ORDER_INVALID)
            stage = PreparedStage.DIALOG_WINDOW_VERIFIED
            return result
        } catch (failure: Exception) { abort(); throw failure }
    }

    private suspend fun verify(phase: PreparedPhase, expected: PreparedStage, next: PreparedStage, checks: suspend (Scope) -> Unit) {
        if (stage != expected || active != null) stop(PreparedVerdict.STAGE_ORDER_INVALID)
        val scope = Scope(phase, completedOrdinal).also { active = it }
        try {
            checks(scope)
            if (stage != expected || active !== scope) stop(PreparedVerdict.STAGE_ORDER_INVALID)
            val result = scope.final ?: throw PreparedFailure(PreparedVerdict.FINAL_RESULT_MISSING)
            val deadline = scope.finalDeadline ?: throw PreparedFailure(PreparedVerdict.FINAL_RESULT_MISSING)
            val lastOrdinal = result.maximumOrdinal
            deadline.check()
            completedOrdinal = lastOrdinal
            scope.previous = emptyMap()
            active = null
            stage = next
            deadline.check() // A late phase transition aborts, never returns a verified phase.
        } catch (failure: Exception) { abort(); throw failure }
    }

    fun abort() { active?.previous = emptyMap(); active?.final = null; active?.finalDeadline = null; active = null; stage = PreparedStage.ABORTED }
    fun outcome(cleanupOk: Boolean, readsClean: Boolean): String =
        if (stage == PreparedStage.DIALOG_VERIFIED && cleanupOk && readsClean) "OBSERVED" else "INCONCLUSIVE"
    private fun stop(verdict: PreparedVerdict): Nothing { abort(); throw PreparedFailure(verdict) }
    override fun toString() = "PreparedDialogProgress(stage=${stage.name})"
}
