package kr.closeforyou.servicetest

import kotlin.coroutines.cancellation.CancellationException
import kr.closeforyou.synthetictest.DecorBufferRelation
import kr.closeforyou.synthetictest.PatternCode

enum class PatternMode { DEFAULT_ELEVATION, ZERO_ELEVATION }
enum class ObservedElevation { YES, NO, UNKNOWN }
enum class PatternModeResult { PASSED, OBSERVATION_TIMEOUT, NOT_ARMED, MAPPING_NOT_VERIFIED,
    API_NOT_WINDOW, FRAME_NOT_VERIFIED, ELEVATION_UNAVAILABLE, ZERO_ELEVATION_NOT_APPLIED,
    SESSION_NOT_STARTED, SESSION_EXPIRED, PROBE_PERMISSION_DENIED, PROBE_UNAVAILABLE,
    HOST_LAUNCH_FAILED, OBSERVATION_FAILED, INCONSISTENT_RESULT, CLEANUP_FAILED }
enum class ElevationEffect { CONSISTENT_WITH_ELEVATION_INSETS, NO_RELATION_CHANGE, INCONCLUSIVE }

/** Fixed codes only. These are test observations, never a production crop authorization. */
data class PatternModeOutcome(val mode: PatternMode, val result: PatternModeResult,
    val pattern: PatternCode = PatternCode.NOT_OBSERVED,
    val decor: DecorBufferRelation = DecorBufferRelation.UNKNOWN,
    val elevated: ObservedElevation = ObservedElevation.UNKNOWN) {
    val passed get() = result == PatternModeResult.PASSED && pattern == PatternCode.TRANSLATION_VERIFIED &&
        decor != DecorBufferRelation.UNKNOWN && elevated != ObservedElevation.UNKNOWN &&
        (mode != PatternMode.ZERO_ELEVATION || elevated == ObservedElevation.NO)
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
