package kr.closeforyou.servicetest

internal enum class MinimalDialogPhase { BASELINE, DIALOG }
internal enum class MinimalDialogVerdict {
    BASELINE_CONFIRMED, DIALOG_CONFIRMED, UNOBSERVED, BASELINE_ONLY_NOT_SEEN, DIALOG_CANDIDATE_NOT_SEEN,
    RESPONSE_REJECTED, OVERFLOW, INVALID_DIAGNOSTIC, COLLECTION_BLOCKED, GEOMETRY_REJECTED,
    BASELINE_FALSE_CANDIDATE, UNEXPECTED_ASSESSMENT, DETECTOR_UNCERTAIN,
    OBSERVATION_TIMEOUT, STAGE_ORDER_INVALID,
}

/** No Android or source data. Every reason/disposition/geometry match is within ONE validated row.
 * Window scoping belongs to the existing provider + owned-window reader, not to caller-supplied enums.
 */
internal object MinimalDialogContract {
    fun assess(phase: MinimalDialogPhase, code: String?, attempts: List<String>, overflow: Boolean): MinimalDialogVerdict {
        if (code != "OBSERVED") return MinimalDialogVerdict.RESPONSE_REJECTED
        if (overflow || attempts.size > 256) return MinimalDialogVerdict.OVERFLOW
        if (attempts.isEmpty()) return MinimalDialogVerdict.UNOBSERVED
        val ordinals = mutableSetOf<String>()
        var baseline = false
        var candidate = false
        for (encoded in attempts) {
            if (encoded.length > 2_048) return MinimalDialogVerdict.INVALID_DIAGNOSTIC
            val formatted = DetectionAttemptOutput.format(encoded)
            if (formatted == "ATTEMPT_DIAGNOSTIC_INVALID") return MinimalDialogVerdict.INVALID_DIAGNOSTIC
            val pairs = formatted.split(';').map { it.split('=', limit = 2) }
            if (pairs.any { it.size != 2 } || pairs.map { it[0] }.toSet().size != pairs.size)
                return MinimalDialogVerdict.INVALID_DIAGNOSTIC
            val row = pairs.associate { it[0] to it[1] }
            if (!ordinals.add(row.getValue("ATTEMPT"))) return MinimalDialogVerdict.INVALID_DIAGNOSTIC
            if (row["RESULT"] == "BLOCKED") return MinimalDialogVerdict.COLLECTION_BLOCKED
            if (row["GEOMETRY"] != "VALID" || row["FRAME_EXTENT"] != "POSITIVE" || row["WINDOW_EXTENT"] != "POSITIVE" ||
                row["WINDOW_RELATION"] != "WITHIN_FRAME" || row["OUTSIDE_EDGES"] != "NONE")
                return MinimalDialogVerdict.GEOMETRY_REJECTED
            val disposition = row["DISPOSITION"]
            val reason = row["REASON"]
            if (disposition == "UNCERTAIN" && reason in setOf("UNCONFIRMED_TRANSITION", "UNCONFIRMED_STRUCTURE"))
                return MinimalDialogVerdict.DETECTOR_UNCERTAIN
            if (phase == MinimalDialogPhase.BASELINE) {
                if (disposition == "POPUP_CANDIDATE") return MinimalDialogVerdict.BASELINE_FALSE_CANDIDATE
                if (disposition != "NO_POPUP" || reason !in setOf("BASELINE_ONLY", "UNCHANGED", "ORDINARY_CONTENT_CHANGE"))
                    return MinimalDialogVerdict.UNEXPECTED_ASSESSMENT
                baseline = baseline || reason == "BASELINE_ONLY"
            } else {
                if (disposition == "POPUP_CANDIDATE" && reason in setOf("BOUNDED_WINDOW", "SEMANTIC_DIALOG", "PANE_WITH_UNDERLAY"))
                    candidate = true
                else if (disposition != "NO_POPUP" || reason != "UNCHANGED")
                    return MinimalDialogVerdict.UNEXPECTED_ASSESSMENT
            }
        }
        return if (phase == MinimalDialogPhase.BASELINE) {
            if (baseline) MinimalDialogVerdict.BASELINE_CONFIRMED else MinimalDialogVerdict.BASELINE_ONLY_NOT_SEEN
        } else if (candidate) MinimalDialogVerdict.DIALOG_CONFIRMED else MinimalDialogVerdict.DIALOG_CANDIDATE_NOT_SEEN
    }

    /** Error paths cannot establish whole-experiment success, even with a phase-success verdict.
     * Both categories cause final JUnit failure. Neither is counted as original F1 accuracy.
     */
    fun failureOutcome(verdict: MinimalDialogVerdict): String = when (verdict) {
        MinimalDialogVerdict.BASELINE_FALSE_CANDIDATE, MinimalDialogVerdict.DIALOG_CANDIDATE_NOT_SEEN,
        MinimalDialogVerdict.UNEXPECTED_ASSESSMENT -> "FAILURE"
        else -> "INCONCLUSIVE"
    }

    fun expected(phase: MinimalDialogPhase): MinimalDialogVerdict = if (phase == MinimalDialogPhase.BASELINE)
        MinimalDialogVerdict.BASELINE_CONFIRMED else MinimalDialogVerdict.DIALOG_CONFIRMED

    fun requireExpected(phase: MinimalDialogPhase, verdict: MinimalDialogVerdict) {
        if (verdict != expected(phase)) throw MinimalDialogObservationFailure(verdict)
    }
}

internal class MinimalDialogObservationFailure(val verdict: MinimalDialogVerdict) : RuntimeException(verdict.name)

/** The actual instrumentation polling loop. JVM tests supply a monotonic fake clock and pause.
 * No Android evidence is created here. Blocking IPC is not forcibly interrupted by this deadline.
 */
internal object MinimalDialogObservation {
    suspend fun await(phase: MinimalDialogPhase, now: () -> Long,
        read: suspend () -> MinimalDialogVerdict, pause: suspend (Long) -> Unit) {
        val start = now()
        while (true) {
            if (now() - start >= 3_000) throw MinimalDialogObservationFailure(MinimalDialogVerdict.OBSERVATION_TIMEOUT)
            val verdict = read()
            val elapsed = now() - start
            // A late response, or a resumed delay past the cutoff, never reuses a cached success.
            if (elapsed >= 3_000) throw MinimalDialogObservationFailure(MinimalDialogVerdict.OBSERVATION_TIMEOUT)
            if (verdict != MinimalDialogContract.expected(phase) && verdict !in setOf(
                    MinimalDialogVerdict.UNOBSERVED, MinimalDialogVerdict.BASELINE_ONLY_NOT_SEEN,
                    MinimalDialogVerdict.DIALOG_CANDIDATE_NOT_SEEN))
                throw MinimalDialogObservationFailure(verdict)
            if (verdict == MinimalDialogContract.expected(phase) && elapsed >= 2_000) return
            pause(200)
        }
    }
}

internal enum class MinimalDialogStage { START, BASELINE_VERIFIED, DIALOG_WINDOW_VERIFIED, DIALOG_VERIFIED, ABORTED }

/** Sequential, test-only orchestration, not a receipt of Android F0/currentness/TTL.
 * Instrumentation supplies real checks; JVM callbacks verify control flow only.
 */
internal class MinimalDialogProgress {
    var stage = MinimalDialogStage.START
        private set

    suspend fun verifyBaseline(checks: suspend () -> Unit) = advance(
        MinimalDialogStage.START, MinimalDialogStage.BASELINE_VERIFIED, checks)

    suspend fun <T> showAndVerifyDialogWindow(stimulusAndChecks: suspend () -> T): T = advance(
        MinimalDialogStage.BASELINE_VERIFIED, MinimalDialogStage.DIALOG_WINDOW_VERIFIED, stimulusAndChecks)

    suspend fun verifyDialog(checks: suspend () -> Unit) = advance(
        MinimalDialogStage.DIALOG_WINDOW_VERIFIED, MinimalDialogStage.DIALOG_VERIFIED, checks)

    fun abort() { stage = MinimalDialogStage.ABORTED }
    fun outcome(): String = if (stage == MinimalDialogStage.DIALOG_VERIFIED) "OBSERVED" else "INCONCLUSIVE"

    private suspend fun <T> advance(expected: MinimalDialogStage, next: MinimalDialogStage, checks: suspend () -> T): T {
        fun requireStage() {
            if (stage != expected) {
                abort()
                throw MinimalDialogObservationFailure(MinimalDialogVerdict.STAGE_ORDER_INVALID)
            }
        }
        requireStage()
        try {
            val result = checks()
            requireStage() // An abort during checks must never be promoted back to a completed stage.
            stage = next
            return result
        } catch (failure: Exception) {
            abort()
            throw failure
        }
    }

    override fun toString() = "MinimalDialogProgress(stage=${stage.name})"
}
