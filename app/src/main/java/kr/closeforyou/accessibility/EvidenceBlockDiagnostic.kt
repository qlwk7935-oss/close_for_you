package kr.closeforyou.accessibility

import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.PrivacyBlockReason

internal enum class EvidenceBlockSource {
    UNSPECIFIED, INCOMPLETE_SNAPSHOT, CURRENT_SCREEN_UNAVAILABLE,
    RECOLLECTION_FAILED, SNAPSHOT_MISMATCH, TEXT_PROTECTION,
}
internal enum class EvidenceMismatch { NONE, PACKAGE, WINDOW, STRUCTURE, CONTENT, FRAME, WINDOW_SET }

/** The original short-circuit comparison, on existing DTOs only. No new Android reads. */
internal object EvidenceSnapshotComparison {
    fun firstMismatch(original: PopupSnapshot, fresh: PopupSnapshot): EvidenceMismatch {
        val a = original.token
        val b = fresh.token
        return when {
            a.packageName != b.packageName -> EvidenceMismatch.PACKAGE
            a.windowId != b.windowId -> EvidenceMismatch.WINDOW
            a.structureDigest != b.structureDigest -> EvidenceMismatch.STRUCTURE
            a.contentDigest != b.contentDigest -> EvidenceMismatch.CONTENT
            original.screenBounds != fresh.screenBounds -> EvidenceMismatch.FRAME
            original.windows.toSet() != fresh.windows.toSet() -> EvidenceMismatch.WINDOW_SET
            else -> EvidenceMismatch.NONE
        }
    }
}

/** One evidence operation only. Retains enums, never DTOs, Android objects or exceptions.
 * Observation only: cannot change evidence/publication decisions or certify F0 completion.
 */
internal class EvidenceBlockDiagnostic {
    private var source = EvidenceBlockSource.UNSPECIFIED
    private var mismatch = EvidenceMismatch.NONE
    private var phase: CollectionPhase? = null
    private var operation = CollectionOperation.NONE
    private var finding = CollectionFinding.NONE
    private var reason: PrivacyBlockReason? = null
    private var issue: EvidenceIssue? = null
    private var reported = false

    fun rejected(at: EvidenceBlockSource, field: EvidenceMismatch = EvidenceMismatch.NONE,
        collection: CollectionProtectionDiagnostic? = null) {
        if (source != EvidenceBlockSource.UNSPECIFIED) return
        source = at
        mismatch = field
        phase = collection?.phase
        operation = collection?.operation ?: CollectionOperation.NONE
        finding = collection?.finding ?: CollectionFinding.NONE
    }

    fun blocked(blockReason: PrivacyBlockReason, blockIssue: EvidenceIssue) {
        if (reason != null) return
        reason = blockReason
        issue = blockIssue
    }

    /** Called after service block/abandon, outside the collection budget. One bounded line.
     * A logging failure is not a new collection failure. Cancellation still propagates.
     */
    fun report(write: (String) -> Unit) {
        val blockReason = reason ?: return
        if (reported) return
        reported = true
        try {
            write("A_EVIDENCE_BLOCK;SOURCE=${source.name};CHECK=${mismatch.name};" +
                "REASON=${blockReason.name};ISSUE=${issue?.name ?: "NONE"};" +
                "COLLECTION_PHASE=${phase?.name ?: "NONE"};" +
                "OPERATION=${operation.name};FINDING=${finding.name}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log the logging exception or replace the existing evidence decision.
        }
    }

    override fun toString() = "EvidenceBlockDiagnostic(redacted)"
}
