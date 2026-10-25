package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.accessibility.action.F9ContentSafety

/** Private A tree stream, separate from OCR's intentionally redacted public analysis. No replay.
 * Collection alone never grants eligibility: a fresh complete F0 screen pass is still required.
 */
internal class F9CollectedSource(private val now: () -> Long) : SnapshotSource, EvidenceExtractor {
    private val stream = MutableSharedFlow<PopupEvent>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val events = stream
    private var token: SnapshotToken? = null
    private var analysis: PopupAnalysis? = null
    private var captured = -1L
    private var floor = Long.MIN_VALUE

    override fun currentToken(): SnapshotToken? {
        if (captured < 0 || now() < captured || now() - captured >= AutoEligibility.TTL_MILLIS) {
            token = null; analysis = null
        }
        return token
    }
    fun publish(snapshot: PopupSnapshot, detection: PopupDetection) {
        if (detection.token != snapshot.token || snapshot.token.screenVersion < floor ||
            snapshot.completeness != SnapshotCompleteness.COMPLETE || now() < snapshot.capturedAtUptimeMillis ||
            now() - snapshot.capturedAtUptimeMillis >= AutoEligibility.TTL_MILLIS) return
        val tree = texts(snapshot)
        val completeText = tree.joinToString("\n") { it.text }
        // Preserve CaptureOcrPipeline's combined-text F0 check before retaining a new private
        // tree analysis. Per-node inspection alone misses a sensitive word split across nodes.
        if (F9ContentSafety.protectedPackage(snapshot.token.packageName) || PrivacyPolicy.hasSensitiveText(completeText) ||
            PrivacyPolicy.hasUncertainText(completeText)) {
            invalidate(snapshot.token.screenVersion); return
        }
        floor = snapshot.token.screenVersion; token = snapshot.token; captured = snapshot.capturedAtUptimeMillis
        analysis = PopupAnalysis(snapshot.token, texts = tree)
        stream.tryEmit(PopupEvent.Candidate(snapshot, detection))
    }
    override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
        check(currentToken() == snapshot.token) { "F9_EXPIRED_COLLECTION" }
        return requireNotNull(analysis)
    }
    fun invalidate(version: Long) {
        floor = maxOf(floor, version); token = null; analysis = null; captured = -1
        stream.tryEmit(PopupEvent.Invalidated(version))
    }
    companion object {
        fun texts(snapshot: PopupSnapshot) = snapshot.nodes.flatMap { node ->
            listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }.map {
                ExtractedText(it, node.bounds, TextSource.ACCESSIBILITY_TREE, null)
            }
        }.toPersistentList()
    }
}
