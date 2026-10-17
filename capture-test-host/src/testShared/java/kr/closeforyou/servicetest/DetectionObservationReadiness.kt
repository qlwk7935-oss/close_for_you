package kr.closeforyou.servicetest

internal enum class DetectionObservationPhase { BASELINE, STIMULUS }

/** Raw event delivery is not evidence that baseline collection/F0-A completed. */
internal object DetectionObservationReadiness {
    fun collected(candidates: Int, nonPopups: Int, uncertain: Int, blocks: Int) =
        candidates > 0 || nonPopups > 0 || uncertain > 0 || blocks > 0
    fun usableBaseline(nonPopups: Int, uncertain: Int, complete: Boolean) =
        complete && (nonPopups > 0 || uncertain > 0)
}
