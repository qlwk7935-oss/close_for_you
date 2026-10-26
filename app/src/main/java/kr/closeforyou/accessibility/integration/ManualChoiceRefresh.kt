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
        read: (() -> Unit) -> PopupSnapshot?): ManualChoiceEvidence? = withTimeoutOrNull(AutoEligibility.TTL_MILLIS) {
        awaitRemoval()
        awaitQuiet()
        collect(original, displayed, choice, detection, now, current, read)
    }

    suspend fun collect(original: PopupSnapshot, displayed: PopupAnalysis, choice: UserChoice,
        detection: PopupDetection, now: () -> Long, current: () -> Boolean,
        read: (() -> Unit) -> PopupSnapshot?): ManualChoiceEvidence? {
        val context = currentCoroutineContext()
        fun checkpoint() {
            context.ensureActive()
            if (!current()) throw CancellationException("MANUAL_REFRESH_STALE")
        }
        checkpoint()
        if (choice.kind != ChoiceKind.CLOSE_ONCE || !choice.buttonMeaningConfirmed ||
            choice.token != original.token || displayed.token != original.token || detection.token != original.token) return null
        val offered = displayed.closeCandidates.singleOrNull { it.id == choice.candidateId } ?: return null
        val started = now()
        val fresh = read(::checkpoint) ?: return null
        checkpoint()
        val completed = now()
        if (started < 0 || completed < started || fresh.capturedAtUptimeMillis < started ||
            fresh.capturedAtUptimeMillis > completed || completed - fresh.capturedAtUptimeMillis >= AutoEligibility.TTL_MILLIS ||
            fresh.completeness != SnapshotCompleteness.COMPLETE || original.completeness != SnapshotCompleteness.COMPLETE ||
            fresh.token == original.token || fresh.token.screenVersion <= original.token.screenVersion ||
            fresh.token.captureEpoch != fresh.token.screenVersion ||
            EvidenceSnapshotComparison.firstMismatch(original, fresh) != EvidenceMismatch.NONE ||
            fresh.nodes != original.nodes) return null
        val texts = F9CollectedSource.texts(fresh)
        val content = texts.joinToString("\n") { it.text }
        if (F9ContentSafety.protectedPackage(fresh.token.packageName) || PrivacyPolicy.hasSensitiveText(content) ||
            PrivacyPolicy.hasUncertainText(content) || F9ContentSafety.blocked(content)) return null
        // Detection scope is transferred only AFTER exact full-tree/window/geometry continuity.
        // B candidates and all eligibility evidence are recomputed from the newly read tree.
        val freshDetection = detection.copy(token = fresh.token)
        val analysis = DecisionCandidateAttacher().attach(PopupEvent.Candidate(fresh, freshDetection),
            PopupAnalysis(fresh.token, texts = texts))
        val target = analysis.closeCandidates.singleOrNull { it.id == choice.candidateId } ?: return null
        if (target != offered) return null
        checkpoint()
        return ManualChoiceEvidence(fresh, analysis, choice.copy(token = fresh.token, candidateId = target.id), freshDetection)
    }
}
