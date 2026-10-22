package kr.closeforyou.accessibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kr.closeforyou.core.model.PrivacyBlockReason

internal enum class ServiceWorkKind { CAPTURE, EVIDENCE }
internal enum class ServiceWorkStage {
    QUEUED, RUNNING, DEBOUNCE, SUBSCRIPTION, CAPTURE, F1, BEGIN, PIPELINE, READY, PUBLISH, CLEANUP,
}
internal enum class ServiceWorkEnd {
    UNCLASSIFIED, INTEGRATION_UNAVAILABLE, CAPTURE_STALE, CAPTURE_BLOCKED, F1_NOT_CANDIDATE,
    EVIDENCE_SCHEDULED, BEGIN_REJECTED, EVIDENCE_BLOCKED, READY_STALE, PUBLISH_REJECTED, PUBLISHED,
    CAPTURE_CANCELLED, CAPTURE_EXCEPTION, EVIDENCE_CANCELLED, EVIDENCE_EXCEPTION,
}
internal enum class DiagnosticCleanup { NONE, STARTED, COMPLETED, FAILED }

/** One actual Job, no screen/operation identifiers and no retained exception or result DTO.
 * These enums are observations only and are never inputs to publication or action policy.
 */
internal class ServiceWorkDiagnostic(private val kind: ServiceWorkKind, private val write: (String) -> Unit) {
    val block = EvidenceBlockDiagnostic()
    private var stage = ServiceWorkStage.QUEUED
    private var outcome = ServiceWorkEnd.UNCLASSIFIED
    private var disposition: DetectionDisposition? = null
    private var detectionReason: DetectionReason? = null
    private var handledException = false
    private var cleanup = DiagnosticCleanup.NONE
    private var reported = false

    @Synchronized fun at(value: ServiceWorkStage) { stage = value }
    @Synchronized fun outcome(value: ServiceWorkEnd) { outcome = value }
    @Synchronized fun exceptionHandled() { handledException = true }
    @Synchronized fun assessed(value: DetectionAssessment) {
        disposition = value.disposition; detectionReason = value.reason
    }
    @Synchronized fun captureBlocked(reason: PrivacyBlockReason, diagnostic: CollectionProtectionDiagnostic?) {
        block.rejected(EvidenceBlockSource.INITIAL_COLLECTION_FAILED, collection = diagnostic)
        block.blocked(reason, null) // CollectionResult has no EvidenceIssue; do not invent one.
        block.returned()
        outcome = ServiceWorkEnd.CAPTURE_BLOCKED
    }
    @Synchronized fun cleanupStarted() { stage = ServiceWorkStage.CLEANUP; cleanup = DiagnosticCleanup.STARTED }
    @Synchronized fun cleanupCompleted() { cleanup = DiagnosticCleanup.COMPLETED }

    /** A Job's final completion callback runs after its body's finally, including pre-start cancellation. */
    @Synchronized fun completed(failure: Throwable?) {
        if (reported) return
        reported = true
        val end = when {
            failure is CancellationException -> if (kind == ServiceWorkKind.CAPTURE)
                ServiceWorkEnd.CAPTURE_CANCELLED else ServiceWorkEnd.EVIDENCE_CANCELLED
            failure != null || handledException -> if (kind == ServiceWorkKind.CAPTURE)
                ServiceWorkEnd.CAPTURE_EXCEPTION else ServiceWorkEnd.EVIDENCE_EXCEPTION
            else -> outcome
        }
        if (cleanup == DiagnosticCleanup.STARTED) cleanup = DiagnosticCleanup.FAILED
        val prefix = if (block.result == ObservedBlockResult.NONE) "A_FLOW_END" else "A_EVIDENCE_BLOCK"
        diagnosticWrite {
            write("$prefix;TASK=${kind.name};END=${end.name};OUTCOME=${outcome.name};" +
                "LAST_STAGE=${stage.name};CLEANUP=${cleanup.name};BLOCK_RESULT=${block.result.name};" +
                "DISPOSITION=${disposition?.name ?: "NONE"};DETECTION_REASON=${detectionReason?.name ?: "NONE"};${block.fields()}")
        }
    }

    override fun toString() = "ServiceWorkDiagnostic(redacted)"
}

/** Only isolates a non-suspending diagnostic sink. Never wraps/catches actual work or cleanup. */
internal inline fun diagnosticWrite(write: () -> Unit) {
    try { write() } catch (_: Exception) {
        // Includes a sink-thrown CancellationException, not cancellation of the observed Job.
        // Actual work cancellation remains that Job's original cause and is not intercepted here.
    }
}

/** Same launch/context/parent/start semantics as the service's original launch. Release passes null. */
internal fun CoroutineScope.launchDiagnosed(diagnostic: ServiceWorkDiagnostic?, work: suspend CoroutineScope.() -> Unit): Job {
    if (diagnostic == null) return launch(block = work)
    val job = launch {
        diagnostic.at(ServiceWorkStage.RUNNING)
        work()
    }
    job.invokeOnCompletion { failure -> diagnostic.completed(failure) }
    return job
}

/** The service's original non-suspending finally body; no catch/replacement of its exceptions. */
internal inline fun <T> diagnosedCleanup(diagnostic: ServiceWorkDiagnostic?, cleanup: () -> T): T {
    diagnostic?.cleanupStarted()
    val result = cleanup()
    diagnostic?.cleanupCompleted()
    return result
}
