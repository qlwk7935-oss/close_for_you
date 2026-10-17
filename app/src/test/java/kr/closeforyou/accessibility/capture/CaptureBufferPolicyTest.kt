package kr.closeforyou.accessibility.capture

import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.*
import org.junit.Test

class CaptureBufferPolicyTest {
    private val window = ScreenBounds(40, 900, 1040, 1320)
    private val display = ScreenBounds(0, 0, 1080, 2400)
    private fun assess(width: Int, height: Int, layers: Int = 1, supported: Boolean = true,
        region: ScreenBounds = window, windowCapture: Boolean = true) = CaptureBufferPolicy.assess(
        width, height, layers, supported, window, display, region, windowCapture)

    @Test fun exactDimensionsKeepExistingPathWithoutTransformOrResize() {
        val result = assess(1000, 420)
        assertNull(result.failure)
        assertEquals(BufferMapping.EXACT_SIZE, result.diagnostic.mapping)
        assertEquals(BufferExtent.MATCH, result.diagnostic.extent)
        assertEquals(BufferMemoryCheck.WITHIN_LIMIT, result.diagnostic.memory)
        assertFalse(result.diagnostic.released) // Only a real close can set the trace flag.
    }
    @Test fun oneAxisMismatchIsNeverPaddedOrTrimmed() {
        val widthOnly = assess(1001, 420)
        assertEquals(BufferAxisRelation.LARGER, widthOnly.diagnostic.width)
        assertEquals(BufferAxisRelation.MATCH, widthOnly.diagnostic.height)
        assertEquals(BufferExtent.BUFFER_LARGER, widthOnly.diagnostic.extent)
        assertEquals(EvidenceIssue.REGION_UNCERTAIN, widthOnly.failure)
        val heightOnly = assess(1000, 419)
        assertEquals(BufferAxisRelation.MATCH, heightOnly.diagnostic.width)
        assertEquals(BufferAxisRelation.SMALLER, heightOnly.diagnostic.height)
        assertEquals(BufferExtent.BUFFER_SMALLER, heightOnly.diagnostic.extent)
        assertEquals(EvidenceIssue.REGION_UNCERTAIN, heightOnly.failure)
    }
    @Test fun largerSmallerAndMixedBuffersHaveNoInferredCropOrigin() {
        for ((size, expected) in listOf((1100 to 500) to BufferExtent.BUFFER_LARGER,
                (900 to 400) to BufferExtent.BUFFER_SMALLER, (1100 to 400) to BufferExtent.MIXED_MISMATCH)) {
            val result = assess(size.first, size.second)
            assertEquals(expected, result.diagnostic.extent)
            assertEquals(BufferMapping.UNPROVEN_WINDOW, result.diagnostic.mapping)
            assertEquals(EvidenceIssue.REGION_UNCERTAIN, result.failure)
        }
    }
    @Test fun displaySizedWindowBufferIsDiagnosticOnlyNeverFullscreenFallback() {
        val result = assess(1080, 2400)
        assertTrue(result.diagnostic.displaySized)
        assertEquals(BufferMemoryCheck.WITHIN_LIMIT, result.diagnostic.memory)
        assertEquals(BufferMapping.UNPROVEN_WINDOW, result.diagnostic.mapping)
        assertEquals(EvidenceIssue.REGION_UNCERTAIN, result.failure)
    }
    @Test fun swappedWindowDimensionsDoNotAuthorizeRotation() {
        val result = assess(420, 1000)
        assertTrue(result.diagnostic.windowDimensionsSwapped)
        assertEquals(BufferExtent.MIXED_MISMATCH, result.diagnostic.extent)
        assertEquals(EvidenceIssue.REGION_UNCERTAIN, result.failure)
    }
    @Test fun actualSourceAreaLimitHasPriorityOverGeometryMismatchAndCannotOverflow() {
        val tooLarge = assess(4001, 2000)
        assertEquals(BufferMemoryCheck.SOURCE_LIMIT, tooLarge.diagnostic.memory)
        assertEquals(EvidenceIssue.MEMORY_LIMIT, tooLarge.failure)
        val extreme = assess(Int.MAX_VALUE, Int.MAX_VALUE)
        assertEquals(BufferMemoryCheck.SOURCE_LIMIT, extreme.diagnostic.memory)
        assertEquals(EvidenceIssue.MEMORY_LIMIT, extreme.failure)
    }
    @Test fun exactLimitIsAcceptedOnlyForSupportedSingleLayerBufferAndBoundedRoi() {
        val largeWindow = ScreenBounds(0, 0, 4000, 2000)
        val result = CaptureBufferPolicy.assess(4000, 2000, 1, true, largeWindow,
            ScreenBounds(0, 0, 5000, 3000), ScreenBounds(100, 100, 1100, 500), true)
        assertNull(result.failure)
        assertEquals(BufferMemoryCheck.WITHIN_LIMIT, result.diagnostic.memory)
        assertEquals(BufferMemoryCheck.LAYER_LIMIT, assess(1000, 420, layers = 2).diagnostic.memory)
        assertEquals(EvidenceIssue.MEMORY_LIMIT, assess(1000, 420, layers = 2).failure)
        assertEquals(BufferMemoryCheck.FORMAT_UNSUPPORTED, assess(1000, 420, supported = false).diagnostic.memory)
        assertEquals(EvidenceIssue.MEMORY_LIMIT, assess(1000, 420, supported = false).failure)
    }
    @Test fun oversizedRoiAndInvalidNativeDimensionsAreRejectedBeforeAllocation() {
        val hugeRoi = ScreenBounds(0, 0, 2000, 2000)
        val result = CaptureBufferPolicy.assess(2000, 2000, 1, true, hugeRoi, display, hugeRoi, true)
        assertEquals(BufferMemoryCheck.ROI_LIMIT, result.diagnostic.memory)
        assertEquals(EvidenceIssue.MEMORY_LIMIT, result.failure)
        for (size in listOf(0 to 420, 1000 to -1)) {
            assertEquals(BufferMemoryCheck.INVALID_DIMENSIONS, assess(size.first, size.second).diagnostic.memory)
            assertEquals(EvidenceIssue.MEMORY_LIMIT, assess(size.first, size.second).failure)
        }
    }
    @Test fun exactSizeStillRequiresRoiContainment() {
        val result = assess(1000, 420, region = ScreenBounds(39, 900, 1040, 1320))
        assertEquals(BufferMapping.ROI_OUTSIDE_SOURCE, result.diagnostic.mapping)
        assertEquals(EvidenceIssue.REGION_UNCERTAIN, result.failure)
    }
    @Test fun displayApiAlsoRejectsMismatchesWithoutRescaling() {
        assertNull(assess(1080, 2400, windowCapture = false).failure)
        val result = assess(1000, 420, windowCapture = false)
        assertEquals(BufferMapping.UNPROVEN_DISPLAY, result.diagnostic.mapping)
        assertEquals(EvidenceIssue.REGION_UNCERTAIN, result.failure)
    }
    @Test fun diagnosticsNeverRetainRawPixelSizesOrCoordinates() {
        val diagnostic = assess(1080, 2400).diagnostic.toString()
        for (value in listOf("1080", "2400", "1320", "ScreenBounds", "Bitmap")) assertFalse(diagnostic.contains(value))
    }
}
