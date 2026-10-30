package kr.closeforyou.servicetest

import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

class MinimalDialogContractTest {
    private val baseline = MinimalDialogPhase.BASELINE
    private val dialog = MinimalDialogPhase.DIALOG
    private fun row(id: Int = 1, disposition: String = "NO_POPUP", reason: String = "BASELINE_ONLY") =
        "$id:ASSESSED:$disposition:$reason:VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE:OLDER"
    private fun candidate(id: Int = 1, reason: String = "BOUNDED_WINDOW") = row(id, "POPUP_CANDIDATE", reason)
    private fun assess(phase: MinimalDialogPhase, vararg rows: String) = MinimalDialogContract.assess(phase, "OBSERVED", rows.toList(), false)

    @Test fun baselineRequiresAnActualBaselineOnlyRow() {
        assertEquals(MinimalDialogVerdict.BASELINE_CONFIRMED, assess(baseline, row()))
        assertEquals(MinimalDialogVerdict.BASELINE_CONFIRMED, assess(baseline, row(), row(2, reason = "UNCHANGED"), row(3, reason = "ORDINARY_CONTENT_CHANGE")))
    }
    @Test fun validOrdinaryOrUnchangedRowsCannotInventBaselineOnly() {
        for (reason in listOf("UNCHANGED", "ORDINARY_CONTENT_CHANGE"))
            assertEquals(MinimalDialogVerdict.BASELINE_ONLY_NOT_SEEN, assess(baseline, row(reason = reason)))
    }
    @Test fun baselineFalseCandidateIsFailureEvenBesideBaselineOnly() {
        assertEquals(MinimalDialogVerdict.BASELINE_FALSE_CANDIDATE, assess(baseline, row(), candidate(2)))
        assertEquals("FAILURE", MinimalDialogContract.failureOutcome(MinimalDialogVerdict.BASELINE_FALSE_CANDIDATE))
    }
    @Test fun uncertainTransitionDoesNotEstablishBaseline() {
        assertEquals(MinimalDialogVerdict.DETECTOR_UNCERTAIN, assess(baseline, row(disposition = "UNCERTAIN", reason = "UNCONFIRMED_TRANSITION")))
    }
    @Test fun dialogRequiresRealCandidateAndAllowsItsUnchangedContinuation() {
        for (reason in listOf("BOUNDED_WINDOW", "SEMANTIC_DIALOG", "PANE_WITH_UNDERLAY")) {
            assertEquals(MinimalDialogVerdict.DIALOG_CONFIRMED, assess(dialog, candidate(reason = reason), row(2, reason = "UNCHANGED")))
        }
    }
    @Test fun dialogVisibilityOrOrdinaryReportDoesNotCountAsCandidate() {
        assertEquals(MinimalDialogVerdict.DIALOG_CANDIDATE_NOT_SEEN, assess(dialog, row(reason = "UNCHANGED")))
        assertEquals(MinimalDialogVerdict.UNEXPECTED_ASSESSMENT, assess(dialog, row()))
        assertEquals("FAILURE", MinimalDialogContract.failureOutcome(MinimalDialogVerdict.DIALOG_CANDIDATE_NOT_SEEN))
    }
    @Test fun collectionBlocksNeverBecomeBaselineOrDetection() {
        for (phase in MinimalDialogPhase.entries) for (reason in listOf("PASSWORD", "SYSTEM_WINDOW", "TRUNCATED_TREE", "UNREADABLE_TREE"))
            assertEquals(MinimalDialogVerdict.COLLECTION_BLOCKED, assess(phase, "1:BLOCKED:$reason:NONE"))
    }
    @Test fun timeLimitAndTargetMissingCannotBeMaskedByExpectedRows() {
        val time = "2:BLOCKED:TRUNCATED_TREE:SNAPSHOT_PASS:TREE_PROTECTION:TARGET:NONE:TIME_LIMIT:NONE:VERIFIED:TREE_BUDGET:NONE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED:OLDER:CONTENT_CHANGED"
        val missing = "2:BLOCKED:UNREADABLE_TREE:WINDOWS:TARGET_MISSING:TARGET:NONE:WINDOW_GUARD:NONE:VERIFIED:NONE:NONE:UNOBSERVED:UNOBSERVED:UNOBSERVED:UNOBSERVED:OLDER:WINDOW_CHANGED"
        for (bad in listOf(time, missing)) {
            assertEquals(MinimalDialogVerdict.COLLECTION_BLOCKED, assess(baseline, row(), bad))
            assertEquals(MinimalDialogVerdict.COLLECTION_BLOCKED, assess(dialog, candidate(), bad))
        }
    }
    @Test fun geometryMustBeValidWithinTheCandidateRow() {
        val outside = candidate().replace("VALID:POSITIVE:POSITIVE:WITHIN_FRAME:NONE", "WINDOW_OUTSIDE_FRAME:POSITIVE:POSITIVE:WINDOW_OUTSIDE_FRAME:RIGHT")
        assertEquals(MinimalDialogVerdict.GEOMETRY_REJECTED, assess(dialog, outside, row(2, reason = "UNCHANGED")))
        assertEquals(MinimalDialogVerdict.GEOMETRY_REJECTED, assess(baseline, row().replace("VALID", "NONPOSITIVE_WINDOW")))
    }
    @Test fun geometryLabelAloneCannotHideInconsistentExtentsOrRelation() {
        for ((from, to) in listOf("POSITIVE:POSITIVE" to "POSITIVE:NONPOSITIVE_WIDTH", "WITHIN_FRAME" to "WINDOW_OUTSIDE_FRAME", ":NONE:" to ":LEFT:"))
            assertEquals(MinimalDialogVerdict.GEOMETRY_REJECTED, assess(dialog, candidate().replace(from, to)))
    }
    @Test fun missingGeometryCannotBeFilledFromAnotherAttempt() {
        val missing = "1:ASSESSED:POPUP_CANDIDATE:BOUNDED_WINDOW:UNOBSERVED:OLDER"
        assertEquals(MinimalDialogVerdict.GEOMETRY_REJECTED, assess(dialog, missing, row(2, reason = "UNCHANGED")))
    }
    @Test fun duplicateOrInvalidOrdinalsAreRejected() {
        assertEquals(MinimalDialogVerdict.INVALID_DIAGNOSTIC, assess(dialog, candidate(), candidate()))
        for (id in listOf("0", "01", "257", "private"))
            assertEquals(MinimalDialogVerdict.INVALID_DIAGNOSTIC, assess(baseline, row().replaceFirst("1:", "$id:")))
    }
    @Test fun noRowsOrInvalidResponseStayInconclusive() {
        for (phase in MinimalDialogPhase.entries) {
            assertEquals(MinimalDialogVerdict.UNOBSERVED, assess(phase))
            for (code in listOf(null, "ENDED", "F1_SESSION_INVALIDATED"))
                assertEquals(MinimalDialogVerdict.RESPONSE_REJECTED, MinimalDialogContract.assess(phase, code, listOf(row()), false))
        }
    }
    @Test fun overflowAndCardinalityBoundAreFailClosed() {
        assertEquals(MinimalDialogVerdict.OVERFLOW, MinimalDialogContract.assess(dialog, "OBSERVED", listOf(candidate()), true))
        assertEquals(MinimalDialogVerdict.OVERFLOW, MinimalDialogContract.assess(dialog, "OBSERVED", List(257) { candidate() }, false))
    }
    @Test fun malformedOrOversizeInputIsNeverEchoed() {
        for (bad in listOf("SYNTHETIC_PRIVATE_VALUE", " ".repeat(3_000) + "SYNTHETIC_PRIVATE_VALUE", row() + ":EXTRA")) {
            val result = assess(baseline, bad)
            assertEquals(MinimalDialogVerdict.INVALID_DIAGNOSTIC, result)
            assertFalse(result.toString().contains("SYNTHETIC_PRIVATE_VALUE"))
        }
    }
    @Test fun invalidDispositionReasonCombinationCannotInventDetection() {
        assertEquals(MinimalDialogVerdict.UNEXPECTED_ASSESSMENT, assess(dialog, row(disposition = "POPUP_CANDIDATE")))
        assertEquals(MinimalDialogVerdict.DETECTOR_UNCERTAIN, assess(dialog, row(disposition = "UNCERTAIN", reason = "UNCONFIRMED_TRANSITION")))
    }
    @Test fun oneGoodAttemptCannotMaskAnotherWrongAssessment() {
        assertEquals(MinimalDialogVerdict.UNEXPECTED_ASSESSMENT, assess(dialog, candidate(), row(2, reason = "ORDINARY_CONTENT_CHANGE")))
        assertEquals(MinimalDialogVerdict.GEOMETRY_REJECTED, assess(baseline, row(), row(2).replace("VALID", "NONPOSITIVE_FRAME")))
    }
    @Test fun substantiveFailuresAndIncompleteEvidenceAreDistinctButNeitherPasses() {
        for (result in listOf(MinimalDialogVerdict.UNOBSERVED, MinimalDialogVerdict.BASELINE_ONLY_NOT_SEEN,
            MinimalDialogVerdict.COLLECTION_BLOCKED, MinimalDialogVerdict.GEOMETRY_REJECTED, MinimalDialogVerdict.INVALID_DIAGNOSTIC))
            assertEquals("INCONCLUSIVE", MinimalDialogContract.failureOutcome(result))
        assertEquals("INCONCLUSIVE", MinimalDialogContract.failureOutcome(MinimalDialogVerdict.BASELINE_CONFIRMED))
        assertEquals("INCONCLUSIVE", MinimalDialogContract.failureOutcome(MinimalDialogVerdict.DIALOG_CONFIRMED))
    }

    // Executes the same suspend loop as instrumentation, with inline fake IPC/pause callbacks.
    // There is no wall-clock sleep, Android or coroutine scheduler in these regressions.
    private fun <T> immediately(block: suspend () -> T): T {
        var finished: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { finished = result }
        })
        return checkNotNull(finished) { "FAKE_CALLBACK_SUSPENDED" }.getOrThrow()
    }

    private suspend fun poll(phase: MinimalDialogPhase, lateResume: Boolean = false) {
        var clock = 0L
        var reads = 0
        MinimalDialogObservation.await(phase, { clock }, read = {
            if (reads++ == 0) clock = 1_900
            MinimalDialogContract.expected(phase)
        }, pause = { clock = if (lateResume) 3_100 else clock + it })
    }

    private fun finalReport(phase: MinimalDialogPhase, rows: List<String>, overflow: Boolean = false) {
        MinimalDialogContract.requireExpected(phase, MinimalDialogContract.assess(phase, "OBSERVED", rows, overflow))
    }

    private fun rejected(expected: MinimalDialogVerdict, block: suspend () -> Unit) {
        try { immediately(block); fail("EXPECTED_FIXED_FAILURE") }
        catch (failure: MinimalDialogObservationFailure) {
            assertEquals(expected, failure.verdict)
            assertEquals(expected.name, failure.message)
            assertNotEquals("OBSERVED", MinimalDialogContract.failureOutcome(failure.verdict))
        }
    }

    @Test fun cachedBaselineSuccessAt1900ThenResumeAfter3000CannotShowDialogOrPass() {
        val progress = MinimalDialogProgress()
        var dialogShows = 0
        var finalChecks = 0
        rejected(MinimalDialogVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyBaseline { poll(baseline, lateResume = true); finalChecks++ }
            progress.showAndVerifyDialogWindow { dialogShows++ }
        }
        assertEquals(0, dialogShows)
        assertEquals(0, finalChecks)
        assertEquals(MinimalDialogStage.ABORTED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome())
    }

    @Test fun cachedDialogSuccessAt1900ThenResumeAfter3000CannotPassOrVerifyFinal() {
        val progress = MinimalDialogProgress()
        var dialogShows = 0
        var dialogFinalChecks = 0
        rejected(MinimalDialogVerdict.OBSERVATION_TIMEOUT) {
            progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
            progress.showAndVerifyDialogWindow { dialogShows++ }
            progress.verifyDialog { poll(dialog, lateResume = true); dialogFinalChecks++ }
        }
        assertEquals(1, dialogShows)
        assertEquals(0, dialogFinalChecks)
        assertEquals("INCONCLUSIVE", progress.outcome())
    }

    @Test fun baselineFinalVerificationAloneCannotCompleteExperiment() = immediately {
        val progress = MinimalDialogProgress()
        progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
        assertEquals(MinimalDialogStage.BASELINE_VERIFIED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome())
    }

    @Test fun dialogWindowWithoutDialogFinalVerificationCannotCompleteExperiment() = immediately {
        val progress = MinimalDialogProgress()
        progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
        progress.showAndVerifyDialogWindow { /* Synthetic host callback only, not Android evidence. */ }
        assertEquals(MinimalDialogStage.DIALOG_WINDOW_VERIFIED, progress.stage)
        assertEquals("INCONCLUSIVE", progress.outcome())
    }

    @Test fun onlyAllOrderedCallbacksAndBothFinalReportsCompleteExperiment() = immediately {
        val progress = MinimalDialogProgress()
        val calls = mutableListOf<String>()
        progress.verifyBaseline {
            poll(baseline)
            calls += "BASELINE_NATIVE_REMOTE"
            finalReport(baseline, listOf(row()))
            calls += "BASELINE_FINAL"
        }
        assertEquals("INCONCLUSIVE", progress.outcome())
        progress.showAndVerifyDialogWindow { calls += "HOST_SHOW_AND_WINDOW_CHECK" }
        assertEquals("INCONCLUSIVE", progress.outcome())
        progress.verifyDialog {
            poll(dialog)
            calls += "DIALOG_NATIVE_REMOTE"
            finalReport(dialog, listOf(candidate()))
            calls += "DIALOG_FINAL"
        }
        assertEquals(listOf("BASELINE_NATIVE_REMOTE", "BASELINE_FINAL", "HOST_SHOW_AND_WINDOW_CHECK", "DIALOG_NATIVE_REMOTE", "DIALOG_FINAL"), calls)
        assertEquals(MinimalDialogStage.DIALOG_VERIFIED, progress.stage)
        assertEquals("OBSERVED", progress.outcome())
    }

    @Test fun everyVerdictOnExceptionPathIsNonSuccess() {
        for (verdict in MinimalDialogVerdict.entries) assertNotEquals("OBSERVED", MinimalDialogContract.failureOutcome(verdict))
        for (verdict in listOf(MinimalDialogVerdict.BASELINE_CONFIRMED, MinimalDialogVerdict.DIALOG_CONFIRMED)) {
            val progress = MinimalDialogProgress()
            rejected(verdict) { progress.verifyBaseline { throw MinimalDialogObservationFailure(verdict) } }
            assertEquals("INCONCLUSIVE", progress.outcome())
        }
    }

    @Test fun dialogCannotBeShownBeforeBaselineFinalCallbackReturns() {
        val progress = MinimalDialogProgress()
        var shows = 0
        rejected(MinimalDialogVerdict.STAGE_ORDER_INVALID) { progress.showAndVerifyDialogWindow { shows++ } }
        assertEquals(0, shows)
        assertEquals(MinimalDialogStage.ABORTED, progress.stage)
    }

    @Test fun dialogFinalCannotReplaceMissingHostWindowStage() {
        val progress = MinimalDialogProgress()
        rejected(MinimalDialogVerdict.STAGE_ORDER_INVALID) {
            progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
            progress.verifyDialog { finalReport(dialog, listOf(candidate())) }
        }
        assertEquals("INCONCLUSIVE", progress.outcome())
    }

    @Test fun exactDeadlineAndLateIpcResponseNeverPass() {
        for (phase in MinimalDialogPhase.entries) for (responseTime in listOf(3_000L, 3_001L)) {
            var clock = 0L
            rejected(MinimalDialogVerdict.OBSERVATION_TIMEOUT) {
                MinimalDialogObservation.await(phase, { clock }, read = {
                    clock = responseTime
                    MinimalDialogContract.expected(phase)
                }, pause = { fail("DEADLINE_MUST_TERMINATE") })
            }
        }
    }

    @Test fun observationCannotReturnBeforeMinimum2000AndAcceptsFreshSuccessAfterward() = immediately {
        for (phase in MinimalDialogPhase.entries) {
            var clock = 0L
            var reads = 0
            var pauses = 0
            MinimalDialogObservation.await(phase, { clock }, read = {
                reads++
                if (reads == 1) clock = 1_999
                MinimalDialogContract.expected(phase)
            }, pause = {
                assertEquals(200L, it)
                pauses++
                clock = 2_199
            })
            assertEquals(2, reads)
            assertEquals(1, pauses)
        }
    }

    @Test fun absentObservationsTimeoutInsteadOfPassing() {
        for (phase in MinimalDialogPhase.entries) {
            var clock = 0L
            rejected(MinimalDialogVerdict.OBSERVATION_TIMEOUT) {
                MinimalDialogObservation.await(phase, { clock }, read = { MinimalDialogVerdict.UNOBSERVED }, pause = { clock += it })
            }
        }
    }

    @Test fun lateBlockedFinalReportAfterLoopSuccessAbortsBothPhases() {
        for (phase in MinimalDialogPhase.entries) {
            val progress = MinimalDialogProgress()
            rejected(MinimalDialogVerdict.COLLECTION_BLOCKED) {
                if (phase == baseline) progress.verifyBaseline {
                    poll(baseline)
                    finalReport(baseline, listOf(row(), "2:BLOCKED:UNREADABLE_TREE:NONE"))
                } else {
                    progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
                    progress.showAndVerifyDialogWindow { }
                    progress.verifyDialog { poll(dialog); finalReport(dialog, listOf(candidate(), "2:BLOCKED:UNREADABLE_TREE:NONE")) }
                }
            }
            assertEquals("INCONCLUSIVE", progress.outcome())
        }
    }

    @Test fun lateOverflowAfterLoopSuccessAbortsBothPhases() {
        for (phase in MinimalDialogPhase.entries) {
            val progress = MinimalDialogProgress()
            rejected(MinimalDialogVerdict.OVERFLOW) {
                if (phase == baseline) progress.verifyBaseline {
                    poll(baseline); finalReport(baseline, listOf(row()), overflow = true)
                } else {
                    progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
                    progress.showAndVerifyDialogWindow { }
                    progress.verifyDialog { poll(dialog); finalReport(dialog, listOf(candidate()), overflow = true) }
                }
            }
            assertEquals("INCONCLUSIVE", progress.outcome())
        }
    }

    @Test fun lateUnobservedFinalReportAfterLoopSuccessAbortsBothPhases() {
        for (phase in MinimalDialogPhase.entries) {
            val progress = MinimalDialogProgress()
            rejected(MinimalDialogVerdict.UNOBSERVED) {
                if (phase == baseline) progress.verifyBaseline {
                    poll(baseline); finalReport(baseline, emptyList())
                } else {
                    progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
                    progress.showAndVerifyDialogWindow { }
                    progress.verifyDialog { poll(dialog); finalReport(dialog, emptyList()) }
                }
            }
            assertEquals("INCONCLUSIVE", progress.outcome())
        }
    }

    @Test fun lateDuplicateOrMalformedFinalReportCannotComplete() {
        for ((rows, expected) in listOf(listOf(candidate(), candidate()) to MinimalDialogVerdict.INVALID_DIAGNOSTIC,
            listOf(candidate(), "INVALID") to MinimalDialogVerdict.INVALID_DIAGNOSTIC)) {
            val progress = MinimalDialogProgress()
            rejected(expected) {
                progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
                progress.showAndVerifyDialogWindow { }
                progress.verifyDialog { poll(dialog); finalReport(dialog, rows) }
            }
            assertEquals("INCONCLUSIVE", progress.outcome())
        }
    }

    @Test fun uncertainTransitionsAndStructuresAreInconclusiveNotMissesOrSuccesses() {
        for (phase in MinimalDialogPhase.entries) for (reason in listOf("UNCONFIRMED_TRANSITION", "UNCONFIRMED_STRUCTURE")) {
            val verdict = assess(phase, row(disposition = "UNCERTAIN", reason = reason))
            assertEquals(MinimalDialogVerdict.DETECTOR_UNCERTAIN, verdict)
            assertEquals("INCONCLUSIVE", MinimalDialogContract.failureOutcome(verdict))
            val progress = MinimalDialogProgress()
            rejected(MinimalDialogVerdict.DETECTOR_UNCERTAIN) {
                if (phase == dialog) {
                    progress.verifyBaseline { poll(baseline); finalReport(baseline, listOf(row())) }
                    progress.showAndVerifyDialogWindow { }
                    progress.verifyDialog {
                        MinimalDialogObservation.await(dialog, { 0L }, { verdict }, { fail("UNCERTAINTY_MUST_TERMINATE") })
                    }
                } else progress.verifyBaseline {
                    MinimalDialogObservation.await(baseline, { 0L }, { verdict }, { fail("UNCERTAINTY_MUST_TERMINATE") })
                }
            }
            assertEquals("INCONCLUSIVE", progress.outcome())
        }
    }

    @Test fun ordinaryDialogNoCandidateRemainsDistinctFromUncertainty() {
        val verdict = assess(dialog, row(reason = "UNCHANGED"))
        assertEquals(MinimalDialogVerdict.DIALOG_CANDIDATE_NOT_SEEN, verdict)
        assertEquals("FAILURE", MinimalDialogContract.failureOutcome(verdict))
    }

    @Test fun abortDuringChecksAndAfterCompletionCannotBeReversed() {
        val during = MinimalDialogProgress()
        rejected(MinimalDialogVerdict.STAGE_ORDER_INVALID) { during.verifyBaseline { during.abort() } }
        immediately {
            val complete = MinimalDialogProgress()
            complete.verifyBaseline { finalReport(baseline, listOf(row())) }
            complete.showAndVerifyDialogWindow { }
            complete.verifyDialog { finalReport(dialog, listOf(candidate())) }
            complete.abort()
            assertEquals("INCONCLUSIVE", complete.outcome())
            rejected(MinimalDialogVerdict.STAGE_ORDER_INVALID) { complete.verifyBaseline { } }
        }
    }

    @Test fun cancellationInPollingAbortsProgressAndPropagates() {
        val progress = MinimalDialogProgress()
        try {
            immediately {
                progress.verifyBaseline {
                    MinimalDialogObservation.await(baseline, { 0L }, { throw java.util.concurrent.CancellationException("FIXED_CANCEL") }, { })
                }
            }
            fail("CANCELLATION_MUST_PROPAGATE")
        } catch (_: java.util.concurrent.CancellationException) {
            assertEquals(MinimalDialogStage.ABORTED, progress.stage)
            assertEquals("INCONCLUSIVE", progress.outcome())
        }
    }
}
