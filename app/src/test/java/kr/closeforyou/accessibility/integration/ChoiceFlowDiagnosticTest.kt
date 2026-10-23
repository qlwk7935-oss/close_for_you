package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.contract.PopupClassifier
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

class ChoiceFlowDiagnosticTest {
    private val points = mutableListOf<ChoiceFlowPoint>()
    private val diagnostic = ChoiceFlowDiagnostic { point, _, _ -> points += point }
    private val analysis = MockPopupFixtures.notice().analysis
    private val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE)
    private class Presenter : ChoicePromptPresenter {
        var calls = 0
        var failure: Exception? = null
        override val isShowing = false
        override val windowState = OverlayWindowState.HIDDEN
        override fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult {
            calls++; failure?.let { throw it }; return PromptShowResult.SHOWN
        }
        override fun dismiss() = Unit
    }
    @Test fun classifierReportsOnlyAfterTheRealDelegateCompletes() = runTest {
        var calls = 0
        val delegate = object : PopupClassifier {
            override suspend fun classify(analysis: PopupAnalysis): PopupClassification {
                assertTrue(points.isEmpty()); calls++; return PopupClassification.NOTICE
            }
        }
        assertEquals(PopupClassification.NOTICE, diagnosedClassifier(delegate, diagnostic).classify(analysis))
        assertEquals(1, calls); assertEquals(listOf(ChoiceFlowPoint.CLASSIFIED), points)
    }
    @Test fun cancelledClassificationIsNotReportedAsCompletedAndKeepsOriginalCancellation() = runTest {
        val original = CancellationException("SYNTHETIC_SECRET")
        val delegate = object : PopupClassifier {
            override suspend fun classify(analysis: PopupAnalysis): PopupClassification = throw original
        }
        try { diagnosedClassifier(delegate, diagnostic).classify(analysis); fail() }
        catch (failure: CancellationException) { assertSame(original, failure) }
        assertTrue(points.isEmpty())
    }
    @Test fun exceptionalClassificationIsNotReportedAsCompleted() = runTest {
        val original = IllegalStateException("SYNTHETIC_SECRET")
        val delegate = object : PopupClassifier {
            override suspend fun classify(analysis: PopupAnalysis): PopupClassification = throw original
        }
        try { diagnosedClassifier(delegate, diagnostic).classify(analysis); fail() }
        catch (failure: IllegalStateException) { assertSame(original, failure) }
        assertTrue(points.isEmpty())
    }
    @Test fun presentExceptionIsDistinctFromReturnedFailedAndIsNotReplaced() {
        val p = Presenter(); val original = IllegalStateException("SYNTHETIC_SECRET"); p.failure = original
        try { diagnosedPresenter(p, diagnostic).present(prompt) {}; fail() }
        catch (failure: IllegalStateException) { assertSame(original, failure) }
        assertEquals(listOf(ChoiceFlowPoint.PRESENT_REQUESTED, ChoiceFlowPoint.PRESENT_EXCEPTION), points)
        assertEquals(1, p.calls)
    }
    @Test fun presentCancellationRemainsCancellationEvenWhenReportingFails() {
        val p = Presenter(); val original = CancellationException("SYNTHETIC_SECRET"); p.failure = original
        val broken = ChoiceFlowDiagnostic { _, _, _ -> throw IllegalStateException("SINK_SECRET") }
        try { diagnosedPresenter(p, broken).present(prompt) {}; fail() }
        catch (failure: CancellationException) { assertSame(original, failure) }
        assertEquals(1, p.calls)
    }
    @Test fun cancelledPresentationIsNotLoggedAsShownOrReturnedFailed() {
        val p = Presenter(); p.failure = CancellationException("SYNTHETIC_SECRET")
        try { diagnosedPresenter(p, diagnostic).present(prompt) {}; fail() }
        catch (_: CancellationException) { }
        assertEquals(listOf(ChoiceFlowPoint.PRESENT_REQUESTED, ChoiceFlowPoint.PRESENT_CANCELLED), points)
    }
    @Test fun releaseNullPortReturnsOriginalDelegates() {
        val p = Presenter()
        val classifier = object : PopupClassifier {
            override suspend fun classify(analysis: PopupAnalysis) = PopupClassification.NOTICE
        }
        assertSame(p, diagnosedPresenter(p, null)); assertSame(classifier, diagnosedClassifier(classifier, null))
    }
    @Test fun sinkCancellationCannotChangeReturnedPresentationResult() {
        val p = Presenter()
        val broken = ChoiceFlowDiagnostic { _, _, _ -> throw CancellationException("SINK_SECRET") }
        assertEquals(PromptShowResult.SHOWN, diagnosedPresenter(p, broken).present(prompt) {})
        assertEquals(1, p.calls)
    }
    @Test fun sinkExceptionCannotChangeReturnedClassification() = runTest {
        val classifier = object : PopupClassifier {
            override suspend fun classify(analysis: PopupAnalysis) = PopupClassification.NOTICE
        }
        val broken = ChoiceFlowDiagnostic { _, _, _ -> throw IllegalStateException("SINK_SECRET") }
        assertEquals(PopupClassification.NOTICE, diagnosedClassifier(classifier, broken).classify(analysis))
    }
    @Test fun knownAndUnknownInvalidationCausesStaySeparateFixedEnums() {
        val received = mutableListOf<ChoiceInvalidationCause>()
        val d = ChoiceFlowDiagnostic { point, detail, cause ->
            assertEquals(ChoiceFlowPoint.INVALIDATED, point)
            assertEquals(ChoiceFlowDetail.SERVICE_BOUNDARY, detail)
            received += cause
        }
        ChoiceInvalidationCause.entries.forEach { d.record(ChoiceFlowPoint.INVALIDATED, ChoiceFlowDetail.SERVICE_BOUNDARY, it) }
        assertEquals(ChoiceInvalidationCause.entries.toList(), received)
    }
    @Test fun diagnosticRepresentationNeverIncludesTheSinkOrObservedPayload() {
        assertEquals("ChoiceFlowDiagnostic(redacted)", diagnostic.toString())
        diagnostic.record(ChoiceFlowPoint.CLASSIFIED)
        assertEquals(listOf(ChoiceFlowPoint.CLASSIFIED), points)
    }
}
