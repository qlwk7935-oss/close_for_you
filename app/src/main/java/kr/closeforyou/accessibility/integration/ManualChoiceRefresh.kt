package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.EvidenceMismatch
import kr.closeforyou.accessibility.EvidenceSnapshotComparison
import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.accessibility.action.F9ContentSafety
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateAttacher

/** Newly collected execution evidence, never a timestamp/token copy of a displayed snapshot. */
internal class ManualChoiceEvidence(val snapshot: PopupSnapshot, val analysis: PopupAnalysis,
    val choice: UserChoice, val detection: PopupDetection) {
    override fun toString() = "ManualChoiceEvidence(redacted)"
}

internal object ManualChoiceRefresh {
    /** Android and JVM use the same removal -> quiet -> new collector ordering. This bounded
     * admission wait does not renew either the display lease or any execution evidence. */
    suspend fun afterRemoval(original: PopupSnapshot, displayed: PopupAnalysis, choice: UserChoice,
        detection: PopupDetection, now: () -> Long, current: () -> Boolean,
        awaitRemoval: suspend () -> Unit, awaitQuiet: suspend () -> Unit,
        trace: ManualActionTrace? = null, read: (() -> Unit) -> PopupSnapshot?): ManualChoiceEvidence? {
        var stage = ManualActionStep.OVERLAY_REMOVAL
        var returned = false
        try {
            val result = withTimeoutOrNull(AutoEligibility.TTL_MILLIS) {
                trace?.record(stage, ManualActionState.WAITING)
                awaitRemoval()
                trace?.record(stage, ManualActionState.PASSED)
                stage = ManualActionStep.INTERACTION_END
                trace?.record(stage, ManualActionState.WAITING)
                awaitQuiet()
                trace?.record(stage, ManualActionState.PASSED)
                stage = ManualActionStep.MANUAL_REFRESH
                collect(original, displayed, choice, detection, now, current, trace, read).also { returned = true }
            }
            if (!returned) trace?.record(stage, ManualActionState.TIMED_OUT)
            return result
        } catch (cancelled: CancellationException) {
            trace?.record(stage, ManualActionState.CANCELLED)
            throw cancelled
        } catch (failure: Exception) {
            trace?.record(stage, ManualActionState.FAILED)
            throw failure
        }
    }

    suspend fun collect(original: PopupSnapshot, displayed: PopupAnalysis, choice: UserChoice,
        detection: PopupDetection, now: () -> Long, current: () -> Boolean,
        trace: ManualActionTrace? = null, read: (() -> Unit) -> PopupSnapshot?): ManualChoiceEvidence? {
        val context = currentCoroutineContext()
        fun checkpoint() {
            context.ensureActive()
            if (!current()) throw CancellationException("MANUAL_REFRESH_STALE")
        }
        checkpoint()
        if (choice.kind != ChoiceKind.CLOSE_ONCE || !choice.buttonMeaningConfirmed ||
            choice.token != original.token || displayed.token != original.token || detection.token != original.token) {
            trace?.record(ManualActionStep.MANUAL_REFRESH, ManualActionState.REJECTED, ManualActionReason.INVALID_CHOICE)
            return null
        }
        val offered = displayed.closeCandidates.singleOrNull { it.id == choice.candidateId } ?: run {
            trace?.record(ManualActionStep.TARGET_MATCH, ManualActionState.REJECTED, ManualActionReason.TARGET_UNAVAILABLE)
            return null
        }
        val started = now()
        trace?.record(ManualActionStep.MANUAL_REFRESH, ManualActionState.STARTED)
        val fresh = read(::checkpoint) ?: run {
            trace?.record(ManualActionStep.MANUAL_REFRESH, ManualActionState.REJECTED, ManualActionReason.COLLECTION_UNAVAILABLE)
            return null
        }
        checkpoint()
        val completed = now()
        trace?.record(ManualActionStep.MANUAL_REFRESH, ManualActionState.COMPLETED)
        if (started < 0 || completed < started || fresh.capturedAtUptimeMillis < started ||
            fresh.capturedAtUptimeMillis > completed || completed - fresh.capturedAtUptimeMillis >= AutoEligibility.TTL_MILLIS ||
            fresh.completeness != SnapshotCompleteness.COMPLETE || original.completeness != SnapshotCompleteness.COMPLETE ||
            fresh.token == original.token || fresh.token.screenVersion <= original.token.screenVersion ||
            fresh.token.captureEpoch != fresh.token.screenVersion) {
            trace?.record(ManualActionStep.SNAPSHOT_MATCH, ManualActionState.REJECTED, ManualActionReason.FRESHNESS_OR_COMPLETENESS)
            return null
        }
        val mismatch = EvidenceSnapshotComparison.firstMismatch(original, fresh)
        if (mismatch != EvidenceMismatch.NONE) {
            trace?.record(ManualActionStep.SNAPSHOT_MATCH, ManualActionState.REJECTED, mismatch = mismatch)
            return null
        }
        if (fresh.nodes != original.nodes) {
            trace?.record(ManualActionStep.SNAPSHOT_MATCH, ManualActionState.REJECTED, ManualActionReason.NODES_CHANGED)
            return null
        }
        trace?.record(ManualActionStep.SNAPSHOT_MATCH, ManualActionState.PASSED)
        val texts = F9CollectedSource.texts(fresh)
        val content = texts.joinToString("\n") { it.text }
        if (F9ContentSafety.protectedPackage(fresh.token.packageName) || PrivacyPolicy.hasSensitiveText(content) ||
            PrivacyPolicy.hasUncertainText(content) || F9ContentSafety.blocked(content)) {
            trace?.record(ManualActionStep.MANUAL_REFRESH, ManualActionState.REJECTED, ManualActionReason.PROTECTED_CONTENT)
            return null
        }
        // Detection scope is transferred only AFTER exact full-tree/window/geometry continuity.
        // B candidates and all eligibility evidence are recomputed from the newly read tree.
        val freshDetection = detection.copy(token = fresh.token)
        val analysis = DecisionCandidateAttacher().attach(PopupEvent.Candidate(fresh, freshDetection),
            PopupAnalysis(fresh.token, texts = texts))
        val target = analysis.closeCandidates.singleOrNull { it.id == choice.candidateId } ?: run {
            trace?.record(ManualActionStep.TARGET_MATCH, ManualActionState.REJECTED, ManualActionReason.TARGET_UNAVAILABLE)
            return null
        }
        if (target != offered) {
            trace?.record(ManualActionStep.TARGET_MATCH, ManualActionState.REJECTED, ManualActionReason.TARGET_CHANGED)
            return null
        }
        trace?.record(ManualActionStep.TARGET_MATCH, ManualActionState.PASSED)
        checkpoint()
        return ManualChoiceEvidence(fresh, analysis, choice.copy(token = fresh.token, candidateId = target.id), freshDetection)
    }
}
