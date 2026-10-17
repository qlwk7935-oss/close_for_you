package kr.closeforyou.accessibility.capture

import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kr.closeforyou.accessibility.PrivacyPolicy
import kr.closeforyou.accessibility.CaptureTrace
import kr.closeforyou.accessibility.CaptureStage
import kr.closeforyou.accessibility.CaptureDetail
import kr.closeforyou.core.contract.CaptureCapability
import kr.closeforyou.core.model.*

internal interface CaptureFrame : AutoCloseable { val bounds: ScreenBounds }
internal interface ScreenshotPort : CaptureCapability {
    val isolatedWindowCapture: Boolean get() = false
    fun denial(token: SnapshotToken): EvidenceIssue?
    fun denialTraced(token: SnapshotToken, trace: CaptureTrace): EvidenceIssue? = denial(token)
    suspend fun captureTraced(snapshot: PopupSnapshot, region: CaptureRegion, trace: CaptureTrace): CaptureFrame = capture(snapshot, region)
    suspend fun capture(snapshot: PopupSnapshot, region: CaptureRegion): CaptureFrame
}
internal fun interface OcrPort { suspend fun recognize(frame: CaptureFrame): List<OcrLine> }

internal sealed interface EvidenceOutcome {
    data class Ready(val analysis: PopupAnalysis, val issue: EvidenceIssue, val snapshot: PopupSnapshot) : EvidenceOutcome
    data class Blocked(val reason: PrivacyBlockReason, val issue: EvidenceIssue) : EvidenceOutcome
}

/** No Android objects escape this pipeline. Service owns cancellation and the original token TTL. */
internal class CaptureOcrPipeline(
    private val screenshots: ScreenshotPort,
    private val ocr: OcrPort,
    private val current: (SnapshotToken) -> Boolean,
    private val preflight: (PopupSnapshot) -> PrivacyBlockReason?,
    private val ocrTimeoutMillis: Long = 3_000,
) {
    suspend fun analyze(
        snapshot: PopupSnapshot,
        trace: CaptureTrace = CaptureTrace(null),
        protection: (PopupSnapshot) -> PrivacyBlockReason? = preflight,
    ): EvidenceOutcome {
        trace.stage(CaptureStage.F0_A)
        checkCurrent(snapshot)
        protection(snapshot)?.let { trace.block(it); return EvidenceOutcome.Blocked(it, EvidenceIssue.PRIVACY_BLOCKED) }
        val tree = treeTexts(snapshot)
        val treeContent = tree.joinToString("\n") { it.text }
        if (PrivacyPolicy.hasSensitiveText(treeContent) || PrivacyPolicy.hasUncertainText(treeContent)) {
            trace.block(PrivacyBlockReason.SENSITIVE_CONTENT); return EvidenceOutcome.Blocked(PrivacyBlockReason.SENSITIVE_CONTENT, EvidenceIssue.PRIVACY_BLOCKED)
        }
        trace.stage(CaptureStage.F0_A_PASSED)
        checkCurrent(snapshot)
        // Known decision text requires only a fixed, non-executable summary, never a new capture.
        DecisionEvidence.summarize(snapshot, tree)?.let { return it }
        fun partial(issue: EvidenceIssue) = EvidenceOutcome.Ready(
            PopupAnalysis(snapshot.token, texts = tree.toPersistentList(),
                limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED)), issue, snapshot,
        )
        screenshots.denialTraced(snapshot.token, trace)?.let { return partial(it) }
        trace.stage(CaptureStage.REGION)
        snapshot.windows.singleOrNull { it.id == snapshot.token.windowId }?.let {
            trace.geometry(PopupRegionResolver.geometry(it.bounds, snapshot.screenBounds))
        }
        val resolution = PopupRegionResolver.inspect(snapshot, screenshots.isolatedWindowCapture)
        trace.detail(resolution.detail, resolution.relatedType)
        val region = resolution.region ?: return partial(EvidenceIssue.REGION_UNCERTAIN)
        if (!PopupRegionResolver.needsOcr(snapshot, region)) return partial(EvidenceIssue.TREE_SUFFICIENT)
        var frame: CaptureFrame? = null
        try {
            trace.stage(CaptureStage.CAPTURE_REQUEST)
            frame = screenshots.captureTraced(snapshot, region, trace)
            trace.stage(CaptureStage.CAPTURE_RETURNED)
            checkCurrent(snapshot)
            protection(snapshot)?.let { trace.block(it); return EvidenceOutcome.Blocked(it, EvidenceIssue.PRIVACY_BLOCKED) }
            if (frame.bounds != region.bounds) {
                trace.detail(CaptureDetail.FRAME_GEOMETRY)
                return partial(EvidenceIssue.REGION_UNCERTAIN)
            }
            trace.stage(CaptureStage.OCR)
            val lines = try {
                withTimeout(ocrTimeoutMillis) { ocr.recognize(frame) }
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                checkCurrent(snapshot)
                protection(snapshot)?.let { trace.block(it); return EvidenceOutcome.Blocked(it, EvidenceIssue.PRIVACY_BLOCKED) }
                return partial(EvidenceIssue.OCR_TIMEOUT)
            }
            trace.stage(CaptureStage.OCR_RETURNED)
            checkCurrent(snapshot)
            protection(snapshot)?.let { trace.block(it); return EvidenceOutcome.Blocked(it, EvidenceIssue.PRIVACY_BLOCKED) }
            trace.stage(CaptureStage.F0_B)
            when (OcrPrivacyReview.review(lines, region.bounds)) {
                PrivacyReview.SENSITIVE -> { trace.block(PrivacyBlockReason.SENSITIVE_CONTENT); return EvidenceOutcome.Blocked(PrivacyBlockReason.SENSITIVE_CONTENT, EvidenceIssue.PRIVACY_BLOCKED) }
                PrivacyReview.UNCERTAIN -> return partial(EvidenceIssue.PRIVACY_UNCERTAIN)
                PrivacyReview.TEXT_CHECK_PASSED -> Unit
            }
            trace.stage(CaptureStage.F0_B_PASSED)
            // Preserve both tree and OCR provenance, including agreeing cross-source evidence.
            // Deduplicate only within a source at the same position; never collapse different dates.
            val combined = (tree + lines.map { ExtractedText(it.text, it.bounds, TextSource.OCR, it.confidence) })
                .distinctBy { Triple(it.source, it.text.trim(), it.bounds) }
                .sortedWith(compareBy<ExtractedText> { it.bounds.top }.thenBy { it.bounds.left }.thenBy { it.source.ordinal })
            checkCurrent(snapshot)
            DecisionEvidence.summarize(snapshot, combined)?.let { return it }
            return EvidenceOutcome.Ready(PopupAnalysis(snapshot.token, texts = combined.toPersistentList(),
                limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED)), EvidenceIssue.NONE, snapshot)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: EvidenceFailure) {
            checkCurrent(snapshot)
            protection(snapshot)?.let { trace.block(it); return EvidenceOutcome.Blocked(it, EvidenceIssue.PRIVACY_BLOCKED) }
            return partial(failure.issue)
        } finally {
            frame?.close()
        }
    }

    private suspend fun checkCurrent(snapshot: PopupSnapshot) {
        currentCoroutineContext().ensureActive()
        if (!current(snapshot.token)) throw CancellationException(EvidenceIssue.STALE_SCREEN.name)
    }

    private fun treeTexts(snapshot: PopupSnapshot): List<ExtractedText> = snapshot.nodes.flatMap { node ->
        listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }.map {
            ExtractedText(it, node.bounds, TextSource.ACCESSIBILITY_TREE, null)
        }
    }.distinctBy { Pair(it.text, it.bounds) }
}
