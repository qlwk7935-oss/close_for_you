package kr.closeforyou.accessibility

import kr.closeforyou.core.model.*
import kr.closeforyou.synthetictest.*
import org.junit.Assert.*
import org.junit.Test

/** DTO/primitive F0-A + F1 matrix. Does NOT exercise Android events, IME, Toast, or capture. */
class F1SyntheticMatrixTest {
    @Test fun `fixed oracle compares fourteen synthetic model scenarios without hiding uncertainty`() {
        val screen = ScreenBounds(0, 0, 1_000, 2_000)
        val popup = ScreenBounds(100, 500, 900, 1_200)
        val underlay = DetectionOutline("underlay", screen, 4, false, "0/0")
        fun frame(id: Int = 1, bounds: ScreenBounds = screen, nodes: List<DetectionOutline> = listOf(underlay),
            digest: String = "base") = DetectionFrame("fixed-salted-app", id, setOf(id), digest, screen, nodes,
            PopupTrigger.CONTENT_CHANGED, bounds)
        val results = DetectionScenario.entries.map { scenario ->
            val detector = PopupCandidateDetector()
            detector.assess(frame())
            val block = when (scenario) {
                DetectionScenario.LOGIN -> PrivacyPolicy.fieldBlock(true, true, 0x81)
                DetectionScenario.PAYMENT_INPUT -> if (PrivacyPolicy.hasSensitiveText("합성 카드 입력")) PrivacyBlockReason.SENSITIVE_CONTENT else null
                DetectionScenario.KEYBOARD -> PrivacyPolicy.fieldBlock(false, true, 1)
                DetectionScenario.UNCERTAIN_CONTENT -> if (PrivacyPolicy.hasUncertainText("합성 보호 \u0627")) PrivacyBlockReason.SENSITIVE_CONTENT else null
                else -> null
            }
            val actual = if (block != null) DetectionActual.BLOCKED else {
                val next = when (scenario) {
                    DetectionScenario.DIALOG, DetectionScenario.SEQUENCE_FIRST, DetectionScenario.PERMISSION_PROMPT -> frame(2, popup)
                    DetectionScenario.SEQUENCE_SECOND -> { detector.assess(frame(2, popup)); frame(3, popup) }
                    DetectionScenario.INLINE_OVERLAY -> frame(nodes = listOf(underlay,
                        DetectionOutline("pane", popup, 2, false, "0/1", pane = true)), digest = "overlay")
                    DetectionScenario.UNMARKED_OVERLAY -> frame(nodes = listOf(underlay,
                        DetectionOutline("unknown", popup, 2, false, "0/1")), digest = "unknown")
                    DetectionScenario.SCREEN_TRANSITION -> frame(2, digest = "next-page")
                    DetectionScenario.LAYOUT_CHANGE -> frame(nodes = listOf(underlay.copy(bounds = ScreenBounds(0, 20, 1_000, 2_000))), digest = "layout")
                    else -> frame()
                }
                when (detector.assess(next).disposition) {
                    DetectionDisposition.POPUP_CANDIDATE -> DetectionActual.DETECTED
                    DetectionDisposition.NO_POPUP -> DetectionActual.NO_POPUP
                    DetectionDisposition.UNCERTAIN -> DetectionActual.UNCERTAIN
                }
            }
            val grade = DetectionComparison.grade(scenario.expected, actual)
            assertTrue("MODEL_CASE=${scenario.name};ACTUAL=${actual.name};GRADE=${grade.name}", DetectionComparison.accepted(grade))
            grade
        }
        assertEquals(5, results.count { it == DetectionGrade.DETECTION_SUCCESS })
        assertEquals(4, results.count { it == DetectionGrade.SAFE_BLOCK })
        assertEquals(3, results.count { it == DetectionGrade.CORRECT_NON_POPUP })
        assertEquals(1, results.count { it == DetectionGrade.UNCERTAIN_RETAINED })
        assertEquals(1, results.count { it == DetectionGrade.UNRESOLVED })
        assertEquals(0, results.count { it == DetectionGrade.FALSE_POSITIVE || it == DetectionGrade.MISSED_POPUP })
        // Permission/payment verbs alone still take the fixed, non-executable decision path.
        for (text in listOf("합성 권한 요청", "합성 결제 안내", "약관 동의 안내")) {
            assertFalse(PrivacyPolicy.hasSensitiveText(text))
            assertTrue(PrivacyPolicy.decisionSummaries(text).isNotEmpty())
        }
    }
}
