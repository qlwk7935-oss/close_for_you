package kr.closeforyou.accessibility

/** A-only monotonic capture envelope, sampled before F0 and after all handles are released.
 * Timing is diagnostic evidence, never an event exemption or a renewed authorization. */
internal class CaptureObservationInterval(val started: Long, val completed: Long) {
    val valid: Boolean get() = started >= 0 && completed >= started
    override fun toString() = "CaptureObservationInterval(redacted)"
}
internal enum class EventCapturePhase {
    BEFORE_INITIAL_F0, DURING_INITIAL_F0, BETWEEN_INITIAL_AND_FINAL_F0,
    DURING_FINAL_F0, AFTER_FINAL_F0, UNKNOWN,
}
