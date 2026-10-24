package kr.closeforyou.decision

import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupDetection
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.SnapshotToken
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicReference

/**
 * B: wraps A's [SnapshotSource] and [EvidenceExtractor] so every analysis carries B's close/skip
 * candidates for its own screen, without changing A's, C's or D's code.
 *
 * A passes [source] and [extractor] wherever it would pass its own: to C's `PopupChoiceFlow` and to
 * D's fingerprint provider. The source remembers the detection of the latest `Candidate` event
 * (signals only, never the snapshot). The extractor attaches candidates only when the snapshot is
 * A's current screen and the snapshot, its analysis and that detection all have the same token.
 * Anything else, including a screen that changed, expired or was blocked meanwhile, yields an
 * analysis with no candidates, so a button from one screen is never offered for another. For a
 * same-window popup the texts are narrowed to the popup's own plus any warning from the page
 * ([DecisionPopupScope]), so what C reads and what B classifies is the popup.
 *
 * A's events are a shared flow and may have several collectors. An event older than what is
 * already remembered (a smaller screen version) never replaces it, so a slow collector cannot bring
 * back a detection for a screen A has already moved past.
 */
class DecisionCandidateWiring(
    private val base: SnapshotSource,
    private val baseExtractor: EvidenceExtractor,
    private val finder: DecisionCloseCandidateFinder = DecisionCloseCandidateFinder(),
) {
    /** The remembered detection and the lowest screen version still allowed to replace it. */
    private data class Memory(val detection: PopupDetection?, val floor: Long)

    private val memory = AtomicReference(Memory(detection = null, floor = Long.MIN_VALUE))

    val source: SnapshotSource = object : SnapshotSource {
        override val events: Flow<PopupEvent> = base.events.onEach(::remember)
        override fun currentToken(): SnapshotToken? = base.currentToken()
    }

    val extractor: EvidenceExtractor = object : EvidenceExtractor {
        override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
            val analysis = baseExtractor.extract(snapshot)
            val detection = memory.get().detection?.takeIf {
                it.token == snapshot.token && analysis.token == snapshot.token && base.currentToken() == snapshot.token
            }
            if (detection == null) return analysis.copy(closeCandidates = persistentListOf())
            // Same popup boundary for texts as for candidates (AUTO_CLOSE.md 5절): page text behind a
            // same-window popup no longer decides its kind, and warnings from the page are kept.
            return analysis.copy(
                texts = DecisionPopupScope.texts(snapshot, detection, analysis.texts).toPersistentList(),
                closeCandidates = finder.find(snapshot, detection),
            )
        }
    }

    // A raises the screen version on every change: Invalidated(v) starts version v, and a Candidate
    // of that screen carries v. A block at version v also covers a detection of version v.
    private fun remember(event: PopupEvent) {
        memory.updateAndGet { m ->
            when (event) {
                is PopupEvent.Candidate -> {
                    val version = event.detection.token.screenVersion
                    if (version >= m.floor) Memory(event.detection, version) else m
                }
                is PopupEvent.Invalidated -> forget(m, event.screenVersion)
                is PopupEvent.Blocked -> forget(m, event.screenVersion + 1)
            }
        }
    }

    /** Drops a detection older than [from]; nothing older than [from] may come back. */
    private fun forget(m: Memory, from: Long): Memory {
        val floor = maxOf(m.floor, from)
        return Memory(m.detection?.takeIf { it.token.screenVersion >= floor }, floor)
    }
}
