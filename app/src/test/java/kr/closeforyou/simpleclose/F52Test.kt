package kr.closeforyou.simpleclose

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** F52: one TIMING line per handled popup; its parts add up to total; numbers and a result code only. */
class F52Test {
    private fun parts(line: String): Map<String, String> =
        line.removePrefix("TIMING ").split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }

    private fun sumMatches(line: String) {
        val p = parts(line)
        val sum = p.filterKeys { it != "total" && it != "result" }.values.sumOf { it.toLong() }
        assertEquals(line, p.getValue("total").toLong(), sum)
    }

    @Test fun aClosedPopupHasEveryPartAndTheyAddUp() {
        val t = CloseTiming(firstEvent = 1_000, found = 1_420, settled = 1_790, decided = 1_812)
        t.clicked = 1_905
        t.returned = 2_480
        t.announced = 2_495
        val line = CloseTimingLine.line(t, TimingResult.CLOSED)
        assertEquals("TIMING detect=420 settle=370 judge=22 press=93 confirm=575 announce=15 total=1495 result=CLOSED", line)
        sumMatches(line)
    }

    @Test fun anAskedPopupEndsAtJudge() {
        val line = CloseTimingLine.line(CloseTiming(1_000, 1_300, 1_670, 1_700), TimingResult.ASKED)
        assertEquals("TIMING detect=300 settle=370 judge=30 total=700 result=ASKED", line)
        sumMatches(line)
        assertFalse(line.contains("press") || line.contains("announce"))
    }

    @Test fun aStoppedPressRunsPressToTheEndOfTheRecheckAndANotClosedOneHasNoAnnounce() {
        val stopped = CloseTiming(1_000, 1_100, 1_100, 1_110).apply { returned = 1_160 }
        val a = CloseTimingLine.line(stopped, TimingResult.STOPPED)
        assertEquals("TIMING detect=100 settle=0 judge=10 press=50 total=160 result=STOPPED", a)
        sumMatches(a)
        val notClosed = CloseTiming(1_000, 1_100, 1_250, 1_260).apply { clicked = 1_300; returned = 2_800 }
        val b = CloseTimingLine.line(notClosed, TimingResult.NOT_CLOSED)
        assertEquals("TIMING detect=100 settle=150 judge=10 press=40 confirm=1500 total=1800 result=NOT_CLOSED", b)
        sumMatches(b)
    }

    @Test fun noFirstEventCountsFromTheRead() {
        // A read with no event before it (firstEvent = found): detect 0, never negative.
        val line = CloseTimingLine.line(CloseTiming(firstEvent = 5_000, found = 5_000, settled = 5_200, decided = 5_210), TimingResult.ASKED)
        assertEquals("0", parts(line).getValue("detect"))
        sumMatches(line)
    }

    @Test fun theLineHoldsNumbersAndOneCodeOnly() {
        val t = CloseTiming(0, 10, 20, 30).apply { clicked = 40; returned = 50; announced = 60 }
        for (result in TimingResult.entries) {
            val line = CloseTimingLine.line(t, result)
            assertTrue(line, Regex("TIMING( [a-z]+=-?\\d+)+ result=[A-Z_]+").matches(line))
            assertEquals(result.name, parts(line).getValue("result"))
            sumMatches(line)
        }
    }
}
