package kr.closeforyou.decision

import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupDetection
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.SnapshotToken
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicReference

/**
 * B: wraps A's [SnapshotSource] and [EvidenceExtractor] so every analysis carries B's close/skip
 * candidates for its own screen, without changing A's, C's or D's code.
 *
 * A passes [source] and [extractor] wherever it would pass its own: to C's `PopupChoiceFlow` and to
 * D's fingerprint provider. The source remembers the detection of the latest `Candidate` event
 * (signals only, never the snapshot); the extractor attaches candidates only when the snapshot,
 * its analysis and that detection all have the same token. Anything else, including a screen
 * that changed meanwhile or a `Blocked`/`Invalidated` event, yields an analysis with no
 * candidates, so a button from one screen is never offered for another.
 */
class DecisionCandidateWiring(
    base: SnapshotSource,
    private val baseExtractor: EvidenceExtractor,
    private val finder: DecisionCloseCandidateFinder = DecisionCloseCandidateFinder(),
) {
    private val latest = AtomicReference<PopupDetection?>(null)

    val source: SnapshotSource = object : SnapshotSource {
        override val events: Flow<PopupEvent> = base.events.onEach { event ->
            latest.set((event as? PopupEvent.Candidate)?.detection)
        }

        override fun currentToken(): SnapshotToken? = base.currentToken()
    }

    val extractor: EvidenceExtractor = object : EvidenceExtractor {
        override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
            val analysis = baseExtractor.extract(snapshot)
            val detection = latest.get()?.takeIf { it.token == snapshot.token && analysis.token == snapshot.token }
            val candidates = if (detection != null) finder.find(snapshot, detection) else persistentListOf()
            return analysis.copy(closeCandidates = candidates)
        }
    }
}
