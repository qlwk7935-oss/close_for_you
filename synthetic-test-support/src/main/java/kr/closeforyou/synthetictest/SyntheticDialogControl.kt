package kr.closeforyou.synthetictest

/** Host-only experimental factors. NONE preserves the original theme and clipping. */
enum class SyntheticDialogControl(val rounded: Boolean, val clipped: Boolean) {
    NONE(false, false), ROUND_CLIPPED(true, true), ROUND_UNCLIPPED(true, false),
    SQUARE_CLIPPED(false, true), SQUARE_UNCLIPPED(false, false);
    val radius get() = if (rounded) 8f else 0f // fixed synthetic radius; never inferred from screenshot
}
enum class ControlConfiguration { NOT_REQUESTED, VERIFIED, UNVERIFIED }
enum class ControlIssue { NONE, BACKGROUND_IDENTITY, BACKGROUND_SHAPE, BACKGROUND_OPACITY, CORNER_RADIUS,
    DECOR_OUTLINE, OUTLINE_PROVIDER, ELEVATION, CONTENT_OUTLINE, ANCESTOR_OUTLINE, ANCESTOR_UNCONFIRMED }
enum class LayoutRelation { SAME, DIFFERENT, UNKNOWN }

/** Owned synthetic layout only, ephemeral. No absolute screen origin or public measurements. */
class PatternLayoutSignature private constructor(private val values: IntArray) {
    fun compare(other: PatternLayoutSignature): LayoutRelation =
        if (values.contentEquals(other.values)) LayoutRelation.SAME else LayoutRelation.DIFFERENT
    override fun toString() = "PatternLayoutSignature(redacted)"
    companion object {
        fun from(plan: PatternPlan): PatternLayoutSignature? = if (!plan.valid()) null else PatternLayoutSignature(intArrayOf(
            plan.decor.width, plan.decor.height, plan.content.width, plan.content.height,
            plan.content.left - plan.decor.left, plan.content.top - plan.decor.top))
    }
}
