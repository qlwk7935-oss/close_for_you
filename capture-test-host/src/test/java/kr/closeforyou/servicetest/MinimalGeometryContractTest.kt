package kr.closeforyou.servicetest

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class MinimalGeometryContractTest {
    private fun valid(id: Int = 1, disposition: String = "NO_POPUP", reason: String = "BASELINE_ONLY") =
        "$id:ASSESSED:$disposition:$reason:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
    private fun outside(id: Int = 1, edges: String = "RIGHT,BOTTOM") =
        "$id:ASSESSED:UNCERTAIN:INVALID_GEOMETRY:WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:$edges:WITHIN_ONE_SECOND"
    private fun assess(vararg rows: String) = MinimalGeometryContract.assess("OBSERVED", rows.toList(), false)
    private val good get() = assess(valid())
    private val out get() = assess(outside())
    private fun complete(launch: GeometryServiceObservation? = good, stable: GeometryServiceObservation? = good,
        hostLaunch: GeometryState? = GeometryState.VALID, hostStable: GeometryState? = GeometryState.VALID,
        issue: GeometryIssue = GeometryIssue.NONE, cleanup: Boolean = true) =
        MinimalGeometryContract.completion(launch, stable, hostLaunch, hostStable, issue, cleanup)

    @Test fun validAndOutsideRowsBothProvideDiagnosticEvidence() {
        assertEquals(GeometryServiceObservation(GeometryIssue.NONE, GeometryState.VALID), good)
        assertEquals(GeometryServiceObservation(GeometryIssue.NONE, GeometryState.OUTSIDE), out)
        assertEquals("OBSERVATIONS_COMPLETE", complete(stable = out, hostStable = GeometryState.OUTSIDE))
    }
    @Test fun changingOutsideEdgesDoesNotMixFieldsAcrossAttempts() {
        assertEquals(GeometryState.OUTSIDE, assess(outside(), outside(2, "RIGHT")).geometry)
        assertEquals(GeometryIssue.NONE, assess(outside(), outside(2, "RIGHT")).issue)
    }
    @Test fun validGeometryDoesNotClaimDetectorBaselineOrCandidateSuccess() {
        for ((disposition, reason) in listOf("NO_POPUP" to "UNCHANGED", "NO_POPUP" to "ORDINARY_CONTENT_CHANGE",
            "UNCERTAIN" to "UNCONFIRMED_TRANSITION", "UNCERTAIN" to "UNCONFIRMED_STRUCTURE",
            "POPUP_CANDIDATE" to "BOUNDED_WINDOW", "POPUP_CANDIDATE" to "SEMANTIC_DIALOG", "POPUP_CANDIDATE" to "PANE_WITH_UNDERLAY"))
            assertEquals(GeometryIssue.NONE, assess(valid(disposition = disposition, reason = reason)).issue)
    }
    @Test fun impossibleDispositionReasonAndGeometryCombinationsAreRejected() {
        for (row in listOf(outside().replace("UNCERTAIN", "POPUP_CANDIDATE"),
            outside().replace("INVALID_GEOMETRY", "BOUNDED_WINDOW"),
            valid(disposition = "UNCERTAIN", reason = "BASELINE_ONLY"),
            valid(reason = "INVALID_GEOMETRY"), valid(disposition = "POPUP_CANDIDATE")))
            assertEquals(GeometryIssue.INVALID_DIAGNOSTIC, assess(row).issue)
    }
    @Test fun inconsistentRelationsAndExtentsCannotBorrowAnotherRowsGeometry() {
        for (row in listOf(outside().replace(":RIGHT,BOTTOM:", ":NONE:"),
            valid().replace(":WITHIN_FRAME:", ":WINDOW_OUTSIDE_FRAME:"),
            valid().replace(":NONE:", ":RIGHT:")))
            assertEquals(GeometryIssue.INVALID_DIAGNOSTIC, assess(row, valid(2)).issue)
        for (row in listOf(valid().replace("POSITIVE:POSITIVE", "POSITIVE:NONPOSITIVE_WIDTH"),
            "1:ASSESSED:NO_POPUP:BASELINE_ONLY:UNOBSERVED:OLDER"))
            assertEquals(GeometryIssue.UNSUPPORTED_GEOMETRY, assess(row, valid(2)).issue)
    }
    @Test fun blocksAndCollectionLimitsCannotBecomeDiagnosticCompleteness() {
        for (block in listOf("PASSWORD", "EDITABLE_FIELD", "SENSITIVE_CONTENT", "SYSTEM_WINDOW", "UNREADABLE_TREE", "TRUNCATED_TREE")) {
            val observation = assess(valid(), "2:BLOCKED:$block:NONE")
            assertEquals(GeometryIssue.COLLECTION_BLOCKED, observation.issue)
            assertEquals("INCONCLUSIVE", complete(stable = observation))
        }
    }
    @Test fun overflowInvalidResponseAndMissingAttemptsAreRejected() {
        assertEquals(GeometryIssue.OVERFLOW, MinimalGeometryContract.assess("OBSERVED", listOf(valid()), true).issue)
        assertEquals(GeometryIssue.OVERFLOW, MinimalGeometryContract.assess("OBSERVED", List(257) { valid() }, false).issue)
        assertEquals(GeometryIssue.UNOBSERVED, assess().issue)
        for (code in listOf(null, "ENDED", "F1_SESSION_INVALIDATED", "INVALID_SESSION"))
            assertEquals(GeometryIssue.RESPONSE_REJECTED, MinimalGeometryContract.assess(code, listOf(valid()), false).issue)
    }
    @Test fun duplicateInvalidOrNoncanonicalOrdinalsAreRejected() {
        assertEquals(GeometryIssue.INVALID_DIAGNOSTIC, assess(valid(), outside()).issue)
        for (id in listOf("0", "01", "257", "PRIVATE"))
            assertEquals(GeometryIssue.INVALID_DIAGNOSTIC, assess(valid().replaceFirst("1:", "$id:")).issue)
    }
    @Test fun malformedAndOversizeRowsOnlyReturnFixedCodes() {
        for (row in listOf("SYNTHETIC_PRIVATE_VALUE", " ".repeat(3_000) + "SYNTHETIC_PRIVATE_VALUE", valid() + ":EXTRA")) {
            val observation = assess(row)
            assertEquals(GeometryIssue.INVALID_DIAGNOSTIC, observation.issue)
            assertFalse(observation.toString().contains("SYNTHETIC_PRIVATE_VALUE"))
            assertEquals("INVALID_DIAGNOSTIC", GeometryDiagnosticFailure(observation.issue).message)
        }
    }
    @Test fun mixedValidAndOutsideRemainsMixedButCompleteObservation() {
        val mixed = assess(valid(), outside(2))
        assertEquals(GeometryState.MIXED, mixed.geometry)
        assertEquals(GeometryContrast.MIXED, MinimalGeometryContract.contrast(mixed.geometry, GeometryState.VALID))
        assertEquals("OBSERVATIONS_COMPLETE", complete(launch = mixed))
    }
    @Test fun sourceContrastsAreIndependentAndConservative() {
        assertEquals(GeometryContrast.LAUNCH_ONLY, MinimalGeometryContract.contrast(GeometryState.OUTSIDE, GeometryState.VALID))
        assertEquals(GeometryContrast.STABLE_OUTSIDE, MinimalGeometryContract.contrast(GeometryState.VALID, GeometryState.OUTSIDE))
        assertEquals(GeometryContrast.STABLE_VALID, MinimalGeometryContract.contrast(GeometryState.VALID, GeometryState.VALID))
        assertEquals(GeometryContrast.INCONCLUSIVE, MinimalGeometryContract.contrast(null, GeometryState.VALID))
        assertEquals(GeometryContrast.INCONCLUSIVE, MinimalGeometryContract.contrast(GeometryState.VALID, GeometryState.INCONCLUSIVE))
        assertEquals("OBSERVATIONS_COMPLETE", complete(launch = out, stable = out)) // Host valid does not overwrite service outside.
    }
    @Test fun everyMissingPhaseErrorOrCleanupFailurePreventsCompletion() {
        assertEquals("INCONCLUSIVE", complete(launch = null))
        assertEquals("INCONCLUSIVE", complete(stable = null))
        assertEquals("INCONCLUSIVE", complete(hostLaunch = null))
        assertEquals("INCONCLUSIVE", complete(hostStable = null))
        assertEquals("INCONCLUSIVE", complete(cleanup = false))
        for (issue in GeometryIssue.entries.filter { it != GeometryIssue.NONE }) assertEquals("INCONCLUSIVE", complete(issue = issue))
    }
    @Test fun hostRelationsKeepOriginalRectanglesWithoutClamping() {
        val frame = IsolationRect(0, 0, 100, 200)
        val window = IsolationRect(0, 0, 101, 201)
        assertEquals(GeometryState.OUTSIDE, MinimalGeometryContract.host(window, frame))
        assertEquals("RIGHT,BOTTOM", window.outside(frame))
        assertEquals(101, window.right)
        assertEquals(201, window.bottom)
        assertEquals(GeometryState.INCONCLUSIVE, MinimalGeometryContract.host(IsolationRect(0, 9, 2, 1), frame))
    }

    private fun <T> immediately(block: suspend () -> T): T {
        var finished: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { finished = result }
        })
        return checkNotNull(finished) { "FAKE_CALLBACK_SUSPENDED" }.getOrThrow()
    }
    private fun rejected(issue: GeometryIssue, block: suspend () -> Unit) {
        try { immediately(block); fail("EXPECTED_FIXED_FAILURE") }
        catch (failure: GeometryDiagnosticFailure) { assertEquals(issue, failure.issue); assertEquals(issue.name, failure.message) }
    }
    @Test fun cachedCompleteResponseAt1900CannotPassAfterResumeBeyond3000() {
        var clock = 0L
        var reads = 0
        rejected(GeometryIssue.OBSERVATION_TIMEOUT) {
            MinimalGeometryPolling.await({ clock }, { reads++; clock = 1_900; good }, { clock = 3_100 })
        }
        assertEquals(1, reads)
    }
    @Test fun exactDeadlineAndLateResponseAreRejected() {
        for (time in listOf(3_000L, 3_001L)) {
            var clock = 0L
            rejected(GeometryIssue.OBSERVATION_TIMEOUT) {
                MinimalGeometryPolling.await({ clock }, { clock = time; out }, { fail("NO_PAUSE_EXPECTED") })
            }
        }
    }
    @Test fun minimumObservationTimeRequiresFreshReadAfter2000() = immediately {
        var clock = 0L
        var reads = 0
        val result = MinimalGeometryPolling.await({ clock }, { reads++; if (reads == 1) clock = 1_900; out }, { clock += it })
        assertEquals(2, reads)
        assertEquals(GeometryState.OUTSIDE, result.geometry)
    }
    @Test fun noServiceAttemptCannotBeFilledByHostGeometry() {
        var clock = 0L
        rejected(GeometryIssue.OBSERVATION_TIMEOUT) {
            MinimalGeometryPolling.await({ clock }, { assess() }, { clock += it })
        }
        assertEquals("INCONCLUSIVE", complete(stable = assess()))
    }
    @Test fun lateFinalBlockOverflowUnobservedAndInvalidRowsRejectPriorCompleteObservation() = immediately {
        for (final in listOf(assess(valid(), "2:BLOCKED:UNREADABLE_TREE:NONE"),
            MinimalGeometryContract.assess("OBSERVED", listOf(valid()), true), assess(), assess(valid(), valid()), assess("INVALID"))) {
            var clock = 0L
            MinimalGeometryPolling.await({ clock }, { good }, { clock += it })
            rejected(final.issue) { MinimalGeometryContract.requireComplete(final) }
            assertEquals("INCONCLUSIVE", complete(stable = final))
        }
    }

    private data class RawKey(val rect: IsolationRect, val rotation: Int = 0, val configuration: Int = 1, val window: Int = 1) {
        override fun toString() = "RawKey(redacted)"
    }
    @Test fun stableGateUsesRawValuesNotMatchingRelationEnums() {
        val frame = IsolationRect(0, 0, 100, 200)
        var clock = 0L
        var count = 0
        rejected(GeometryIssue.STABLE_UNOBSERVED) {
            MinimalGeometryPolling.stable<RawKey>({ clock }, {
                val rect = IsolationRect(0, 0, 101 + count++, 201)
                assertEquals(RectRelation.CONTAINS, rect.relation(frame))
                RawKey(rect)
            }, { true }, { clock += it })
        }
    }
    @Test fun stabilityNeedsThreeSamplesAndAtLeastOneSecond() = immediately {
        val key = RawKey(IsolationRect(0, 0, 101, 201))
        var clock = 0L
        var reads = 0
        val result = MinimalGeometryPolling.stable({ clock }, { reads++; key }, { true }, { clock += it })
        assertEquals(key, result)
        assertEquals(6, reads)
        assertTrue(clock >= 1_000)
    }
    @Test fun twoSamplesEvenAfterOneSecondAreInsufficient() {
        val key = RawKey(IsolationRect(0, 0, 101, 201))
        var clock = 0L
        var reads = 0
        rejected(GeometryIssue.STABLE_UNOBSERVED) {
            MinimalGeometryPolling.stable<RawKey>({ clock }, { reads++; key }, { true }, { clock = if (reads == 1) 1_100 else 3_100 })
        }
        assertEquals(2, reads)
    }
    @Test fun pendingLayoutCannotBeMadeStableByElapsedTime() {
        var clock = 0L
        rejected(GeometryIssue.STABLE_UNOBSERVED) {
            MinimalGeometryPolling.stable<String>({ clock }, { "FIXED" }, { false }, { clock += it })
        }
    }
    @Test fun postObservationCannotResettleToDifferentRawBoundsRotationConfigurationOrWindow() {
        val key = RawKey(IsolationRect(0, 0, 101, 201))
        for (changed in listOf(key.copy(rect = IsolationRect(0, 0, 102, 201)), key.copy(rotation = 1),
            key.copy(configuration = 2), key.copy(window = 2))) {
            rejected(GeometryIssue.STABLE_GEOMETRY_CHANGED) {
                MinimalGeometryPolling.stable<RawKey>({ 0L }, { changed }, { true }, { fail("CHANGE_MUST_NOT_SETTLE") }, anchor = key)
            }
        }
    }
    @Test fun postObservationRechecksFullStabilityAgainstSameAnchor() = immediately {
        val key = RawKey(IsolationRect(0, 0, 101, 201))
        var clock = 0L
        var count = 0
        MinimalGeometryPolling.stable({ clock }, { count++; key }, { true }, { clock += it }, anchor = key)
        assertEquals(6, count)
        assertTrue(clock >= 1_000)
    }
    @Test fun noStabilitySuccessAfterReadCrossesDeadline() {
        var clock = 0L
        rejected(GeometryIssue.STABLE_UNOBSERVED) {
            MinimalGeometryPolling.stable<String>({ clock }, { clock = 3_000; "FIXED" }, { true }, { })
        }
    }
    @Test fun cancellationPropagatesFromRealSharedLoops() {
        try {
            immediately { MinimalGeometryPolling.await({ 0L }, { throw java.util.concurrent.CancellationException("FIXED") }, { }) }
            fail("CANCELLATION_REQUIRED")
        } catch (_: java.util.concurrent.CancellationException) { /* No conversion to diagnostic completion. */ }
        try {
            immediately { MinimalGeometryPolling.stable({ 0L }, { "FIXED" }, { true }, { throw java.util.concurrent.CancellationException("FIXED") }) }
            fail("CANCELLATION_REQUIRED")
        } catch (_: java.util.concurrent.CancellationException) { /* No conversion to diagnostic completion. */ }
    }
    @Test fun resourceReleaseFailureStillTriesRemainingResourcesAndCannotCompleteNormally() {
        val released = mutableListOf<Int>()
        try {
            PriorityReleaseScope().protect { scope ->
                scope.releaseAll(listOf(Any(), Any())) { released += released.size; if (released.size == 1) error("SYNTHETIC_PRIVATE_VALUE") }
            }
            fail("RELEASE_FAILURE_REQUIRED")
        } catch (failure: PriorityReleaseException) { assertEquals("FRAMEWORK_RELEASE_FAILED", failure.message) }
        assertEquals(2, released.size)
    }
    @Test fun originalInspectionFailureSurvivesOrdinaryReleaseFailure() {
        val original = GeometryDiagnosticFailure(GeometryIssue.WINDOW_MISMATCH)
        try {
            PriorityReleaseScope().protect<Unit> { scope ->
                try { throw original } finally { scope.release { error("SYNTHETIC_PRIVATE_VALUE") } }
            }
            fail("INSPECTION_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertSame(original, failure); assertEquals("WINDOW_MISMATCH", failure.message) }
    }

    @Test fun rootUnavailablePlusWindowReleaseFailureCannotRetryIntoSuccess() {
        val safety = GeometryReadSafety()
        val primary = GeometryDiagnosticFailure(GeometryIssue.ROOT_UNAVAILABLE)
        var reads = 0
        var releases = 0
        var clock = 0L
        try {
            immediately {
                MinimalGeometryPolling.firstCoherent<Boolean>({ clock }, safety, {
                    reads++
                    if (reads > 1) true else safety.inspect<Boolean> { scope ->
                        try { throw primary } finally {
                            scope.releaseAll(listOf(Any(), Any(), Any())) {
                                releases++
                                if (releases == 1) error("SYNTHETIC_PRIVATE_RELEASE")
                            }
                        }
                    }
                }, { it }, { clock += it })
            }
            fail("READ_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertSame(primary, failure) }
        assertEquals(1, reads)
        assertEquals(3, releases)
        assertEquals(GeometryReadFailure(GeometryIssue.ROOT_UNAVAILABLE, PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED), safety.failure())
        assertEquals("INCONCLUSIVE", complete(cleanup = safety.failure() == null))
    }

    @Test fun layoutPendingPlusNodeReleaseFailureCannotRetryIntoSuccess() {
        val safety = GeometryReadSafety()
        var reads = 0
        var releases = 0
        rejected(GeometryIssue.LAYOUT_PENDING) {
            MinimalGeometryPolling.firstCoherent<Boolean>({ 0L }, safety, {
                reads++
                if (reads > 1) true else safety.inspect<Boolean> { scope ->
                    try { throw GeometryDiagnosticFailure(GeometryIssue.LAYOUT_PENDING) }
                    finally { scope.release { releases++; error("SYNTHETIC_PRIVATE_NODE") } }
                }
            }, { it }, { fail("PAIRED_FAILURE_MUST_NOT_PAUSE") })
        }
        assertEquals(1, reads)
        assertEquals(1, releases)
        assertEquals(GeometryReadFailure(GeometryIssue.LAYOUT_PENDING, PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED), safety.failure())
        assertEquals("INCONCLUSIVE", complete(cleanup = safety.failure() == null))
    }

    @Test fun everyRecoverableInitialIssueCanRetryWhenAllReleasesSucceed() = immediately {
        for (issue in listOf(GeometryIssue.ROOT_UNAVAILABLE, GeometryIssue.REMOTE_UNAVAILABLE, GeometryIssue.LAYOUT_PENDING)) {
            val safety = GeometryReadSafety()
            var clock = 0L
            var reads = 0
            var releases = 0
            val result = MinimalGeometryPolling.firstCoherent<Boolean>({ clock }, safety, {
                reads++
                safety.inspect { scope ->
                    try {
                        if (reads == 1) throw GeometryDiagnosticFailure(issue)
                        true
                    } finally { scope.release { releases++ } }
                }
            }, { it }, { assertEquals(150L, it); clock += it })
            assertTrue(result)
            assertEquals(2, reads)
            assertEquals(2, releases)
            assertNull(safety.failure())
        }
    }

    @Test fun releaseFailureAloneIsNonrecoverableAndKeepsPrimaryNone() {
        val safety = GeometryReadSafety()
        var reads = 0
        try {
            immediately {
                MinimalGeometryPolling.firstCoherent<Boolean>({ 0L }, safety, {
                    reads++
                    safety.inspect { scope -> scope.release { error("SYNTHETIC_PRIVATE_RELEASE") }; true }
                }, { it }, { fail("RELEASE_FAILURE_MUST_NOT_RETRY") })
            }
            fail("RELEASE_FAILURE_REQUIRED")
        } catch (failure: PriorityReleaseException) { assertEquals("FRAMEWORK_RELEASE_FAILED", failure.message) }
        assertEquals(1, reads)
        assertEquals(GeometryReadFailure(GeometryIssue.NONE, PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED), safety.failure())
        assertEquals("INCONCLUSIVE", complete(cleanup = safety.failure() == null))
    }

    @Test fun inspectionAndMultipleChildRootWindowReleaseFailuresPreservePrimaryAndAttemptAll() {
        val safety = GeometryReadSafety()
        val original = GeometryDiagnosticFailure(GeometryIssue.REMOTE_UNAVAILABLE)
        val released = mutableListOf<String>()
        try {
            safety.inspect<Unit> { scope ->
                try { throw original } finally {
                    scope.release { released += "CHILD"; error("SYNTHETIC_PRIVATE_CHILD") }
                    scope.release { released += "ROOT"; error("SYNTHETIC_PRIVATE_ROOT") }
                    scope.releaseAll(listOf(Any(), Any())) { released += "WINDOW"; error("SYNTHETIC_PRIVATE_WINDOW") }
                }
            }
            fail("ORIGINAL_FAILURE_REQUIRED")
        } catch (failure: GeometryDiagnosticFailure) { assertSame(original, failure) }
        assertEquals(listOf("CHILD", "ROOT", "WINDOW", "WINDOW"), released)
        assertEquals(GeometryReadFailure(GeometryIssue.REMOTE_UNAVAILABLE, PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED), safety.failure())
        assertEquals("INCONCLUSIVE", complete(cleanup = safety.failure() == null))
    }

    @Test fun cancellationAndMultipleReleaseFailuresPreserveCancellationAndAttemptAll() {
        val safety = GeometryReadSafety()
        val cancelled = java.util.concurrent.CancellationException("SYNTHETIC_PRIVATE_CANCEL")
        var released = 0
        try {
            immediately {
                MinimalGeometryPolling.firstCoherent<Boolean>({ 0L }, safety, {
                    safety.inspect<Boolean> { scope ->
                        try { throw cancelled } finally {
                            scope.release { released++; error("SYNTHETIC_PRIVATE_CHILD") }
                            scope.release { released++; error("SYNTHETIC_PRIVATE_ROOT") }
                            scope.releaseAll(listOf(Any(), Any())) { released++; error("SYNTHETIC_PRIVATE_WINDOW") }
                        }
                    }
                }, { it }, { fail("CANCELLATION_MUST_NOT_RETRY") })
            }
            fail("CANCELLATION_REQUIRED")
        } catch (failure: java.util.concurrent.CancellationException) { assertSame(cancelled, failure) }
        assertEquals(4, released)
        assertEquals(GeometryReadFailure(GeometryIssue.READ_CANCELLED, PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED), safety.failure())
        assertEquals("INCONCLUSIVE", complete(cleanup = safety.failure() == null))
    }

    @Test fun cancellationDuringReleaseAloneIsRecordedAndPropagates() {
        val safety = GeometryReadSafety()
        var released = 0
        try {
            safety.inspect { scope ->
                scope.release { released++; throw java.util.concurrent.CancellationException("SYNTHETIC_PRIVATE_RELEASE") }
                scope.release { released++ }
                true
            }
            fail("CANCELLATION_REQUIRED")
        } catch (_: java.util.concurrent.CancellationException) { /* Fixed exception from existing release scope. */ }
        assertEquals(2, released)
        assertEquals(GeometryReadFailure(GeometryIssue.NONE, PriorityReleaseFailure.RELEASE_CANCELLED), safety.failure())
        assertEquals("INCONCLUSIVE", complete(cleanup = safety.failure() == null))
    }

    @Test fun failedReaderCannotEnterLaterFirstOrStableReadAndFirstCodesRemain() {
        val safety = GeometryReadSafety()
        rejected(GeometryIssue.ROOT_UNAVAILABLE) {
            safety.inspect<Unit> { scope ->
                try { throw GeometryDiagnosticFailure(GeometryIssue.ROOT_UNAVAILABLE) }
                finally { scope.release { error("SYNTHETIC_PRIVATE_RELEASE") } }
            }
        }
        val first = safety.failure()
        var reads = 0
        rejected(GeometryIssue.RELEASE_FAILED) {
            MinimalGeometryPolling.firstCoherent<Boolean>({ 0L }, safety, { reads++; true }, { it }, { })
        }
        rejected(GeometryIssue.RELEASE_FAILED) {
            MinimalGeometryPolling.stable<Boolean>({ 0L }, {
                safety.inspect { reads++; true }
            }, { it }, { })
        }
        assertEquals(0, reads)
        assertEquals(first, safety.failure())
    }

    @Test fun swallowedReadExceptionStillCannotBypassLatchedReleaseFailure() {
        val safety = GeometryReadSafety()
        rejected(GeometryIssue.RELEASE_FAILED) {
            MinimalGeometryPolling.firstCoherent<Boolean>({ 0L }, safety, {
                try {
                    safety.inspect<Unit> { scope ->
                        try { throw GeometryDiagnosticFailure(GeometryIssue.ROOT_UNAVAILABLE) }
                        finally { scope.release { error("SYNTHETIC_PRIVATE_RELEASE") } }
                    }
                } catch (_: GeometryDiagnosticFailure) { /* Deliberately bad fake reader. */ }
                true
            }, { it }, { })
        }
        assertEquals("INCONCLUSIVE", complete(cleanup = safety.failure() == null))
    }

    @Test fun unexpectedInspectionExceptionIsNotRetainedInDiagnosticCodes() {
        val safety = GeometryReadSafety()
        val original = IllegalArgumentException("SYNTHETIC_PRIVATE_INPUT")
        try {
            safety.inspect<Unit> { scope ->
                try { throw original } finally { scope.release { error("SYNTHETIC_PRIVATE_RELEASE") } }
            }
            fail("ORIGINAL_FAILURE_REQUIRED")
        } catch (failure: IllegalArgumentException) { assertSame(original, failure) }
        assertEquals(GeometryReadFailure(GeometryIssue.OBSERVATION_FAILED, PriorityReleaseFailure.FRAMEWORK_RELEASE_FAILED), safety.failure())
        assertFalse(safety.toString().contains("SYNTHETIC_PRIVATE"))
        assertFalse(safety.failure().toString().contains("SYNTHETIC_PRIVATE"))
        assertEquals("RELEASE_FAILED", try { safety.requireClean(); "UNEXPECTED" } catch (f: GeometryDiagnosticFailure) { f.message })
    }

    @Test fun firstCoherentDoesNotReuseReadySampleWhenReadCrossesDeadline() {
        var clock = 0L
        rejected(GeometryIssue.STABLE_UNOBSERVED) {
            MinimalGeometryPolling.firstCoherent<Boolean>({ clock }, GeometryReadSafety(), { clock = 3_000; true }, { it }, { })
        }
    }

    @Test fun firstCoherentDoesNotReusePreDeadlineSampleAfterDelayedResume() {
        var clock = 0L
        var reads = 0
        rejected(GeometryIssue.STABLE_UNOBSERVED) {
            MinimalGeometryPolling.firstCoherent<Boolean>({ clock }, GeometryReadSafety(), {
                reads++; clock = 2_900; false
            }, { it }, { clock = 3_100 })
        }
        assertEquals(1, reads)
    }

    @Test fun observedABABeforeStabilityRestartsBothCountAndTime() {
        val a = RawKey(IsolationRect(0, 0, 101, 201))
        val b = a.copy(rect = IsolationRect(0, 0, 102, 201))
        val gate = ObservedGeometryStability<RawKey>()
        assertFalse(gate.observe(a, true, 0))
        assertFalse(gate.observe(a, true, 400))
        assertFalse(gate.observe(b, true, 800))
        assertFalse(gate.observe(a, true, 1_000))
        assertFalse(gate.observe(a, true, 1_400))
        assertFalse(gate.observe(a, true, 1_800))
        assertTrue(gate.observe(a, true, 2_000))
    }

    @Test fun actualSharedStableLoopDoesNotCombineOriginalAWithReturnedA() = immediately {
        val a = RawKey(IsolationRect(0, 0, 101, 201))
        val b = a.copy(rect = IsolationRect(0, 0, 102, 201))
        val script = listOf(0L to a, 400L to a, 800L to b, 1_000L to a, 1_400L to a, 1_800L to a, 2_000L to a)
        var index = 0
        val result = MinimalGeometryPolling.stable<RawKey>({ script[index].first }, { script[index].second }, { true }, { index++ })
        assertEquals(a, result)
        assertEquals(6, index)
    }

    @Test fun observedABAAfterAnchorRejectsBImmediatelyWithoutReadingReturnedA() {
        val a = RawKey(IsolationRect(0, 0, 101, 201))
        val b = a.copy(rect = IsolationRect(0, 0, 102, 201))
        val script = listOf(a, b, a)
        var index = 0
        var reads = 0
        rejected(GeometryIssue.STABLE_GEOMETRY_CHANGED) {
            MinimalGeometryPolling.stable<RawKey>({ index * 200L }, { reads++; script[index] }, { true }, { index++ }, anchor = a)
        }
        assertEquals(2, reads)
        assertEquals(1, index)
        assertEquals("INCONCLUSIVE", complete(issue = GeometryIssue.STABLE_GEOMETRY_CHANGED))
    }
}
