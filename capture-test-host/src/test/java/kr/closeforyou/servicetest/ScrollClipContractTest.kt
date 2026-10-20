package kr.closeforyou.servicetest

import java.util.concurrent.CancellationException
import kotlin.coroutines.*
import org.junit.Assert.*
import org.junit.Test

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
}
