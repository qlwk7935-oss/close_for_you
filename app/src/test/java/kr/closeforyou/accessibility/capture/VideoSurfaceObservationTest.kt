package kr.closeforyou.accessibility.capture

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kr.closeforyou.accessibility.PopupCandidateDetector
import kr.closeforyou.accessibility.DetectionFrame
import kr.closeforyou.accessibility.DetectionOutline
import kr.closeforyou.accessibility.LiveEvidenceState
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateAttacher
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.decision.DecisionVideoAd
import kr.closeforyou.decision.DecisionActionPolicy
import kr.closeforyou.decision.DecisionChoiceHandler
import kr.closeforyou.accessibility.integration.ReviewOnlyChoiceExecutor
import kr.closeforyou.accessibility.action.NonExecutingActionExecutor
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.contract.DecisionContext
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

/** Same pure observation called by the production pipeline; synthetic DTOs, no Android playback. */
class VideoSurfaceObservationTest {
    private val screen = ScreenBounds(0, 0, 1_000, 1_600)
    private val window = ScreenBounds(100, 200, 900, 1_200)
    private val surface = ScreenBounds(150, 300, 850, 900)
    private fun snapshot(name: String? = "android.view.SurfaceView", body: String = "합성 화면") = PopupSnapshot(
        SnapshotToken(1, 7, "synthetic.fixture", "synthetic-popup", "structure", "content", 1),
        1_000, persistentListOf(
            NodeSnapshot("0", null, "android.widget.FrameLayout", body, null, false, true, window),
            NodeSnapshot("0/0", null, name, null, null, false, true, surface)),
        persistentListOf(WindowSnapshot(7, WindowType.APPLICATION, window, true, true, 1)),
        screen, PopupTrigger.WINDOW_CHANGED, SnapshotCompleteness.COMPLETE)
    private fun frame(s: PopupSnapshot) = DetectionFrame("application", s.token.windowId,
        setOf(s.token.windowId), s.token.structureDigest, s.screenBounds,
        s.nodes.map { DetectionOutline(it.path, it.bounds, 0, it.className?.contains("dialog", ignoreCase = true) == true, it.path, visible = true) },
        s.trigger, s.windows.single().bounds)
    private fun observed(s: PopupSnapshot, f: DetectionFrame?) =
        VideoSurfaceObservation.observed(s, f, f?.let { PopupCandidateDetector().assess(it) })
    private suspend fun CaptureOcrPipeline.analyzeObserved(s: PopupSnapshot, f: DetectionFrame = frame(s)) =
        analyze(s, detectionFrame = f, detectionAssessment = PopupCandidateDetector().assess(f))
    private fun withImage(s: PopupSnapshot) = s.copy(nodes = s.nodes.toPersistentList().add(
        NodeSnapshot("0/1", null, "android.widget.ImageView", null, null, false, true, surface)))
    private class Shots(var issue: EvidenceIssue? = EvidenceIssue.NOT_REQUESTED) : ScreenshotPort {
        var calls = 0
        var closed = false
        override fun availability(token: SnapshotToken) = CaptureAvailability.AVAILABLE
        override fun denial(token: SnapshotToken) = issue
        override suspend fun capture(snapshot: PopupSnapshot, region: CaptureRegion): CaptureFrame {
            calls++
            return object : CaptureFrame {
                override val bounds = region.bounds
                override fun close() { closed = true }
            }
        }
    }
    private suspend fun tree(s: PopupSnapshot, f: DetectionFrame? = frame(s), shots: Shots = Shots()): EvidenceOutcome.Ready =
        CaptureOcrPipeline(shots, OcrPort { error("UNEXPECTED_OCR") }, { true }, { null })
            .analyze(s, detectionFrame = f, detectionAssessment = f?.let { PopupCandidateDetector().assess(it) }) as EvidenceOutcome.Ready

    @Test fun exactKnownClassesWithVisibleMatchingOutlineAreObserved() {
        for (name in listOf("android.view.SurfaceView", "android.view.TextureView", "android.widget.VideoView")) {
            val s = snapshot(name); assertTrue(observed(s, frame(s)))
        }
    }
    @Test fun namesTextAndDescriptionsDoNotGuessAClassOrCustomSubclass() {
        for (name in listOf(null, "SurfaceView", "android.view.SurfaceViewExtra", "fixture.VideoView",
            "android.view.surfaceview", "android.webkit.WebView", "android.widget.ImageView")) {
            val s = snapshot(name, "SurfaceView VideoView TextureView")
            assertFalse(observed(s, frame(s)))
        }
    }
    @Test fun hiddenSurfaceInSameGeometryDoesNotSetSignal() {
        val s = snapshot(); val f = frame(s)
        assertFalse(observed(s, f.copy(outlines = f.outlines.map {
            if (it.path == "0/0") it.copy(visible = false) else it })))
    }
    @Test fun emptyOutsideAndPartiallyOutsideSurfaceAreNotObservedOrCorrected() {
        for (bounds in listOf(ScreenBounds(150, 300, 150, 900), ScreenBounds(150, 300, 850, 300),
            ScreenBounds(50, 300, 850, 900), ScreenBounds(950, 300, 990, 900))) {
            val s = snapshot().let { it.copy(nodes = it.nodes.toPersistentList().set(1, it.nodes[1].copy(bounds = bounds))) }
            assertFalse(observed(s, frame(s)))
            assertEquals(bounds, s.nodes[1].bounds)
        }
    }
    @Test fun missingOrIncompleteFrameCannotEstablishVisibility() {
        val s = snapshot(); val f = frame(s)
        assertFalse(observed(s, null))
        assertFalse(observed(s.copy(completeness = SnapshotCompleteness.TRUNCATED), f))
        assertFalse(observed(s, f.copy(outlines = f.outlines.dropLast(1))))
    }
    @Test fun mismatchedFrameWindowStructureScreenAndTriggerAreRejected() {
        val s = snapshot(); val f = frame(s)
        for (other in listOf(f.copy(activeWindowId = 8), f.copy(structureDigest = "different"),
            f.copy(screenBounds = window), f.copy(windowBounds = surface),
            f.copy(applicationWindowIds = setOf(8)), f.copy(trigger = PopupTrigger.CONTENT_CHANGED)))
            assertFalse(observed(s, other))
    }
    @Test fun unknownInactiveDuplicateOrOutsideTargetWindowDoesNotSetSignal() {
        val s = snapshot(); val w = s.windows.single()
        for (windows in listOf(persistentListOf(), persistentListOf(w, w),
            persistentListOf(w.copy(active = false)), persistentListOf(w.copy(type = WindowType.SYSTEM)),
            persistentListOf(w.copy(bounds = ScreenBounds(100, 200, 1_100, 1_200))))) {
            val changed = s.copy(windows = windows)
            assertFalse(observed(changed, frame(s).copy(windowBounds = windows.firstOrNull()?.bounds ?: window)))
        }
    }
    @Test fun duplicateOrReplacedPathsAndMismatchedBoundsDoNotJoinEvidence() {
        val s = snapshot(); val f = frame(s)
        for (outlines in listOf(listOf(f.outlines[1], f.outlines[1]),
            f.outlines.map { if (it.path == "0/0") it.copy(path = "0/2") else it },
            f.outlines.map { if (it.path == "0/0") it.copy(bounds = window) else it }))
            assertFalse(observed(s, f.copy(outlines = outlines)))
        assertFalse(observed(s.copy(nodes = persistentListOf(s.nodes[1], s.nodes[1])), f))
    }
    @Test fun sameWindowDialogRequiresItsOwnSurfaceDescendantNotOverlappingBackground() {
        val base = snapshot()
        val dialog = base.nodes.first().copy(path = "0/1", className = "android.app.Dialog", text = null)
        // The inset Activity may satisfy the OCR resolver's window area rule while F1 still
        // correctly uses its SEMANTIC_DIALOG branch. Do not infer scope from the area alone.
        for (activityBounds in listOf(screen, ScreenBounds(0, 150, 1_000, 1_550))) {
            val sameWindow = base.copy(windows = persistentListOf(base.windows.single().copy(bounds = activityBounds)),
                nodes = persistentListOf(base.nodes[0].copy(bounds = activityBounds), base.nodes[1], dialog))
            assertEquals(kr.closeforyou.accessibility.DetectionReason.SEMANTIC_DIALOG,
                PopupCandidateDetector().assess(frame(sameWindow)).reason)
            // Bounds overlap the Dialog, but this is its background sibling, not its child.
            assertFalse(observed(sameWindow, frame(sameWindow)))
            val ownSurface = sameWindow.copy(nodes = sameWindow.nodes.toPersistentList().add(base.nodes[1].copy(path = "0/1/0")))
            assertTrue(observed(ownSurface, frame(ownSurface)))
            val hidden = frame(ownSurface).let { f -> f.copy(outlines = f.outlines.map {
                if (it.path == "0/1") it.copy(visible = false) else it }) }
            assertFalse(observed(ownSurface, hidden))
            val escaped = ownSurface.copy(nodes = ownSurface.nodes.toPersistentList().set(3,
                ownSurface.nodes[3].copy(bounds = ScreenBounds(50, 300, 850, 900))))
            assertFalse(observed(escaped, frame(escaped)))
        }
    }
    @Test fun missingNonCandidateOrUnsupportedAssessmentCannotSetSignal() {
        val s = snapshot(); val f = frame(s); val assessment = PopupCandidateDetector().assess(f)
        assertFalse(VideoSurfaceObservation.observed(s, f, null))
        for (other in listOf(assessment.copy(geometry = null),
            assessment.copy(disposition = kr.closeforyou.accessibility.DetectionDisposition.NO_POPUP),
            assessment.copy(disposition = kr.closeforyou.accessibility.DetectionDisposition.UNCERTAIN),
            assessment.copy(reason = kr.closeforyou.accessibility.DetectionReason.PANE_WITH_UNDERLAY)))
            assertFalse(VideoSurfaceObservation.observed(s, f, other))
    }
    @Test fun unknownFullScreenOrAmbiguousDialogScopeDoesNotSetSignal() {
        val base = snapshot()
        val full = base.copy(windows = persistentListOf(base.windows.single().copy(bounds = screen)),
            nodes = persistentListOf(base.nodes[0].copy(bounds = screen), base.nodes[1]))
        assertFalse(observed(full, frame(full)))
        val duplicateDialogs = full.copy(nodes = full.nodes.toPersistentList().addAll(listOf(
            base.nodes[0].copy(path = "0/1", className = "android.app.Dialog"),
            base.nodes[0].copy(path = "0/2", className = "android.app.Dialog"),
            base.nodes[1].copy(path = "0/1/0"))))
        assertFalse(observed(duplicateDialogs, frame(duplicateDialogs)))
    }
    @Test fun occludedTargetOrOversizedNodeSetDoesNotSetSignal() {
        val s = snapshot(); val f = frame(s)
        val occluded = s.copy(windows = s.windows.toPersistentList().add(
            WindowSnapshot(8, WindowType.ACCESSIBILITY_OVERLAY, surface, false, false, 2)))
        assertFalse(observed(occluded, f))
        val oversized = s.copy(nodes = (0..1_200).map { s.nodes[1].copy(path = "0/$it") }.toPersistentList())
        assertFalse(observed(oversized, frame(oversized)))
    }
    @Test fun treeOnlyConsentOffCarriesSignalWithoutCaptureOrClassificationPromotion() = runTest {
        val s = snapshot(); val shots = Shots(); val ready = tree(s, shots = shots)
        assertTrue(ready.analysis.videoSurfaceObserved); assertSame(s, ready.snapshot)
        assertEquals(EvidenceIssue.NOT_REQUESTED, ready.issue); assertEquals(0, shots.calls)
        assertEquals(PopupClassification.UNKNOWN, ready.analysis.classification)
        assertEquals(PopupClassification.UNKNOWN, DecisionPopupClassifier().classify(ready.analysis))
        assertTrue(ready.analysis.closeCandidates.isEmpty())
        val report = DecisionVideoAd.inspect(ready.analysis)
        assertTrue(report.videoObserved); assertFalse(report.advertisementEvidence); assertFalse(report.isVideoAd)
        assertNull(report.countdownSeconds); assertTrue(report.skipCandidateIds.isEmpty())
        assertTrue(AnalysisLimit.INCOMPLETE_EVIDENCE in ready.analysis.limitations)
    }
    @Test fun oldCallWithoutOutlineRemainsFalse() = runTest {
        assertFalse(tree(snapshot(), null).analysis.videoSurfaceObserved)
    }
    @Test fun treeSufficientAndOcrSuccessPreserveTheSameObservation() = runTest {
        val s = snapshot(); val noCapture = Shots(null)
        val enough = tree(s, shots = noCapture)
        assertEquals(EvidenceIssue.TREE_SUFFICIENT, enough.issue)
        assertTrue(enough.analysis.videoSurfaceObserved); assertEquals(0, noCapture.calls)
        val image = withImage(s); val shots = Shots(null)
        val ready = CaptureOcrPipeline(shots, OcrPort { listOf(OcrLine("합성 문장", surface)) }, { true }, { null })
            .analyzeObserved(image) as EvidenceOutcome.Ready
        assertTrue(ready.analysis.videoSurfaceObserved); assertEquals(EvidenceIssue.NONE, ready.issue)
        assertEquals(1, shots.calls); assertTrue(shots.closed)
    }
    @Test fun ocrFailureFallbackRetainsOnlyTreeObservationAndExistingLimitations() = runTest {
        val s = withImage(snapshot()); val shots = Shots(null)
        val ready = CaptureOcrPipeline(shots, OcrPort { throw EvidenceFailure(EvidenceIssue.OCR_FAILED) }, { true }, { null })
            .analyzeObserved(s) as EvidenceOutcome.Ready
        assertTrue(ready.analysis.videoSurfaceObserved); assertEquals(EvidenceIssue.OCR_FAILED, ready.issue)
        assertTrue(ready.analysis.texts.all { it.source == TextSource.ACCESSIBILITY_TREE }); assertTrue(shots.closed)
    }
    @Test fun decisionSummaryDoesNotAddOriginalTreeSurfaceMetadata() = runTest {
        val s = snapshot(body = "결제 안내"); val ready = tree(s)
        assertEquals(EvidenceIssue.DECISION_SUMMARY_ONLY, ready.issue)
        assertTrue(ready.snapshot.nodes.isEmpty()); assertFalse(ready.analysis.videoSurfaceObserved)
        val image = withImage(snapshot()); val shots = Shots(null)
        val ocrSummary = CaptureOcrPipeline(shots, OcrPort { listOf(OcrLine("결제 안내", surface)) }, { true }, { null })
            .analyzeObserved(image) as EvidenceOutcome.Ready
        assertEquals(EvidenceIssue.DECISION_SUMMARY_ONLY, ocrSummary.issue)
        assertFalse(ocrSummary.analysis.videoSurfaceObserved); assertTrue(shots.closed)
    }
    @Test fun protectionAndTruncationStillBlockBeforeAnySurfaceAnalysisIsReturned() = runTest {
        val s = snapshot(); val shots = Shots()
        for (reason in listOf(PrivacyBlockReason.NO_CONSENT, PrivacyBlockReason.PASSWORD, PrivacyBlockReason.EDITABLE_FIELD)) {
            val result = CaptureOcrPipeline(shots, OcrPort { error("UNEXPECTED_OCR") }, { true }, { reason })
                .analyzeObserved(s) as EvidenceOutcome.Blocked
            assertEquals(reason, result.reason)
        }
        val incomplete = s.copy(completeness = SnapshotCompleteness.TRUNCATED)
        assertTrue(CaptureOcrPipeline(shots, OcrPort { error("UNEXPECTED_OCR") }, { true }, { null })
            .analyzeObserved(incomplete, frame(s)) is EvidenceOutcome.Blocked)
        assertEquals(0, shots.calls)
    }
    @Test fun sensitiveTreeAndOcrStillDiscardTheEntireAnalysis() = runTest {
        val s = snapshot(body = "비밀번호"); val shots = Shots(null)
        assertTrue(CaptureOcrPipeline(shots, OcrPort { error("UNEXPECTED_OCR") }, { true }, { null })
            .analyzeObserved(s) is EvidenceOutcome.Blocked)
        assertEquals(0, shots.calls)
        val image = withImage(snapshot())
        val result = CaptureOcrPipeline(shots, OcrPort { listOf(OcrLine("비밀번호", surface)) }, { true }, { null })
            .analyzeObserved(image) as EvidenceOutcome.Blocked
        assertEquals(PrivacyBlockReason.SENSITIVE_CONTENT, result.reason); assertTrue(shots.closed)
    }
    @Test fun staleTokenAndCancellationNeverReturnSurfaceEvidence() = runTest {
        val s = snapshot(); val shots = Shots()
        try {
            CaptureOcrPipeline(shots, OcrPort { error("UNEXPECTED_OCR") }, { false }, { null })
                .analyzeObserved(s)
            fail("STALE_ACCEPTED")
        } catch (_: CancellationException) { }
        val cancelled = CancellationException("SYNTHETIC_CANCEL")
        val image = withImage(s); shots.issue = null
        try {
            CaptureOcrPipeline(shots, OcrPort { throw cancelled }, { true }, { null })
                .analyzeObserved(image)
            fail("CANCELLATION_ACCEPTED")
        } catch (caught: CancellationException) {
            // Coroutine stack-trace recovery may wrap this exact original cancellation.
            assertTrue(caught === cancelled || caught.cause === cancelled)
        }
        assertTrue(shots.closed)
    }
    @Test fun livePublicationAndBCandidateAttachmentPreserveSignalButNotBeyondTtlOrInvalidation() = runTest {
        val s = snapshot(); val ready = tree(s); var now = 1_000L
        val live = LiveEvidenceState({ now }); live.connect(); live.invalidate(1)
        val permit = requireNotNull(live.begin(s))
        val detection = PopupDetection(s.token, persistentSetOf(PopupSignal.NEW_WINDOW), true)
        assertTrue(live.publish(permit, s, detection, ready.analysis, ready.issue))
        val extracted = requireNotNull(live.extract(s))
        assertTrue(DecisionCandidateAttacher().attach(PopupEvent.Candidate(s, detection), extracted).videoSurfaceObserved)
        assertFalse(live.publish(permit, s, detection, ready.analysis, ready.issue))
        var deliveries = 0
        assertTrue(live.deliver(permit, s) { deliveries++ })
        assertFalse(live.deliver(permit, s) { deliveries++ }); assertEquals(1, deliveries)
        now = 6_000; assertNull(live.extract(s)); assertNull(live.currentToken())
        assertFalse(live.publish(permit, s, detection, ready.analysis, ready.issue))
        live.invalidate(2); assertNull(live.extract(s))
    }
    @Test fun signalLeavesTokenNodesAndOriginalEvidenceFieldsUnchangedAndRedacted() = runTest {
        val s = snapshot(); val withSignal = tree(s); val withoutSignal = tree(s, null)
        assertEquals(withoutSignal.analysis, withSignal.analysis.copy(videoSurfaceObserved = false))
        assertSame(s, withSignal.snapshot); assertEquals(s.token, withSignal.analysis.token)
        val rendered = withSignal.analysis.toString()
        assertFalse(rendered.contains("synthetic.fixture")); assertFalse(rendered.contains("structure"))
        assertFalse(rendered.contains("합성 화면")); assertFalse(rendered.contains("SurfaceView"))
    }
    private suspend fun skipAnalysis(visible: Boolean = true, body: String = "합성 광고 5초 후 건너뛰기"): Pair<PopupSnapshot, PopupAnalysis> {
        val s = snapshot(body = body).let { it.copy(nodes = it.nodes.toPersistentList().add(
            NodeSnapshot("0/1", "synthetic:skip", "android.widget.Button", "건너뛰기", null,
                true, true, ScreenBounds(650, 1_000, 850, 1_150), persistentSetOf(NodeAction.CLICK)))) }
        val f = frame(s).let { f -> f.copy(outlines = f.outlines.map {
            if (it.path == "0/0") it.copy(visible = visible) else it }) }
        val ready = tree(s, f)
        val d = PopupDetection(s.token, persistentSetOf(PopupSignal.NEW_WINDOW), true)
        return s to DecisionCandidateAttacher().attach(PopupEvent.Candidate(ready.snapshot, d), ready.analysis)
    }
    @Test fun mainB20ReadsAObservationTextCountdownAndTreeSkipAsSeparateSignals() = runTest {
        val (_, analysis) = skipAnalysis()
        val report = DecisionVideoAd.inspect(analysis)
        assertTrue(report.videoObserved); assertTrue(report.advertisementEvidence); assertTrue(report.isVideoAd)
        assertEquals(5, report.countdownSeconds); assertEquals(TextSource.ACCESSIBILITY_TREE, report.countdownSource)
        assertEquals(analysis.closeCandidates.filter { it.role == ButtonRole.SKIP }.map { it.id }, report.skipCandidateIds)
        assertEquals(1, report.skipCandidateIds.size)
        val decision = DecisionActionPolicy().decide(analysis, DecisionContext(analysis.token, null, null, false, true))
        assertEquals(ActionAuthorization.NEEDS_CONFIRMATION, decision.authorization)
    }
    @Test fun mainB20DoesNotInferSurfaceFromAdTextOrCountdownFromElapsedTime() = runTest {
        val (_, hidden) = skipAnalysis(visible = false)
        val report = DecisionVideoAd.inspect(hidden)
        assertFalse(report.videoObserved); assertTrue(report.advertisementEvidence); assertFalse(report.isVideoAd)
        val (_, withoutTime) = skipAnalysis(body = "합성 광고")
        assertTrue(DecisionVideoAd.inspect(withoutTime).videoObserved)
        assertNull(DecisionVideoAd.inspect(withoutTime).countdownSeconds)
    }
    @Test fun mainB20CannotPromoteRedactedDecisionEvidenceToVideoOrSkipPermission() = runTest {
        val (_, analysis) = skipAnalysis(body = "결제 안내")
        val report = DecisionVideoAd.inspect(analysis)
        assertFalse(report.videoObserved); assertFalse(report.advertisementEvidence); assertFalse(report.isVideoAd)
        assertTrue(report.skipCandidateIds.isEmpty())
        val decision = DecisionActionPolicy().decide(analysis, DecisionContext(analysis.token, null, null, false, true))
        assertEquals(ActionAuthorization.DENY, decision.authorization)
        assertTrue(SafetyReason.IMPORTANT_DECISION in decision.reasons)
    }
    @Test fun mainB19PolicyAndF9StillReturnNotExecutedForExplicitCurrentVideoSkip() = runTest {
        val (s, analysis) = skipAnalysis()
        val candidate = analysis.closeCandidates.single { it.role == ButtonRole.SKIP }
        var current: SnapshotToken? = s.token
        val source = object : SnapshotSource {
            override val events = emptyFlow<PopupEvent>()
            override fun currentToken() = current
        }
        val executor = ReviewOnlyChoiceExecutor { 1_000L }
        val handler = DecisionChoiceHandler(source, executor, { false }, { true })
        val choice = UserChoice(s.token, ChoiceKind.SKIP_ONCE, candidate.id, buttonMeaningConfirmed = true)
        val result = requireNotNull(handler.handle(choice, analysis))
        assertEquals(ActionStatus.NOT_EXECUTED, result.status); assertFalse(result.attempted)
        assertFalse(result.semanticSafetyConfirmedByUser); assertEquals(1, executor.reviews)
        val decision = DecisionActionPolicy().decide(analysis, DecisionContext(s.token, choice, null, false, true))
        val review = NonExecutingActionExecutor().inspect(kr.closeforyou.core.contract.ActionRequest(decision, candidate))
        assertFalse(review.productionReady)
        current = s.token.copy(screenVersion = 2)
        val stale = requireNotNull(handler.handle(choice, analysis))
        assertEquals(ActionStatus.STALE, stale.status); assertFalse(stale.attempted); assertEquals(1, executor.reviews)
    }

}
