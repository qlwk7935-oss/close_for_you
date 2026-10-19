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
}
