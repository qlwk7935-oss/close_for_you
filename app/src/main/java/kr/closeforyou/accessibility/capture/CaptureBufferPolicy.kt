package kr.closeforyou.accessibility.capture

import kr.closeforyou.core.model.ScreenBounds

internal enum class BufferAxisRelation { MATCH, LARGER, SMALLER }
internal enum class BufferExtent { MATCH, BUFFER_LARGER, BUFFER_SMALLER, MIXED_MISMATCH }
internal enum class BufferMapping { EXACT_SIZE, UNPROVEN_WINDOW, UNPROVEN_DISPLAY, ROI_OUTSIDE_SOURCE }
internal enum class BufferMemoryCheck { WITHIN_LIMIT, INVALID_DIMENSIONS, SOURCE_LIMIT, ROI_LIMIT, LAYER_LIMIT, FORMAT_UNSUPPORTED }

/** Relations only: no pixel sizes, coordinates, insets, image, text or identities retained. */
internal data class BufferDiagnostic(
    val width: BufferAxisRelation,
    val height: BufferAxisRelation,
    val extent: BufferExtent,
    val displaySized: Boolean,
    val windowDimensionsSwapped: Boolean,
    val mapping: BufferMapping,
    val memory: BufferMemoryCheck,
    val released: Boolean = false,
)
internal data class BufferAssessment(val diagnostic: BufferDiagnostic, val failure: EvidenceIssue?)

/** Exact-size compatibility path only. Mismatches lack a public buffer-to-screen transform;
 * a larger, display-sized, or swapped buffer never authorizes resizing/cropping/rotation.
 * Source limits apply to ACTUAL metadata before Bitmap wrapping or CPU allocation. */
internal object CaptureBufferPolicy {
    const val MAX_SOURCE_PIXELS = 8_000_000L // Logical pixel cap; native allocation stride is not public.
    const val MAX_ROI_PIXELS = 2_000_000L // <=8 MB software OCR input.

    fun assess(width: Int, height: Int, layers: Int, supportedFormat: Boolean,
        window: ScreenBounds, display: ScreenBounds, region: ScreenBounds, windowCapture: Boolean): BufferAssessment {
        val source = if (windowCapture) window else display
        fun relation(actual: Int, expected: Int) = when {
            actual == expected -> BufferAxisRelation.MATCH
            actual > expected -> BufferAxisRelation.LARGER
            else -> BufferAxisRelation.SMALLER
        }
        val w = relation(width, source.width)
        val h = relation(height, source.height)
        val exact = w == BufferAxisRelation.MATCH && h == BufferAxisRelation.MATCH
        val extent = when {
            exact -> BufferExtent.MATCH
            width >= source.width && height >= source.height -> BufferExtent.BUFFER_LARGER
            width <= source.width && height <= source.height -> BufferExtent.BUFFER_SMALLER
            else -> BufferExtent.MIXED_MISMATCH
        }
        val memory = when {
            width <= 0 || height <= 0 -> BufferMemoryCheck.INVALID_DIMENSIONS
            // Int x Int promoted BEFORE multiplication; no bytes multiplication can overflow.
            width.toLong() * height > MAX_SOURCE_PIXELS -> BufferMemoryCheck.SOURCE_LIMIT
            PopupRegionResolver.area(region) > MAX_ROI_PIXELS -> BufferMemoryCheck.ROI_LIMIT
            layers != 1 -> BufferMemoryCheck.LAYER_LIMIT
            !supportedFormat -> BufferMemoryCheck.FORMAT_UNSUPPORTED
            else -> BufferMemoryCheck.WITHIN_LIMIT
        }
        val mapping = when {
            !PopupRegionResolver.contains(source, region) -> BufferMapping.ROI_OUTSIDE_SOURCE
            exact -> BufferMapping.EXACT_SIZE
            windowCapture -> BufferMapping.UNPROVEN_WINDOW
            else -> BufferMapping.UNPROVEN_DISPLAY
        }
        val diagnostic = BufferDiagnostic(w, h, extent,
            width == display.width && height == display.height,
            !exact && width == window.height && height == window.width,
            mapping, memory)
        val failure = when {
            memory != BufferMemoryCheck.WITHIN_LIMIT -> EvidenceIssue.MEMORY_LIMIT
            mapping != BufferMapping.EXACT_SIZE -> EvidenceIssue.REGION_UNCERTAIN
            else -> null
        }
        return BufferAssessment(diagnostic, failure)
    }
}
