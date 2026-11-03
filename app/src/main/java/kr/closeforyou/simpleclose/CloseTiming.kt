package kr.closeforyou.simpleclose

/** F52: how one handled popup ended, for the `TIMING` line. */
enum class TimingResult {
    /** Closed and confirmed, the result sentence requested. */
    CLOSED,
    /** Pressed, not closed (or back again): asked. */
    NOT_CLOSED,
    /** The press took the person out of the app. */
    LEFT_APP,
    /** Not pressed: the re-check right before the press stopped it (moved, gone, B's fresh verdict). */
    STOPPED,
    /** Judged to ask, nothing pressed. */
    ASKED,
}

/**
 * F52 (영상 "판단 + 처리 + 결과 안내" 카드): the moments of one handled popup (uptime ms), each the end of a part:
 * - [firstEvent]: the first accepted event of the search that found it (the read's own end when there was none);
 * - [found]: the end of the first read that found its popup root;
 * - [settled]: the end of the read that found it standing still (F50); [found] when it was not waited on;
 * - [decided]: the decision made (B's classification, the preset, the safety checks; `DECIDED_*`);
 * - [clicked]: the click sent (after the focus look-up, the re-check, B's pre-press verdict); null = no click;
 * - [returned]: the close check over (`CLOSED_CONFIRMED` after the strict re-read, or not closed); null = not pressed;
 * - [announced]: the result sentence handed to TalkBack's card or our TTS (the request, not the end of the speech).
 */
class CloseTiming(val firstEvent: Long, val found: Long, val settled: Long, val decided: Long) {
    var clicked: Long? = null
    var returned: Long? = null
    var announced: Long? = null
}

/** F52: one line of numbers and a result code; never text, a package or a window id. Pure; JVM tested. */
object CloseTimingLine {
    /**
     * `TIMING detect= settle= judge= press= confirm= announce= total= result=` (ms). Parts whose end was not reached are
     * left out (an [TimingResult.ASKED] line ends at judge); without a click, press runs to the end of the re-check.
     * The parts always add up to total (each part starts where the one before ended).
     */
    fun line(t: CloseTiming, result: TimingResult): String {
        val start = minOf(t.firstEvent, t.found)
        val parts = mutableListOf("detect" to t.found - start, "settle" to t.settled - t.found, "judge" to t.decided - t.settled)
        var end = t.decided
        if (result != TimingResult.ASKED) {
            val clicked = t.clicked
            val returned = t.returned
            if (clicked != null) {
                parts += "press" to clicked - end
                end = clicked
                if (returned != null) {
                    parts += "confirm" to returned - end
                    end = returned
                }
            } else if (returned != null) {
                parts += "press" to returned - end
                end = returned
            }
            t.announced?.let {
                parts += "announce" to it - end
                end = it
            }
        }
        return buildString {
            append("TIMING")
            parts.forEach { (name, ms) -> append(' ').append(name).append('=').append(ms) }
            append(" total=").append(end - start).append(" result=").append(result.name)
        }
    }
}
