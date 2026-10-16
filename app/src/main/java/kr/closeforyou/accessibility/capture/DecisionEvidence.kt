package kr.closeforyou.accessibility.capture

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.core.model.*

/** A's data minimization boundary, NOT B's classifier or action policy.
 * Call only after the applicable privacy checks. Never expose original decision text or targets. */
internal object DecisionEvidence {
    fun summarize(snapshot: PopupSnapshot, texts: List<ExtractedText>): EvidenceOutcome.Ready? {
        val summaries = texts.flatMap { evidence ->
            PrivacyPolicy.decisionSummaries(evidence.text).map { label ->
                ExtractedText(label, evidence.bounds, evidence.source, null)
            }
        }.distinctBy { Triple(it.text, it.bounds, it.source) }
            .sortedWith(compareBy<ExtractedText> { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.source.ordinal })
        if (summaries.isEmpty()) return null
        return EvidenceOutcome.Ready(
            PopupAnalysis(snapshot.token, texts = summaries.toPersistentList(),
                limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED)),
            EvidenceIssue.DECISION_SUMMARY_ONLY,
            // Candidate is also public: redacting only PopupAnalysis would leak the original tree.
            // Keep token/window geometry for invalidation; provide no node IDs, labels or actions.
            snapshot.copy(nodes = persistentListOf()),
        )
    }
}
