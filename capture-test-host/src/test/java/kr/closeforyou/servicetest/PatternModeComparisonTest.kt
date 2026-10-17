package kr.closeforyou.servicetest

import kotlin.coroutines.*
import kotlin.coroutines.cancellation.CancellationException
import kr.closeforyou.synthetictest.*
import org.junit.Assert.*
import org.junit.Test

class PatternModeComparisonTest {
    private fun passed(mode: PatternMode, decor: DecorBufferRelation = DecorBufferRelation.EXACT) =
        PatternModeOutcome(mode, PatternModeResult.PASSED, PatternCode.TRANSLATION_VERIFIED, decor,
            if (mode == PatternMode.DEFAULT_ELEVATION) ObservedElevation.YES else ObservedElevation.NO)
    private fun <T> immediate(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(result: Result<T>) { outcome = result }
        })
        return checkNotNull(outcome) { "UNEXPECTED_SUSPENSION" }.getOrThrow()
    }
    @Test fun defaultContentFailureStillObservesZeroThenFailsWithInconclusiveComparison() {
        val visited = mutableListOf<PatternMode>()
        var pauses = 0
        val comparison = immediate { PatternModeRunner.run({ mode ->
            visited += mode
            if (mode == PatternMode.DEFAULT_ELEVATION) PatternModeOutcome(mode,
                PatternModeResult.MAPPING_NOT_VERIFIED, PatternCode.CONTENT_NOT_SYNTHETIC)
            else passed(mode)
        }, { pauses++ }) }
        assertEquals(listOf(PatternMode.DEFAULT_ELEVATION, PatternMode.ZERO_ELEVATION), visited)
        assertEquals(1, pauses)
        assertFalse(comparison.passed)
        assertEquals(PatternModeResult.MAPPING_NOT_VERIFIED, comparison.normal.result)
        assertEquals(PatternModeResult.PASSED, comparison.flat.result)
        assertEquals(ElevationEffect.INCONCLUSIVE, comparison.effect)
    }
    @Test fun unknownRelationsFromTwoFailuresNeverBecomeNoRelationChange() {
        val c = PatternModeComparison(PatternModeOutcome(PatternMode.DEFAULT_ELEVATION, PatternModeResult.OBSERVATION_TIMEOUT),
            PatternModeOutcome(PatternMode.ZERO_ELEVATION, PatternModeResult.SESSION_NOT_STARTED))
        assertFalse(c.passed)
        assertEquals(ElevationEffect.INCONCLUSIVE, c.effect)
    }
    @Test fun unexpectedDefaultExceptionIsSanitizedAndDoesNotSkipZero() {
        val visited = mutableListOf<PatternMode>()
        val c = immediate { PatternModeRunner.run({ mode ->
            visited += mode
            if (mode == PatternMode.DEFAULT_ELEVATION) error("UNTRUSTED_DETAIL") else passed(mode)
        }, {}) }
        assertEquals(2, visited.size)
        assertEquals(PatternModeResult.OBSERVATION_FAILED, c.normal.result)
        assertFalse(c.toString().contains("UNTRUSTED_DETAIL"))
        assertFalse(c.passed)
    }
    @Test fun unexpectedAssertionFailureStillAttemptsBothModesAndCannotPass() {
        val visited = mutableListOf<PatternMode>()
        val c = immediate { PatternModeRunner.run({ mode ->
            visited += mode
            if (mode == PatternMode.DEFAULT_ELEVATION) throw AssertionError("UNTRUSTED_DETAIL") else passed(mode)
        }, {}) }
        assertEquals(listOf(PatternMode.DEFAULT_ELEVATION, PatternMode.ZERO_ELEVATION), visited)
        assertEquals(PatternModeResult.OBSERVATION_FAILED, c.normal.result)
        assertFalse(c.passed)
    }
    @Test fun zeroFailureKeepsFinalTestFailed() {
        val c = PatternModeComparison(passed(PatternMode.DEFAULT_ELEVATION),
            PatternModeOutcome(PatternMode.ZERO_ELEVATION, PatternModeResult.MAPPING_NOT_VERIFIED))
        assertFalse(c.passed)
        assertEquals(ElevationEffect.INCONCLUSIVE, c.effect)
    }
    @Test fun comparisonRequiresBothVerifiedMappingsAndActualElevationContrast() {
        val c = PatternModeComparison(passed(PatternMode.DEFAULT_ELEVATION, DecorBufferRelation.INSET_ALL), passed(PatternMode.ZERO_ELEVATION))
        assertTrue(c.passed)
        assertEquals(ElevationEffect.CONSISTENT_WITH_ELEVATION_INSETS, c.effect)
    }
    @Test fun sameKnownBucketWithVerifiedElevationContrastHasOnlyNoRelationChange() {
        val c = PatternModeComparison(passed(PatternMode.DEFAULT_ELEVATION), passed(PatternMode.ZERO_ELEVATION))
        assertTrue(c.passed)
        assertEquals(ElevationEffect.NO_RELATION_CHANGE, c.effect)
    }
    @Test fun absentElevationContrastRemainsInconclusiveEvenWithVerifiedPixels() {
        val c = PatternModeComparison(passed(PatternMode.DEFAULT_ELEVATION).copy(elevated = ObservedElevation.NO), passed(PatternMode.ZERO_ELEVATION))
        assertTrue(c.passed)
        assertEquals(ElevationEffect.INCONCLUSIVE, c.effect)
    }
    @Test fun unknownGeometryOrWrongZeroElevationCannotPass() {
        val normal = passed(PatternMode.DEFAULT_ELEVATION)
        val flat = passed(PatternMode.ZERO_ELEVATION)
        assertFalse(PatternModeComparison(normal.copy(decor = DecorBufferRelation.UNKNOWN), flat).passed)
        assertFalse(PatternModeComparison(normal, flat.copy(elevated = ObservedElevation.YES)).passed)
    }
    @Test fun wrongModeReplyCannotBeUsedAsTheOtherObservation() {
        val c = immediate { PatternModeRunner.run({ passed(PatternMode.DEFAULT_ELEVATION) }, {}) }
        assertEquals(PatternModeResult.INCONSISTENT_RESULT, c.flat.result)
        assertFalse(c.passed)
    }
    @Test fun explicitCancellationStopsInsteadOfLaunchingAnotherDialog() {
        val visited = mutableListOf<PatternMode>()
        try {
            immediate { PatternModeRunner.run({ mode -> visited += mode; throw CancellationException() }, {}) }
            fail("CANCELLATION_SWALLOWED")
        } catch (_: CancellationException) {
            assertEquals(listOf(PatternMode.DEFAULT_ELEVATION), visited)
        }
    }
}
