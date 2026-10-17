package kr.closeforyou.servicetest

import kotlin.coroutines.cancellation.CancellationException
import kr.closeforyou.synthetictest.*

enum class PatternMode(val control: SyntheticDialogControl = SyntheticDialogControl.NONE) {
    DEFAULT_ELEVATION, ZERO_ELEVATION,
    ROUND_CLIPPED(SyntheticDialogControl.ROUND_CLIPPED), ROUND_UNCLIPPED(SyntheticDialogControl.ROUND_UNCLIPPED),
    SQUARE_CLIPPED(SyntheticDialogControl.SQUARE_CLIPPED), SQUARE_UNCLIPPED(SyntheticDialogControl.SQUARE_UNCLIPPED),
}
enum class ObservedElevation { YES, NO, UNKNOWN }
enum class PatternModeResult { PASSED, OBSERVATION_TIMEOUT, NOT_ARMED, MAPPING_NOT_VERIFIED,
    API_NOT_WINDOW, FRAME_NOT_VERIFIED, ELEVATION_UNAVAILABLE, ZERO_ELEVATION_NOT_APPLIED,
    SESSION_NOT_STARTED, SESSION_EXPIRED, PROBE_PERMISSION_DENIED, PROBE_UNAVAILABLE,
    HOST_LAUNCH_FAILED, OBSERVATION_FAILED, INCONSISTENT_RESULT, CLEANUP_FAILED, CONTROL_NOT_VERIFIED }
enum class ElevationEffect { CONSISTENT_WITH_ELEVATION_INSETS, NO_RELATION_CHANGE, INCONCLUSIVE }

/** Fixed codes only. These are test observations, never a production crop authorization. */
data class PatternModeOutcome(val mode: PatternMode, val result: PatternModeResult,
    val pattern: PatternCode = PatternCode.NOT_OBSERVED,
    val decor: DecorBufferRelation = DecorBufferRelation.UNKNOWN,
    val elevated: ObservedElevation = ObservedElevation.UNKNOWN,
    val observationReady: Boolean = false,
    val landmarks: LandmarkCheck = LandmarkCheck.NOT_VERIFIED,
    val contentScan: ContentScan = ContentScan.NOT_RUN,
    val mismatches: Set<ContentMismatch> = emptySet(),
    val anchorDecor: DecorBufferRelation = DecorBufferRelation.UNKNOWN,
    val controlConfiguration: ControlConfiguration = ControlConfiguration.NOT_REQUESTED,
    val layout: LayoutRelation = LayoutRelation.UNKNOWN,
) {
    val passed get() = result == PatternModeResult.PASSED && pattern == PatternCode.TRANSLATION_VERIFIED &&
        decor != DecorBufferRelation.UNKNOWN && elevated != ObservedElevation.UNKNOWN &&
        (mode == PatternMode.DEFAULT_ELEVATION || elevated == ObservedElevation.NO) &&
        (mode.control == SyntheticDialogControl.NONE || (controlConfiguration == ControlConfiguration.VERIFIED && observationReady &&
            landmarks == LandmarkCheck.ALIGNED && contentScan == ContentScan.COMPLETE && mismatches.isEmpty()))
}
data class PatternModeComparison(val normal: PatternModeOutcome, val flat: PatternModeOutcome) {
    val passed get() = normal.passed && flat.passed
    val effect: ElevationEffect get() = when {
        !passed || normal.elevated != ObservedElevation.YES || flat.elevated != ObservedElevation.NO -> ElevationEffect.INCONCLUSIVE
        normal.decor == DecorBufferRelation.INSET_ALL && flat.decor == DecorBufferRelation.EXACT -> ElevationEffect.CONSISTENT_WITH_ELEVATION_INSETS
        normal.decor == flat.decor -> ElevationEffect.NO_RELATION_CHANGE
        else -> ElevationEffect.INCONCLUSIVE
    }
}
internal object PatternModeRunner {
    suspend fun run(observe: suspend (PatternMode) -> PatternModeOutcome, pause: suspend () -> Unit): PatternModeComparison {
        suspend fun attempt(mode: PatternMode): PatternModeOutcome = try {
            observe(mode).let { if (it.mode == mode) it else PatternModeOutcome(mode, PatternModeResult.INCONSISTENT_RESULT) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { PatternModeOutcome(mode, PatternModeResult.OBSERVATION_FAILED) }
        catch (_: AssertionError) { PatternModeOutcome(mode, PatternModeResult.OBSERVATION_FAILED) }
        val normal = attempt(PatternMode.DEFAULT_ELEVATION)
        pause() // existing capture interval; cancellation remains an immediate stop
        val flat = attempt(PatternMode.ZERO_ELEVATION)
        return PatternModeComparison(normal, flat)
    }
}
