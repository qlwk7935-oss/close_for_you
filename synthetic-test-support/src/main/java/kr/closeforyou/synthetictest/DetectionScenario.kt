package kr.closeforyou.synthetictest

/** Fixed synthetic oracle, never supplied to the production detector. No user data. */
enum class DetectionExpectation { POPUP, NON_POPUP, PROTECTED, UNCERTAIN }
enum class DetectionScenario(val expected: DetectionExpectation) {
    DIALOG(DetectionExpectation.POPUP),
    INLINE_OVERLAY(DetectionExpectation.POPUP),
    SEQUENCE_FIRST(DetectionExpectation.POPUP),
    SEQUENCE_SECOND(DetectionExpectation.POPUP),
    SCREEN_TRANSITION(DetectionExpectation.NON_POPUP),
    KEYBOARD(DetectionExpectation.PROTECTED),
    TOAST(DetectionExpectation.NON_POPUP),
    SCROLL(DetectionExpectation.NON_POPUP),
    LAYOUT_CHANGE(DetectionExpectation.NON_POPUP),
    LOGIN(DetectionExpectation.PROTECTED),
    PAYMENT_INPUT(DetectionExpectation.PROTECTED),
    PERMISSION_PROMPT(DetectionExpectation.POPUP),
    UNCERTAIN_CONTENT(DetectionExpectation.PROTECTED),
    UNMARKED_OVERLAY(DetectionExpectation.UNCERTAIN),
}

enum class DetectionActual { DETECTED, NO_POPUP, UNCERTAIN, BLOCKED, CONFLICT, UNOBSERVED }
enum class DetectionGrade {
    DETECTION_SUCCESS, MISSED_POPUP, FALSE_POSITIVE, CORRECT_NON_POPUP,
    SAFE_BLOCK, UNEXPECTED_BLOCK, UNCERTAIN_RETAINED, UNCERTAINTY_LOST, UNRESOLVED, SAFETY_FAILURE, UNOBSERVED,
}
object DetectionComparison {
    fun grade(expected: DetectionExpectation, actual: DetectionActual): DetectionGrade = when {
        actual == DetectionActual.UNOBSERVED -> DetectionGrade.UNOBSERVED
        actual == DetectionActual.CONFLICT -> if (expected == DetectionExpectation.PROTECTED)
            DetectionGrade.SAFETY_FAILURE else DetectionGrade.UNEXPECTED_BLOCK
        expected == DetectionExpectation.PROTECTED -> if (actual == DetectionActual.BLOCKED)
            DetectionGrade.SAFE_BLOCK else DetectionGrade.SAFETY_FAILURE
        expected == DetectionExpectation.POPUP -> when (actual) {
            DetectionActual.DETECTED -> DetectionGrade.DETECTION_SUCCESS
            DetectionActual.BLOCKED -> DetectionGrade.UNEXPECTED_BLOCK
            else -> DetectionGrade.MISSED_POPUP
        }
        actual == DetectionActual.DETECTED -> DetectionGrade.FALSE_POSITIVE
        actual == DetectionActual.BLOCKED -> DetectionGrade.UNEXPECTED_BLOCK
        expected == DetectionExpectation.UNCERTAIN -> if (actual == DetectionActual.UNCERTAIN)
            DetectionGrade.UNCERTAIN_RETAINED else DetectionGrade.UNCERTAINTY_LOST
        actual == DetectionActual.UNCERTAIN -> DetectionGrade.UNRESOLVED
        else -> DetectionGrade.CORRECT_NON_POPUP
    }
    fun accepted(grade: DetectionGrade): Boolean = grade in setOf(
        DetectionGrade.DETECTION_SUCCESS, DetectionGrade.CORRECT_NON_POPUP,
        DetectionGrade.SAFE_BLOCK, DetectionGrade.UNCERTAIN_RETAINED,
        // Uncertainty on an ordinary transition is not a false popup, but never a true negative.
        DetectionGrade.UNRESOLVED,
    )
}
