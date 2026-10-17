package kr.closeforyou.servicetest

import kotlin.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
import kr.closeforyou.synthetictest.*
import org.junit.Assert.*
import org.junit.Test

class RenderControlMatrixTest {
    private fun pass(mode: PatternMode) = PatternModeOutcome(mode, PatternModeResult.PASSED,
        PatternCode.TRANSLATION_VERIFIED, DecorBufferRelation.EXACT, ObservedElevation.NO,
        observationReady = true, landmarks = LandmarkCheck.ALIGNED, contentScan = ContentScan.COMPLETE,
        anchorDecor = DecorBufferRelation.EXACT,
        controlConfiguration = if (mode.control == SyntheticDialogControl.NONE) ControlConfiguration.NOT_REQUESTED else ControlConfiguration.VERIFIED,
        layout = if (mode.control == SyntheticDialogControl.NONE) LayoutRelation.UNKNOWN else LayoutRelation.SAME)
    private fun cornerFailure(mode: PatternMode) = pass(mode).copy(result = PatternModeResult.MAPPING_NOT_VERIFIED,
        pattern = PatternCode.CONTENT_NOT_SYNTHETIC, decor = DecorBufferRelation.UNKNOWN,
        mismatches = setOf(ContentMismatch.CORNER_ALPHA_PARTIAL))
    private fun sample() = RenderControlMatrix.MODES.map { mode ->
        if (mode in setOf(PatternMode.ZERO_ELEVATION, PatternMode.ROUND_CLIPPED)) cornerFailure(mode) else pass(mode)
    }
    private fun matrixWith(mode: PatternMode, change: (PatternModeOutcome) -> PatternModeOutcome) =
        RenderControlMatrix(sample().map { if (it.mode == mode) change(it) else it })
    private fun <T> immediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "UNEXPECTED_SUSPENSION" }.getOrThrow()
    }
    @Test fun squareSuccessDoesNotUpgradeEitherFailedRoundedMode() {
        val matrix = RenderControlMatrix(sample())
        assertTrue(matrix.squareControlsPassed)
        assertFalse(matrix.outcome(PatternMode.ZERO_ELEVATION).passed)
        assertFalse(matrix.outcome(PatternMode.ROUND_CLIPPED).passed)
        assertEquals(PatternModeResult.MAPPING_NOT_VERIFIED, matrix.outcome(PatternMode.ZERO_ELEVATION).result)
        assertEquals(RenderContrast.CORNER_ALPHA_FAILURE_REMOVED, matrix.contrast(PatternMode.ROUND_CLIPPED, PatternMode.ROUND_UNCLIPPED))
        assertEquals(RenderContrast.CORNER_ALPHA_FAILURE_REMOVED, matrix.contrast(PatternMode.ROUND_CLIPPED, PatternMode.SQUARE_CLIPPED))
        assertEquals(RenderContrast.NO_CONTENT_FAILURE, matrix.contrast(PatternMode.SQUARE_CLIPPED, PatternMode.SQUARE_UNCLIPPED))
    }
    @Test fun anySquareContentFailureFailsSquareExperiment() {
        for (mode in listOf(PatternMode.SQUARE_CLIPPED, PatternMode.SQUARE_UNCLIPPED)) {
            val matrix = matrixWith(mode) { cornerFailure(mode) }
            assertFalse(matrix.squareControlsPassed)
        }
    }
    @Test fun unverifiedControlConfigurationCannotSupportConclusion() {
        val m = matrixWith(PatternMode.ROUND_UNCLIPPED) { it.copy(controlConfiguration = ControlConfiguration.UNVERIFIED) }
        assertFalse(m.complete)
        assertFalse(m.squareControlsPassed)
        assertEquals(RenderContrast.INCONCLUSIVE, m.contrast(PatternMode.ROUND_CLIPPED, PatternMode.SQUARE_CLIPPED))
    }
    @Test fun differentLayoutsInvalidateSingleFactorComparison() {
        val m = matrixWith(PatternMode.SQUARE_CLIPPED) { it.copy(layout = LayoutRelation.DIFFERENT) }
        assertFalse(m.squareControlsPassed)
        assertEquals(RenderContrast.INCONCLUSIVE, m.contrast(PatternMode.ROUND_CLIPPED, PatternMode.ROUND_UNCLIPPED))
    }
    @Test fun incompleteCaptureOrScanCannotBeCalledObservedExperiment() {
        assertFalse(matrixWith(PatternMode.ZERO_ELEVATION) { it.copy(observationReady = false) }.complete)
        assertFalse(matrixWith(PatternMode.ROUND_CLIPPED) { it.copy(contentScan = ContentScan.INTERRUPTED) }.complete)
        assertFalse(matrixWith(PatternMode.ROUND_CLIPPED) { it.copy(landmarks = LandmarkCheck.NOT_VERIFIED) }.complete)
        assertFalse(matrixWith(PatternMode.SQUARE_CLIPPED) { it.copy(result = PatternModeResult.CLEANUP_FAILED) }.squareControlsPassed)
    }
    @Test fun interiorOrOtherMismatchCannotSupportCornerAlphaConclusion() {
        val m = matrixWith(PatternMode.ROUND_CLIPPED) { it.copy(mismatches = setOf(ContentMismatch.INTERIOR_OPAQUE_OTHER)) }
        assertEquals(RenderContrast.INCONCLUSIVE, m.contrast(PatternMode.ROUND_CLIPPED, PatternMode.SQUARE_CLIPPED))
    }
    @Test fun inconsistentPassedPayloadCannotUpgradeContentFailure() {
        val m = matrixWith(PatternMode.SQUARE_CLIPPED) { it.copy(mismatches = setOf(ContentMismatch.CORNER_ALPHA_PARTIAL)) }
        assertFalse(m.squareControlsPassed)
        assertEquals(RenderContrast.INCONCLUSIVE, m.contrast(PatternMode.ROUND_CLIPPED, PatternMode.SQUARE_CLIPPED))
    }
    @Test fun missingOrDuplicatedModesRemainIncomplete() {
        assertFalse(RenderControlMatrix(sample().dropLast(1)).complete)
        assertFalse(RenderControlMatrix(sample() + sample().last()).complete)
    }
    @Test fun allModesRunAfterFailureAndUnexpectedException() {
        val visited = mutableListOf<PatternMode>()
        var pauses = 0
        val matrix = immediate { RenderControlMatrix.run({ mode ->
            visited += mode
            if (mode == PatternMode.ROUND_CLIPPED) error("UNTRUSTED_DETAIL") else cornerFailure(mode)
        }, { pauses++ }) }
        assertEquals(RenderControlMatrix.MODES, visited)
        assertEquals(4, pauses)
        assertEquals(PatternModeResult.OBSERVATION_FAILED, matrix.outcome(PatternMode.ROUND_CLIPPED).result)
        assertFalse(matrix.squareControlsPassed)
    }
    @Test fun cancellationStopsOpeningFurtherWindows() {
        val visited = mutableListOf<PatternMode>()
        try {
            immediate { RenderControlMatrix.run({ mode -> visited += mode; throw CancellationException() }, {}) }
            fail("CANCELLATION_SWALLOWED")
        } catch (_: CancellationException) { assertEquals(listOf(PatternMode.ZERO_ELEVATION), visited) }
    }
    @Test fun ownedRelativeLayoutIgnoresScreenOriginButRejectsDifferentSize() {
        fun signature(offset: Int, width: Int = 240) = requireNotNull(PatternLayoutSignature.from(
            PatternPlan(7, PatternRect(offset, 200, offset + width, 380), PatternRect(offset + 10, 220, offset + width - 10, 370), false)))
        val a = signature(80); val b = signature(90); val different = signature(90, 250)
        assertEquals(LayoutRelation.SAME, a.compare(b))
        assertEquals(LayoutRelation.DIFFERENT, a.compare(different))
        assertEquals("PatternLayoutSignature(redacted)", a.toString())
        val reference = ControlLayoutReference()
        assertEquals(LayoutRelation.UNKNOWN, reference.observe(PatternMode.ZERO_ELEVATION, a))
        assertEquals(LayoutRelation.SAME, reference.observe(PatternMode.ROUND_CLIPPED, a))
        assertEquals(LayoutRelation.DIFFERENT, reference.observe(PatternMode.SQUARE_CLIPPED, different))
        reference.clear()
        assertEquals(LayoutRelation.SAME, reference.observe(PatternMode.SQUARE_CLIPPED, different))
    }
    @Test fun originalTwoModeRunnerStillRunsOnlyDefaultAndZero() {
        val visited = mutableListOf<PatternMode>()
        val comparison = immediate { PatternModeRunner.run({ mode -> visited += mode; cornerFailure(mode) }, {}) }
        assertEquals(listOf(PatternMode.DEFAULT_ELEVATION, PatternMode.ZERO_ELEVATION), visited)
        assertFalse(comparison.passed)
        assertEquals(ElevationEffect.INCONCLUSIVE, comparison.effect)
    }
}
