package kr.closeforyou.servicetest

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Synthetic IPC/time/release callbacks only, never an Android F0 receipt or Dialog result. */
class PreparedMinimalDialogContractTest {
    private fun plain(id: Int = 1, reason: String = "BASELINE_ONLY") =
        "$id:ASSESSED:NO_POPUP:$reason:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
    private fun outside(id: Int = 1) =
        "$id:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:RIGHT,BOTTOM:WITHIN_ONE_SECOND"
    private fun candidate(id: Int = 3, reason: String = "BOUNDED_WINDOW") =
        "$id:ASSESSED:POPUP_CANDIDATE:$reason:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
    private fun assess(phase: PreparedPhase, vararg rows: String, floor: Int = 0) =
        PreparedMinimalDialogContract.assess(phase, "OBSERVED", rows.toList(), false, floor)
    private fun <T> immediately(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return checkNotNull(completed) { "FAKE_CALLBACK_SUSPENDED" }.getOrThrow()
    }
    private fun rejected(verdict: PreparedVerdict, block: suspend () -> Unit) {
        try { immediately(block); fail("EXPECTED_FIXED_FAILURE") }
        catch (failure: PreparedFailure) {
            assertEquals(verdict, failure.verdict)
            assertEquals(verdict.name, failure.message)
            assertNotEquals("OBSERVED", PreparedMinimalDialogContract.failureOutcome(verdict))
        }
    }
    private fun read(progress: PreparedDialogProgress, scope: PreparedDialogProgress.Scope,
        rows: List<String>, final: Boolean = false, overflow: Boolean = false) = progress.assess(scope, "OBSERVED", rows, overflow, final,
        if (final) PreparedFinalDeadline.start { 0L } else null)
    private suspend fun poll(progress: PreparedDialogProgress, scope: PreparedDialogProgress.Scope,
        rows: List<String>, late: Boolean = false) {
        var clock = 0L
        PreparedDialogObservation.await(scope.phase, { clock }, {
            if (late) clock = 1_900
            read(progress, scope, rows)
        }, { clock = if (late) 3_100 else clock + it })
    }
    private suspend fun preparation(progress: PreparedDialogProgress, rows: List<String> = listOf(outside())) =
        progress.verifyPreparation { scope -> poll(progress, scope, rows); read(progress, scope, rows, final = true) }
    private suspend fun baseline(progress: PreparedDialogProgress, rows: List<String> = listOf(plain(2, "ORDINARY_CONTENT_CHANGE"))) =
        progress.verifyBaseline { scope -> poll(progress, scope, rows); read(progress, scope, rows, final = true) }
    private suspend fun dialog(progress: PreparedDialogProgress) = progress.verifyDialog { scope ->
        val rows = listOf(candidate())
        poll(progress, scope, rows); read(progress, scope, rows, final = true)
    }
    private suspend fun verified(progress: PreparedDialogProgress) {
        preparation(progress); baseline(progress); progress.showAndVerifyDialogWindow { }; dialog(progress)
    }

    @Test fun preparationOutsideIsRecordedButNeverEstablishesBaseline() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress)
        assertEquals(PreparedStage.PREPARATION_VERIFIED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        rejected(PreparedVerdict.STAGE_ORDER_INVALID) { progress.showAndVerifyDialogWindow { fail("MUST_NOT_SHOW") } }
    }
    @Test fun preparationValidRowsAreNotStrictBaselineEvidence() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress, listOf(plain()))
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        rejected(PreparedVerdict.BASELINE_INSUFFICIENT) {
            progress.verifyBaseline { read(progress, it, listOf(plain(2, "UNCHANGED")), final = true) }
        }
    }
    @Test fun preparationAcceptsOnlyConsistentRejectedGeometryOrOrdinaryValidRows() {
        assertEquals(PreparedVerdict.PREPARATION_RECORDED,
            assess(PreparedPhase.PREPARATION, outside(), plain(2), plain(3, "ORDINARY_CONTENT_CHANGE")).verdict)
        for (bad in listOf(outside().replace(":RIGHT,BOTTOM:", ":NONE:"),
            outside().replace("UNCERTAIN", "POPUP_CANDIDATE"), outside().replace("INVALID_GEOMETRY", "BOUNDED_WINDOW")))
            assertNotEquals(PreparedVerdict.PREPARATION_RECORDED, assess(PreparedPhase.PREPARATION, bad).verdict)
    }
    @Test fun preparationBlocksAbortBeforeBaselineOrDialog() {
        for (block in listOf("PASSWORD", "EDITABLE_FIELD", "SENSITIVE_CONTENT", "SYSTEM_WINDOW", "UNREADABLE_TREE", "TRUNCATED_TREE")) {
            val progress = PreparedDialogProgress()
            var baselineCalls = 0
            var shows = 0
            rejected(PreparedVerdict.COLLECTION_BLOCKED) {
                progress.verifyPreparation { read(progress, it, listOf("1:BLOCKED:$block:NONE"), final = true) }
                baselineCalls++; baseline(progress)
                progress.showAndVerifyDialogWindow { shows++ }
            }
            assertEquals(0, baselineCalls); assertEquals(0, shows); assertEquals(PreparedStage.ABORTED, progress.stage)
        }
    }
    @Test fun preparationOverflowStopsWithoutNextPhase() {
        val progress = PreparedDialogProgress()
        rejected(PreparedVerdict.OVERFLOW) {
            progress.verifyPreparation { read(progress, it, listOf(plain()), overflow = true) }
        }
        assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun preparationUnobservedCannotBeFilledByHostSamples() {
        val progress = PreparedDialogProgress()
        rejected(PreparedVerdict.UNOBSERVED) { progress.verifyPreparation { read(progress, it, emptyList(), final = true) } }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }
    @Test fun eitherActualBaselineOnlyOrOrdinaryChangeIsRequiredInStrictRows() {
        for (reason in listOf("BASELINE_ONLY", "ORDINARY_CONTENT_CHANGE")) {
            val result = assess(PreparedPhase.BASELINE, plain(2, reason), plain(3, "UNCHANGED"), floor = 1)
            assertEquals(PreparedVerdict.BASELINE_CONFIRMED, result.verdict)
            assertEquals(3, result.maximumOrdinal)
        }
        assertEquals(PreparedVerdict.BASELINE_INSUFFICIENT, assess(PreparedPhase.BASELINE, plain(reason = "UNCHANGED")).verdict)
    }
    @Test fun strictGoodRowCannotMaskOutsideRowInEitherOrder() {
        for (rows in listOf(listOf(plain(2), outside(3)), listOf(outside(2), plain(3)))) {
            assertEquals(PreparedVerdict.GEOMETRY_REJECTED,
                PreparedMinimalDialogContract.assess(PreparedPhase.BASELINE, "OBSERVED", rows, false, 1).verdict)
        }
    }
    @Test fun strictCandidateOrUncertaintyIsNeverBaselineSuccess() {
        assertEquals(PreparedVerdict.BASELINE_FALSE_CANDIDATE, assess(PreparedPhase.BASELINE, plain(), candidate(2)).verdict)
        for (reason in listOf("UNCONFIRMED_TRANSITION", "UNCONFIRMED_STRUCTURE")) {
            val uncertain = "2:ASSESSED:UNCERTAIN:$reason:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
            assertEquals(PreparedVerdict.DETECTOR_UNCERTAIN, assess(PreparedPhase.BASELINE, plain(), uncertain).verdict)
            assertEquals("INCONCLUSIVE", PreparedMinimalDialogContract.failureOutcome(PreparedVerdict.DETECTOR_UNCERTAIN))
        }
    }
    @Test fun crossPhaseOrdinalsCannotReusePreparationBaselineOrCandidate() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress, listOf(plain(8)))
        rejected(PreparedVerdict.PHASE_ATTEMPT_REUSED) {
            progress.verifyBaseline { read(progress, it, listOf(plain(8)), final = true) }
        }
    }
    @Test fun scopeFromAnotherExperimentCannotSupplySameSessionClaim() = immediately {
        val first = PreparedDialogProgress()
        val second = PreparedDialogProgress()
        first.verifyPreparation { alien ->
            rejected(PreparedVerdict.STAGE_ORDER_INVALID) {
                second.verifyPreparation { read(second, alien, listOf(plain()), final = true) }
            }
            read(first, alien, listOf(plain()), final = true)
        }
        assertEquals(PreparedStage.ABORTED, second.stage)
    }
    @Test fun closedPreparationScopeCannotBeReusedInBaseline() = immediately {
        val progress = PreparedDialogProgress()
        var old: PreparedDialogProgress.Scope? = null
        progress.verifyPreparation { old = it; read(progress, it, listOf(plain()), final = true) }
        rejected(PreparedVerdict.STAGE_ORDER_INVALID) {
            progress.verifyBaseline { read(progress, checkNotNull(old), listOf(plain(2)), final = true) }
        }
    }
    @Test fun sameSnapshotCanRepeatButCannotLoseOrAlterObservedAttempts() = immediately {
        for (changed in listOf(listOf(plain(2)), listOf(plain(1, "UNCHANGED"), plain(2)))) {
            val progress = PreparedDialogProgress()
            rejected(PreparedVerdict.SNAPSHOT_REGRESSED) {
                progress.verifyPreparation { scope ->
                    read(progress, scope, listOf(plain()))
                    read(progress, scope, listOf(plain()))
                    read(progress, scope, changed, final = true)
                }
            }
        }
    }
    @Test fun missingPreparationFinalResultPreventsBaseline() {
        val progress = PreparedDialogProgress()
        rejected(PreparedVerdict.FINAL_RESULT_MISSING) {
            progress.verifyPreparation { poll(progress, it, listOf(plain())) }
        }
        assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun baselineFinalFailureOrMissingResultMeansZeroDialogShows() = immediately {
        for (omitFinal in listOf(true, false)) {
            val progress = PreparedDialogProgress()
            preparation(progress)
            var shows = 0
            rejected(if (omitFinal) PreparedVerdict.FINAL_RESULT_MISSING else PreparedVerdict.GEOMETRY_REJECTED) {
                progress.verifyBaseline { scope ->
                    poll(progress, scope, listOf(plain(2)))
                    if (!omitFinal) read(progress, scope, listOf(plain(2), outside(3)), final = true)
                }
                progress.showAndVerifyDialogWindow { shows++ }
            }
            assertEquals(0, shows)
        }
    }
    @Test fun lateFinalBlockOverflowMissingDuplicateAndInvalidRejectPriorGoodBaseline() = immediately {
        for (bad in listOf("BLOCK", "OVERFLOW", "MISSING", "DUPLICATE", "INVALID")) {
            val progress = PreparedDialogProgress()
            preparation(progress)
            try {
                progress.verifyBaseline { scope ->
                    poll(progress, scope, listOf(plain(2)))
                    val rows = when (bad) {
                        "BLOCK" -> listOf(plain(2), "3:BLOCKED:TRUNCATED_TREE:NONE")
                        "MISSING" -> emptyList()
                        "DUPLICATE" -> listOf(plain(2), plain(2))
                        "INVALID" -> listOf("SYNTHETIC_PRIVATE")
                        else -> listOf(plain(2))
                    }
                    read(progress, scope, rows, final = true, overflow = bad == "OVERFLOW")
                }
                fail("FINAL_FAILURE_REQUIRED")
            } catch (_: PreparedFailure) { assertEquals(PreparedStage.ABORTED, progress.stage) }
            assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        }
    }
    @Test fun dialogRequiresBoundedWindowInOneValidRow() {
        assertEquals(PreparedVerdict.DIALOG_CONFIRMED, assess(PreparedPhase.DIALOG, candidate(), plain(4, "UNCHANGED")).verdict)
        for (reason in listOf("SEMANTIC_DIALOG", "PANE_WITH_UNDERLAY", "BASELINE_ONLY"))
            assertEquals(PreparedVerdict.UNEXPECTED_ASSESSMENT, assess(PreparedPhase.DIALOG, candidate(reason = reason)).verdict)
    }
    @Test fun candidateCannotBorrowGeometryFromAnotherRow() {
        for (bad in listOf(candidate().replace("VALID", "WINDOW_OUTSIDE_FRAME"),
            "3:ASSESSED:POPUP_CANDIDATE:BOUNDED_WINDOW:UNOBSERVED:OLDER",
            candidate().replace("POSITIVE:POSITIVE", "POSITIVE:NONPOSITIVE_WIDTH"),
            candidate().replace(":NONE:", ":RIGHT:")))
            assertNotEquals(PreparedVerdict.DIALOG_CONFIRMED, assess(PreparedPhase.DIALOG, bad, plain(4, "UNCHANGED")).verdict)
    }
    @Test fun dialogUnchangedAloneIsNotCandidateAndOrdinaryChangeIsRejected() {
        assertEquals(PreparedVerdict.CANDIDATE_NOT_SEEN, assess(PreparedPhase.DIALOG, plain(reason = "UNCHANGED")).verdict)
        assertEquals(PreparedVerdict.UNEXPECTED_ASSESSMENT,
            assess(PreparedPhase.DIALOG, candidate(), plain(4, "ORDINARY_CONTENT_CHANGE")).verdict)
    }
    @Test fun missingDialogWindowVerificationPreventsDialogCompletion() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress); baseline(progress)
        rejected(PreparedVerdict.STAGE_ORDER_INVALID) { dialog(progress) }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }
    @Test fun windowVerificationExceptionAbortsBeforeDialogRead() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress); baseline(progress)
        try { progress.showAndVerifyDialogWindow { error("SYNTHETIC_PRIVATE_WINDOW_FAILURE") } }
        catch (_: IllegalStateException) { /* Android wrapper sanitizes exceptions; progress retains no Throwable. */ }
        assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun dialogFinalResultMustExistEvenAfterCandidateObservation() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress); baseline(progress); progress.showAndVerifyDialogWindow { }
        rejected(PreparedVerdict.FINAL_RESULT_MISSING) {
            progress.verifyDialog { poll(progress, it, listOf(candidate())) }
        }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }
    @Test fun dialogFinalOutsideRowCannotBeMaskedByEarlierCandidate() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress); baseline(progress); progress.showAndVerifyDialogWindow { }
        rejected(PreparedVerdict.GEOMETRY_REJECTED) {
            progress.verifyDialog { scope ->
                poll(progress, scope, listOf(candidate()))
                read(progress, scope, listOf(candidate(), outside(4)), final = true)
            }
        }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }
    @Test fun timeLimitAndTargetMissingAreSeparateBlockingRowsNotGeometryEvidence() {
        val time = "2:BLOCKED:TRUNCATED_TREE:SNAPSHOT_PASS:TREE_PROTECTION:TARGET:NONE:TIME_LIMIT:NONE:VERIFIED:TREE_BUDGET:NONE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED:OLDER:CONTENT_CHANGED"
        val missing = "2:BLOCKED:UNREADABLE_TREE:WINDOWS:TARGET_MISSING:TARGET:NONE:WINDOW_GUARD:NONE:VERIFIED:NONE:NONE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED:OLDER:WINDOW_CHANGED"
        for (phase in PreparedPhase.entries) for (blocked in listOf(time, missing)) {
            val good = if (phase == PreparedPhase.DIALOG) candidate(1) else plain()
            assertEquals(PreparedVerdict.COLLECTION_BLOCKED, assess(phase, good, blocked).verdict)
        }
    }
    @Test fun onlyOrderedStagesBothStrictFinalResultsAndCleanupAllowSuccess() = immediately {
        val progress = PreparedDialogProgress()
        verified(progress)
        assertEquals(PreparedStage.DIALOG_VERIFIED, progress.stage)
        assertEquals("OBSERVED", progress.outcome(true, true))
        assertEquals("INCONCLUSIVE", progress.outcome(false, true))
        assertEquals("INCONCLUSIVE", progress.outcome(true, false))
        progress.abort()
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }
    @Test fun allExceptionVerdictsIncludingSuccessCodesCannotReturnObserved() {
        for (verdict in PreparedVerdict.entries)
            assertNotEquals("OBSERVED", PreparedMinimalDialogContract.failureOutcome(verdict))
    }
    @Test fun phaseScopeCannotBeReadAfterItsFinalResult() = immediately {
        val progress = PreparedDialogProgress()
        rejected(PreparedVerdict.STAGE_ORDER_INVALID) {
            progress.verifyPreparation {
                read(progress, it, listOf(plain()), final = true)
                read(progress, it, listOf(plain()))
            }
        }
    }
    @Test fun abortInsideChecksCannotBePromotedBackToVerified() {
        val progress = PreparedDialogProgress()
        rejected(PreparedVerdict.STAGE_ORDER_INVALID) {
            progress.verifyPreparation { scope ->
                read(progress, scope, listOf(plain()), final = true)
                progress.abort()
            }
        }
        assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun successAt1900ThenResumeAfter3000FailsInEveryActualPhaseLoop() = immediately {
        for (phase in PreparedPhase.entries) {
            val progress = PreparedDialogProgress()
            if (phase != PreparedPhase.PREPARATION) preparation(progress)
            if (phase == PreparedPhase.DIALOG) { baseline(progress); progress.showAndVerifyDialogWindow { } }
            var shows = 0
            var finalChecks = 0
            val check: suspend (PreparedDialogProgress.Scope) -> Unit = { scope ->
                val rows = when (phase) {
                    PreparedPhase.PREPARATION -> listOf(plain())
                    PreparedPhase.BASELINE -> listOf(plain(2))
                    PreparedPhase.DIALOG -> listOf(candidate())
                }
                poll(progress, scope, rows, late = true)
                finalChecks++; read(progress, scope, rows, final = true)
            }
            rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
                when (phase) {
                    PreparedPhase.PREPARATION -> progress.verifyPreparation(check)
                    PreparedPhase.BASELINE -> progress.verifyBaseline(check)
                    PreparedPhase.DIALOG -> progress.verifyDialog(check)
                }
                progress.showAndVerifyDialogWindow { shows++ }
            }
            assertEquals(0, shows); assertEquals(0, finalChecks)
            assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        }
    }
    @Test fun exactDeadlineOrLateResponseNeverReturnsSuccess() {
        for (time in listOf(3_000L, 3_001L)) {
            var clock = 0L
            rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
                PreparedDialogObservation.await(PreparedPhase.BASELINE, { clock }, {
                    clock = time; assess(PreparedPhase.BASELINE, plain())
                }, { fail("MUST_NOT_PAUSE") })
            }
        }
    }
    @Test fun finalSnapshotAtOrAfterDeadlineCannotInstallFinalMarkerOrShowDialog() = immediately {
        for (late in listOf(3_000L, 3_001L)) {
            val progress = PreparedDialogProgress()
            preparation(progress)
            var shows = 0
            var installed = false
            rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
                progress.verifyBaseline { scope ->
                    poll(progress, scope, listOf(plain(2)))
                    var clock = 0L
                    PreparedDialogObservation.finalSnapshot({ clock }, { clock = late; listOf(plain(2)) }) { rows, deadline ->
                        installed = true
                        progress.assess(scope, "OBSERVED", rows, false, final = true, deadline = deadline)
                    }
                }
                progress.showAndVerifyDialogWindow { shows++ }
            }
            assertFalse(installed); assertEquals(0, shows); assertEquals(PreparedStage.ABORTED, progress.stage)
        }
    }
    @Test fun finalSnapshotUsesFreshResponseAndPropagatesCancellation() = immediately {
        var reads = 0
        PreparedDialogObservation.finalSnapshot({ 0L }, { reads++; listOf(plain()) }) { rows, deadline ->
            deadline.check(); assertEquals(listOf(plain()), rows)
        }
        assertEquals(1, reads)
        val cancelled = CancellationException("PRIVATE_CANCEL")
        try { PreparedDialogObservation.finalSnapshot<Unit>({ 0L }, { throw cancelled }) { _, _ -> fail("MUST_NOT_ACCEPT") }; fail("CANCEL_REQUIRED") }
        catch (failure: CancellationException) { assertSame(cancelled, failure) }
    }
    @Test fun minimumTwoSecondsRequiresAnotherFreshResponse() = immediately {
        var clock = 0L
        var reads = 0
        PreparedDialogObservation.await(PreparedPhase.PREPARATION, { clock }, {
            reads++; if (reads == 1) clock = 1_900; assess(PreparedPhase.PREPARATION, outside())
        }, { clock += it })
        assertEquals(2, reads)
    }
    @Test fun insufficientBaselineTimesOutWithoutShowOrFinalization() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress)
        rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyBaseline { poll(progress, it, listOf(plain(2, "UNCHANGED"))) }
        }
        assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun protectedRowsAndAllBadRepliesRemainFailClosed() {
        for (phase in PreparedPhase.entries) {
            assertEquals(PreparedVerdict.RESPONSE_REJECTED,
                PreparedMinimalDialogContract.assess(phase, "INVALID_SESSION", listOf(plain()), false).verdict)
            assertEquals(PreparedVerdict.UNOBSERVED, assess(phase).verdict)
            assertEquals(PreparedVerdict.OVERFLOW,
                PreparedMinimalDialogContract.assess(phase, "OBSERVED", List(257) { plain() }, false).verdict)
        }
    }
    @Test fun duplicateNoncanonicalOrdinalAndInvalidRowsCannotLeakInput() {
        for (rows in listOf(listOf(plain(), plain()), listOf(plain().replaceFirst("1:", "01:")),
            listOf("PRIVATE_SOURCE"), listOf(" ".repeat(3_000) + "PRIVATE_SOURCE"))) {
            val result = PreparedMinimalDialogContract.assess(PreparedPhase.PREPARATION, "OBSERVED", rows, false)
            assertEquals(PreparedVerdict.INVALID_DIAGNOSTIC, result.verdict)
            assertTrue(result.rows.isEmpty())
            assertFalse(result.toString().contains("PRIVATE_SOURCE"))
            assertEquals("INVALID_DIAGNOSTIC", PreparedFailure(result.verdict).message)
        }
    }
    @Test fun nodeRootWindowReleaseFailuresPreserveOriginalAndCannotRetryIntoPreparation() {
        val safety = GeometryReadSafety()
        val original = GeometryDiagnosticFailure(GeometryIssue.ROOT_UNAVAILABLE)
        val progress = PreparedDialogProgress()
        var reads = 0
        var releases = 0
        try {
            immediately {
                progress.verifyPreparation {
                    MinimalGeometryPolling.firstCoherent<Boolean>({ 0L }, safety, {
                        reads++
                        if (reads > 1) true else safety.inspect<Boolean> { scope ->
                            try { throw original } finally {
                                scope.release { releases++; error("PRIVATE_CHILD") }
                                scope.release { releases++; error("PRIVATE_ROOT") }
                                scope.releaseAll(listOf(Any(), Any())) { releases++; error("PRIVATE_WINDOW") }
                            }
                        }
                    }, { it }, { fail("MUST_NOT_RETRY") })
                    read(progress, it, listOf(plain()), final = true)
                }
            }
            fail("READ_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertSame(original, failure) }
        assertEquals(1, reads); assertEquals(4, releases)
        assertEquals(GeometryIssue.ROOT_UNAVAILABLE, safety.failure()?.inspection)
        assertEquals(PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED, safety.failure()?.release)
        assertEquals(PreparedStage.ABORTED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome(true, safety.failure() == null))
    }
    @Test fun cleanInitialReadMayRecoverButReleaseFailureAloneCannot() = immediately {
        val safety = GeometryReadSafety()
        var clock = 0L
        var reads = 0
        assertTrue(MinimalGeometryPolling.firstCoherent<Boolean>({ clock }, safety, {
            safety.inspect { if (reads++ == 0) throw GeometryDiagnosticFailure(GeometryIssue.LAYOUT_PENDING); true }
        }, { it }, { clock += it }))
        assertEquals(2, reads); assertNull(safety.failure())
        try { safety.inspect { scope -> scope.release { error("PRIVATE_RELEASE") }; true }; fail("RELEASE_REQUIRED") }
        catch (_: PriorityReleaseException) { assertNotNull(safety.failure()) }
    }
    @Test fun cancellationPlusReleaseFailuresStillAttemptsAllAndAborts() {
        val safety = GeometryReadSafety()
        val progress = PreparedDialogProgress()
        val cancelled = CancellationException("PRIVATE_CANCEL")
        var released = 0
        try {
            immediately {
                progress.verifyPreparation {
                    safety.inspect<Unit> { scope ->
                        try { throw cancelled } finally {
                            scope.release { released++; error("PRIVATE_RELEASE") }
                            scope.release { released++; error("PRIVATE_RELEASE") }
                        }
                    }
                }
            }
            fail("CANCEL_REQUIRED")
        } catch (failure: CancellationException) { assertSame(cancelled, failure) }
        assertEquals(2, released); assertEquals(GeometryIssue.READ_CANCELLED, safety.failure()?.inspection)
        assertEquals("INCONCLUSIVE", progress.outcome(true, safety.failure() == null))
        assertFalse(safety.failure().toString().contains("PRIVATE_CANCEL"))
    }
    private data class Raw(val bounds: IsolationRect, val window: Int = 1, val rotation: Int = 0, val config: Int = 1) {
        override fun toString() = "PreparedRaw(redacted)"
    }
    @Test fun observedABABeforeStabilityCannotReuseOldCountOrStartTime() = immediately {
        val a = Raw(IsolationRect(0, 0, 100, 200))
        val b = a.copy(bounds = IsolationRect(0, 0, 101, 200))
        val script = listOf(0L to a, 400L to a, 800L to b, 1_000L to a, 1_400L to a, 1_800L to a, 2_000L to a)
        var index = 0
        assertEquals(a, MinimalGeometryPolling.stable<Raw>({ script[index].first }, { script[index].second }, { true }, { index++ }))
        assertEquals(6, index)
    }
    @Test fun anchoredABAChangeAbortsExperimentBeforeReturnedA() {
        val a = Raw(IsolationRect(0, 0, 100, 200))
        val b = a.copy(bounds = IsolationRect(0, 0, 101, 200))
        val progress = PreparedDialogProgress()
        var index = 0
        var reads = 0
        try {
            immediately {
                progress.verifyPreparation {
                    MinimalGeometryPolling.stable<Raw>({ index * 200L }, { reads++; listOf(a, b, a)[index] },
                        { true }, { index++ }, anchor = a)
                }
            }
            fail("ANCHOR_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.STABLE_GEOMETRY_CHANGED, failure.issue) }
        assertEquals(2, reads); assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun windowRotationAndConfigurationChangeCannotResettleAgainstAnchor() {
        val a = Raw(IsolationRect(0, 0, 100, 200))
        for (changed in listOf(a.copy(window = 2), a.copy(rotation = 1), a.copy(config = 2))) {
            try {
                immediately { MinimalGeometryPolling.stable<Raw>({ 0L }, { changed }, { true }, { }, anchor = a) }
                fail("ANCHOR_FAILURE_REQUIRED")
            } catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.STABLE_GEOMETRY_CHANGED, failure.issue) }
        }
    }

    /** Same finalSnapshot -> diagnostics -> assess(final, deadline) boundary as finalResult(). */
    private suspend fun finalBoundary(progress: PreparedDialogProgress, scope: PreparedDialogProgress.Scope,
        now: () -> Long, ipc: suspend () -> List<String>, diagnostics: () -> Unit = {}) {
        PreparedDialogObservation.finalSnapshot(now, ipc) { rows, deadline ->
            diagnostics()
            progress.assess(scope, "OBSERVED", rows, false, final = true, deadline = deadline)
        }
    }

    @Test fun baselineIpcAt2900ThenDiagnosticsAt3100CannotMarkCompleteOrShowDialog() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress)
        var clock = 0L
        var shows = 0
        var captured: PreparedDialogProgress.Scope? = null
        rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyBaseline { scope ->
                captured = scope
                poll(progress, scope, listOf(plain(2)))
                finalBoundary(progress, scope, { clock }, { clock = 2_900; listOf(plain(2)) }, { clock = 3_100 })
            }
            progress.showAndVerifyDialogWindow { shows++ }
        }
        assertNull(captured?.final); assertEquals(0, shows)
        assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun dialogIpcAt2900ThenDiagnosticsAt3100CannotReturnObserved() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress); baseline(progress); progress.showAndVerifyDialogWindow { }
        var clock = 0L
        rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyDialog { scope ->
                poll(progress, scope, listOf(candidate()))
                finalBoundary(progress, scope, { clock }, { clock = 2_900; listOf(candidate()) }, { clock = 3_100 })
            }
        }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }
    @Test fun validationCrossingDeadlineCannotInstallFinalEvenWithoutOutputDelay() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress)
        var clock = 0L
        val delayed = object : AbstractList<String>() {
            override val size get() = 1
            override fun get(index: Int): String { clock = 3_100; return plain(2) }
        }
        var captured: PreparedDialogProgress.Scope? = null
        rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyBaseline { scope ->
                captured = scope
                finalBoundary(progress, scope, { clock }, { clock = 2_900; delayed })
            }
        }
        assertNull(captured?.final)
    }
    @Test fun exactly3000OrLaterAfterDiagnosticsCannotAcceptCachedSuccess() = immediately {
        for (late in listOf(3_000L, 3_001L)) {
            val progress = PreparedDialogProgress()
            preparation(progress)
            var clock = 0L
            rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
                progress.verifyBaseline { scope ->
                    finalBoundary(progress, scope, { clock }, { clock = 2_900; listOf(plain(2)) }, { clock = late })
                }
            }
            assertEquals(PreparedStage.ABORTED, progress.stage)
        }
    }
    @Test fun allThreeFinalBoundariesWithinDeadlineAllowOrderedCompletion() = immediately {
        val progress = PreparedDialogProgress()
        suspend fun final(scope: PreparedDialogProgress.Scope, rows: List<String>) {
            var clock = 0L
            poll(progress, scope, rows)
            finalBoundary(progress, scope, { clock }, { clock = 2_900; rows }, { clock = 2_999 })
        }
        progress.verifyPreparation { final(it, listOf(outside())) }
        progress.verifyBaseline { final(it, listOf(plain(2))) }
        progress.showAndVerifyDialogWindow { }
        progress.verifyDialog { final(it, listOf(candidate())) }
        assertEquals("OBSERVED", progress.outcome(true, true))
    }
    @Test fun delayAfterMarkerButInsideFinalCallbackStillAbortsPhase() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress)
        var clock = 0L
        rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyBaseline { scope ->
                PreparedDialogObservation.finalSnapshot({ clock }, { clock = 2_900; listOf(plain(2)) }) { rows, deadline ->
                    progress.assess(scope, "OBSERVED", rows, false, final = true, deadline = deadline)
                    clock = 3_100
                }
            }
        }
        assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun delayAfterFinalHelperReturnCannotPromotePhaseAfterDeadline() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress)
        var clock = 0L
        var shows = 0
        rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyBaseline { scope ->
                finalBoundary(progress, scope, { clock }, { clock = 2_900; listOf(plain(2)) })
                clock = 3_100 // Models descheduling before verify() accepts the phase.
            }
            progress.showAndVerifyDialogWindow { shows++ }
        }
        assertEquals(0, shows)
    }
    @Test fun deadlineCrossedDuringPhaseTransitionIsAbortedBeforeReturn() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress)
        var replied = false
        val clock = { if (!replied) 0L else if (progress.stage == PreparedStage.BASELINE_VERIFIED) 3_100L else 2_900L }
        rejected(PreparedVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyBaseline { scope ->
                finalBoundary(progress, scope, clock, { replied = true; listOf(plain(2)) })
            }
        }
        assertEquals(PreparedStage.ABORTED, progress.stage)
    }
    @Test fun finalAcceptanceWithoutDeadlineIsRejected() = immediately {
        val progress = PreparedDialogProgress()
        rejected(PreparedVerdict.FINAL_RESULT_MISSING) {
            progress.verifyPreparation { progress.assess(it, "OBSERVED", listOf(plain()), false, final = true) }
        }
    }
    @Test fun successVerdictThrownAfterFinalMarkerCannotProduceSuccess() = immediately {
        val progress = PreparedDialogProgress()
        preparation(progress); baseline(progress); progress.showAndVerifyDialogWindow { }
        rejected(PreparedVerdict.DIALOG_CONFIRMED) {
            progress.verifyDialog { scope ->
                finalBoundary(progress, scope, { 0L }, { listOf(candidate()) })
                throw PreparedFailure(PreparedVerdict.DIALOG_CONFIRMED)
            }
        }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
    }

    private fun ownershipRejected(block: () -> Unit) {
        try { block(); fail("OWNERSHIP_REJECTION_REQUIRED") }
        catch (failure: GeometryDiagnosticFailure) {
            assertEquals(GeometryIssue.WINDOW_MISMATCH, failure.issue)
            assertEquals("WINDOW_MISMATCH", failure.message)
        }
    }
    @Test fun foreignNullOrUnclearRootPackageReadsNoGeometryTextOrChildrenButReleasesAll() {
        for (pkg in listOf("synthetic.foreign", null, "")) {
            val safety = GeometryReadSafety()
            var dataReads = 0
            var windowReads = 0
            var releases = 0
            ownershipRejected {
                safety.inspect<Unit> { scope ->
                    try {
                        PreparedNodeOwnership.read(7, { pkg }, { windowReads++; 7 }) {
                            dataReads += 3 // Stand-ins for geometry/text/children getters.
                        }
                    } finally {
                        scope.release { releases++ }
                        scope.releaseAll(listOf(Any(), Any())) { releases++ }
                    }
                }
            }
            assertEquals(0, dataReads); assertEquals(0, windowReads); assertEquals(3, releases)
        }
    }
    @Test fun rootWindowMismatchReadsNoGeometryOrText() {
        var geometry = 0
        var text = 0
        ownershipRejected {
            PreparedNodeOwnership.read(7, { "kr.closeforyou.capturetest" }, { 8 }) { geometry++; text++ }
        }
        assertEquals(0, geometry); assertEquals(0, text)
    }
    @Test fun validNodeReadsOwnershipBeforeData() {
        val order = mutableListOf<String>()
        PreparedNodeOwnership.read(7, { order += "PACKAGE"; "kr.closeforyou.capturetest" }, { order += "WINDOW"; 7 }) {
            order += "GEOMETRY"; order += "TEXT"; order += "CHILDREN"
        }
        assertEquals(listOf("PACKAGE", "WINDOW", "GEOMETRY", "TEXT", "CHILDREN"), order)
    }
    @Test fun childOwnershipMustPassSameContractBeforeChildData() {
        var childData = 0
        var childReleased = false
        ownershipRejected {
            GeometryReadSafety().inspect { scope ->
                PreparedNodeOwnership.read(7, { "kr.closeforyou.capturetest" }, { 7 }) {
                    try {
                        PreparedNodeOwnership.read(7, { "synthetic.foreign" }, { 7 }) { childData++ }
                    } finally { scope.release { childReleased = true } }
                }
            }
        }
        assertEquals(0, childData); assertTrue(childReleased)
    }
    @Test fun ownershipGetterExceptionDoesNotReachDataAndStillReleases() {
        val original = IllegalStateException("PRIVATE_METADATA_FAILURE")
        var data = 0
        var released = 0
        try {
            GeometryReadSafety().inspect { scope ->
                try { PreparedNodeOwnership.read(7, { throw original }, { 7 }) { data++ } }
                finally { scope.release { released++ } }
            }
            fail("METADATA_FAILURE_REQUIRED")
        } catch (failure: IllegalStateException) { assertSame(original, failure) }
        assertEquals(0, data); assertEquals(1, released)
    }
    @Test fun ownershipAndReleaseFailureCannotRecoverThroughActualInitialReadLoop() {
        val safety = GeometryReadSafety()
        var reads = 0
        var data = 0
        var releases = 0
        ownershipRejected {
            immediately {
                MinimalGeometryPolling.firstCoherent<Boolean>({ 0L }, safety, {
                    reads++
                    if (reads > 1) true else safety.inspect<Boolean> { scope ->
                        try {
                            PreparedNodeOwnership.read(7, { null }, { 7 }) { data++; true }
                        } finally {
                            scope.release { releases++; error("PRIVATE_ROOT_RELEASE") }
                            scope.releaseAll(listOf(Any(), Any())) { releases++ }
                        }
                    }
                }, { it }, { fail("MUST_NOT_RETRY") })
            }
        }
        assertEquals(1, reads); assertEquals(0, data); assertEquals(3, releases)
        assertEquals(GeometryIssue.WINDOW_MISMATCH, safety.failure()?.inspection)
        assertEquals(PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED, safety.failure()?.release)
        try { safety.requireClean(); fail("LATCH_REQUIRED") }
        catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.RELEASE_FAILED, failure.issue) }
    }

    @Test fun firstCleanupFailureStillExecutesEveryRealSharedCallbackInOrder() = immediately {
        val cleanup = PreparedDialogCleanup()
        val order = mutableListOf<PreparedCleanupStep>()
        cleanup.run(
            { order += PreparedCleanupStep.SESSION_END; error("PRIVATE_END") },
            { order += PreparedCleanupStep.DIALOG_DISMISS },
            { order += PreparedCleanupStep.ACTIVITY_FINISH },
            { order += PreparedCleanupStep.FLAGS_RESTORE })
        assertEquals(PreparedCleanupStep.entries, order)
        assertEquals(setOf(PreparedCleanupStep.SESSION_END), cleanup.failures)
        assertFalse(cleanup.clean)
    }
    @Test fun failureInAnyCleanupCallbackPreventsExperimentSuccess() = immediately {
        for (failed in PreparedCleanupStep.entries) {
            val progress = PreparedDialogProgress()
            verified(progress)
            val cleanup = PreparedDialogCleanup()
            val order = mutableListOf<PreparedCleanupStep>()
            fun action(step: PreparedCleanupStep) { order += step; if (step == failed) error("PRIVATE_CLEANUP") }
            cleanup.run({ action(PreparedCleanupStep.SESSION_END) }, { action(PreparedCleanupStep.DIALOG_DISMISS) },
                { action(PreparedCleanupStep.ACTIVITY_FINISH) }, { action(PreparedCleanupStep.FLAGS_RESTORE) })
            assertEquals(PreparedCleanupStep.entries, order)
            assertEquals("INCONCLUSIVE", progress.outcome(cleanup.clean, true))
        }
    }
    @Test fun multipleCleanupFailuresAreStoredAsCodesOnly() = immediately {
        val cleanup = PreparedDialogCleanup()
        cleanup.run({ error("PRIVATE_END") }, { error("PRIVATE_DISMISS") }, { error("PRIVATE_FINISH") }, { error("PRIVATE_FLAGS") })
        assertEquals(PreparedCleanupStep.entries.toSet(), cleanup.failures)
        assertFalse(cleanup.toString().contains("PRIVATE"))
        assertFalse(cleanup.failures.toString().contains("PRIVATE"))
    }
    @Test fun successfulSharedCleanupAndVerifiedExperimentCanComplete() = immediately {
        val progress = PreparedDialogProgress()
        verified(progress)
        val cleanup = PreparedDialogCleanup()
        cleanup.run({}, {}, {}, {})
        cleanup.propagateCancellation()
        assertTrue(cleanup.clean)
        assertEquals("OBSERVED", progress.outcome(cleanup.clean, true))
    }
    @Test fun unrunCleanupCannotBeConsideredClean() {
        assertFalse(PreparedDialogCleanup().clean)
    }
    @Test fun originalCancellationSurvivesAllCleanupFailuresAndAllCallbacksRun() {
        val original = CancellationException("PRIVATE_ORIGINAL_CANCEL")
        val cleanup = PreparedDialogCleanup()
        var count = 0
        try {
            immediately {
                try { throw original } finally {
                    cleanup.run({ count++; error("PRIVATE_END") }, { count++; throw CancellationException("PRIVATE_DISMISS_CANCEL") },
                        { count++; error("PRIVATE_FINISH") }, { count++; error("PRIVATE_FLAGS") })
                }
            }
            fail("ORIGINAL_CANCEL_REQUIRED")
        } catch (failure: CancellationException) { assertSame(original, failure) }
        assertEquals(4, count); assertFalse(cleanup.clean)
        assertFalse(cleanup.toString().contains("PRIVATE_ORIGINAL_CANCEL"))
    }
    @Test fun cleanupOnlyCancellationPropagatesAfterRemainingCallbacks() = immediately {
        val cleanup = PreparedDialogCleanup()
        var count = 0
        cleanup.run({ count++; throw CancellationException("PRIVATE_CANCEL") }, { count++ }, { count++ }, { count++ })
        try { cleanup.propagateCancellation(); fail("CANCEL_REQUIRED") }
        catch (failure: CancellationException) { assertEquals("PREPARED_CLEANUP_CANCELLED", failure.message) }
        assertEquals(4, count); assertFalse(cleanup.clean)
    }
    @Test fun ordinaryInspectionFailureIsNotReplacedByCleanupFailure() {
        val original = IllegalArgumentException("PRIVATE_INSPECTION")
        val cleanup = PreparedDialogCleanup()
        try {
            immediately {
                try { throw original } finally { cleanup.run({ error("PRIVATE_END") }, {}, {}, {}) }
            }
            fail("ORIGINAL_FAILURE_REQUIRED")
        } catch (failure: IllegalArgumentException) { assertSame(original, failure) }
        assertFalse(cleanup.clean)
    }
    @Test fun cleanupCannotBeRetriedToEraseEarlierFailure() = immediately {
        val cleanup = PreparedDialogCleanup()
        cleanup.run({ error("PRIVATE_END") }, {}, {}, {})
        try { cleanup.run({}, {}, {}, {}); fail("SECOND_CLEANUP_MUST_NOT_RUN") }
        catch (failure: IllegalStateException) { assertEquals("PREPARED_CLEANUP_ALREADY_RUN", failure.message) }
        assertEquals(setOf(PreparedCleanupStep.SESSION_END), cleanup.failures)
        assertFalse(cleanup.clean)
    }
    @Test fun cleanupReportingFailureCannotReplaceOriginalCancellation() {
        val original = CancellationException("PRIVATE_ORIGINAL_CANCEL")
        val cleanup = PreparedDialogCleanup()
        var callbacks = 0
        try {
            immediately {
                try { throw original } finally {
                    cleanup.run({ callbacks++; error("PRIVATE_END") }, { callbacks++ }, { callbacks++ }, { callbacks++ })
                    cleanup.report { error("PRIVATE_REPORT") }
                }
            }
            fail("CANCEL_REQUIRED")
        } catch (failure: CancellationException) { assertSame(original, failure) }
        assertEquals(4, callbacks); assertTrue(cleanup.reportingFailed); assertFalse(cleanup.clean)
    }
    @Test fun reportingOnlyFailureOrCancellationCannotBecomeSuccess() = immediately {
        for (cancelReport in listOf(false, true)) {
            val cleanup = PreparedDialogCleanup()
            cleanup.run({}, {}, {}, {})
            cleanup.report { if (cancelReport) throw CancellationException("PRIVATE_REPORT_CANCEL") else error("PRIVATE_REPORT") }
            assertTrue(cleanup.reportingFailed); assertFalse(cleanup.clean)
            if (cancelReport) {
                try { cleanup.propagateCancellation(); fail("CANCEL_REQUIRED") }
                catch (failure: CancellationException) { assertEquals("PREPARED_CLEANUP_CANCELLED", failure.message) }
            } else cleanup.propagateCancellation()
        }
    }
}
