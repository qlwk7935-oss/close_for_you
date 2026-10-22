package kr.closeforyou.decision

import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.PopupAnalysis
import kotlinx.collections.immutable.persistentListOf

/**
 * B: puts F3 close/skip candidates on A's analysis of the same popup, in the integration layer
 * agreed with A (A's `extract` result carries no candidates). Call it right after `extract` and
 * before classifying, with the `Candidate` event the analysis came from.
 *
 * Candidates come only from this finder, so any candidates already on the analysis are replaced.
 * If the analysis and the event describe different screens, no candidates are attached at all:
 * buttons from one screen must never be offered for another.
 */
class DecisionCandidateAttacher(
    private val finder: DecisionCloseCandidateFinder = DecisionCloseCandidateFinder(),
) {
    fun attach(event: PopupEvent.Candidate, analysis: PopupAnalysis): PopupAnalysis {
        val sameScreen = analysis.token == event.snapshot.token && event.detection.token == event.snapshot.token
        val candidates = if (sameScreen) finder.find(event.snapshot, event.detection) else persistentListOf()
        return analysis.copy(closeCandidates = candidates)
    }
}
