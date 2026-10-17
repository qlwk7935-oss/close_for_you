package kr.closeforyou.servicetest

import kotlin.coroutines.cancellation.CancellationException
import kr.closeforyou.synthetictest.*

/** Keeps only an owned relative layout signature until the experiment ends; never prints it. */
internal class ControlLayoutReference {
    private var reference: PatternLayoutSignature? = null
    fun observe(mode: PatternMode, signature: PatternLayoutSignature?): LayoutRelation {
        if (mode.control == SyntheticDialogControl.NONE || signature == null) return LayoutRelation.UNKNOWN
        val first = reference ?: signature.also { reference = it }
        return first.compare(signature)
    }
    fun clear() { reference = null }
    override fun toString() = "ControlLayoutReference(redacted)"
}
enum class RenderContrast { INCONCLUSIVE, CORNER_ALPHA_FAILURE_REMOVED, NO_CONTENT_FAILURE }
internal class RenderControlMatrix(val outcomes: List<PatternModeOutcome>) {
    fun outcome(mode: PatternMode) = outcomes.singleOrNull { it.mode == mode }
        ?: PatternModeOutcome(mode, PatternModeResult.INCONSISTENT_RESULT)
    private fun observed(o: PatternModeOutcome): Boolean = o.observationReady &&
        (if (o.result == PatternModeResult.PASSED) o.passed && o.mismatches.isEmpty()
        else o.result == PatternModeResult.MAPPING_NOT_VERIFIED && o.pattern == PatternCode.CONTENT_NOT_SYNTHETIC) &&
        o.landmarks == LandmarkCheck.ALIGNED && o.contentScan == ContentScan.COMPLETE &&
        o.anchorDecor != DecorBufferRelation.UNKNOWN && o.elevated == ObservedElevation.NO
    val complete get() = outcomes.map { it.mode } == MODES && outcomes.all { o -> observed(o) &&
        (o.mode.control == SyntheticDialogControl.NONE ||
            (o.controlConfiguration == ControlConfiguration.VERIFIED && o.layout == LayoutRelation.SAME)) }
    // This predicate is deliberately scoped to square controls, NOT the baseline's rendering.
    val squareControlsPassed get() = complete && outcome(PatternMode.SQUARE_CLIPPED).passed && outcome(PatternMode.SQUARE_UNCLIPPED).passed
    private fun cornerAlphaFailure(o: PatternModeOutcome) = observed(o) &&
        o.result == PatternModeResult.MAPPING_NOT_VERIFIED && o.pattern == PatternCode.CONTENT_NOT_SYNTHETIC &&
        o.mismatches.isNotEmpty() && o.mismatches.all {
            it == ContentMismatch.CORNER_ALPHA_PARTIAL || it == ContentMismatch.CORNER_ALPHA_TRANSPARENT
        }
    fun contrast(from: PatternMode, to: PatternMode): RenderContrast {
        if (!complete) return RenderContrast.INCONCLUSIVE
        val a = outcome(from); val b = outcome(to)
        return when {
            cornerAlphaFailure(a) && b.passed -> RenderContrast.CORNER_ALPHA_FAILURE_REMOVED
            a.passed && b.passed -> RenderContrast.NO_CONTENT_FAILURE
            else -> RenderContrast.INCONCLUSIVE
        }
    }
    companion object {
        val MODES = listOf(PatternMode.ZERO_ELEVATION, PatternMode.ROUND_CLIPPED, PatternMode.ROUND_UNCLIPPED,
            PatternMode.SQUARE_CLIPPED, PatternMode.SQUARE_UNCLIPPED)
        suspend fun run(observe: suspend (PatternMode) -> PatternModeOutcome, pause: suspend () -> Unit): RenderControlMatrix {
            val results = mutableListOf<PatternModeOutcome>()
            for (mode in MODES) {
                if (results.isNotEmpty()) pause()
                val result = try { observe(mode).let { if (it.mode == mode) it else PatternModeOutcome(mode, PatternModeResult.INCONSISTENT_RESULT) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { PatternModeOutcome(mode, PatternModeResult.OBSERVATION_FAILED) }
                    catch (_: AssertionError) { PatternModeOutcome(mode, PatternModeResult.OBSERVATION_FAILED) }
                results += result
            }
            return RenderControlMatrix(results)
        }
    }
}
