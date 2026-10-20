package kr.closeforyou.servicetest

import java.util.concurrent.CancellationException
import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kr.closeforyou.servicetest.ScrollClipEnvironmentCheck.*
import kr.closeforyou.servicetest.ScrollClipEnvironmentField.*
import kr.closeforyou.servicetest.ScrollClipReadStage.*

/** Same contracts as the Android reader/driver. Fake callbacks are not Android F0 or recycle evidence. */
class ScrollClipContractTest {
    private val viewport = IsolationRect(0, 0, 100, 100)
    private fun ready(id: Int, outside: Boolean = false) = if (outside)
        "$id:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:RIGHT,BOTTOM:OLDER"
        else "$id:ASSESSED:NO_POPUP:BASELINE_ONLY:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
    private fun malformed(id: Int) = "$id:BLOCKED:UNREADABLE_TREE:SNAPSHOT_PASS:TREE_PROTECTION:TARGET:NONE:" +
        "MALFORMED_NODE_BOUNDS:NONE:VERIFIED:NODE_BOUNDS_VALIDATE:NONE:REVERSED_Y:NOT_VISIBLE:SCROLL_ANCESTOR:DESCENDANT:OLDER:CONTENT_CHANGED"
    private fun <T> run(block: suspend () -> T): T {
        var completed: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { completed = result }
        })
        return checkNotNull(completed) { "FAKE_CALLBACK_SUSPENDED" }.getOrThrow()
    }
    private fun rejects(issue: ScrollClipIssue, action: suspend () -> Unit) {
        try { run(action); fail("REJECTION_REQUIRED") }
        catch (failure: ScrollClipFailure) { assertEquals(issue, failure.issue); assertEquals(issue.name, failure.message) }
    }
    private fun assess(vararg rows: String) = ScrollClipContract.assess("OBSERVED", rows.toList(), false, 0)
    private suspend fun final(progress: ScrollClipProgress, scope: ScrollClipProgress.Scope, rows: List<String>,
        now: () -> Long = { 0L }, diagnostics: () -> Unit = {}) =
        PreparedDialogObservation.finalSnapshot(now, { rows }) { reply, deadline ->
            diagnostics()
            progress.assess(scope, "OBSERVED", reply, false, deadline)
        }

    @Test fun nativeReferencePathAndUniqueRemoteParentAncestorAllRequired() {
        ScrollClipContract.requireIdentity(true, 1, true, true)
    }
    @Test fun changedViewOrNativePathIsNotSameChild() = rejects(ScrollClipIssue.IDENTITY_UNCONFIRMED) {
        ScrollClipContract.requireIdentity(false, 1, true, true)
    }
    @Test fun missingOrMultipleRemoteMatchesAreInconclusive() {
        for (matches in listOf(0, 2)) rejects(ScrollClipIssue.IDENTITY_UNCONFIRMED) {
            ScrollClipContract.requireIdentity(true, matches, true, true)
        }
    }
    @Test fun wrongRemoteParentIsNotAUniqueOwnedChildProof() = rejects(ScrollClipIssue.IDENTITY_UNCONFIRMED) {
        ScrollClipContract.requireIdentity(true, 1, false, true)
    }
    @Test fun scrollClassNameAloneCannotReplaceMatchedScrollAncestor() = rejects(ScrollClipIssue.IDENTITY_UNCONFIRMED) {
        ScrollClipContract.requireIdentity(true, 1, true, false)
    }
    @Test fun ownerCheckPrecedesAllDataCallbacks() {
        for ((pkg, actual) in listOf(null to 7, "PRIVATE_OTHER" to 7, "kr.closeforyou.capturetest" to 8)) {
            var reads = 0
            rejects(ScrollClipIssue.OWNERSHIP_REJECTED) {
                ScrollClipContract.owned(7, { pkg }, { actual }) { reads++ }
            }
            assertEquals(0, reads)
        }
    }
    @Test fun matchingOwnerAllowsDataExactlyOnce() {
        var reads = 0
        ScrollClipContract.owned(7, { "kr.closeforyou.capturetest" }, { 7 }) { reads++ }
        assertEquals(1, reads)
    }
    @Test fun invalidExpectedWindowFailsClosed() = rejects(ScrollClipIssue.OWNERSHIP_REJECTED) {
        ScrollClipContract.requireOwner(-1, "kr.closeforyou.capturetest", -1)
    }
    @Test fun shapesPreserveReversedAndEmptyWithoutNormalizing() {
        val cases = listOf(IsolationRect(1, 1, 2, 2) to ScrollClipShape.ORDERED,
            IsolationRect(1, 1, 1, 2) to ScrollClipShape.EMPTY,
            IsolationRect(2, 1, 1, 2) to ScrollClipShape.REVERSED_X,
            IsolationRect(1, 2, 2, 1) to ScrollClipShape.REVERSED_Y,
            IsolationRect(2, 2, 1, 1) to ScrollClipShape.REVERSED_BOTH)
        cases.forEach { (rect, shape) -> assertEquals(shape, ScrollClipContract.shape(rect)) }
    }
    @Test fun rawPlacementDistinguishesInsideOutsidePartialAndInvalid() {
        assertEquals(ScrollClipPosition.INSIDE, ScrollClipContract.position(viewport, IsolationRect(0, 20, 100, 40)))
        assertEquals(ScrollClipPosition.OUTSIDE, ScrollClipContract.position(viewport, IsolationRect(0, 100, 100, 120)))
        assertEquals(ScrollClipPosition.PARTIAL, ScrollClipContract.position(viewport, IsolationRect(0, 90, 100, 120)))
        assertEquals(ScrollClipPosition.INVALID, ScrollClipContract.position(viewport, IsolationRect(0, 120, 100, 90)))
    }
    @Test fun extremePlacementDoesNotOverflow() {
        assertEquals(ScrollClipPosition.OUTSIDE, ScrollClipContract.position(viewport,
            IsolationRect(Int.MIN_VALUE, Int.MIN_VALUE, -1, -1)))
    }
    @Test fun phaseAndBothVisibilitySourcesMustAgreeOnPlacement() {
        ScrollClipContract.requirePlacement(ScrollClipPhase.INSIDE, ScrollClipPosition.INSIDE, true, true)
        ScrollClipContract.requirePlacement(ScrollClipPhase.OUTSIDE_BEFORE, ScrollClipPosition.OUTSIDE, false, false)
        ScrollClipContract.requirePlacement(ScrollClipPhase.OUTSIDE_AFTER, ScrollClipPosition.OUTSIDE, false, false)
        for (position in listOf(ScrollClipPosition.OUTSIDE, ScrollClipPosition.PARTIAL, ScrollClipPosition.INVALID))
            rejects(ScrollClipIssue.POSITION_UNCONFIRMED) {
                ScrollClipContract.requirePlacement(ScrollClipPhase.INSIDE, position, true, true)
            }
    }
    @Test fun missingHiddenRemoteVisibilityIsNotFilledFromNative() = rejects(ScrollClipIssue.POSITION_UNCONFIRMED) {
        ScrollClipContract.requirePlacement(ScrollClipPhase.OUTSIDE_BEFORE, ScrollClipPosition.OUTSIDE, false, true)
    }
    @Test fun actualServiceMalformedRemainsBlockedCategory() {
        val result = assess(malformed(1))
        assertEquals(ScrollClipService.MALFORMED_ONLY, result.kind)
        assertTrue(result.rows.getValue(1).contains("RESULT=BLOCKED;BLOCK=UNREADABLE_TREE;STEP=SNAPSHOT_PASS;"))
    }
    @Test fun actualAssessedGeometryIsIndependentFromHostNodeShape() {
        assertEquals(ScrollClipService.ASSESSED_ONLY, assess(ready(1)).kind)
        assertEquals(ScrollClipService.ASSESSED_ONLY, assess(ready(1, outside = true)).kind)
    }
    @Test fun mixedRealServiceResultsNeverHideMalformedBlock() {
        assertEquals(ScrollClipService.MIXED, assess(malformed(1), ready(2)).kind)
    }
    @Test fun sensitiveAndTimeLimitBlocksAreNotObservationSuccess() {
        rejects(ScrollClipIssue.UNEXPECTED_BLOCK) { assess("1:BLOCKED:PASSWORD:NONE") }
        rejects(ScrollClipIssue.UNEXPECTED_BLOCK) { assess(malformed(1).replace("MALFORMED_NODE_BOUNDS", "TIME_LIMIT")) }
    }
    @Test fun malformedStageOrCauseCannotBorrowOtherRowsFacts() = rejects(ScrollClipIssue.UNEXPECTED_BLOCK) {
        assess(malformed(1).replace("SNAPSHOT_PASS", "PROTECTION_PASS"), malformed(2))
    }
    @Test fun badGeometryTupleRejectedRatherThanUsingHostFrame() = rejects(ScrollClipIssue.INVALID_DIAGNOSTIC) {
        assess(ready(1).replace("WITHIN_FRAME", "WINDOW_OUTSIDE_FRAME"))
    }
    @Test fun duplicateOldAndCorruptRowsRejected() {
        rejects(ScrollClipIssue.INVALID_DIAGNOSTIC) { assess(ready(1), ready(1)) }
        rejects(ScrollClipIssue.INVALID_DIAGNOSTIC) { assess("PRIVATE_SOURCE") }
        rejects(ScrollClipIssue.INVALID_DIAGNOSTIC) { ScrollClipContract.assess("OBSERVED", listOf(ready(1)), false, 1) }
    }
    @Test fun missingAndMistypedFieldsFailClosed() {
        for ((rows, overflow) in listOf(null to false, emptyList<String>() to null, emptyList<String>() to "PRIVATE_FALSE"))
            rejects(ScrollClipIssue.INVALID_DIAGNOSTIC) { ScrollClipContract.assess("OBSERVED", rows, overflow, 0) }
        rejects(ScrollClipIssue.RESPONSE_REJECTED) { ScrollClipContract.assess("INVALID_SESSION", emptyList(), false, 0) }
    }
    @Test fun overflowAndSizeLimitsCannotComplete() {
        rejects(ScrollClipIssue.OVERFLOW) { ScrollClipContract.assess("OBSERVED", emptyList(), true, 0) }
        rejects(ScrollClipIssue.OVERFLOW) { ScrollClipContract.assess("OBSERVED", List(257) { ready(1) }, false, 0) }
        rejects(ScrollClipIssue.INVALID_DIAGNOSTIC) { assess("X".repeat(2_049)) }
    }
    @Test fun emptyServiceResultsTimeoutWithActualSharedLoop() {
        var clock = 0L
        rejects(ScrollClipIssue.TIMEOUT) {
            ScrollClipObservation.await({ clock }, { assess() }, { clock += it })
        }
        assertEquals(3_000L, clock)
    }
    @Test fun realRowsNeedMinimumTwoSecondObservation() = run {
        var clock = 0L
        ScrollClipObservation.await({ clock }, { assess(malformed(1)) }, { clock += it })
        assertEquals(2_000L, clock)
    }
    @Test fun lastSuccessAt1900CannotBeReusedAfterDeadline() {
        var clock = 0L
        rejects(ScrollClipIssue.TIMEOUT) {
            ScrollClipObservation.await({ clock }, { clock = if (clock == 0L) 1_900L else 3_100L; assess(ready(1)) }, { clock += it })
        }
    }
    @Test fun allThreeOrderedPhasesAndFinalSnapshotsRequired() = run {
        val progress = ScrollClipProgress()
        for (phase in ScrollClipPhase.entries) {
            assertEquals("INCONCLUSIVE", progress.outcome(true, true))
            progress.phase(phase) { final(progress, it, listOf(malformed(phase.ordinal + 1))) }
        }
        assertEquals("OBSERVATIONS_COMPLETE", progress.outcome(true, true))
        assertEquals("INCONCLUSIVE", progress.outcome(false, true))
        assertEquals("INCONCLUSIVE", progress.outcome(true, false))
    }
    @Test fun skippedAndRepeatedPhasesRejected() {
        val skipped = ScrollClipProgress()
        rejects(ScrollClipIssue.PHASE_ORDER) { skipped.phase(ScrollClipPhase.INSIDE) {} }
        val repeated = ScrollClipProgress()
        run { repeated.phase(ScrollClipPhase.OUTSIDE_BEFORE) { final(repeated, it, listOf(ready(1))) } }
        rejects(ScrollClipIssue.PHASE_ORDER) { repeated.phase(ScrollClipPhase.OUTSIDE_BEFORE) {} }
    }
    @Test fun failureCannotBeReversedByLaterNormalInput() {
        val progress = ScrollClipProgress()
        rejects(ScrollClipIssue.FINAL_MISSING) { progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) {} }
        rejects(ScrollClipIssue.PHASE_ORDER) { progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) { final(progress, it, listOf(ready(1))) } }
    }
    @Test fun scopedRowsCannotDisappearOrChange() {
        for (later in listOf(emptyList(), listOf(ready(2)), listOf(ready(1, true)))) {
            val progress = ScrollClipProgress()
            rejects(ScrollClipIssue.SNAPSHOT_REGRESSED) {
                progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) { scope ->
                    progress.assess(scope, "OBSERVED", listOf(ready(1)), false)
                    progress.assess(scope, "OBSERVED", later, false)
                }
            }
        }
    }
    @Test fun priorPhaseOrdinalCannotBeReusedAfterReset() {
        val progress = ScrollClipProgress()
        run { progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) { final(progress, it, listOf(ready(1))) } }
        rejects(ScrollClipIssue.INVALID_DIAGNOSTIC) {
            progress.phase(ScrollClipPhase.INSIDE) { final(progress, it, listOf(ready(1))) }
        }
    }
    @Test fun finalEmptyResultIsNotCompletion() {
        val progress = ScrollClipProgress()
        rejects(ScrollClipIssue.UNOBSERVED) {
            progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) { final(progress, it, emptyList()) }
        }
    }
    @Test fun finalIpcAndDiagnosticsShareTheSameDeadline() {
        for (late in listOf(3_000L, 3_100L)) {
            val progress = ScrollClipProgress()
            var clock = 0L
            try {
                run { progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) {
                    final(progress, it, listOf(ready(1)), { clock }, { clock = late })
                } }
                fail("DEADLINE_REQUIRED")
            } catch (failure: PreparedFailure) { assertEquals(PreparedVerdict.OBSERVATION_TIMEOUT, failure.verdict) }
            assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        }
    }
    @Test fun actualObservedStabilityUsesRawValuesAndResetsObservedAba() {
        val gate = ObservedGeometryStability<IsolationRect>()
        val a = IsolationRect(0, 0, 10, 10); val b = IsolationRect(0, 1, 10, 11)
        assertFalse(gate.observe(a, true, 0)); assertFalse(gate.observe(a, true, 500))
        assertFalse(gate.observe(b, true, 900)); assertFalse(gate.observe(a, true, 1_100))
        assertFalse(gate.observe(a, true, 1_800)); assertTrue(gate.observe(a, true, 2_100))
    }
    @Test fun stableAnchorCannotAcceptObservedChangeEvenIfOriginalReturns() {
        val a = IsolationRect(0, 0, 10, 10); val b = IsolationRect(0, 1, 10, 11)
        var reads = 0; var clock = 0L
        try {
            run { MinimalGeometryPolling.stable({ clock }, { if (++reads == 2) b else a }, { true }, { clock += it }, anchor = a) }
            fail("ANCHOR_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.STABLE_GEOMETRY_CHANGED, failure.issue) }
        assertEquals(2, reads)
    }
    @Test fun inspectionAndMultipleReleaseFailuresPreservePrimaryAndTryAll() {
        val safety = GeometryReadSafety(); val primary = ScrollClipFailure(ScrollClipIssue.IDENTITY_UNCONFIRMED)
        var releases = 0
        try {
            safety.inspect { scope ->
                try { throw primary }
                finally { scope.releaseAll(listOf(Any(), Any(), Any())) { releases++; throw IllegalStateException("PRIVATE_RELEASE") } }
            }
        } catch (failure: ScrollClipFailure) { assertSame(primary, failure) }
        assertEquals(3, releases); assertNotNull(safety.failure())
        try { safety.requireClean(); fail("LATCH_REQUIRED") }
        catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.RELEASE_FAILED, failure.issue) }
    }
    @Test fun recoverableRootFailurePlusReleaseFailureCannotRetryToSuccess() {
        val safety = GeometryReadSafety(); var reads = 0
        try {
            run { MinimalGeometryPolling.firstCoherent({ 0L }, safety, {
                reads++
                safety.inspect { scope -> try { throw GeometryDiagnosticFailure(GeometryIssue.ROOT_UNAVAILABLE) }
                    finally { scope.release { throw IllegalStateException("PRIVATE_RELEASE") } } }
            }, { true }, {}) }
            fail("ROOT_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.ROOT_UNAVAILABLE, failure.issue) }
        assertEquals(1, reads)
        assertEquals(PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED, safety.failure()?.release)
    }
    @Test fun releaseFailureOnlyCannotReturnSuccessfulSample() {
        val safety = GeometryReadSafety()
        try { safety.inspect { it.release { throw IllegalStateException("PRIVATE_RELEASE") }; Unit }; fail("RELEASE_FAILURE_REQUIRED") }
        catch (_: PriorityReleaseException) { assertNotNull(safety.failure()) }
    }
    @Test fun cancellationAndReleaseFailureKeepCancellationAndLatch() {
        val primary = CancellationException("PRIVATE_CANCEL"); val safety = GeometryReadSafety(); var releases = 0
        try {
            safety.inspect { scope -> try { throw primary } finally {
                scope.release { releases++; throw CancellationException("PRIVATE_RELEASE_CANCEL") }
                scope.release { releases++ }
            } }
        } catch (failure: CancellationException) { assertSame(primary, failure) }
        assertEquals(2, releases); assertEquals(GeometryIssue.READ_CANCELLED, safety.failure()?.inspection)
    }
    @Test fun cancellationAbortsPhaseAndCannotBecomeCompletion() {
        val progress = ScrollClipProgress()
        try { run { progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) { throw CancellationException("PRIVATE_CANCEL") } } }
        catch (_: CancellationException) { assertEquals("INCONCLUSIVE", progress.outcome(true, true)) }
        rejects(ScrollClipIssue.PHASE_ORDER) { progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) {} }
    }
    @Test fun sharedCleanupAttemptsEveryCallbackAndRejectsAnyFailure() = run {
        val cleanup = PreparedDialogCleanup(); val calls = mutableListOf<String>()
        cleanup.run({ calls += "END"; throw IllegalStateException("PRIVATE_END") }, { calls += "DISMISS" },
            { calls += "FINISH"; throw IllegalStateException("PRIVATE_FINISH") }, { calls += "FLAGS" })
        assertEquals(listOf("END", "DISMISS", "FINISH", "FLAGS"), calls)
        assertFalse(cleanup.clean)
        assertEquals(setOf(PreparedCleanupStep.SESSION_END, PreparedCleanupStep.ACTIVITY_FINISH), cleanup.failures)
    }
    @Test fun cleanupAndReportCancellationNeverBecomeSuccess() = run {
        val cleanup = PreparedDialogCleanup(); var later = 0
        cleanup.run({ throw CancellationException("PRIVATE_END") }, { later++ }, { later++ }, { later++ })
        cleanup.report { throw IllegalStateException("PRIVATE_REPORT") }
        assertEquals(3, later); assertFalse(cleanup.clean)
        try { cleanup.propagateCancellation(); fail("CANCEL_REQUIRED") }
        catch (failure: CancellationException) { assertEquals("PREPARED_CLEANUP_CANCELLED", failure.message) }
    }
    @Test fun privateValuesAreRedactedInDiagnosticObjects() {
        val rect = IsolationRect(1234567, 9876543, 1234568, 9876544)
        assertEquals("IsolationRect(redacted)", rect.toString())
        assertEquals("ScrollClipReport(kind=MALFORMED_ONLY)", assess(malformed(1)).toString())
        assertEquals("ScrollClipProgress(codes_only)", ScrollClipProgress().toString())
        assertEquals("IDENTITY_UNCONFIRMED", ScrollClipFailure(ScrollClipIssue.IDENTITY_UNCONFIRMED).message)
    }
    @Test fun actualContrastRequiresAllThreeObservationsWithoutF1Promotion() {
        assertEquals(ScrollClipContrast.INCONCLUSIVE, ScrollClipContract.contrast(listOf(ScrollClipShape.REVERSED_Y)))
        assertEquals(ScrollClipContrast.REVERSED_Y_OUTSIDE_ONLY,
            ScrollClipContract.contrast(listOf(ScrollClipShape.REVERSED_Y, ScrollClipShape.ORDERED, ScrollClipShape.REVERSED_Y)))
        assertEquals(ScrollClipContrast.OTHER_PATTERN, ScrollClipContract.contrast(List(3) { ScrollClipShape.REVERSED_Y }))
    }
    @Test fun actualFormatterOnlyAcceptsCodesNotRawSourceValues() {
        val output = ScrollClipContract.codes(ScrollClipPhase.OUTSIDE_BEFORE, ScrollClipSource.HOST_NATIVE,
            ScrollClipPosition.OUTSIDE, ScrollClipShape.REVERSED_Y, false)
        assertEquals("F1_SCROLL_CLIP_SAMPLE;PHASE=OUTSIDE_BEFORE;SOURCE=HOST_NATIVE;IDENTITY=CONFIRMED;POSITION=OUTSIDE;" +
            "NODE_RECT=REVERSED_Y;NODE_VISIBILITY=NOT_VISIBLE;NODE_ANCESTRY=SCROLL_ANCESTOR;ATOMIC_BINDING=NONE", output)
    }
    @Test fun actualResetBeforeScrollSequencePreservesFirstLaunchAndPhaseGeneration() = run {
        val calls = mutableListOf<String>()
        for (phase in ScrollClipPhase.entries) ScrollClipTransition.run(phase, { calls += "RESET" },
            { calls += "ACTIVE" }, { calls += phase.name })
        assertEquals(listOf("ACTIVE", "OUTSIDE_BEFORE", "RESET", "ACTIVE", "INSIDE", "RESET", "ACTIVE", "OUTSIDE_AFTER"), calls)
    }
    @Test fun actualResetOrCancellationFailurePreventsHostMove() = run {
        for (cancel in listOf(false, true)) {
            var moves = 0
            val primary = if (cancel) CancellationException("PRIVATE_CANCEL") else IllegalStateException("PRIVATE_RESET")
            try {
                ScrollClipTransition.run(ScrollClipPhase.INSIDE, { if (!cancel) throw primary }, { if (cancel) throw primary }, { moves++ })
                fail("FAILURE_REQUIRED")
            } catch (failure: Exception) { assertSame(primary, failure) }
            assertEquals(0, moves)
        }
    }

    private fun environmentFailure(diagnostics: ScrollClipReadDiagnostics, stage: ScrollClipReadStage,
        check: ScrollClipEnvironmentCheck, field: ScrollClipEnvironmentField, read: () -> Unit): ScrollClipFailure {
        try { diagnostics.at(stage, read); fail("ENVIRONMENT_FAILURE_REQUIRED") }
        catch (failure: ScrollClipFailure) {
            assertEquals(ScrollClipIssue.ENVIRONMENT_CHANGED, failure.issue)
            assertEquals("ENVIRONMENT_CHANGED", failure.message)
            val codes = checkNotNull(failure.environment)
            assertSame(diagnostics.failure(), codes)
            assertEquals(stage, codes.stage); assertEquals(check, codes.check); assertEquals(field, codes.field)
            return failure
        }
        throw AssertionError("ENVIRONMENT_FAILURE_REQUIRED")
    }

    @Test fun missingDisplayUsesActualSharedNullBranch() {
        val diagnostics = ScrollClipReadDiagnostics()
        environmentFailure(diagnostics, INITIAL_PROBE, DISPLAY_MISSING, DISPLAY) { diagnostics.display<Any>(null) }
    }
    @Test fun invalidDisplayUsesActualSharedValidityBranch() {
        val diagnostics = ScrollClipReadDiagnostics()
        environmentFailure(diagnostics, INITIAL_PROBE, DISPLAY_INVALID, DISPLAY) { diagnostics.displayValid(false) }
    }
    @Test fun presentValidDisplayDoesNotCreateFailureOrReadAnythingElse() {
        val diagnostics = ScrollClipReadDiagnostics(); val display = Any()
        assertSame(display, diagnostics.display(display))
        diagnostics.displayValid(true)
        assertNull(diagnostics.failure())
    }
    @Test fun everyActualPositiveBoundsFieldRejectsEmptyAndReversedWithoutCorrection() {
        for (field in listOf(LOGICAL, MAXIMUM, CURRENT, DECOR, VIEWPORT, REMOTE_WINDOW, REMOTE_ROOT)) {
            for (rect in listOf(IsolationRect(0, 0, 0, 1), IsolationRect(0, 2, 1, 1), IsolationRect(2, 0, 1, 1))) {
                val diagnostics = ScrollClipReadDiagnostics()
                environmentFailure(diagnostics, PHASE_FIRST_SAMPLE, NONPOSITIVE_BOUNDS, field) { diagnostics.positive(field, rect) }
            }
        }
    }
    @Test fun nativeNonpositiveChecksRetainOriginalShortCircuitOrder() {
        val diagnostics = ScrollClipReadDiagnostics(); val reads = mutableListOf<ScrollClipEnvironmentField>()
        environmentFailure(diagnostics, INITIAL_PROBE, NONPOSITIVE_BOUNDS, MAXIMUM) {
            for ((field, rect) in listOf(LOGICAL to viewport, MAXIMUM to IsolationRect(0, 0, 0, 0), CURRENT to viewport)) {
                reads += field; diagnostics.positive(field, rect)
            }
        }
        assertEquals(listOf(LOGICAL, MAXIMUM), reads)
    }
    @Test fun remoteWindowNonpositiveIsFirstEvenWhenRootAlsoInvalid() {
        val diagnostics = ScrollClipReadDiagnostics(); var rootChecked = false
        environmentFailure(diagnostics, STABILITY_CHECK, NONPOSITIVE_BOUNDS, REMOTE_WINDOW) {
            diagnostics.positive(REMOTE_WINDOW, IsolationRect(0, 0, 0, 0))
            rootChecked = true; diagnostics.positive(REMOTE_ROOT, IsolationRect(0, 0, 0, 0))
        }
        assertFalse(rootChecked)
    }
    @Test fun everySavedEnvironmentAndRemoteFieldUsesSharedMismatchBranch() {
        for (field in listOf(WINDOW_ID, ROTATION, CONFIGURATION, APPLICATION_CONFIGURATION,
            LOGICAL, MAXIMUM, CURRENT, DECOR, VIEWPORT, REMOTE_PATH, REMOTE_WINDOW, REMOTE_ROOT)) {
            val diagnostics = ScrollClipReadDiagnostics()
            environmentFailure(diagnostics, ANCHOR_CHECK, SAVED_BASELINE_MISMATCH, field) {
                diagnostics.same(field, "PRIVATE_SAVED", "PRIVATE_CHANGED")
            }
        }
    }
    @Test fun absentSavedBaselineAndEqualValuesRetainOriginalAcceptance() {
        val diagnostics = ScrollClipReadDiagnostics()
        diagnostics.same(REMOTE_PATH, null, listOf(1, 2))
        diagnostics.same(REMOTE_PATH, listOf(1, 2), listOf(1, 2))
        diagnostics.positive(REMOTE_ROOT, viewport)
        assertNull(diagnostics.failure())
    }
    @Test fun originalComparisonOrderKeepsFirstMismatchOnly() {
        val diagnostics = ScrollClipReadDiagnostics(); var remoteCompared = false
        environmentFailure(diagnostics, STABILITY_CHECK, SAVED_BASELINE_MISMATCH, ROTATION) {
            diagnostics.same(WINDOW_ID, 7, 7)
            diagnostics.same(ROTATION, 0, 1)
            remoteCompared = true; diagnostics.same(REMOTE_PATH, listOf(0), listOf(1))
        }
        assertFalse(remoteCompared)
    }
    @Test fun coordinateOverflowIsClassifiedAtEveryActualCallSiteField() {
        for (field in listOf(VIEWPORT, DECOR, TARGET_LAYOUT)) {
            for ((a, b) in listOf(Int.MAX_VALUE to 1, Int.MIN_VALUE to -1)) {
                val diagnostics = ScrollClipReadDiagnostics()
                environmentFailure(diagnostics, INITIAL_PROBE, COORDINATE_OVERFLOW, field) { diagnostics.sum(a, b, field) }
            }
        }
    }
    @Test fun exactCoordinateLimitsStillAcceptedWithoutClamping() {
        val diagnostics = ScrollClipReadDiagnostics()
        assertEquals(Int.MAX_VALUE, diagnostics.sum(Int.MAX_VALUE - 1, 1, DECOR))
        assertEquals(Int.MIN_VALUE, diagnostics.sum(Int.MIN_VALUE + 1, -1, VIEWPORT))
        assertEquals(-1, diagnostics.sum(Int.MIN_VALUE, Int.MAX_VALUE, TARGET_LAYOUT))
        assertNull(diagnostics.failure())
    }
    @Test fun allFourActualReadStagesAreDistinctAndCodesOnly() {
        for (stage in ScrollClipReadStage.entries) {
            val diagnostics = ScrollClipReadDiagnostics()
            val failure = environmentFailure(diagnostics, stage, SAVED_BASELINE_MISMATCH, CURRENT) {
                diagnostics.same(CURRENT, viewport, IsolationRect(0, 0, 101, 100))
            }
            assertEquals("READ_STAGE=${stage.name};CHECK=SAVED_BASELINE_MISMATCH;FIELD=CURRENT", failure.environment?.codes())
        }
    }
    @Test fun initialProbeAndPhaseFirstSampleNeverPinBaseline() = run {
        val diagnostics = ScrollClipReadDiagnostics()
        val baseline = preparationBaseline(diagnostics)
        var time = 0L
        for ((stage, sample) in listOf(INITIAL_PROBE to preparationSample(),
            PHASE_FIRST_SAMPLE to preparationSample().copy(window = IsolationRect(0, 0, 101, 100)))) {
            MinimalGeometryPolling.firstCoherent({ time }, GeometryReadSafety(), {
                diagnostics.at(stage) { baseline.check(sample); sample }
            }, { true }, { time += it })
        }
        assertEquals(0L, time)
        assertEquals("ScrollClipBaseline(state=NEW)", baseline.toString())
        assertNull(diagnostics.failure())
    }
    @Test fun environmentFailureIsNotRetriedByActualFirstCoherentLoop() {
        val diagnostics = ScrollClipReadDiagnostics(); var reads = 0; var pauses = 0
        rejects(ScrollClipIssue.ENVIRONMENT_CHANGED) {
            MinimalGeometryPolling.firstCoherent({ 0L }, GeometryReadSafety(), {
                diagnostics.at(INITIAL_PROBE) { reads++; diagnostics.display<Any>(null) }
            }, { true }, { pauses++ })
        }
        assertEquals(1, reads); assertEquals(0, pauses)
    }
    @Test fun mainThreadIssueOnlyRelayReattachesFirstEnumCodes() {
        val diagnostics = ScrollClipReadDiagnostics()
        val original = environmentFailure(diagnostics, INITIAL_PROBE, DISPLAY_INVALID, DISPLAY) { diagnostics.displayValid(false) }
        val relayed = diagnostics.exception(original.issue)
        assertSame(original.environment, relayed.environment)
        assertEquals(original.message, relayed.message)
        assertNull(relayed.cause)
        assertNull(diagnostics.exception(ScrollClipIssue.OWNERSHIP_REJECTED).environment)
    }
    @Test fun laterChecksAndCleanupCannotOverwriteFirstFailure() = run {
        val diagnostics = ScrollClipReadDiagnostics()
        val first = environmentFailure(diagnostics, INITIAL_PROBE, DISPLAY_MISSING, DISPLAY) { diagnostics.display<Any>(null) }
        try { diagnostics.at(ANCHOR_CHECK) { diagnostics.sum(Int.MAX_VALUE, 1, TARGET_LAYOUT) } }
        catch (later: ScrollClipFailure) { assertSame(first.environment, later.environment) }
        val cleanup = PreparedDialogCleanup()
        cleanup.run({ throw IllegalStateException("PRIVATE_END") }, {}, {}, {})
        assertFalse(cleanup.clean)
        assertSame(first.environment, diagnostics.failure())
    }
    @Test fun environmentInspectionWithMultipleReleaseFailuresKeepsPrimaryAndAllReleases() {
        val diagnostics = ScrollClipReadDiagnostics(); val safety = GeometryReadSafety(); var releases = 0
        environmentFailure(diagnostics, STABILITY_CHECK, NONPOSITIVE_BOUNDS, REMOTE_ROOT) {
            safety.inspect { scope ->
                try { diagnostics.positive(REMOTE_ROOT, IsolationRect(0, 0, 0, 0)) }
                finally { scope.releaseAll(listOf(1, 2, 3)) { releases++; throw IllegalStateException("PRIVATE_RELEASE") } }
            }
        }
        assertEquals(3, releases); assertNotNull(safety.failure())
        try { safety.requireClean(); fail("LATCH_REQUIRED") } catch (failure: GeometryDiagnosticFailure) {
            assertEquals(GeometryIssue.RELEASE_FAILED, failure.issue)
        }
        assertEquals(STABILITY_CHECK, diagnostics.failure()?.stage)
    }
    @Test fun stageScopePreservesCancellationAndReleaseLatch() {
        val diagnostics = ScrollClipReadDiagnostics(); val safety = GeometryReadSafety()
        val primary = CancellationException("PRIVATE_CANCEL"); var releases = 0
        try { diagnostics.at(ANCHOR_CHECK) {
            safety.inspect { scope ->
                try { throw primary }
                finally { scope.release { releases++; throw IllegalStateException("PRIVATE_RELEASE") }; scope.release { releases++ } }
            }
        }; fail("CANCEL_REQUIRED") } catch (failure: CancellationException) { assertSame(primary, failure) }
        assertEquals(2, releases); assertNotNull(safety.failure()); assertNull(diagnostics.failure())
    }
    @Test fun newDiagnosticFailureStillAbortsProgressAndCannotComplete() {
        val progress = ScrollClipProgress(); val diagnostics = ScrollClipReadDiagnostics()
        rejects(ScrollClipIssue.ENVIRONMENT_CHANGED) {
            progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) { diagnostics.at(INITIAL_PROBE) { diagnostics.displayValid(false) } }
        }
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        rejects(ScrollClipIssue.PHASE_ORDER) { progress.phase(ScrollClipPhase.OUTSIDE_BEFORE) {} }
    }
    @Test fun successfulDiagnosticsDoNotChangeActualProgressCompletion() = run {
        val diagnostics = ScrollClipReadDiagnostics(); val progress = ScrollClipProgress()
        for ((index, phase) in ScrollClipPhase.entries.withIndex()) progress.phase(phase) { scope ->
            diagnostics.at(PHASE_FIRST_SAMPLE) { diagnostics.same(REMOTE_WINDOW, viewport, viewport) }
            final(progress, scope, listOf(ready(index + 1)))
        }
        assertNull(diagnostics.failure()); assertEquals("OBSERVATIONS_COMPLETE", progress.outcome(true, true))
        assertEquals("INCONCLUSIVE", progress.outcome(false, true))
        assertEquals("INCONCLUSIVE", progress.outcome(true, false))
    }
    @Test fun failurePayloadAndStringsContainOnlyEnumsNeverPrivateInputs() {
        val diagnostics = ScrollClipReadDiagnostics()
        val failure = environmentFailure(diagnostics, ANCHOR_CHECK, SAVED_BASELINE_MISMATCH, REMOTE_PATH) {
            diagnostics.same(REMOTE_PATH, "PRIVATE_PATH_123456789", "PRIVATE_CHANGED_987654321")
        }
        assertEquals("ScrollClipReadDiagnostics(codes_only)", diagnostics.toString())
        assertEquals("ScrollClipEnvironmentFailure(READ_STAGE=ANCHOR_CHECK;CHECK=SAVED_BASELINE_MISMATCH;FIELD=REMOTE_PATH)", failure.environment.toString())
        assertEquals("ENVIRONMENT_CHANGED", failure.message); assertNull(failure.cause)
        assertTrue(ScrollClipEnvironmentFailure::class.java.declaredFields.filterNot { it.isSynthetic }.all { it.type.isEnum })
        assertFalse((failure.toString() + diagnostics + failure.environment).contains("PRIVATE_"))
    }

    private data class PreparationSample(val configuration: Int, val window: IsolationRect, val root: IsolationRect,
        val path: List<Int>, val targetBounds: IsolationRect) {
        override fun toString() = "PreparationSample(redacted)"
    }
    private fun preparationSample() = PreparationSample(1, viewport, viewport, listOf(0, 1), IsolationRect(0, 120, 10, 140))
    private fun preparationBaseline(diagnostics: ScrollClipReadDiagnostics) = ScrollClipBaseline<PreparationSample, PreparationSample>(
        { it }, { saved, sample ->
            diagnostics.same(CONFIGURATION, saved.configuration, sample.configuration)
            diagnostics.same(REMOTE_PATH, saved.path, sample.path)
            diagnostics.same(REMOTE_WINDOW, saved.window, sample.window)
            diagnostics.same(REMOTE_ROOT, saved.root, sample.root)
        })
    private suspend fun establish(baseline: ScrollClipBaseline<PreparationSample, PreparationSample>,
        sample: PreparationSample = preparationSample()): PreparationSample {
        var time = 0L
        return baseline.establish({ time }, GeometryReadSafety(), { baseline.check(sample); sample }, { time += it }, {})
    }

    @Test fun initialRemoteWindowChangeSettlesThenOnlyStableReferenceIsAccepted() = run {
        val diagnostics = ScrollClipReadDiagnostics(); val baseline = preparationBaseline(diagnostics)
        val first = preparationSample(); val settled = first.copy(window = IsolationRect(0, 0, 101, 100))
        diagnostics.at(INITIAL_PROBE) { baseline.check(first) }
        diagnostics.at(PHASE_FIRST_SAMPLE) { baseline.check(first) }
        var time = 0L; var reads = 0
        val stable = baseline.establish({ time }, GeometryReadSafety(), {
            val sample = if (reads++ == 0) first else settled
            diagnostics.at(STABILITY_CHECK) { baseline.check(sample) }
            assertEquals("ScrollClipBaseline(state=STABILIZING)", baseline.toString())
            sample
        }, { time += it }, {})
        assertEquals(settled, stable); assertEquals(1_200L, time)
        diagnostics.at(ANCHOR_CHECK) { baseline.check(settled) }
        environmentFailure(diagnostics, ANCHOR_CHECK, SAVED_BASELINE_MISMATCH, REMOTE_WINDOW) { baseline.check(first) }
        Unit
    }
    @Test fun rawTargetBoundsChangeAlsoRestartsInitialStabilityCount() = run {
        val diagnostics = ScrollClipReadDiagnostics(); val baseline = preparationBaseline(diagnostics)
        val first = preparationSample(); val settled = first.copy(targetBounds = IsolationRect(0, 121, 10, 141))
        var time = 0L; var reads = 0
        val stable = baseline.establish({ time }, GeometryReadSafety(), {
            val sample = if (reads++ == 0) first else settled
            baseline.check(sample); sample
        }, { time += it }, {})
        assertEquals(settled, stable); assertEquals(1_200L, time)
    }
    @Test fun atLeastThreeSamplesAreRequiredEvenIfOneSecondAlreadyPassed() = run {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); var time = 0L; var reads = 0
        baseline.establish({ time }, GeometryReadSafety(), { reads++; preparationSample() }, { time += 1_000L }, {})
        assertEquals(3, reads); assertEquals(2_000L, time)
    }
    @Test fun manyEqualSamplesStillRequireOneFullSecond() = run {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); var time = 0L; var reads = 0
        baseline.establish({ time }, GeometryReadSafety(), { reads++; preparationSample() }, { time += it }, {})
        assertEquals(6, reads); assertEquals(1_000L, time)
    }
    @Test fun observedInitialAbaDoesNotReuseOldStartOrCount() = run {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); var time = 0L; var reads = 0
        val a = preparationSample(); val b = a.copy(window = IsolationRect(0, 0, 101, 100))
        baseline.establish({ time }, GeometryReadSafety(), { if (reads++ == 1) b else a }, { time += it }, {})
        assertEquals(1_400L, time)
    }
    @Test fun neverStableRawSamplesTimeOutWithoutReference() {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); var time = 0L; var reads = 0
        try { run { baseline.establish({ time }, GeometryReadSafety(), {
            preparationSample().copy(configuration = reads++ % 2)
        }, { time += it }, {}) }; fail("STABILITY_TIMEOUT_REQUIRED") }
        catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.STABLE_UNOBSERVED, failure.issue) }
        assertEquals(3_000L, time)
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun everyFixedReferenceComponentRejectsChangeWithExistingCodes() = run {
        val first = preparationSample()
        val changes = listOf(CONFIGURATION to first.copy(configuration = 2),
            REMOTE_PATH to first.copy(path = listOf(0, 2)),
            REMOTE_WINDOW to first.copy(window = IsolationRect(0, 0, 101, 100)),
            REMOTE_ROOT to first.copy(root = IsolationRect(0, 0, 100, 101)))
        for ((field, changed) in changes) {
            val diagnostics = ScrollClipReadDiagnostics(); val baseline = preparationBaseline(diagnostics)
            establish(baseline, first)
            environmentFailure(diagnostics, PHASE_FIRST_SAMPLE, SAVED_BASELINE_MISMATCH, field) { baseline.check(changed) }
            rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(first) }
        }
    }
    @Test fun approvedReferenceCannotBeReestablishedForLaterPhase() = run {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); establish(baseline)
        var reads = 0
        rejects(ScrollClipIssue.PHASE_ORDER) {
            baseline.establish({ 0L }, GeometryReadSafety(), { reads++; preparationSample() }, {}, {})
        }
        assertEquals(0, reads)
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun changedTargetAllowedAcrossPhasesButFixedEnvironmentRemainsRequired() = run {
        val diagnostics = ScrollClipReadDiagnostics(); val baseline = preparationBaseline(diagnostics)
        val outside = establish(baseline)
        baseline.check(outside.copy(targetBounds = IsolationRect(0, 20, 10, 40)))
        baseline.check(outside)
        assertNull(diagnostics.failure())
    }
    @Test fun laterPhaseAnchorStillRejectsObservedTargetChangeBeforeReturnToA() = run {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); val first = establish(baseline)
        var reads = 0; var time = 0L
        try {
            MinimalGeometryPolling.stable({ time }, {
                val sample = if (++reads == 1) first.copy(targetBounds = IsolationRect(0, 21, 10, 41)) else first
                baseline.check(sample); sample
            }, { true }, { time += it }, anchor = first)
            fail("ANCHOR_REJECTION_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.STABLE_GEOMETRY_CHANGED, failure.issue) }
        assertEquals(1, reads)
    }
    @Test fun cancellationDuringInitialPauseIsPreservedAndPreventsReference() {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); val primary = CancellationException("PRIVATE_CANCEL")
        try { run { baseline.establish({ 0L }, GeometryReadSafety(), { preparationSample() }, { throw primary }, {}) }; fail("CANCEL_REQUIRED") }
        catch (failure: CancellationException) { assertSame(primary, failure) }
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun cancellationAtReferenceAcceptanceIsPreservedWithoutCommit() {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); var time = 0L; var reads = 0
        val primary = CancellationException("PRIVATE_CANCEL")
        try { run { baseline.establish({ time }, GeometryReadSafety(), { reads++; preparationSample() }, { time += it }, {
            if (reads >= 6) throw primary
        }) }; fail("CANCEL_REQUIRED") } catch (failure: CancellationException) { assertSame(primary, failure) }
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun referenceProjectionReachingExactDeadlineCannotCommit() {
        for (late in listOf(3_000L, 3_100L)) {
            var time = 0L
            val baseline = ScrollClipBaseline<PreparationSample, PreparationSample>({ time = late; it }, { _, _ -> })
            try { run { baseline.establish({ time }, GeometryReadSafety(), { preparationSample() }, { time += it }, {}) }; fail("TIMEOUT_REQUIRED") }
            catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.STABLE_UNOBSERVED, failure.issue) }
            rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
        }
    }
    @Test fun lateCoherentReadCannotSupplyApprovedReference() {
        var time = 0L; val baseline = preparationBaseline(ScrollClipReadDiagnostics())
        try { run { baseline.establish({ time }, GeometryReadSafety(), { time = 3_000L; preparationSample() }, {}, {}) }; fail("TIMEOUT_REQUIRED") }
        catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.STABLE_UNOBSERVED, failure.issue) }
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun deadlineCrossedAtReferenceAssignmentDiscardsTheTentativeReference() {
        var time = 0L; var selected = false; var acceptanceClockReads = 0
        val baseline = ScrollClipBaseline<PreparationSample, PreparationSample>({ selected = true; it }, { _, _ -> })
        val now = { if (selected && ++acceptanceClockReads >= 2) 3_000L else time }
        try { run { baseline.establish(now, GeometryReadSafety(), { preparationSample() }, { time += it }, {}) }; fail("TIMEOUT_REQUIRED") }
        catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.STABLE_UNOBSERVED, failure.issue) }
        assertEquals("ScrollClipBaseline(state=DISPOSED)", baseline.toString())
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun invalidOwnedSampleIsNotRetriedDuringInitialStability() {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); var reads = 0
        rejects(ScrollClipIssue.OWNERSHIP_REJECTED) {
            baseline.establish({ 0L }, GeometryReadSafety(), {
                reads++; ScrollClipContract.requireOwner(1, "PRIVATE_OTHER", 1); preparationSample()
            }, {}, {})
        }
        assertEquals(1, reads)
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun nonpositiveBoundsStillFailWithExistingDiagnosisBeforeAnyReference() {
        val diagnostics = ScrollClipReadDiagnostics(); val baseline = preparationBaseline(diagnostics); var reads = 0
        environmentFailure(diagnostics, STABILITY_CHECK, NONPOSITIVE_BOUNDS, REMOTE_WINDOW) {
            run { baseline.establish({ 0L }, GeometryReadSafety(), {
                reads++; diagnostics.positive(REMOTE_WINDOW, IsolationRect(0, 0, 0, 0)); preparationSample()
            }, {}, {}) }
        }
        assertEquals(1, reads)
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun finalStableSampleReleaseFailurePreventsReferenceAndLaterReuse() {
        val safety = GeometryReadSafety(); val baseline = preparationBaseline(ScrollClipReadDiagnostics()); var time = 0L
        try { run { baseline.establish({ time }, safety, { safety.inspect { scope ->
            if (time == 1_000L) scope.release { throw IllegalStateException("PRIVATE_RELEASE") }
            preparationSample()
        } }, { time += it }, {}) }; fail("RELEASE_FAILURE_REQUIRED") } catch (_: PriorityReleaseException) { }
        assertNotNull(safety.failure())
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun initialInspectionAndMultipleReleaseFailuresKeepOriginalAndTryAll() {
        val safety = GeometryReadSafety(); val baseline = preparationBaseline(ScrollClipReadDiagnostics())
        val primary = CancellationException("PRIVATE_CANCEL"); var releases = 0
        try { run { baseline.establish({ 0L }, safety, { safety.inspect { scope ->
            try { throw primary } finally {
                scope.release { releases++; throw IllegalStateException("PRIVATE_RELEASE") }
                scope.release { releases++; throw CancellationException("PRIVATE_RELEASE_CANCEL") }
                scope.release { releases++ }
            }
        } }, {}, {}) }; fail("CANCEL_REQUIRED") } catch (failure: CancellationException) { assertSame(primary, failure) }
        assertEquals(3, releases); assertNotNull(safety.failure())
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun releaseLatchCannotBeErasedBySuccessfulLaterReadOrReferenceProjection() {
        val safety = GeometryReadSafety(); var time = 0L; var reads = 0
        val baseline = preparationBaseline(ScrollClipReadDiagnostics())
        try { run { baseline.establish({ time }, safety, {
            reads++
            if (reads == 2) {
                val primary = GeometryDiagnosticFailure(GeometryIssue.ROOT_UNAVAILABLE)
                safety.inspect { scope -> try { throw primary } finally { scope.release { throw IllegalStateException("PRIVATE_RELEASE") } } }
            }
            preparationSample()
        }, { time += it }, {}) }; fail("PRIMARY_REQUIRED") }
        catch (failure: GeometryDiagnosticFailure) { assertEquals(GeometryIssue.ROOT_UNAVAILABLE, failure.issue) }
        assertEquals(2, reads); assertNotNull(safety.failure())
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
    }
    @Test fun closingBaselineReleasesReferenceAndNeverPermitsReestablishment() = run {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); establish(baseline)
        baseline.close()
        assertEquals("ScrollClipBaseline(state=DISPOSED)", baseline.toString())
        rejects(ScrollClipIssue.PHASE_ORDER) { baseline.check(preparationSample()) }
        rejects(ScrollClipIssue.PHASE_ORDER) { establish(baseline) }
    }
    @Test fun stableReferenceStillCannotReplaceMissingServiceOrCleanupEvidence() = run {
        val baseline = preparationBaseline(ScrollClipReadDiagnostics()); establish(baseline)
        val progress = ScrollClipProgress()
        assertEquals("INCONCLUSIVE", progress.outcome(true, true))
        for ((index, phase) in ScrollClipPhase.entries.withIndex()) progress.phase(phase) { scope ->
            baseline.check(preparationSample())
            final(progress, scope, listOf(ready(index + 1)))
        }
        assertEquals("OBSERVATIONS_COMPLETE", progress.outcome(true, true))
        assertEquals("INCONCLUSIVE", progress.outcome(false, true))
        assertEquals("INCONCLUSIVE", progress.outcome(true, false))
    }
}
