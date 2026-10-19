package kr.closeforyou.servicetest

import java.util.concurrent.CancellationException
import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Fake time/IPC/host callbacks exercise the actual driver contracts, not Android F0 or event delivery. */
class LayoutChangeContractTest {
    private fun row(id: Int, reason: String = "ORDINARY_CONTENT_CHANGE") =
        "$id:ASSESSED:NO_POPUP:$reason:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
    private fun outside(id: Int) = "$id:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:RIGHT,BOTTOM:WITHIN_ONE_SECOND"
    private fun candidate(id: Int) = "$id:ASSESSED:POPUP_CANDIDATE:BOUNDED_WINDOW:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
    private fun assess(phase: LayoutPhase, vararg rows: String, code: String = "OBSERVED", overflow: Boolean = false, floor: Int = 0) =
        LayoutChangeContract.assess(phase, code, rows.toList(), overflow, floor)
    private fun <T> immediately(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return checkNotNull(completed) { "FAKE_CALLBACK_SUSPENDED" }.getOrThrow()
    }
    private fun rejects(expected: PreparedVerdict, block: suspend () -> Unit) {
        try { immediately(block); fail("FIXED_FAILURE_REQUIRED") }
        catch (failure: PreparedFailure) { assertEquals(expected, failure.verdict); assertEquals(expected.name, failure.message) }
    }
    private fun mutationRejects(expected: LayoutIssue, block: () -> Unit) {
        try { block(); fail("FIXTURE_REJECTION_REQUIRED") }
        catch (failure: LayoutFailure) { assertEquals(expected, failure.issue); assertEquals(expected.name, failure.message) }
    }
    private suspend fun final(progress: LayoutChangeProgress, scope: LayoutChangeProgress.Scope, rows: List<String>,
        now: () -> Long = { 0L }, ipc: suspend () -> List<String> = { rows }, diagnostics: () -> Unit = {}) {
        PreparedDialogObservation.finalSnapshot(now, ipc) { result, deadline ->
            diagnostics()
            progress.assess(scope, "OBSERVED", result, false, final = true, deadline = deadline)
        }
    }
    private suspend fun ready(): LayoutChangeProgress = LayoutChangeProgress().also { progress ->
        progress.preparation { final(progress, it, listOf(outside(1))) }
        progress.baseline { final(progress, it, listOf(row(2))) }
    }
    private suspend fun beforeChange() = ready().also { it.changeAndVerifyHost { } }

    @Test fun changeRequiresActualOrdinaryReasonNotBaselineOnlyOrUnchanged() {
        for (reason in listOf("BASELINE_ONLY", "UNCHANGED"))
            assertEquals(PreparedVerdict.BASELINE_INSUFFICIENT, assess(LayoutPhase.LAYOUT_CHANGE, row(3, reason)).verdict)
        assertEquals(PreparedVerdict.BASELINE_CONFIRMED,
            assess(LayoutPhase.LAYOUT_CHANGE, row(3), row(4, "UNCHANGED")).verdict)
    }
    @Test fun preparationRejectedGeometryIsNotStrictEvidence() {
        assertEquals(PreparedVerdict.PREPARATION_RECORDED, assess(LayoutPhase.PREPARATION, outside(1)).verdict)
        for (phase in listOf(LayoutPhase.BASELINE, LayoutPhase.LAYOUT_CHANGE))
            assertEquals(PreparedVerdict.GEOMETRY_REJECTED, assess(phase, outside(1)).verdict)
    }
    @Test fun candidatesAnywhereAreFalsePositivesIncludingMixedRowOrder() {
        for (phase in LayoutPhase.entries) for (rows in listOf(listOf(row(3), candidate(4)),
            listOf(outside(3), candidate(4)), listOf(candidate(3), outside(4)), listOf("4:BLOCKED:PASSWORD:NONE", candidate(5)))) {
            val result = LayoutChangeContract.assess(phase, "OBSERVED", rows, false, 0)
            assertEquals(PreparedVerdict.BASELINE_FALSE_CANDIDATE, result.verdict)
            assertEquals("FALSE_POSITIVE", LayoutChangeContract.failureOutcome(result.verdict))
        }
        assertEquals(PreparedVerdict.BASELINE_FALSE_CANDIDATE,
            assess(LayoutPhase.LAYOUT_CHANGE, candidate(4), overflow = true).verdict)
    }
    @Test fun badResultsAreNeverWaitingOrEvidence() {
        for (phase in LayoutPhase.entries) {
            assertEquals(PreparedVerdict.RESPONSE_REJECTED, assess(phase, code = "INVALID_SESSION").verdict)
            assertEquals(PreparedVerdict.OVERFLOW, assess(phase, overflow = true).verdict)
            assertEquals(PreparedVerdict.INVALID_DIAGNOSTIC, assess(phase, "PRIVATE_INVALID").verdict)
            assertEquals(PreparedVerdict.COLLECTION_BLOCKED, assess(phase, "3:BLOCKED:TRUNCATED_TREE:NONE").verdict)
            assertEquals(PreparedVerdict.DETECTOR_UNCERTAIN, assess(phase,
                "3:ASSESSED:UNCERTAIN:UNCONFIRMED_STRUCTURE:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER").verdict)
        }
    }
    @Test fun ordinaryRowCannotBorrowGeometryFromAnotherAttempt() {
        val bad = row(3).replace(":VALID:", ":UNOBSERVED:")
        assertNotEquals(PreparedVerdict.BASELINE_CONFIRMED, assess(LayoutPhase.LAYOUT_CHANGE, bad, row(4, "UNCHANGED")).verdict)
    }
    @Test fun limitsDuplicateAndPriorPhaseOrdinalsAreRejected() {
        assertEquals(PreparedVerdict.OVERFLOW, LayoutChangeContract.assess(LayoutPhase.LAYOUT_CHANGE,
            "OBSERVED", List(257) { row(3) }, false, 0).verdict)
        assertEquals(PreparedVerdict.INVALID_DIAGNOSTIC, assess(LayoutPhase.LAYOUT_CHANGE, row(3), row(3)).verdict)
        assertEquals(PreparedVerdict.PHASE_ATTEMPT_REUSED, assess(LayoutPhase.LAYOUT_CHANGE, row(2), floor = 2).verdict)
    }
    @Test fun preparationRowsCannotCompleteBaselineOrChange() = immediately {
        val progress = LayoutChangeProgress()
        progress.preparation { final(progress, it, listOf(row(8))) }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        rejects(PreparedVerdict.PHASE_ATTEMPT_REUSED) {
            progress.baseline { final(progress, it, listOf(row(8))) }
        }
    }
    @Test fun baselineFinalFailureMeansZeroLayoutMutations() = immediately {
        val progress = LayoutChangeProgress()
        progress.preparation { final(progress, it, listOf(row(1))) }
        var mutations = 0
        rejects(PreparedVerdict.GEOMETRY_REJECTED) {
            progress.baseline { final(progress, it, listOf(outside(2))) }
            progress.changeAndVerifyHost { mutations++ }
        }
        assertEquals(0, mutations)
    }
    @Test fun missingBaselineFinalAlsoMeansZeroMutations() = immediately {
        val progress = LayoutChangeProgress()
        progress.preparation { final(progress, it, listOf(row(1))) }
        var mutations = 0
        rejects(PreparedVerdict.FINAL_RESULT_MISSING) {
            progress.baseline { progress.assess(it, "OBSERVED", listOf(row(2)), false) }
            progress.changeAndVerifyHost { mutations++ }
        }
        assertEquals(0, mutations)
    }
    @Test fun baselineOnlyIsAllowedForBaselineButNotChange() = immediately {
        val progress = LayoutChangeProgress()
        progress.preparation { final(progress, it, listOf(outside(1))) }
        progress.baseline { final(progress, it, listOf(row(2, "BASELINE_ONLY"))) }
        progress.changeAndVerifyHost { }
        rejects(PreparedVerdict.BASELINE_INSUFFICIENT) { progress.change { final(progress, it, listOf(row(3, "BASELINE_ONLY"))) } }
    }
    @Test fun hostMutationMustBeVerifiedBeforeChangePhase() = immediately {
        val progress = ready()
        rejects(PreparedVerdict.STAGE_ORDER_INVALID) { progress.change { fail("MUST_NOT_READ") } }
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }
    @Test fun failedHostMutationCannotBePromoted() = immediately {
        val progress = ready()
        mutationRejects(LayoutIssue.LAYOUT_NOT_CHANGED) {
            immediately { progress.changeAndVerifyHost { throw LayoutFailure(LayoutIssue.LAYOUT_NOT_CHANGED) } }
        }
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }
    @Test fun initialEmptyThenOrdinaryUsesActualSharedPollingAndFinalBoundary() = immediately {
        val progress = beforeChange()
        var clock = 0L
        var reads = 0
        progress.change { scope ->
            PreparedDialogObservation.await(scope.phase.observationPhase, { clock }, {
                reads++
                assertEquals("INCONCLUSIVE", progress.outcome(true, true))
                progress.assess(scope, "OBSERVED", if (reads == 1) emptyList() else listOf(row(3)), false)
            }, { clock += it })
            assertEquals(2_000L, clock)
            final(progress, scope, listOf(row(3), row(4, "UNCHANGED")), now = { clock })
        }
        assertEquals(11, reads)
        assertEquals("OBSERVED_NEGATIVE_CONTROL", progress.outcome(true, true))
    }
    @Test fun emptyAndUnchangedOnlyTimeoutAtOriginalDeadline() = immediately {
        for (rows in listOf(emptyList(), listOf(row(3, "UNCHANGED")), listOf(row(3, "BASELINE_ONLY")))) {
            val progress = beforeChange()
            var clock = 0L
            rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
                progress.change { scope ->
                    PreparedDialogObservation.await(scope.phase.observationPhase, { clock },
                        { progress.assess(scope, "OBSERVED", rows, false) }, { clock += it })
                }
            }
            assertEquals(3_000L, clock); assertEquals(LayoutStage.ABORTED, progress.stage)
        }
    }
    @Test fun preparationEmptyStillFailsImmediately() {
        val progress = LayoutChangeProgress()
        rejects(PreparedVerdict.UNOBSERVED) { progress.preparation { progress.assess(it, "OBSERVED", emptyList(), false) } }
    }
    @Test fun observedRowsCannotDisappearOrChange() = immediately {
        for (rows in listOf(emptyList(), listOf(row(3, "UNCHANGED")), listOf(row(4)))) {
            val progress = beforeChange()
            rejects(PreparedVerdict.SNAPSHOT_REGRESSED) {
                progress.change { scope ->
                    progress.assess(scope, "OBSERVED", listOf(row(3)), false)
                    progress.assess(scope, "OBSERVED", rows, false)
                }
            }
        }
    }
    @Test fun lateCandidateInFinalSnapshotCannotBeHidden() = immediately {
        val progress = beforeChange()
        rejects(PreparedVerdict.BASELINE_FALSE_CANDIDATE) {
            progress.change { scope ->
                progress.assess(scope, "OBSERVED", listOf(row(3)), false)
                final(progress, scope, listOf(row(3), candidate(4)))
            }
        }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }
    @Test fun finalEmptyOverflowBadCodeAndDiagnosticsNeverComplete() = immediately {
        for (bad in listOf("EMPTY", "OVERFLOW", "SESSION", "INVALID")) {
            val progress = beforeChange()
            val expected = when (bad) {
                "EMPTY" -> PreparedVerdict.UNOBSERVED
                "OVERFLOW" -> PreparedVerdict.OVERFLOW
                "SESSION" -> PreparedVerdict.RESPONSE_REJECTED
                else -> PreparedVerdict.INVALID_DIAGNOSTIC
            }
            rejects(expected) {
                progress.change { progress.assess(it, if (bad == "SESSION") "INVALID_SESSION" else "OBSERVED",
                    if (bad == "INVALID") listOf("PRIVATE_INVALID") else emptyList(), bad == "OVERFLOW", final = true) }
            }
        }
    }
    @Test fun missingFinalResultCannotBecomeSuccess() = immediately {
        val progress = beforeChange()
        rejects(PreparedVerdict.FINAL_RESULT_MISSING) {
            progress.change { progress.assess(it, "OBSERVED", listOf(row(3)), false) }
        }
    }
    @Test fun responseSuccessAt1900ThenResumeAfter3000IsNotReused() = immediately {
        val progress = beforeChange()
        var clock = 0L
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope ->
                PreparedDialogObservation.await(scope.phase.observationPhase, { clock }, {
                    clock = 1_900; progress.assess(scope, "OBSERVED", listOf(row(3)), false)
                }, { clock = 3_100 })
            }
        }
    }
    @Test fun finalDiagnosticsCannotCrossThreeSecondBoundary() = immediately {
        for (late in listOf(3_000L, 3_100L)) {
            val progress = beforeChange()
            var clock = 0L
            rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
                progress.change { scope -> final(progress, scope, emptyList(), { clock },
                    { clock = 2_900; listOf(row(3)) }, { clock = late }) }
            }
            assertEquals(LayoutStage.ABORTED, progress.stage)
        }
    }
    @Test fun delayBetweenFinalMarkerAndPhaseAcceptanceIsRejected() = immediately {
        val progress = beforeChange()
        var clock = 0L
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope -> final(progress, scope, listOf(row(3)), { clock }); clock = 3_000 }
        }
    }
    @Test fun deadlineCrossedDuringFinalStageWriteAbortsBeforeReturn() = immediately {
        val progress = beforeChange()
        var replied = false
        val clock = { if (!replied) 0L else if (progress.stage == LayoutStage.CHANGE_VERIFIED) 3_100L else 2_900L }
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope -> final(progress, scope, emptyList(), clock, { replied = true; listOf(row(3)) }) }
        }
        assertEquals(LayoutStage.ABORTED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }
    @Test fun hostCallbackCannotUndoAbortOrSkipFinalVerification() = immediately {
        val progress = ready()
        rejects(PreparedVerdict.STAGE_ORDER_INVALID) {
            progress.changeAndVerifyHost { progress.abort() }
        }
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }
    @Test fun finalDeadlineIsRequiredAndSuccessExceptionDoesNotComplete() = immediately {
        var progress = beforeChange()
        rejects(PreparedVerdict.FINAL_RESULT_MISSING) {
            progress.change { progress.assess(it, "OBSERVED", listOf(row(3)), false, final = true) }
        }
        progress = beforeChange()
        rejects(PreparedVerdict.BASELINE_CONFIRMED) {
            progress.change { final(progress, it, listOf(row(3))); throw PreparedFailure(PreparedVerdict.BASELINE_CONFIRMED) }
        }
        assertNotEquals("OBSERVED_NEGATIVE_CONTROL", LayoutChangeContract.failureOutcome(PreparedVerdict.BASELINE_CONFIRMED))
    }
    @Test fun scopeMixingAndClosedScopeReuseAreRejected() = immediately {
        val first = beforeChange(); val second = beforeChange()
        first.change { scope ->
            rejects(PreparedVerdict.STAGE_ORDER_INVALID) { second.change { second.assess(scope, "OBSERVED", listOf(row(3)), false) } }
            final(first, scope, listOf(row(3)))
        }
        assertEquals(LayoutStage.ABORTED, second.stage)
        rejects(PreparedVerdict.STAGE_ORDER_INVALID) { first.change { fail("NO_SECOND_CHANGE") } }
    }
    @Test fun allOrderedPhasesRequiredAndCleanupFailurePreventsSuccess() = immediately {
        val progress = beforeChange()
        progress.change { final(progress, it, listOf(row(3))) }
        for (failed in PreparedCleanupStep.entries) {
            val cleanup = PreparedDialogCleanup()
            var calls = 0
            fun action(step: PreparedCleanupStep) { calls++; if (step == failed) error("PRIVATE_CLEANUP") }
            cleanup.run({ action(PreparedCleanupStep.SESSION_END) }, { action(PreparedCleanupStep.DIALOG_DISMISS) },
                { action(PreparedCleanupStep.ACTIVITY_FINISH) }, { action(PreparedCleanupStep.FLAGS_RESTORE) })
            assertEquals(4, calls)
            assertEquals("INCONCLUSIVE", progress.outcome(cleanup.clean, true))
        }
        assertEquals("INCONCLUSIVE", progress.outcome(true, false))
    }
    @Test fun cancellationAndReleaseFailureAreNeverSuccessfulRetries() {
        val progress = LayoutChangeProgress()
        val safety = GeometryReadSafety()
        val cancelled = CancellationException("PRIVATE_CANCEL")
        var releases = 0
        try {
            immediately {
                progress.preparation {
                    safety.inspect<Unit> { scope ->
                        try { throw cancelled } finally {
                            scope.release { releases++; error("PRIVATE_NODE") }
                            scope.releaseAll(listOf(Any(), Any())) { releases++; error("PRIVATE_WINDOW") }
                        }
                    }
                }
            }
            fail("CANCEL_REQUIRED")
        } catch (failure: CancellationException) { assertSame(cancelled, failure) }
        assertEquals(3, releases); assertNotNull(safety.failure())
        assertEquals(LayoutStage.ABORTED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome(true, safety.failure() == null))
    }
    @Test fun onlyExactHalfHeightIsHostMutationEvidence() {
        val frame = IsolationRect(0, 0, 100, 201)
        assertTrue(LayoutMutation.changed(listOf(frame, frame), listOf(frame, IsolationRect(0, 0, 100, 100))))
        assertFalse(LayoutMutation.changed(listOf(frame, frame), listOf(frame, frame)))
    }
    @Test fun invalidReversedOrMissingFixtureBoundsCannotBeRepaired() {
        val frame = IsolationRect(0, 0, 100, 200)
        for (bad in listOf(emptyList(), listOf(frame), listOf(frame, IsolationRect(0, 200, 100, 0)),
            listOf(frame, IsolationRect(0, 0, 101, 100)), listOf(frame, IsolationRect(0, 0, 100, 99)))) {
            mutationRejects(LayoutIssue.FIXTURE_INVALID) { LayoutMutation.changed(listOf(frame, frame), bad) }
        }
    }
    @Test fun containerChangesCannotMasqueradeAsChildLayout() {
        val frame = IsolationRect(0, 0, 100, 200)
        mutationRejects(LayoutIssue.ENVIRONMENT_CHANGED) {
            LayoutMutation.changed(listOf(frame, frame), listOf(IsolationRect(1, 0, 101, 200), IsolationRect(0, 0, 100, 100)))
        }
    }
    @Test fun boundsMathUsesLongWithoutOverflowOrTolerance() {
        val frame = IsolationRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        assertTrue(LayoutMutation.changed(listOf(frame, frame), listOf(frame, IsolationRect(Int.MIN_VALUE, Int.MIN_VALUE, Int.MAX_VALUE, -1))))
    }
    @Test fun fixedFailureStringsDoNotExposeSyntheticSourceValues() {
        val result = assess(LayoutPhase.LAYOUT_CHANGE, "PRIVATE_TEXT_TOKEN_FINGERPRINT")
        assertEquals(PreparedVerdict.INVALID_DIAGNOSTIC, result.verdict)
        assertFalse(result.toString().contains("PRIVATE"))
        assertEquals("FIXTURE_INVALID", LayoutFailure(LayoutIssue.FIXTURE_INVALID).message)
        assertFalse(LayoutChangeProgress().toString().contains("PRIVATE"))
        assertEquals("IsolationRect(redacted)", IsolationRect(1, 2, 3, 4).toString())
    }
}
