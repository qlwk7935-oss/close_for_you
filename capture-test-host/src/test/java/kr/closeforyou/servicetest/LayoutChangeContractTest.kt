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

    private suspend fun checkpoint(progress: LayoutChangeProgress, rows: List<String>, code: String = "OBSERVED",
        overflow: Boolean = false, now: () -> Long = { 0L }, reset: suspend () -> Unit = {},
        active: suspend () -> Unit = {}, anchor: () -> Unit = {}, query: suspend () -> List<String> = { rows },
        report: (String) -> Unit = {}, request: () -> Unit = {}) = progress.changeAndVerifyHost {
        LayoutBeforeRequestCheckpoint.run(now, reset, active, anchor, query,
            { progress.inspectCheckpoint(code, it, overflow) }, report, request)
    }

    @Test fun checkpointEmptyResultIsNoneWithoutCompletingChange() = immediately {
        val progress = ready()
        var output = ""
        checkpoint(progress, emptyList(), report = { output = it })
        assertEquals("F1_LAYOUT_CHECKPOINT;PHASE=LAYOUT_CHANGE;CHECKPOINT=BEFORE_LAYOUT_REQUEST;" +
            "OWNED_ATTEMPTS=NONE;BASELINE_ONLY_ATTEMPTS=NONE;REASONS=NONE;PHASE_EVIDENCE=NONE;ATOMIC_BINDING=NONE", output)
        assertEquals(LayoutStage.LAYOUT_CHANGED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }

    @Test fun checkpointOneBaselineRowIsOneOneNotSuccess() = immediately {
        val progress = ready()
        var output = ""
        checkpoint(progress, listOf(row(3, "BASELINE_ONLY")), report = { output = it })
        assertTrue(output.contains("OWNED_ATTEMPTS=ONE;BASELINE_ONLY_ATTEMPTS=ONE;REASONS=BASELINE_ONLY_ONLY;"))
        assertFalse(output.contains(";ATTEMPT="))
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }

    @Test fun checkpointMultipleRowsUseUniqueOrdinalCategories() = immediately {
        var output = ""
        checkpoint(ready(), listOf(row(3, "BASELINE_ONLY"), row(4, "BASELINE_ONLY")), report = { output = it })
        assertTrue(output.contains("OWNED_ATTEMPTS=MULTIPLE;BASELINE_ONLY_ATTEMPTS=MULTIPLE;REASONS=BASELINE_ONLY_ONLY;"))
    }

    @Test fun checkpointMixedReasonsAreFixedCodesOnly() = immediately {
        var output = ""
        checkpoint(ready(), listOf(row(3, "BASELINE_ONLY"), row(4, "UNCHANGED")), report = { output = it })
        assertTrue(output.contains("OWNED_ATTEMPTS=MULTIPLE;BASELINE_ONLY_ATTEMPTS=ONE;REASONS=MIXED;"))
    }

    @Test fun checkpointActualSharedSequenceHasExactlyOneQueryBeforeRequest() = immediately {
        val calls = mutableListOf<String>()
        val progress = ready()
        progress.changeAndVerifyHost {
            LayoutBeforeRequestCheckpoint.run({ 0L }, { calls += "RESET" }, { calls += "ACTIVE" },
                { calls += "ANCHOR" }, { calls += "QUERY"; emptyList<String>() },
                { calls += "ASSESS"; progress.inspectCheckpoint("OBSERVED", it, false) },
                { calls += "REPORT"; assertEquals(LayoutStage.BASELINE_VERIFIED, progress.stage) },
                { calls += "REQUEST"; assertEquals(LayoutStage.BASELINE_VERIFIED, progress.stage) })
        }
        assertEquals(listOf("RESET", "ACTIVE", "ANCHOR", "QUERY", "ANCHOR", "ASSESS", "REPORT", "ACTIVE", "REQUEST"), calls)
        assertEquals(LayoutStage.LAYOUT_CHANGED, progress.stage)
    }

    @Test fun checkpointRejectedResultsNeverRequestLayoutOrReportAcceptance() = immediately {
        for ((code, rows, overflow, expected) in listOf(
            CheckpointBad("INVALID_SESSION", emptyList(), false, PreparedVerdict.RESPONSE_REJECTED),
            CheckpointBad("OBSERVED", emptyList(), true, PreparedVerdict.OVERFLOW),
            CheckpointBad("OBSERVED", listOf(outside(3)), false, PreparedVerdict.GEOMETRY_REJECTED),
            CheckpointBad("OBSERVED", listOf("3:BLOCKED:PASSWORD:NONE"), false, PreparedVerdict.COLLECTION_BLOCKED),
            CheckpointBad("OBSERVED", listOf("3:ASSESSED:UNCERTAIN:UNCONFIRMED_STRUCTURE:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"), false, PreparedVerdict.DETECTOR_UNCERTAIN),
            CheckpointBad("OBSERVED", listOf(candidate(3)), false, PreparedVerdict.BASELINE_FALSE_CANDIDATE),
            CheckpointBad("OBSERVED", listOf(row(3), row(3)), false, PreparedVerdict.INVALID_DIAGNOSTIC),
            CheckpointBad("OBSERVED", listOf("PRIVATE_INVALID"), false, PreparedVerdict.INVALID_DIAGNOSTIC),
            CheckpointBad("OBSERVED", listOf(row(2)), false, PreparedVerdict.PHASE_ATTEMPT_REUSED),
        )) {
            val progress = ready()
            var resets = 0; var queries = 0; var reports = 0; var requests = 0
            rejects(expected) { checkpoint(progress, rows, code, overflow, reset = { resets++ },
                query = { queries++; rows }, report = { reports++ }, request = { requests++ }) }
            assertEquals(1, resets); assertEquals(1, queries)
            assertEquals(0, reports); assertEquals(0, requests)
            assertEquals(LayoutStage.ABORTED, progress.stage)
        }
    }
    private data class CheckpointBad(val code: String, val rows: List<String>, val overflow: Boolean, val expected: PreparedVerdict)

    @Test fun checkpointMissingOrMistypedFieldsAreNotAnEmptyValidReply() = immediately {
        val missing: List<Pair<List<String>?, Any?>> = listOf(null to false, emptyList<String>() to null,
            emptyList<String>() to "PRIVATE_FALSE", emptyList<String>() to 0)
        for ((rows, overflow) in missing) {
            val progress = ready()
            var requests = 0
            rejects(PreparedVerdict.INVALID_DIAGNOSTIC) {
                progress.changeAndVerifyHost {
                    LayoutBeforeRequestCheckpoint.run({ 0L }, {}, {}, {}, { Unit },
                        { progress.inspectCheckpoint("OBSERVED", rows, overflow) }, {}, { requests++ })
                }
            }
            assertEquals(0, requests); assertEquals(LayoutStage.ABORTED, progress.stage)
        }
    }

    @Test fun checkpointOwnershipFailureBeforeQueryPreventsAnyRead() = immediately {
        val progress = ready()
        var queries = 0; var requests = 0
        try {
            checkpoint(progress, emptyList(), anchor = { throw GeometryDiagnosticFailure(GeometryIssue.WINDOW_MISMATCH) },
                query = { queries++; emptyList() }, request = { requests++ })
            fail("OWNERSHIP_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.WINDOW_MISMATCH, failure.issue) }
        assertEquals(0, queries); assertEquals(0, requests)
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun checkpointOwnershipFailureAfterResponsePreventsAssessmentAndRequest() = immediately {
        val progress = ready()
        var anchors = 0; var reports = 0; var requests = 0
        try {
            checkpoint(progress, listOf(row(3)), anchor = { if (++anchors == 2) throw GeometryDiagnosticFailure(GeometryIssue.WINDOW_MISMATCH) },
                report = { reports++ }, request = { requests++ })
            fail("OWNERSHIP_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.WINDOW_MISMATCH, failure.issue) }
        assertEquals(0, reports); assertEquals(0, requests)
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun checkpointResetFailurePreventsQueryAndRequest() = immediately {
        val progress = ready()
        var queries = 0; var requests = 0
        rejects(PreparedVerdict.RESPONSE_REJECTED) {
            checkpoint(progress, emptyList(), reset = { throw PreparedFailure(PreparedVerdict.RESPONSE_REJECTED) },
                query = { queries++; emptyList() }, request = { requests++ })
        }
        assertEquals(0, queries); assertEquals(0, requests)
    }

    @Test fun checkpointQueryFailureIsPropagatedAndNeverRetried() = immediately {
        val progress = ready()
        val primary = IllegalStateException("PRIVATE_QUERY_FAILURE")
        var queries = 0; var requests = 0
        try {
            checkpoint(progress, emptyList(), query = { queries++; throw primary }, request = { requests++ })
            fail("QUERY_FAILURE_REQUIRED")
        } catch (failure: IllegalStateException) { assertSame(primary, failure) }
        assertEquals(1, queries); assertEquals(0, requests)
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun checkpointReportFailureOrCancellationPreventsRequest() = immediately {
        for (primary in listOf(IllegalStateException("PRIVATE_REPORT"), CancellationException("PRIVATE_CANCEL"))) {
            val progress = ready()
            var requests = 0
            try {
                checkpoint(progress, emptyList(), report = { throw primary }, request = { requests++ })
                fail("REPORT_FAILURE_REQUIRED")
            } catch (failure: Exception) { assertSame(primary, failure) }
            assertEquals(0, requests); assertEquals(LayoutStage.ABORTED, progress.stage)
        }
    }

    @Test fun checkpointCancellationCheckBeforeRequestCannotBeSkipped() = immediately {
        val progress = ready()
        var checks = 0; var requests = 0
        val primary = CancellationException("PRIVATE_CANCEL")
        try {
            checkpoint(progress, emptyList(), active = { if (++checks == 2) throw primary }, request = { requests++ })
            fail("CANCEL_REQUIRED")
        } catch (failure: CancellationException) { assertSame(primary, failure) }
        assertEquals(0, requests); assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun checkpointLateQueryOrReportingNeverStartsMutation() = immediately {
        for (delayed in listOf("QUERY", "REPORT")) for (late in listOf(3_000L, 3_100L)) {
            val progress = ready()
            var clock = 0L
            var queries = 0; var requests = 0
            rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
                checkpoint(progress, emptyList(), now = { clock }, query = {
                    queries++; if (delayed == "QUERY") clock = late; emptyList()
                }, report = { if (delayed == "REPORT") clock = late }, request = { requests++ })
            }
            assertEquals(1, queries); assertEquals(0, requests)
            assertEquals(LayoutStage.ABORTED, progress.stage)
        }
    }

    @Test fun checkpointCannotBeInspectedOutOfOrderOrTwice() = immediately {
        val early = LayoutChangeProgress()
        rejects(PreparedVerdict.STAGE_ORDER_INVALID) { early.inspectCheckpoint("OBSERVED", emptyList(), false) }
        val progress = ready()
        rejects(PreparedVerdict.STAGE_ORDER_INVALID) {
            progress.changeAndVerifyHost {
                progress.inspectCheckpoint("OBSERVED", emptyList(), false)
                progress.inspectCheckpoint("OBSERVED", emptyList(), false)
            }
        }
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun checkpointOrdinaryRowsAndLaterUnchangedCannotBecomeChangeEvidence() = immediately {
        for (later in listOf(listOf(row(3)), listOf(row(3), row(4, "UNCHANGED")))) {
            val progress = ready()
            checkpoint(progress, listOf(row(3)))
            val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
            var clock = 0L
            rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
                progress.change { scope ->
                    assertEquals(2, scope.floor); assertEquals(3, scope.evidenceFloor)
                    poll(progress, scope, trace, { clock }, { later }, { clock += it })
                }
            }
            assertEquals(3_000L, clock); assertFalse(trace.expectedSeen)
            assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        }
    }

    @Test fun checkpointEmptyResultDoesNotMakeLaterEmptyResultsSuccessful() = immediately {
        val progress = ready()
        checkpoint(progress, emptyList())
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var clock = 0L
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope -> poll(progress, scope, trace, { clock }, { emptyList() }, { clock += it }) }
        }
        assertEquals(3_000L, clock); assertFalse(trace.expectedSeen)
    }

    @Test fun checkpointRetainedRowsPlusNewOrdinaryRowRequireNormalFinalVerification() = immediately {
        for (reason in listOf("BASELINE_ONLY", "ORDINARY_CONTENT_CHANGE")) {
            val progress = ready()
            val prior = row(3, reason)
            checkpoint(progress, listOf(prior))
            val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
            val allRows = listOf(prior, row(4))
            var clock = 0L
            progress.change { scope ->
                poll(progress, scope, trace, { clock }, { allRows }, { clock += it })
                assertEquals(2_000L, clock)
                assertEquals("INCONCLUSIVE", progress.outcome(true, true))
                final(progress, scope, allRows, now = { clock })
            }
            assertEquals("OBSERVED_NEGATIVE_CONTROL", progress.outcome(true, true))
        }
    }

    @Test fun checkpointEvidenceBoundaryDoesNotHideBadOrCandidateRows() = immediately {
        for ((later, expected) in listOf(
            listOf(row(3), candidate(4)) to PreparedVerdict.BASELINE_FALSE_CANDIDATE,
            listOf(outside(3), row(4)) to PreparedVerdict.GEOMETRY_REJECTED,
        )) {
            val progress = ready()
            checkpoint(progress, listOf(row(3)))
            rejects(expected) {
                progress.change { scope -> progress.assess(scope, "OBSERVED", later, false) }
            }
            assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        }
    }

    @Test fun checkpointAcceptedRowsCannotDisappearOrChangeInLaterSnapshots() = immediately {
        for (later in listOf(emptyList(), listOf(row(4)), listOf(row(3, "UNCHANGED"), row(4)))) {
            val progress = ready()
            checkpoint(progress, listOf(row(3, "BASELINE_ONLY")))
            rejects(PreparedVerdict.SNAPSHOT_REGRESSED) {
                progress.change { scope -> progress.assess(scope, "OBSERVED", later, false) }
            }
            assertEquals(LayoutStage.ABORTED, progress.stage)
            assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        }
    }

    private suspend fun poll(progress: LayoutChangeProgress, scope: LayoutChangeProgress.Scope, trace: LayoutWaitDiagnostics,
        now: () -> Long, query: suspend () -> List<String>, pause: suspend (Long) -> Unit,
        anchor: () -> Unit = {}, code: String = "OBSERVED", overflow: Boolean = false) =
        trace.await(now, anchor, query, { progress.assess(scope, code, it, overflow) }, pause)

    private fun diagnosticTimeout(rows: List<String>, expected: LayoutWaitReasons) = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var clock = 0L
        var queries = 0
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope ->
                poll(progress, scope, trace, { clock }, { queries++; rows }, { clock += it })
            }
        }
        assertEquals(3_000L, clock)
        assertEquals(15, queries) // Observer adds no IPC or deadline restart.
        assertEquals(LayoutWaitPoint.ASSESSED, trace.lastPoint)
        assertTrue(trace.responseReceived)
        assertEquals(rows.isNotEmpty(), trace.rowsSeen)
        assertEquals(expected, trace.reasons)
        assertFalse(trace.expectedSeen); assertFalse(trace.finalReached)
        assertEquals(LayoutStage.ABORTED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }

    private fun diagnosticRejection(rows: List<String>, verdict: PreparedVerdict, overflow: Boolean = false,
        code: String = "OBSERVED") = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var pauses = 0
        rejects(verdict) {
            progress.change { scope -> poll(progress, scope, trace, { 0L }, { rows }, { pauses++ }, code = code, overflow = overflow) }
        }
        assertEquals(0, pauses)
        assertEquals(LayoutWaitPoint.HOST_AFTER_CHECK, trace.lastPoint)
        assertTrue(trace.responseReceived); assertFalse(trace.rowsSeen); assertFalse(trace.expectedSeen)
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun diagnosticsEmptyResultsTimeoutWithoutRows() = diagnosticTimeout(emptyList(), LayoutWaitReasons.NONE)
    @Test fun diagnosticsUnchangedOnlyTimesOut() = diagnosticTimeout(listOf(row(3, "UNCHANGED")), LayoutWaitReasons.UNCHANGED_ONLY)
    @Test fun diagnosticsBaselineOnlyTimesOut() = diagnosticTimeout(listOf(row(3, "BASELINE_ONLY")), LayoutWaitReasons.BASELINE_ONLY_ONLY)

    @Test fun diagnosticsExpectedSeenBeforeDeadlineStillCannotCompleteLate() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var clock = 0L
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope ->
                poll(progress, scope, trace, { clock }, { clock = 1_900; listOf(row(3)) }, { clock = 3_100 })
            }
        }
        assertTrue(trace.expectedSeen); assertTrue(trace.rowsSeen)
        assertEquals(LayoutWaitReasons.ORDINARY_CONTENT_CHANGE_SEEN, trace.reasons)
        assertFalse(trace.finalReached)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }

    @Test fun diagnosticsPostResponseAnchorFailureIsNotAnAssessment() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var anchors = 0
        val failure = GeometryDiagnosticFailure(GeometryIssue.WINDOW_MISMATCH)
        try {
            progress.change { scope -> poll(progress, scope, trace, { 0L }, { listOf(row(3)) }, {}, anchor = {
                if (++anchors == 2) throw failure
            }) }
            fail("ANCHOR_FAILURE_REQUIRED")
        } catch (actual: GeometryDiagnosticFailure) { assertSame(failure, actual) }
        assertEquals(LayoutWaitPoint.RESULT_RECEIVED, trace.lastPoint)
        assertTrue(trace.responseReceived); assertFalse(trace.rowsSeen); assertFalse(trace.expectedSeen)
        assertEquals(LayoutWaitReasons.NONE, trace.reasons)
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun diagnosticsFirstAnchorFailureDoesNotQuery() = immediately {
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var queries = 0
        try {
            trace.await({ 0L }, { throw LayoutFailure(LayoutIssue.ENVIRONMENT_CHANGED) },
                { queries++ }, { PreparedAssessment(PreparedVerdict.BASELINE_CONFIRMED) }, {})
            fail("ANCHOR_FAILURE_REQUIRED")
        } catch (failure: LayoutFailure) { assertEquals(LayoutIssue.ENVIRONMENT_CHANGED, failure.issue) }
        assertEquals(0, queries); assertFalse(trace.responseReceived)
        assertEquals(LayoutWaitPoint.HOST_BEFORE_CHECK, trace.lastPoint)
    }

    @Test fun diagnosticsQueryExceptionIsPreservedWithoutPayloadRetention() = immediately {
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        val failure = IllegalStateException("PRIVATE_IPC_VALUE")
        try {
            trace.read<Unit>({}, { throw failure }, { fail("ASSESSMENT_FORBIDDEN"); error("UNREACHABLE") })
            fail("IPC_FAILURE_REQUIRED")
        } catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertFalse(trace.responseReceived); assertFalse(trace.rowsSeen)
        assertFalse(trace.toString().contains("PRIVATE"))
    }

    @Test fun diagnosticsGeometryRejectionStillImmediate() = diagnosticRejection(listOf(outside(3)), PreparedVerdict.GEOMETRY_REJECTED)
    @Test fun diagnosticsProtectionBlockStillImmediate() = diagnosticRejection(listOf("3:BLOCKED:PASSWORD:NONE"), PreparedVerdict.COLLECTION_BLOCKED)
    @Test fun diagnosticsUncertaintyStillImmediate() = diagnosticRejection(
        listOf("3:ASSESSED:UNCERTAIN:UNCONFIRMED_STRUCTURE:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"), PreparedVerdict.DETECTOR_UNCERTAIN)
    @Test fun diagnosticsOverflowStillImmediate() = diagnosticRejection(listOf(row(3)), PreparedVerdict.OVERFLOW, overflow = true)
    @Test fun diagnosticsCandidateStillFalsePositive() {
        diagnosticRejection(listOf(candidate(3)), PreparedVerdict.BASELINE_FALSE_CANDIDATE)
        assertEquals("FALSE_POSITIVE", LayoutChangeContract.failureOutcome(PreparedVerdict.BASELINE_FALSE_CANDIDATE))
    }
    @Test fun diagnosticsBadCodeAndMalformedRowsStillRejected() {
        diagnosticRejection(emptyList(), PreparedVerdict.RESPONSE_REJECTED, code = "INVALID_SESSION")
        diagnosticRejection(listOf("PRIVATE_INVALID"), PreparedVerdict.INVALID_DIAGNOSTIC)
    }

    @Test fun diagnosticsPhasesDoNotShareExpectedRowsOrFinalMarkers() = immediately {
        val preparation = LayoutWaitDiagnostics(LayoutPhase.PREPARATION)
        val baseline = LayoutWaitDiagnostics(LayoutPhase.BASELINE)
        val change = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        val progress = LayoutChangeProgress()
        progress.preparation { scope ->
            preparation.read({}, { listOf(row(1)) }, { progress.assess(scope, "OBSERVED", it, false) })
            preparation.beginFinalResult()
            final(progress, scope, listOf(row(1)))
        }
        assertTrue(preparation.expectedSeen); assertTrue(preparation.finalReached)
        for (trace in listOf(baseline, change)) {
            assertEquals(LayoutWaitPoint.NOT_STARTED, trace.lastPoint)
            assertFalse(trace.responseReceived); assertFalse(trace.rowsSeen)
            assertFalse(trace.expectedSeen); assertFalse(trace.finalReached)
        }
        var clock = 0L
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.baseline { scope -> poll(progress, scope, baseline, { clock }, { emptyList() }, { clock += it }) }
        }
        assertFalse(baseline.expectedSeen); assertFalse(change.responseReceived)
    }

    @Test fun diagnosticsReportingExceptionDoesNotReplaceOriginalFailure() {
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        val cleanup = PreparedDialogCleanup()
        val primary = PreparedFailure(PreparedVerdict.OBSERVATION_TIMEOUT)
        try {
            try { throw primary }
            finally { trace.reportFailure(LayoutWaitFailure.OBSERVATION_TIMEOUT, cleanup) { error("PRIVATE_REPORT") } }
        } catch (actual: PreparedFailure) { assertSame(primary, actual) }
        assertTrue(cleanup.reportingFailed); assertFalse(cleanup.clean)
    }

    @Test fun diagnosticsReportingCancellationDoesNotReplaceUnwindingCancellation() {
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        val cleanup = PreparedDialogCleanup()
        val primary = CancellationException("PRIMARY_SYNTHETIC_CANCEL")
        try {
            try { throw primary }
            finally { trace.reportFailure(LayoutWaitFailure.CANCELLED, cleanup) { throw CancellationException("PRIVATE_REPORT") } }
        } catch (actual: CancellationException) { assertSame(primary, actual) }
        assertTrue(cleanup.reportingFailed); assertFalse(cleanup.clean)
    }

    @Test fun diagnosticsReportOnlyCancellationIsDeferredAndNeverSwallowed() = immediately {
        val cleanup = PreparedDialogCleanup()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        trace.reportFailure(LayoutWaitFailure.OBSERVATION_TIMEOUT, cleanup) { throw CancellationException("PRIVATE_REPORT") }
        var cleaned = 0
        cleanup.run({ cleaned++ }, { cleaned++ }, { cleaned++ }, { cleaned++ })
        assertEquals(4, cleaned); assertFalse(cleanup.clean)
        try { cleanup.propagateCancellation(); fail("CANCEL_REQUIRED") }
        catch (failure: CancellationException) { assertEquals("PREPARED_CLEANUP_CANCELLED", failure.message) }
    }

    @Test fun diagnosticsNormalSuccessKeepsSharedPollingFinalAndStageRequirements() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var clock = 0L
        var queries = 0
        progress.change { scope ->
            poll(progress, scope, trace, { clock }, { queries++; listOf(row(3)) }, { clock += it })
            assertEquals(2_000L, clock); assertEquals(11, queries)
            assertEquals("INCONCLUSIVE", progress.outcome(true, true))
            trace.beginFinalResult()
            PreparedDialogObservation.finalSnapshot({ clock }, { listOf(row(3)).also { trace.received() } }) { rows, deadline ->
                trace.assessed { progress.assess(scope, "OBSERVED", rows, false, final = true, deadline = deadline) }
            }
        }
        assertTrue(trace.finalReached)
        assertEquals("OBSERVED_NEGATIVE_CONTROL", progress.outcome(true, true))
    }

    @Test fun diagnosticsMixedAndOtherReasonsUseOnlyCompletedCanonicalAssessments() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        rejects(PreparedVerdict.FINAL_RESULT_MISSING) {
            progress.change { scope ->
                trace.read({}, { listOf(row(3, "UNCHANGED"), row(4, "BASELINE_ONLY")) },
                    { progress.assess(scope, "OBSERVED", it, false) })
            }
        }
        assertEquals(LayoutWaitReasons.MIXED, trace.reasons); assertFalse(trace.expectedSeen)
        val preparation = LayoutWaitDiagnostics(LayoutPhase.PREPARATION)
        val prepProgress = LayoutChangeProgress()
        prepProgress.preparation { scope ->
            preparation.read({}, { listOf(outside(1)) }, { prepProgress.assess(scope, "OBSERVED", it, false) })
            final(prepProgress, scope, listOf(outside(1)))
        }
        assertEquals(LayoutWaitReasons.OTHER, preparation.reasons)
    }

    @Test fun diagnosticsFinalReachedDoesNotCertifyFinalResponseOrCompletion() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        rejects(PreparedVerdict.SNAPSHOT_REGRESSED) {
            progress.change { scope ->
                trace.read({}, { listOf(row(3)) }, { progress.assess(scope, "OBSERVED", it, false) })
                trace.beginFinalResult()
                PreparedDialogObservation.finalSnapshot({ 0L }, { emptyList<String>().also { trace.received() } }) { rows, deadline ->
                    trace.assessed { progress.assess(scope, "OBSERVED", rows, false, final = true, deadline = deadline) }
                }
            }
        }
        assertTrue(trace.expectedSeen); assertTrue(trace.finalReached)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }

    @Test fun diagnosticsFixedSummaryHasNoPayloadAndStoresOnlyEnumsAndBooleans() = immediately {
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        val progress = beforeChange()
        rejects(PreparedVerdict.INVALID_DIAGNOSTIC) {
            progress.change { scope -> trace.read({}, { listOf("PRIVATE_TEXT_COORDINATES_TOKEN_FINGERPRINT") },
                { progress.assess(scope, "OBSERVED", it, false) }) }
        }
        val cleanup = PreparedDialogCleanup()
        var summary = ""
        trace.reportFailure(LayoutWaitFailure.ASSESSMENT_REJECTED, cleanup) { summary = it }
        assertEquals("F1_LAYOUT_WAIT;PHASE=LAYOUT_CHANGE;LAST_POINT=HOST_AFTER_CHECK;RESULT_RECEIVED=YES;ROWS=NONE;" +
            "OWNED_ATTEMPTS=NONE;BASELINE_ONLY_ATTEMPTS=NONE;REASONS=NONE;EXPECTED_SEEN=NO;" +
            "FINAL_RESULT=NOT_REACHED;DETAIL=ASSESSMENT_REJECTED", summary)
        assertFalse(summary.contains("PRIVATE")); assertFalse(trace.toString().contains("PRIVATE"))
        assertTrue(LayoutWaitDiagnostics::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .all { it.type.isEnum || it.type == Boolean::class.javaPrimitiveType })
    }

    private fun countedTimeout(snapshots: (Int) -> List<String>, owned: LayoutAttemptCount, baseline: LayoutAttemptCount) = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var clock = 0L
        var queries = 0
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope -> poll(progress, scope, trace, { clock }, { snapshots(++queries) }, { clock += it }) }
        }
        assertEquals(3_000L, clock); assertEquals(15, queries)
        assertEquals(owned, trace.ownedAttempts); assertEquals(baseline, trace.baselineOnlyAttempts)
        assertEquals(LayoutStage.ABORTED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        var summary = ""
        trace.reportFailure(LayoutWaitFailure.OBSERVATION_TIMEOUT, PreparedDialogCleanup()) { summary = it }
        assertTrue(summary.contains(";OWNED_ATTEMPTS=${owned.name};BASELINE_ONLY_ATTEMPTS=${baseline.name};"))
        assertFalse(summary.contains(";ATTEMPT=")) // No exact count/ordinal is emitted.
    }

    @Test fun attemptCountsEmptyQueriesRemainNoneAndTimeOut() =
        countedTimeout({ emptyList() }, LayoutAttemptCount.NONE, LayoutAttemptCount.NONE)

    @Test fun attemptCountsRepeatedSameOrdinalRemainOneAndTimeOut() =
        countedTimeout({ listOf(row(3, "BASELINE_ONLY")) }, LayoutAttemptCount.ONE, LayoutAttemptCount.ONE)

    @Test fun attemptCountsDistinctBaselineOrdinalsBecomeMultipleAndTimeOut() = countedTimeout({ query ->
        if (query == 1) listOf(row(3, "BASELINE_ONLY")) else listOf(row(3, "BASELINE_ONLY"), row(4, "BASELINE_ONLY"))
    }, LayoutAttemptCount.MULTIPLE, LayoutAttemptCount.MULTIPLE)

    @Test fun attemptCountsMixedBaselineAndUnchangedAreMultipleOneAndTimeOut() = countedTimeout({ query ->
        if (query == 1) listOf(row(3, "BASELINE_ONLY")) else listOf(row(3, "BASELINE_ONLY"), row(4, "UNCHANGED"))
    }, LayoutAttemptCount.MULTIPLE, LayoutAttemptCount.ONE)

    @Test fun attemptCountsOrdinaryEvidenceDoesNotAllowLateSuccess() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var clock = 0L
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope -> poll(progress, scope, trace, { clock },
                { clock = 1_900; listOf(row(3), row(4, "BASELINE_ONLY")) }, { clock = 3_100 }) }
        }
        assertEquals(LayoutAttemptCount.MULTIPLE, trace.ownedAttempts)
        assertEquals(LayoutAttemptCount.ONE, trace.baselineOnlyAttempts)
        assertTrue(trace.expectedSeen); assertFalse(trace.finalReached)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }

    @Test fun attemptCountsRejectedRepliesCannotInflatePreviouslyAcceptedCounts() = immediately {
        val original = row(3, "BASELINE_ONLY")
        for (kind in listOf("GEOMETRY", "BLOCKED", "UNCERTAIN", "OVERFLOW", "CANDIDATE", "DUPLICATE", "INVALID", "SESSION", "FLOOR")) {
            val progress = beforeChange()
            val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
            val rows = when (kind) {
                "GEOMETRY" -> listOf(original, outside(4))
                "BLOCKED" -> listOf(original, "4:BLOCKED:PASSWORD:NONE")
                "UNCERTAIN" -> listOf(original, "4:ASSESSED:UNCERTAIN:UNCONFIRMED_STRUCTURE:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER")
                "CANDIDATE" -> listOf(original, candidate(4))
                "DUPLICATE" -> listOf(original, original)
                "INVALID" -> listOf(original, "PRIVATE_INVALID")
                "FLOOR" -> listOf(original, row(2, "BASELINE_ONLY"))
                else -> listOf(original, row(4, "BASELINE_ONLY"))
            }
            val expected = when (kind) {
                "GEOMETRY" -> PreparedVerdict.GEOMETRY_REJECTED
                "BLOCKED" -> PreparedVerdict.COLLECTION_BLOCKED
                "UNCERTAIN" -> PreparedVerdict.DETECTOR_UNCERTAIN
                "OVERFLOW" -> PreparedVerdict.OVERFLOW
                "CANDIDATE" -> PreparedVerdict.BASELINE_FALSE_CANDIDATE
                "SESSION" -> PreparedVerdict.RESPONSE_REJECTED
                "FLOOR" -> PreparedVerdict.PHASE_ATTEMPT_REUSED
                else -> PreparedVerdict.INVALID_DIAGNOSTIC
            }
            rejects(expected) {
                progress.change { scope ->
                    trace.read({}, { listOf(original) }, { progress.assess(scope, "OBSERVED", it, false) })
                    trace.read({}, { rows }, { progress.assess(scope,
                        if (kind == "SESSION") "INVALID_SESSION" else "OBSERVED", it, kind == "OVERFLOW") })
                }
            }
            assertEquals(LayoutAttemptCount.ONE, trace.ownedAttempts)
            assertEquals(LayoutAttemptCount.ONE, trace.baselineOnlyAttempts)
            assertEquals(LayoutWaitPoint.HOST_AFTER_CHECK, trace.lastPoint)
            assertEquals(LayoutStage.ABORTED, progress.stage)
        }
    }

    @Test fun attemptCountsRegressedSnapshotsCannotChangeAcceptedCounts() = immediately {
        for (reply in listOf(emptyList(), listOf(row(3, "UNCHANGED")), listOf(row(4, "BASELINE_ONLY")))) {
            val progress = beforeChange()
            val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
            rejects(PreparedVerdict.SNAPSHOT_REGRESSED) {
                progress.change { scope ->
                    trace.read({}, { listOf(row(3, "BASELINE_ONLY")) }, { progress.assess(scope, "OBSERVED", it, false) })
                    trace.read({}, { reply }, { progress.assess(scope, "OBSERVED", it, false) })
                }
            }
            assertEquals(LayoutAttemptCount.ONE, trace.ownedAttempts)
            assertEquals(LayoutAttemptCount.ONE, trace.baselineOnlyAttempts)
        }
    }

    @Test fun attemptCountsPostResponseAnchorFailureCannotCountNewRows() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var anchors = 0
        var queries = 0
        var clock = 0L
        try {
            progress.change { scope -> poll(progress, scope, trace, { clock }, {
                if (++queries == 1) listOf(row(3, "BASELINE_ONLY")) else listOf(row(3, "BASELINE_ONLY"), row(4, "BASELINE_ONLY"))
            }, { clock += it }, anchor = { if (++anchors == 4) throw GeometryDiagnosticFailure(GeometryIssue.WINDOW_MISMATCH) }) }
            fail("ANCHOR_REJECTION_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.WINDOW_MISMATCH, failure.issue) }
        assertEquals(LayoutWaitPoint.RESULT_RECEIVED, trace.lastPoint)
        assertEquals(LayoutAttemptCount.ONE, trace.ownedAttempts)
        assertEquals(LayoutAttemptCount.ONE, trace.baselineOnlyAttempts)
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun attemptCountsPhasesStayIndependentAndPreparationIsNotValidGeometryProof() = immediately {
        val progress = LayoutChangeProgress()
        val preparation = LayoutWaitDiagnostics(LayoutPhase.PREPARATION)
        val baseline = LayoutWaitDiagnostics(LayoutPhase.BASELINE)
        val change = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        progress.preparation { scope ->
            preparation.read({}, { listOf(outside(1)) }, { progress.assess(scope, "OBSERVED", it, false) })
            final(progress, scope, listOf(outside(1)))
        }
        assertEquals(LayoutAttemptCount.ONE, preparation.ownedAttempts)
        assertEquals(LayoutAttemptCount.NONE, preparation.baselineOnlyAttempts)
        assertEquals(LayoutWaitReasons.OTHER, preparation.reasons)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        progress.baseline { scope ->
            baseline.read({}, { listOf(row(2, "BASELINE_ONLY"), row(3)) }, { progress.assess(scope, "OBSERVED", it, false) })
            final(progress, scope, listOf(row(2, "BASELINE_ONLY"), row(3)))
        }
        assertEquals(LayoutAttemptCount.MULTIPLE, baseline.ownedAttempts)
        assertEquals(LayoutAttemptCount.ONE, baseline.baselineOnlyAttempts)
        assertEquals(LayoutAttemptCount.ONE, preparation.ownedAttempts)
        assertEquals(LayoutAttemptCount.NONE, change.ownedAttempts)
        assertEquals(LayoutAttemptCount.NONE, change.baselineOnlyAttempts)
    }

    @Test fun attemptCountsNormalSuccessAndCandidateFailureKeepOriginalOutcomes() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        var clock = 0L
        val rows = listOf(row(3), row(4, "UNCHANGED"))
        progress.change { scope ->
            poll(progress, scope, trace, { clock }, { rows }, { clock += it })
            assertEquals(2_000L, clock)
            trace.beginFinalResult()
            PreparedDialogObservation.finalSnapshot({ clock }, { rows.also { trace.received() } }) { reply, deadline ->
                trace.assessed { progress.assess(scope, "OBSERVED", reply, false, final = true, deadline = deadline) }
            }
        }
        assertEquals(LayoutAttemptCount.MULTIPLE, trace.ownedAttempts)
        assertEquals(LayoutAttemptCount.NONE, trace.baselineOnlyAttempts)
        assertEquals("OBSERVED_NEGATIVE_CONTROL", progress.outcome(true, true))
        val rejected = beforeChange()
        val rejectedTrace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        rejects(PreparedVerdict.BASELINE_FALSE_CANDIDATE) {
            rejected.change { scope -> poll(rejected, scope, rejectedTrace, { 0L }, { listOf(candidate(3)) }, {}) }
        }
        assertEquals(LayoutAttemptCount.NONE, rejectedTrace.ownedAttempts)
        assertEquals(LayoutAttemptCount.NONE, rejectedTrace.baselineOnlyAttempts)
        assertEquals("FALSE_POSITIVE", LayoutChangeContract.failureOutcome(PreparedVerdict.BASELINE_FALSE_CANDIDATE))
    }

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

    /** Mirror experiment()'s actual finally/post-finally order. Every state transition below uses
     * the unchanged shared contracts. Android's NonCancellable/Job/Binder are not simulated here;
     * active represents the existing final ensureActive() boundary, not a new polling operation.
     */
    private suspend fun completeInDriverOrder(progress: LayoutChangeProgress, trace: LayoutWaitDiagnostics,
        cleanup: PreparedDialogCleanup, calls: MutableList<String>, waitFailure: LayoutWaitFailure? = null,
        failureOutcome: String? = null, body: () -> Unit = {},
        release: (PreparedCleanupStep) -> Unit = {}, waitReport: (String) -> Unit = {},
        cleanupReport: () -> Unit = {}, active: () -> Unit = {}): String {
        try { body() } finally {
            calls += "READER_CLOSE" // MinimalGeometryReader.close() only drops its context reference.
            cleanup.run(
                { calls += "SESSION_END"; release(PreparedCleanupStep.SESSION_END) },
                { calls += "DIALOG_DISMISS"; release(PreparedCleanupStep.DIALOG_DISMISS) },
                { calls += "ACTIVITY_FINISH"; release(PreparedCleanupStep.ACTIVITY_FINISH) },
                { calls += "FLAGS_RESTORE"; release(PreparedCleanupStep.FLAGS_RESTORE) })
            waitFailure?.let { detail -> trace.reportFailure(detail, cleanup) {
                calls += "WAIT_REPORT"; waitReport(it)
            } }
            cleanup.report { calls += "CLEANUP_REPORT"; cleanupReport() }
            if (!cleanup.clean) progress.abort()
        }
        calls += "PROPAGATE_CANCEL"
        cleanup.propagateCancellation()
        calls += "ACTIVE_CHECK"
        active()
        val result = failureOutcome ?: progress.outcome(cleanup.clean, true)
        calls += "SUMMARY_$result"
        calls += "FINAL_ASSERTION"
        assertTrue("LAYOUT_CONTROL_NOT_CONFIRMED", result == "OBSERVED_NEGATIVE_CONTROL" &&
            progress.stage == LayoutStage.CHANGE_VERIFIED && cleanup.clean)
        return result
    }
    private fun releasesBeforeReport() = listOf("READER_CLOSE", "SESSION_END", "DIALOG_DISMISS",
        "ACTIVITY_FINISH", "FLAGS_RESTORE")
    private suspend fun verifiedLayout() = beforeChange().also { progress ->
        progress.change { final(progress, it, listOf(row(3))) }
    }

    @Test fun driverOrderWaitReportCancellationAfterAllCleanupPropagatesBeforeSuccess() = immediately {
        val progress = beforeChange()
        val trace = LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE)
        rejects(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.change { scope ->
                trace.assessed { progress.assess(scope, "OBSERVED", listOf(row(3)), false) }
                throw PreparedFailure(PreparedVerdict.OBSERVATION_TIMEOUT)
            }
        }
        val cleanup = PreparedDialogCleanup(); val calls = mutableListOf<String>(); var output = ""
        try {
            completeInDriverOrder(progress, trace, cleanup, calls, LayoutWaitFailure.OBSERVATION_TIMEOUT,
                "INCONCLUSIVE", waitReport = {
                    assertEquals(releasesBeforeReport() + "WAIT_REPORT", calls)
                    assertTrue(cleanup.clean) // Reporting starts only AFTER successful cleanup.
                    output = it
                    throw CancellationException("PRIVATE_REPORT")
                })
            fail("CANCEL_REQUIRED")
        } catch (failure: CancellationException) {
            assertEquals("PREPARED_CLEANUP_CANCELLED", failure.message)
            assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty())
        }
        assertEquals(releasesBeforeReport() + listOf("WAIT_REPORT", "CLEANUP_REPORT", "PROPAGATE_CANCEL"), calls)
        assertTrue(trace.expectedSeen); assertTrue(cleanup.reportingFailed); assertFalse(cleanup.clean)
        assertEquals(LayoutStage.ABORTED, progress.stage)
        assertTrue(output.endsWith("DETAIL=OBSERVATION_TIMEOUT")) // The first failure is not reclassified.
        assertFalse(output.contains("PRIVATE")); assertTrue(cleanup.failures.isEmpty())
    }

    @Test fun driverOrderOriginalCancellationWinsOverCleanupAndBothLaterReports() = immediately {
        val progress = beforeChange(); progress.abort()
        val cleanup = PreparedDialogCleanup(); val calls = mutableListOf<String>()
        // experiment() has already sanitized the body cancellation before entering finally.
        val primary = CancellationException("LAYOUT_CONTROL_CANCELLED")
        try {
            completeInDriverOrder(progress, LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE), cleanup, calls,
                LayoutWaitFailure.CANCELLED, body = { throw primary }, release = { step ->
                    if (step == PreparedCleanupStep.DIALOG_DISMISS) throw CancellationException("PRIVATE_RELEASE_CANCEL")
                    if (step != PreparedCleanupStep.FLAGS_RESTORE) error("PRIVATE_RELEASE")
                }, waitReport = { throw CancellationException("PRIVATE_WAIT") },
                cleanupReport = { error("PRIVATE_REPORT") })
            fail("PRIMARY_CANCEL_REQUIRED")
        } catch (failure: CancellationException) { assertSame(primary, failure) }
        assertEquals(releasesBeforeReport() + listOf("WAIT_REPORT", "CLEANUP_REPORT"), calls)
        assertEquals(setOf(PreparedCleanupStep.SESSION_END, PreparedCleanupStep.DIALOG_DISMISS,
            PreparedCleanupStep.ACTIVITY_FINISH), cleanup.failures)
        assertTrue(cleanup.reportingFailed); assertFalse(cleanup.clean)
        assertEquals("INCONCLUSIVE", progress.outcome(cleanup.clean, true))
        assertFalse(cleanup.toString().contains("PRIVATE")); assertFalse(cleanup.failures.toString().contains("PRIVATE"))
    }

    @Test fun driverOrderCleanupReportCancellationAfterVerifiedChangeCannotPublishSuccess() = immediately {
        val progress = verifiedLayout(); val cleanup = PreparedDialogCleanup(); val calls = mutableListOf<String>()
        try {
            completeInDriverOrder(progress, LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE), cleanup, calls,
                cleanupReport = {
                    assertTrue(cleanup.clean); assertEquals(LayoutStage.CHANGE_VERIFIED, progress.stage)
                    throw CancellationException("PRIVATE_REPORT")
                })
            fail("CANCEL_REQUIRED")
        } catch (failure: CancellationException) { assertEquals("PREPARED_CLEANUP_CANCELLED", failure.message) }
        assertEquals(releasesBeforeReport() + listOf("CLEANUP_REPORT", "PROPAGATE_CANCEL"), calls)
        assertFalse(cleanup.clean); assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun driverOrderOrdinaryWaitReportFailureKeepsFirstFalsePositiveAndRunsLaterReport() = immediately {
        val progress = beforeChange()
        rejects(PreparedVerdict.BASELINE_FALSE_CANDIDATE) {
            progress.change { progress.assess(it, "OBSERVED", listOf(candidate(3)), false) }
        }
        val cleanup = PreparedDialogCleanup(); val calls = mutableListOf<String>(); var output = ""
        try {
            completeInDriverOrder(progress, LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE), cleanup, calls,
                LayoutWaitFailure.ASSESSMENT_REJECTED,
                LayoutChangeContract.failureOutcome(PreparedVerdict.BASELINE_FALSE_CANDIDATE),
                waitReport = { output = it; error("PRIVATE_REPORT") })
            fail("FAILED_ASSERTION_REQUIRED")
        } catch (failure: AssertionError) { assertEquals("LAYOUT_CONTROL_NOT_CONFIRMED", failure.message) }
        assertEquals(releasesBeforeReport() + listOf("WAIT_REPORT", "CLEANUP_REPORT", "PROPAGATE_CANCEL",
            "ACTIVE_CHECK", "SUMMARY_FALSE_POSITIVE", "FINAL_ASSERTION"), calls)
        assertTrue(output.endsWith("DETAIL=ASSESSMENT_REJECTED")); assertFalse(output.contains("PRIVATE"))
        assertTrue(cleanup.reportingFailed); assertFalse(cleanup.clean)
    }

    @Test fun driverOrderOrdinaryCleanupReportFailureAbortsPreviouslyVerifiedChange() = immediately {
        val progress = verifiedLayout(); val cleanup = PreparedDialogCleanup(); val calls = mutableListOf<String>()
        try {
            completeInDriverOrder(progress, LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE), cleanup, calls,
                cleanupReport = { error("PRIVATE_REPORT") })
            fail("FAILED_ASSERTION_REQUIRED")
        } catch (failure: AssertionError) { assertEquals("LAYOUT_CONTROL_NOT_CONFIRMED", failure.message) }
        assertEquals(releasesBeforeReport() + listOf("CLEANUP_REPORT", "PROPAGATE_CANCEL", "ACTIVE_CHECK",
            "SUMMARY_INCONCLUSIVE", "FINAL_ASSERTION"), calls)
        assertTrue(cleanup.reportingFailed); assertFalse(cleanup.clean)
        assertEquals(LayoutStage.ABORTED, progress.stage)
    }

    @Test fun driverOrderFinalActiveCheckCancellationCannotReachSummaryOrAssertion() = immediately {
        val progress = verifiedLayout(); val cleanup = PreparedDialogCleanup(); val calls = mutableListOf<String>()
        val primary = CancellationException("SYNTHETIC_JOB_CANCELLED")
        try {
            completeInDriverOrder(progress, LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE), cleanup, calls,
                active = { throw primary })
            fail("CANCEL_REQUIRED")
        } catch (failure: CancellationException) { assertSame(primary, failure) }
        assertEquals(releasesBeforeReport() + listOf("CLEANUP_REPORT", "PROPAGATE_CANCEL", "ACTIVE_CHECK"), calls)
        assertTrue(cleanup.clean) // Clean resources are not a substitute for the Job check.
    }

    @Test fun driverOrderNormalVerifiedCompletionKeepsCleanupReportsAndFinalChecksRequired() = immediately {
        val progress = verifiedLayout(); val cleanup = PreparedDialogCleanup(); val calls = mutableListOf<String>()
        val result = completeInDriverOrder(progress, LayoutWaitDiagnostics(LayoutPhase.LAYOUT_CHANGE), cleanup, calls)
        assertEquals(releasesBeforeReport() + listOf("CLEANUP_REPORT", "PROPAGATE_CANCEL", "ACTIVE_CHECK",
            "SUMMARY_OBSERVED_NEGATIVE_CONTROL", "FINAL_ASSERTION"), calls)
        assertEquals("OBSERVED_NEGATIVE_CONTROL", result); assertTrue(cleanup.clean)
        assertEquals(LayoutStage.CHANGE_VERIFIED, progress.stage)
    }
}
