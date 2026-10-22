package kr.closeforyou.accessibility

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.capture.*
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServiceWorkDiagnosticTest {
    private class Rig(test: TestScope, kind: ServiceWorkKind, sink: ((String) -> Unit)? = null) {
        val lines = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob(test.backgroundScope.coroutineContext[Job]) +
            StandardTestDispatcher(test.testScheduler) + CoroutineExceptionHandler { _, failure -> failures += failure })
        val diagnostic = ServiceWorkDiagnostic(kind, sink ?: { lines += it })
        fun launch(work: suspend CoroutineScope.() -> Unit) = scope.launchDiagnosed(diagnostic, work)
        fun line() = lines.single()
    }
    private val noCapture = object : ScreenshotPort {
        override fun availability(token: SnapshotToken) = CaptureAvailability.PRIVACY_BLOCKED
        override fun denial(token: SnapshotToken) = EvidenceIssue.NOT_REQUESTED
        override suspend fun capture(snapshot: PopupSnapshot, region: CaptureRegion): CaptureFrame = error("NO_CAPTURE")
    }
    private fun pipeline(current: () -> Boolean = { true }, protection: (PopupSnapshot) -> PrivacyBlockReason? = { null }) =
        CaptureOcrPipeline(noCapture, OcrPort { error("NO_OCR") }, { current() }, protection)

    @Test fun cancelledBeforeBodyStillReportsOnceWithoutRunningBodyOrCleanup() = runTest {
        val rig = Rig(this, ServiceWorkKind.CAPTURE); var body = false
        val job = rig.launch { body = true }
        job.cancel(); runCurrent()
        assertFalse(body); assertTrue(job.isCancelled)
        assertTrue(rig.line().contains("END=CAPTURE_CANCELLED"))
        assertTrue(rig.line().contains("LAST_STAGE=QUEUED;CLEANUP=NONE"))
    }

    @Test fun alreadyCancelledServiceScopeStillReportsQueuedEvidenceCancellation() = runTest {
        val rig = Rig(this, ServiceWorkKind.EVIDENCE); rig.scope.cancel()
        rig.launch { fail("BODY_MUST_NOT_RUN") }; runCurrent()
        assertTrue(rig.line().contains("END=EVIDENCE_CANCELLED"))
        assertTrue(rig.line().contains("LAST_STAGE=QUEUED"))
    }

    @Test fun debounceCancellationIsNotReportedAsF1OrEvidenceCompletion() = runTest {
        val rig = Rig(this, ServiceWorkKind.CAPTURE)
        val job = rig.launch { rig.diagnostic.at(ServiceWorkStage.DEBOUNCE); delay(180); fail("DEBOUNCE_CANCELLED") }
        runCurrent(); job.cancel(); runCurrent()
        assertTrue(rig.line().contains("END=CAPTURE_CANCELLED"))
        assertTrue(rig.line().contains("LAST_STAGE=DEBOUNCE"))
        assertTrue(rig.line().contains("BLOCK_RESULT=NONE"))
    }

    @Test fun subscriptionCancellationKeepsItsOriginalCause() = runTest {
        val rig = Rig(this, ServiceWorkKind.CAPTURE); val original = CancellationException("synthetic-private")
        var cause: Throwable? = null
        val job = rig.launch { rig.diagnostic.at(ServiceWorkStage.SUBSCRIPTION); throw original }
        job.invokeOnCompletion { cause = it }; runCurrent()
        assertSame(original, cause)
        assertTrue(rig.line().contains("LAST_STAGE=SUBSCRIPTION"))
        assertFalse(rig.line().contains("synthetic-private"))
    }

    @Test fun initialCollectionBlockUsesActualReasonAndDoesNotInventEvidenceIssue() = runTest {
        val rig = Rig(this, ServiceWorkKind.CAPTURE)
        rig.launch {
            rig.diagnostic.at(ServiceWorkStage.CAPTURE)
            rig.diagnostic.captureBlocked(PrivacyBlockReason.UNREADABLE_TREE,
                CollectionProtectionDiagnostic(CollectionPhase.SNAPSHOT_PASS, CaptureDetail.NONE,
                    finding = CollectionFinding.TIME_LIMIT, operation = CollectionOperation.TREE_BUDGET))
        }; runCurrent()
        val line = rig.line()
        assertTrue(line.contains("TASK=CAPTURE;END=CAPTURE_BLOCKED"))
        assertTrue(line.contains("SOURCE=INITIAL_COLLECTION_FAILED"))
        assertTrue(line.contains("REASON=UNREADABLE_TREE;ISSUE=NONE"))
        assertTrue(line.contains("FINDING=TIME_LIMIT"))
    }

    @Test fun f1NoCandidateReportsItsActualEnumsWithoutAnyEvidenceWork() = runTest {
        val rig = Rig(this, ServiceWorkKind.CAPTURE)
        rig.launch {
            rig.diagnostic.at(ServiceWorkStage.F1)
            rig.diagnostic.assessed(DetectionAssessment(DetectionDisposition.NO_POPUP, DetectionReason.UNCHANGED))
            rig.diagnostic.outcome(ServiceWorkEnd.F1_NOT_CANDIDATE)
        }; runCurrent()
        assertTrue(rig.line().contains("END=F1_NOT_CANDIDATE"))
        assertTrue(rig.line().contains("DISPOSITION=NO_POPUP;DETECTION_REASON=UNCHANGED"))
    }

    @Test fun uncertainF1IsNotPresentedAsAnOrdinaryNoPopupOrPublication() = runTest {
        val rig = Rig(this, ServiceWorkKind.CAPTURE)
        rig.launch {
            rig.diagnostic.assessed(DetectionAssessment(DetectionDisposition.UNCERTAIN, DetectionReason.INVALID_GEOMETRY))
            rig.diagnostic.outcome(ServiceWorkEnd.F1_NOT_CANDIDATE)
        }; runCurrent()
        assertTrue(rig.line().contains("DISPOSITION=UNCERTAIN;DETECTION_REASON=INVALID_GEOMETRY"))
        assertFalse(rig.line().contains("END=PUBLISHED"))
    }

    @Test fun beginRejectionReportsEvenThoughPipelineAndCleanupNeverStart() = runTest {
        val rig = Rig(this, ServiceWorkKind.EVIDENCE)
        val live = LiveEvidenceState({ 1_000 }) // Disconnected: the real Runtime-owned state rejects begin.
        rig.launch {
            rig.diagnostic.at(ServiceWorkStage.BEGIN)
            val operation = live.begin(MockPopupFixtures.notice().snapshot)
            if (operation == null) {
                rig.diagnostic.outcome(ServiceWorkEnd.BEGIN_REJECTED)
                return@launch
            }
            fail("UNEXPECTED_PIPELINE_ENTRY")
        }; runCurrent()
        assertTrue(rig.line().contains("END=BEGIN_REJECTED"))
        assertTrue(rig.line().contains("LAST_STAGE=BEGIN;CLEANUP=NONE"))
    }

    @Test fun pipelineBlockCreatedThenFinalCurrentnessCancellationIsPreservedAfterCleanup() = runTest {
        val rig = Rig(this, ServiceWorkKind.EVIDENCE); var current = true; var cleanups = 0
        val job = rig.launch {
            try {
                rig.diagnostic.at(ServiceWorkStage.PIPELINE)
                pipeline({ current }) {
                    rig.diagnostic.block.rejected(EvidenceBlockSource.SNAPSHOT_MISMATCH, EvidenceMismatch.CONTENT)
                    current = false
                    PrivacyBlockReason.UNREADABLE_TREE
                }.analyze(MockPopupFixtures.notice().snapshot, blockDiagnostic = rig.diagnostic.block)
                fail("CANCELLED_RESULT_RETURNED")
            } finally {
                diagnosedCleanup(rig.diagnostic) { cleanups++ }
            }
        }; runCurrent()
        assertTrue(job.isCancelled); assertEquals(1, cleanups)
        val line = rig.line()
        assertTrue(line.startsWith("A_EVIDENCE_BLOCK;"))
        assertTrue(line.contains("END=EVIDENCE_CANCELLED;OUTCOME=UNCLASSIFIED"))
        assertTrue(line.contains("CLEANUP=COMPLETED;BLOCK_RESULT=CREATED"))
        assertTrue(line.contains("SOURCE=SNAPSHOT_MISMATCH;CHECK=CONTENT;REASON=UNREADABLE_TREE;ISSUE=PRIVACY_BLOCKED"))
    }

    @Test fun pipelineBlockReturnedNormallyEmitsOneCombinedLineAfterCleanup() = runTest {
        val rig = Rig(this, ServiceWorkKind.EVIDENCE); val order = mutableListOf<String>()
        val observed = ServiceWorkDiagnostic(ServiceWorkKind.EVIDENCE) { code -> order += code }
        rig.scope.launchDiagnosed(observed) {
            try {
                val result = pipeline(protection = {
                    observed.block.rejected(EvidenceBlockSource.RECOLLECTION_FAILED)
                    PrivacyBlockReason.PASSWORD
                }).analyze(MockPopupFixtures.notice().snapshot, blockDiagnostic = observed.block)
                assertTrue(result is EvidenceOutcome.Blocked)
                observed.outcome(ServiceWorkEnd.EVIDENCE_BLOCKED)
                order += "BLOCK"
            } finally {
                diagnosedCleanup(observed) { order += "CLEANUP" }
            }
        }; runCurrent()
        assertEquals(listOf("BLOCK", "CLEANUP"), order.take(2))
        assertEquals(3, order.size)
        assertTrue(order.last().contains("END=EVIDENCE_BLOCKED"))
        assertTrue(order.last().contains("BLOCK_RESULT=RETURNED"))
        assertTrue(order.last().contains("REASON=PASSWORD;ISSUE=PRIVACY_BLOCKED"))
    }

    @Test fun evidenceCancellationReportsAfterFinallyAndNeverSwallowsOriginalCancellation() = runTest {
        val order = mutableListOf<String>(); val rig = Rig(this, ServiceWorkKind.EVIDENCE)
        val diagnostic = ServiceWorkDiagnostic(ServiceWorkKind.EVIDENCE) { order += "REPORT" }
        val original = CancellationException("synthetic-private"); var cause: Throwable? = null
        val job = rig.scope.launchDiagnosed(diagnostic) {
            try { throw original }
            finally { diagnosedCleanup(diagnostic) { order += "CLEANUP" } }
        }
        job.invokeOnCompletion { cause = it }; runCurrent()
        assertSame(original, cause); assertEquals(listOf("CLEANUP", "REPORT"), order)
    }

    @Test fun handledEvidenceExceptionStillHasExceptionEndAfterItsOriginalCleanup() = runTest {
        val rig = Rig(this, ServiceWorkKind.EVIDENCE); var cleanup = 0
        val job = rig.launch {
            try { throw IllegalStateException("synthetic-private") }
            catch (_: Exception) { rig.diagnostic.exceptionHandled() }
            finally { diagnosedCleanup(rig.diagnostic) { cleanup++ } }
        }; runCurrent()
        assertFalse(job.isCancelled); assertEquals(1, cleanup)
        assertTrue(rig.line().contains("END=EVIDENCE_EXCEPTION"))
        assertFalse(rig.line().contains("synthetic-private"))
    }

    @Test fun unhandledCaptureExceptionIsReportedWithoutChangingTheEscapingException() = runTest {
        val rig = Rig(this, ServiceWorkKind.CAPTURE); val original = IllegalArgumentException("synthetic-private")
        rig.launch { throw original }; runCurrent()
        assertSame(original, rig.failures.single())
        assertTrue(rig.line().contains("END=CAPTURE_EXCEPTION"))
        assertFalse(rig.line().contains("synthetic-private"))
    }

    @Test fun publicationSuccessAndRejectionUseTheActualRuntimeStateBoolean() = runTest {
        for (reject in listOf(false, true)) {
            val rig = Rig(this, ServiceWorkKind.EVIDENCE)
            val snapshot = MockPopupFixtures.notice().snapshot.let {
                it.copy(token = it.token.copy(screenVersion = 1, captureEpoch = 1), capturedAtUptimeMillis = 1_000)
            }
            val live = LiveEvidenceState({ 1_000 }); live.connect(); live.invalidate(1)
            val operation = requireNotNull(live.begin(snapshot)); var emitted = 0
            val job = rig.launch {
                var published = false
                try {
                    val result = pipeline().analyze(snapshot, blockDiagnostic = rig.diagnostic.block) as EvidenceOutcome.Ready
                    rig.diagnostic.at(ServiceWorkStage.PUBLISH)
                    val detection = PopupDetection(if (reject) snapshot.token.copy(screenVersion = 2) else snapshot.token,
                        persistentSetOf(PopupSignal.NEW_WINDOW), requiresUserReview = true)
                    published = live.publish(operation, result.snapshot, detection, result.analysis, result.issue) &&
                        live.deliver(operation, result.snapshot) { emitted++ }
                    rig.diagnostic.outcome(if (published) ServiceWorkEnd.PUBLISHED else ServiceWorkEnd.PUBLISH_REJECTED)
                } finally {
                    diagnosedCleanup(rig.diagnostic) { if (!published || !isActive) live.discard(operation) }
                }
            }; runCurrent()
            assertFalse(job.isCancelled)
            assertEquals(if (reject) 0 else 1, emitted)
            assertTrue(rig.line().contains("END=" + if (reject) "PUBLISH_REJECTED" else "PUBLISHED"))
            assertTrue(rig.line().startsWith("A_FLOW_END;"))
            assertTrue(rig.line().contains("BLOCK_RESULT=NONE"))
        }
    }

    @Test fun readyStaleEarlyReturnReportsAfterCleanupWithoutPublication() = runTest {
        val rig = Rig(this, ServiceWorkKind.EVIDENCE); var cleanups = 0
        rig.launch {
            try {
                assertTrue(pipeline().analyze(MockPopupFixtures.notice().snapshot) is EvidenceOutcome.Ready)
                rig.diagnostic.at(ServiceWorkStage.READY)
                rig.diagnostic.outcome(ServiceWorkEnd.READY_STALE)
                return@launch
            } finally { diagnosedCleanup(rig.diagnostic) { cleanups++ } }
        }; runCurrent()
        assertEquals(1, cleanups); assertTrue(rig.line().contains("END=READY_STALE"))
    }

    @Test fun cleanupFailurePreservesItsExceptionAndDoesNotBecomePublishedSuccess() = runTest {
        val rig = Rig(this, ServiceWorkKind.EVIDENCE); val original = IllegalStateException("synthetic-private")
        rig.launch {
            try { rig.diagnostic.outcome(ServiceWorkEnd.PUBLISHED) }
            finally { diagnosedCleanup(rig.diagnostic) { throw original } }
        }; runCurrent()
        assertSame(original, rig.failures.single())
        assertTrue(rig.line().contains("END=EVIDENCE_EXCEPTION;OUTCOME=PUBLISHED"))
        assertTrue(rig.line().contains("CLEANUP=FAILED"))
    }

    @Test fun loggingExceptionCannotFailASuccessfulJobOrRetryASecondLine() = runTest {
        var writes = 0
        val rig = Rig(this, ServiceWorkKind.EVIDENCE) { writes++; throw IllegalStateException("synthetic-private") }
        val job = rig.launch { rig.diagnostic.outcome(ServiceWorkEnd.PUBLISHED) }; runCurrent()
        rig.diagnostic.completed(null)
        assertFalse(job.isCancelled); assertTrue(rig.failures.isEmpty()); assertEquals(1, writes)
    }

    @Test fun sinkCancellationCannotReplaceTheObservedJobsOriginalCancellation() = runTest {
        val original = CancellationException("original-synthetic-private")
        val rig = Rig(this, ServiceWorkKind.EVIDENCE) { throw CancellationException("sink-synthetic-private") }
        var cause: Throwable? = null
        val job = rig.launch {
            try { throw original }
            finally { diagnosedCleanup(rig.diagnostic) {} }
        }
        job.invokeOnCompletion { cause = it }; runCurrent()
        assertSame(original, cause); assertTrue(rig.failures.isEmpty())
    }

    @Test fun sinkCancellationCannotReplaceAnOriginalCleanupFailure() = runTest {
        val original = IllegalStateException("synthetic-private")
        val rig = Rig(this, ServiceWorkKind.EVIDENCE) { throw CancellationException("sink-private") }
        rig.launch { diagnosedCleanup(rig.diagnostic) { throw original } }; runCurrent()
        assertSame(original, rig.failures.single())
    }

    @Test fun nullDiagnosticUsesTheSameJobBodyAndOriginalFinallyOrder() = runTest {
        val order = mutableListOf<String>()
        val job = backgroundScope.launchDiagnosed(null) {
            try { order += "BODY" }
            finally { diagnosedCleanup(null) { order += "CLEANUP" } }
        }; runCurrent()
        assertTrue(job.isCompleted); assertFalse(job.isCancelled)
        assertEquals(listOf("BODY", "CLEANUP"), order)
    }

    @Test fun captureAndEvidenceEachEmitOneLineWithoutCombiningTheirFields() = runTest {
        val capture = Rig(this, ServiceWorkKind.CAPTURE); val evidence = Rig(this, ServiceWorkKind.EVIDENCE)
        capture.launch {
            evidence.launch { evidence.diagnostic.outcome(ServiceWorkEnd.BEGIN_REJECTED) }
            capture.diagnostic.outcome(ServiceWorkEnd.EVIDENCE_SCHEDULED)
        }; runCurrent()
        assertTrue(capture.line().contains("TASK=CAPTURE;END=EVIDENCE_SCHEDULED"))
        assertTrue(evidence.line().contains("TASK=EVIDENCE;END=BEGIN_REJECTED"))
        assertTrue(capture.line().contains("BLOCK_RESULT=NONE"))
    }

    @Test fun everyLineContainsOnlyFixedCodesAndIsBounded() = runTest {
        val rig = Rig(this, ServiceWorkKind.EVIDENCE)
        rig.launch {
            rig.diagnostic.block.rejected(EvidenceBlockSource.TEXT_PROTECTION)
            rig.diagnostic.block.blocked(PrivacyBlockReason.SENSITIVE_CONTENT, EvidenceIssue.PRIVACY_BLOCKED)
            rig.diagnostic.outcome(ServiceWorkEnd.EVIDENCE_BLOCKED)
        }; runCurrent()
        assertTrue(rig.line().matches(Regex("[A-Z_;=]+")))
        assertTrue(rig.line().length < 1_024)
        assertEquals("ServiceWorkDiagnostic(redacted)", rig.diagnostic.toString())
    }
}
