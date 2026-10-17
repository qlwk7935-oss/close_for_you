package kr.closeforyou.synthetictest

/** Synthetic-only, memory-only geometry. Never stringify coordinates in test failures. */
class PatternRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun valid() = left in 0..10000 && top in 0..10000 && right in 1..10000 && bottom in 1..10000 && right > left && bottom > top
    fun contains(other: PatternRect) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
    fun shifted(x: Int, y: Int) = PatternRect(left + x, top + y, right + x, bottom + y)
    override fun toString() = "PatternRect(redacted)"
}
class PatternPlan(val windowId: Int, val decor: PatternRect, val content: PatternRect, val elevated: Boolean) {
    fun valid() = windowId >= 0 && decor.valid() && content.valid() && decor.width in 160..2400 && decor.height in 100..2400 &&
        decor.left in 0..10000 && decor.top in 0..10000 && decor.contains(content) &&
        content.width >= 160 && content.height >= 100
    override fun toString() = "PatternPlan(redacted)"
}
enum class PatternCode { TRANSLATION_VERIFIED, PLAN_INVALID, BUFFER_LIMIT, MARKERS_MISSING_OR_AMBIGUOUS,
    NON_UNIT_OR_INCONSISTENT_MAPPING, DECOR_OUTSIDE_BUFFER, CONTENT_NOT_SYNTHETIC,
    OWNED_WINDOW_MISMATCH, STALE_OR_NOT_ALLOWED, INSPECTION_FAILED, INSPECTION_INTERRUPTED, NOT_OBSERVED }
enum class DecorBufferRelation { EXACT, INSET_ALL, INSET_SOME, UNKNOWN }
enum class OuterPixels { NONE, TRANSPARENT, NONTRANSPARENT, MIXED, UNKNOWN }
enum class LandmarkCheck { NOT_VERIFIED, ALIGNED }
enum class ContentScan { NOT_RUN, COMPLETE, INTERRUPTED }
enum class PatternColorSpace { SRGB, OTHER, UNKNOWN }
enum class PatternRenderHint { HARDWARE, SOFTWARE, CONTENT_OUTLINE, ANCESTOR_OUTLINE, DECOR_OUTLINE,
    ANCESTOR_CLIP_CHILDREN, ANCESTOR_CLIP_PADDING, CONTENT_FOREGROUND, ANCESTOR_FOREGROUND }
/** A bounded set of twelve fixed codes, never positions, values, counts or samples. */
enum class ContentMismatch {
    CORNER_ALPHA_TRANSPARENT, CORNER_ALPHA_PARTIAL, CORNER_OPAQUE_NEAR_WHITE, CORNER_OPAQUE_OTHER,
    EDGE_ALPHA_TRANSPARENT, EDGE_ALPHA_PARTIAL, EDGE_OPAQUE_NEAR_WHITE, EDGE_OPAQUE_OTHER,
    INTERIOR_ALPHA_TRANSPARENT, INTERIOR_ALPHA_PARTIAL, INTERIOR_OPAQUE_NEAR_WHITE, INTERIOR_OPAQUE_OTHER,
}
data class PatternReport(val code: PatternCode, val decorBuffer: DecorBufferRelation = DecorBufferRelation.UNKNOWN,
    val outer: OuterPixels = OuterPixels.UNKNOWN,
    val landmarks: LandmarkCheck = LandmarkCheck.NOT_VERIFIED,
    val contentScan: ContentScan = ContentScan.NOT_RUN,
    val mismatches: Set<ContentMismatch> = emptySet(),
    val sourceColorSpace: PatternColorSpace = PatternColorSpace.UNKNOWN,
    val copyColorSpace: PatternColorSpace = PatternColorSpace.UNKNOWN,
)

/** No estimated crop, resizing, OCR or transform export. Validates a translation only, then forgets it. */
object PatternMapping {
    const val WHITE = -1
    const val SIDE = 13
    const val MAX_PIXELS = 4_000_000L
    val colors = intArrayOf(0xffdf2323.toInt(), 0xff23b947.toInt(), 0xff234fd7.toInt(), 0xffbf23c9.toInt())
    fun markers(width: Int, height: Int): List<PatternRect> = listOf(
        PatternRect(16, 16, 16 + SIDE, 16 + SIDE),
        PatternRect(width - 42, 23, width - 42 + SIDE, 23 + SIDE),
        PatternRect(29, height - 38, 29 + SIDE, height - 38 + SIDE),
        PatternRect(width - 53, height - 47, width - 53 + SIDE, height - 47 + SIDE),
    )
    private fun near(a: Int, b: Int): Boolean = (a ushr 24) == 255 &&
        kotlin.math.abs((a shr 16 and 255) - (b shr 16 and 255)) <= 1 &&
        kotlin.math.abs((a shr 8 and 255) - (b shr 8 and 255)) <= 1 &&
        kotlin.math.abs((a and 255) - (b and 255)) <= 1

    // Diagnostic buckets only. Neither band nor near-white threshold exempts any pixel.
    private fun mismatch(x: Int, y: Int, width: Int, height: Int, p: Int): ContentMismatch {
        val band = minOf(32, width / 4, height / 4)
        val verticalEdge = x < band || x >= width - band
        val horizontalEdge = y < band || y >= height - band
        val zone = if (verticalEdge && horizontalEdge) 0 else if (verticalEdge || horizontalEdge) 1 else 2
        val alpha = p ushr 24
        val red = p shr 16 and 255; val green = p shr 8 and 255; val blue = p and 255
        val appearance = when {
            alpha == 0 -> 0
            alpha < 255 -> 1
            minOf(red, green, blue) >= 240 && maxOf(red, green, blue) - minOf(red, green, blue) <= 4 -> 2
            else -> 3
        }
        return ContentMismatch.entries[zone * 4 + appearance]
    }
    private fun mismatchCodes(mask: Int): Set<ContentMismatch> =
        ContentMismatch.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet()

    fun inspect(plan: PatternPlan, width: Int, height: Int, stillValid: () -> Boolean = { true }, pixel: (Int, Int) -> Int): PatternReport {
        if (!plan.valid()) return PatternReport(PatternCode.PLAN_INVALID)
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) return PatternReport(PatternCode.BUFFER_LIMIT)
        val left = IntArray(4) { width }; val top = IntArray(4) { height }
        val right = IntArray(4); val bottom = IntArray(4); val counts = IntArray(4)
        for (y in 0 until height) {
            if (!stillValid()) return PatternReport(PatternCode.INSPECTION_INTERRUPTED)
            for (x in 0 until width) {
                val p = pixel(x, y)
                for (i in colors.indices) if (near(p, colors[i])) {
                    counts[i]++
                    left[i] = minOf(left[i], x); top[i] = minOf(top[i], y)
                    right[i] = maxOf(right[i], x + 1); bottom[i] = maxOf(bottom[i], y + 1)
                }
            }
        }
        if (colors.indices.any { counts[it] != SIDE * SIDE || right[it] - left[it] != SIDE || bottom[it] - top[it] != SIDE }) {
            return PatternReport(PatternCode.MARKERS_MISSING_OR_AMBIGUOUS)
        }
        val markers = markers(plan.content.width, plan.content.height)
        val dx = left[0] - plan.content.left - markers[0].left
        val dy = top[0] - plan.content.top - markers[0].top
        if (colors.indices.any { left[it] - plan.content.left - markers[it].left != dx ||
                top[it] - plan.content.top - markers[it].top != dy }) {
            return PatternReport(PatternCode.NON_UNIT_OR_INCONSISTENT_MAPPING)
        }
        val decor = plan.decor.shifted(dx, dy)
        val content = plan.content.shifted(dx, dy)
        if (!PatternRect(0, 0, width, height).contains(decor)) return PatternReport(PatternCode.DECOR_OUTSIDE_BUFFER, landmarks = LandmarkCheck.ALIGNED)
        // Inspect all known content pixels within the existing deadline; do not stop at the first
        // mismatch or allocate per-pixel diagnostic objects. Even one mismatch still rejects it.
        var mismatchMask = 0
        for (y in 0 until content.height) {
            if (!stillValid()) return PatternReport(PatternCode.INSPECTION_INTERRUPTED,
                landmarks = LandmarkCheck.ALIGNED, contentScan = ContentScan.INTERRUPTED,
                mismatches = mismatchCodes(mismatchMask))
            for (x in 0 until content.width) {
                val index = markers.indexOfFirst { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
                val expected = if (index < 0) WHITE else colors[index]
                val actual = pixel(content.left + x, content.top + y)
                if (!near(actual, expected)) {
                    mismatchMask = mismatchMask or (1 shl mismatch(x, y, content.width, content.height, actual).ordinal)
                }
            }
        }
        if (mismatchMask != 0) return PatternReport(PatternCode.CONTENT_NOT_SYNTHETIC,
            landmarks = LandmarkCheck.ALIGNED, contentScan = ContentScan.COMPLETE,
            mismatches = mismatchCodes(mismatchMask))
        val relation = when {
            decor.left == 0 && decor.top == 0 && decor.right == width && decor.bottom == height -> DecorBufferRelation.EXACT
            decor.left > 0 && decor.top > 0 && decor.right < width && decor.bottom < height -> DecorBufferRelation.INSET_ALL
            else -> DecorBufferRelation.INSET_SOME
        }
        var transparent = false; var opaque = false
        for (y in 0 until height) {
            if (!stillValid()) return PatternReport(PatternCode.INSPECTION_INTERRUPTED,
                landmarks = LandmarkCheck.ALIGNED, contentScan = ContentScan.COMPLETE)
            for (x in 0 until width) {
                if (x >= decor.left && x < decor.right && y >= decor.top && y < decor.bottom) continue
                if (pixel(x, y) ushr 24 == 0) transparent = true else opaque = true
            }
        }
        val outer = when { transparent && opaque -> OuterPixels.MIXED; transparent -> OuterPixels.TRANSPARENT
            opaque -> OuterPixels.NONTRANSPARENT; else -> OuterPixels.NONE }
        return PatternReport(PatternCode.TRANSLATION_VERIFIED, relation, outer,
            landmarks = LandmarkCheck.ALIGNED, contentScan = ContentScan.COMPLETE)
    }
}
