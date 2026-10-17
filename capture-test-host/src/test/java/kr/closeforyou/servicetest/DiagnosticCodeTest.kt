package kr.closeforyou.servicetest

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticCodeTest {
    @Test fun privacyStagesRemainVisibleWithoutAllowingRawTextOrExtraFields() {
        for (code in listOf("F0_A", "F0_A_PASSED", "F0_B", "F0_B_PASSED", "REGION_UNCERTAIN")) {
            assertEquals(code, DiagnosticCode.safe(code))
        }
        for (raw in listOf(null, "합성 화면", "SYNTHETIC@example.test", "NONE;BLOCK=OTHER", "12345", "A".repeat(65))) {
            assertEquals("UNKNOWN", DiagnosticCode.safe(raw))
        }
    }
}
