package kr.closeforyou.servicetest

import kr.closeforyou.synthetictest.*
import org.junit.Assert.*
import org.junit.Test

class PatternMappingTest {
    private val plan = PatternPlan(7, PatternRect(80, 200, 320, 380), PatternRect(90, 220, 310, 370), true)
    private fun inspect(width: Int = 280, height: Int = 220, originX: Int = 20, originY: Int = 20,
        stillValid: () -> Boolean = { true }, change: (Int, Int, Int) -> Int = { _, _, p -> p }): PatternReport {
        val markers = PatternMapping.markers(plan.content.width, plan.content.height)
        return PatternMapping.inspect(plan, width, height, stillValid) { x, y ->
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
    @Test fun classifiesAllTwelveBucketsWithoutRelaxingValidation() {
        val locations = listOf(30 to 40, 100 to 40, 100 to 100)
        val pixels = listOf(0x00ffffff, 0x80ffffff.toInt(), 0xfffcfcfc.toInt(), 0xff000000.toInt())
        val expected = listOf(
            listOf(ContentMismatch.CORNER_ALPHA_TRANSPARENT, ContentMismatch.CORNER_ALPHA_PARTIAL,
                ContentMismatch.CORNER_OPAQUE_NEAR_WHITE, ContentMismatch.CORNER_OPAQUE_OTHER),
            listOf(ContentMismatch.EDGE_ALPHA_TRANSPARENT, ContentMismatch.EDGE_ALPHA_PARTIAL,
                ContentMismatch.EDGE_OPAQUE_NEAR_WHITE, ContentMismatch.EDGE_OPAQUE_OTHER),
            listOf(ContentMismatch.INTERIOR_ALPHA_TRANSPARENT, ContentMismatch.INTERIOR_ALPHA_PARTIAL,
                ContentMismatch.INTERIOR_OPAQUE_NEAR_WHITE, ContentMismatch.INTERIOR_OPAQUE_OTHER))
        for ((zone, point) in locations.withIndex()) for ((appearance, value) in pixels.withIndex()) {
            val result = inspect { x, y, p -> if (x == point.first && y == point.second) value else p }
            assertEquals(PatternCode.CONTENT_NOT_SYNTHETIC, result.code)
            assertEquals(setOf(expected[zone][appearance]), result.mismatches)
            assertEquals(LandmarkCheck.ALIGNED, result.landmarks)
            assertEquals(ContentScan.COMPLETE, result.contentScan)
            assertEquals(DecorBufferRelation.UNKNOWN, result.decorBuffer)
            assertEquals(OuterPixels.UNKNOWN, result.outer)
        }
    }
    @Test fun collectsEarlyAndLateMismatchKindsInsteadOfReturningFirstPixel() {
        val report = inspect { x, y, p -> when {
            x == 30 && y == 40 -> 0x80ffffff.toInt()
            x == 100 && y == 100 -> 0xff000000.toInt()
            x == 249 && y == 189 -> 0xfffcfcfc.toInt()
            else -> p
        } }
        assertEquals(PatternCode.CONTENT_NOT_SYNTHETIC, report.code)
        assertEquals(setOf(ContentMismatch.CORNER_ALPHA_PARTIAL, ContentMismatch.INTERIOR_OPAQUE_OTHER,
            ContentMismatch.CORNER_OPAQUE_NEAR_WHITE), report.mismatches)
    }
    @Test fun originalOneChannelToleranceIsStillTheAcceptanceBoundary() {
        assertEquals(PatternCode.TRANSLATION_VERIFIED,
            inspect { x, y, p -> if (x == 100 && y == 100) 0xfffefefe.toInt() else p }.code)
        val outside = inspect { x, y, p -> if (x == 100 && y == 100) 0xfffdfdfd.toInt() else p }
        assertEquals(PatternCode.CONTENT_NOT_SYNTHETIC, outside.code)
        assertEquals(setOf(ContentMismatch.INTERIOR_OPAQUE_NEAR_WHITE), outside.mismatches)
    }
    @Test fun slightlyTransparentWhiteIsNotAcceptedAsOpaqueWhite() {
        val report = inspect { x, y, p -> if (x == 100 && y == 100) 0xfeffffff.toInt() else p }
        assertEquals(PatternCode.CONTENT_NOT_SYNTHETIC, report.code)
        assertEquals(setOf(ContentMismatch.INTERIOR_ALPHA_PARTIAL), report.mismatches)
    }
    @Test fun interruptedContentScanKeepsOnlyBoundedPartialCodesAndNeverPasses() {
        var checks = 0
        val report = inspect(stillValid = { ++checks < 223 }) { x, y, p ->
            if (x == 30 && y == 40) 0x00ffffff else p
        }
        assertEquals(PatternCode.INSPECTION_INTERRUPTED, report.code)
        assertEquals(LandmarkCheck.ALIGNED, report.landmarks)
        assertEquals(ContentScan.INTERRUPTED, report.contentScan)
        assertEquals(setOf(ContentMismatch.CORNER_ALPHA_TRANSPARENT), report.mismatches)
    }
    @Test fun markerFailureDoesNotPretendToHaveScannedAlignedContent() {
        val report = inspect { _, _, p -> if (p == PatternMapping.colors[0]) PatternMapping.WHITE else p }
        assertEquals(LandmarkCheck.NOT_VERIFIED, report.landmarks)
        assertEquals(ContentScan.NOT_RUN, report.contentScan)
        assertTrue(report.mismatches.isEmpty())
    }
    @Test fun verifiedContentHasNoMismatchCodes() {
        val report = inspect()
        assertEquals(LandmarkCheck.ALIGNED, report.landmarks)
        assertEquals(ContentScan.COMPLETE, report.contentScan)
        assertTrue(report.mismatches.isEmpty())
    }

}
