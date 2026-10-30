package kr.closeforyou.servicetest

import kr.closeforyou.synthetictest.*
import org.junit.Assert.*
import org.junit.Test

class DetectionComparisonTest {
    @Test fun `miss false positive safety failure and safe block are distinct`() {
        assertEquals(DetectionGrade.MISSED_POPUP, DetectionComparison.grade(DetectionExpectation.POPUP, DetectionActual.NO_POPUP))
        assertEquals(DetectionGrade.FALSE_POSITIVE, DetectionComparison.grade(DetectionExpectation.NON_POPUP, DetectionActual.DETECTED))
        assertEquals(DetectionGrade.SAFETY_FAILURE, DetectionComparison.grade(DetectionExpectation.PROTECTED, DetectionActual.DETECTED))
        assertEquals(DetectionGrade.SAFE_BLOCK, DetectionComparison.grade(DetectionExpectation.PROTECTED, DetectionActual.BLOCKED))
    }
    @Test fun `ordinary transition uncertainty is unresolved not a true negative`() {
        assertEquals(DetectionGrade.UNRESOLVED, DetectionComparison.grade(DetectionExpectation.NON_POPUP, DetectionActual.UNCERTAIN))
        assertEquals(DetectionGrade.CORRECT_NON_POPUP, DetectionComparison.grade(DetectionExpectation.NON_POPUP, DetectionActual.NO_POPUP))
    }
    @Test fun `unobserved and conflicts never pass any oracle`() {
        for (expected in DetectionExpectation.entries) for (actual in listOf(DetectionActual.UNOBSERVED, DetectionActual.CONFLICT)) {
            assertFalse(DetectionComparison.accepted(DetectionComparison.grade(expected, actual)))
        }
    }
    @Test fun `lost uncertainty cannot be upgraded to a negative success`() {
        assertFalse(DetectionComparison.accepted(DetectionComparison.grade(DetectionExpectation.UNCERTAIN, DetectionActual.NO_POPUP)))
        assertEquals(DetectionGrade.UNCERTAIN_RETAINED, DetectionComparison.grade(DetectionExpectation.UNCERTAIN, DetectionActual.UNCERTAIN))
    }
    @Test fun `fourteen fixed cases keep separate denominators`() {
        assertEquals(14, DetectionScenario.entries.size)
        assertEquals(5, DetectionScenario.entries.count { it.expected == DetectionExpectation.POPUP })
        assertEquals(4, DetectionScenario.entries.count { it.expected == DetectionExpectation.PROTECTED })
        assertEquals(4, DetectionScenario.entries.count { it.expected == DetectionExpectation.NON_POPUP })
        assertEquals(1, DetectionScenario.entries.count { it.expected == DetectionExpectation.UNCERTAIN })
    }
}
