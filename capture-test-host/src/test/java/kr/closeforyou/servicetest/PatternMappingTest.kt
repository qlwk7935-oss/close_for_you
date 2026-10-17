package kr.closeforyou.servicetest

import kr.closeforyou.synthetictest.*
import org.junit.Assert.*
import org.junit.Test

class PatternMappingTest {
    private val plan = PatternPlan(7, PatternRect(80, 200, 320, 380), PatternRect(90, 220, 310, 370), true)
    private fun inspect(width: Int = 280, height: Int = 220, originX: Int = 20, originY: Int = 20,
        change: (Int, Int, Int) -> Int = { _, _, p -> p }): PatternReport {
        val markers = PatternMapping.markers(plan.content.width, plan.content.height)
        return PatternMapping.inspect(plan, width, height) { x, y ->
            val localX = x - originX - (plan.content.left - plan.decor.left)
            val localY = y - originY - (plan.content.top - plan.decor.top)
            val marker = markers.indexOfFirst { localX in it.left until it.right && localY in it.top until it.bottom }
            val pixel = when {
                localX in 0 until plan.content.width && localY in 0 until plan.content.height ->
                    if (marker < 0) PatternMapping.WHITE else PatternMapping.colors[marker]
                else -> 0
            }
            change(x, y, pixel)
        }
    }
    @Test fun verifiesTranslatedContentWithUnequalScreenAndBufferOrigins() {
        val result = inspect()
        assertEquals(PatternCode.TRANSLATION_VERIFIED, result.code)
        assertEquals(DecorBufferRelation.INSET_ALL, result.decorBuffer)
        assertEquals(OuterPixels.TRANSPARENT, result.outer)
    }
    @Test fun verifiesExactDecorWithoutInventingPadding() {
        val result = inspect(240, 180, 0, 0)
        assertEquals(PatternCode.TRANSLATION_VERIFIED, result.code)
        assertEquals(DecorBufferRelation.EXACT, result.decorBuffer)
        assertEquals(OuterPixels.NONE, result.outer)
    }
    @Test fun acceptsAsymmetricVerifiedInsetWithoutAssumingCenter() {
        assertEquals(PatternCode.TRANSLATION_VERIFIED, inspect(originX = 3, originY = 9).code)
    }
    @Test fun missingMarkerIsNotAValidTransform() {
        assertEquals(PatternCode.MARKERS_MISSING_OR_AMBIGUOUS, inspect { _, _, p ->
            if (p == PatternMapping.colors[1]) PatternMapping.WHITE else p
        }.code)
    }
    @Test fun duplicateMarkerIsAmbiguous() {
        assertEquals(PatternCode.MARKERS_MISSING_OR_AMBIGUOUS, inspect { x, y, p ->
            if (x in 0..12 && y in 0..12) PatternMapping.colors[0] else p
        }.code)
    }
    @Test fun movingOneMarkerCannotProveUniformTranslation() {
        // Swap distinct marker identities; sizes/color counts survive but relative positions do not.
        assertEquals(PatternCode.NON_UNIT_OR_INCONSISTENT_MAPPING, inspect { _, _, p -> when (p) {
            PatternMapping.colors[0] -> PatternMapping.colors[1]
            PatternMapping.colors[1] -> PatternMapping.colors[0]
            else -> p
        } }.code)
    }
    @Test fun scaledMarkersAreRejected() {
        assertEquals(PatternCode.MARKERS_MISSING_OR_AMBIGUOUS, inspect { x, _, p ->
            if (x == 46 && p == PatternMapping.colors[0]) PatternMapping.WHITE else p
        }.code)
    }
    @Test fun correctMarkersDoNotAuthorizeUnknownContent() {
        assertEquals(PatternCode.CONTENT_NOT_SYNTHETIC, inspect { x, y, p -> if (x == 100 && y == 100) 0xff000000.toInt() else p }.code)
    }
    @Test fun decorOutsideBufferIsRejectedWithoutClamping() {
        assertEquals(PatternCode.DECOR_OUTSIDE_BUFFER, inspect(240, 180, -1, -1).code)
    }
    @Test fun sourceMemoryLimitIsCheckedBeforePixels() {
        val result = PatternMapping.inspect(plan, Int.MAX_VALUE, Int.MAX_VALUE) { _, _ -> error("PIXELS_MUST_NOT_BE_READ") }
        assertEquals(PatternCode.BUFFER_LIMIT, result.code)
    }
    @Test fun invalidOwnedPlanIsRejectedBeforePixels() {
        val invalid = PatternPlan(-1, plan.decor, plan.content, true)
        assertEquals(PatternCode.PLAN_INVALID, PatternMapping.inspect(invalid, 280, 220) { _, _ -> error("PIXELS_MUST_NOT_BE_READ") }.code)
    }
    @Test fun invalidWrappedCoordinatePlanIsRejected() {
        val invalid = PatternPlan(7, plan.decor, PatternRect(Int.MAX_VALUE, 220, -100, 370), true)
        assertFalse(invalid.valid())
    }
    @Test fun cancelledOrExpiredInspectionCannotVerifyMapping() {
        assertEquals(PatternCode.INSPECTION_INTERRUPTED,
            PatternMapping.inspect(plan, 280, 220, stillValid = { false }) { _, _ -> error("PIXELS_MUST_NOT_BE_READ") }.code)
    }
    @Test fun fixedReportsAndRedactedGeometryContainNoCoordinates() {
        assertEquals("PatternPlan(redacted)", plan.toString())
        assertEquals("PatternRect(redacted)", plan.content.toString())
        assertEquals("TRANSLATION_VERIFIED", DiagnosticCode.safe(inspect().code.name))
    }
    @Test fun nonTransparentOuterPixelsAreEvidenceNotAnInsetProof() {
        val report = inspect { _, _, p -> if (p == 0) 0x30000000 else p }
        assertEquals(PatternCode.TRANSLATION_VERIFIED, report.code)
        assertEquals(OuterPixels.NONTRANSPARENT, report.outer)
    }
}
