package kr.closeforyou.servicetest

import org.junit.Assert.*
import org.junit.Test

class CollectionDiagnosticOutputTest {
    @Test fun `renders one correlated fixed code tuple`() {
        assertEquals("BLOCK=EDITABLE_FIELD;STEP=PROTECTION_PASS;DETAIL=TREE_PROTECTION;TREE=TARGET;FIELD=INPUT_TYPE;CAUSE=NON_ZERO_INPUT_TYPE;RELATED=NONE;FRAME=VERIFIED;OP=NODE_FIELD_CHECK;EXCEPTION=NONE",
            CollectionDiagnosticOutput.format("EDITABLE_FIELD:PROTECTION_PASS:TREE_PROTECTION:TARGET:INPUT_TYPE:NON_ZERO_INPUT_TYPE:NONE:VERIFIED:NODE_FIELD_CHECK:NONE"))
    }
    @Test fun `malformed payload never echoes text positions or identities`() {
        for (payload in listOf("합성 문서", "123", "APP:100,200", "A:B:C:D:E:F:G:H;INJECTED", "A:B:C:D:E:F:G", "a:B:C:D:E:F:G:H",
            "EDITABLE_FIELD:PROTECTION_PASS:TREE_PROTECTION:TARGET:INPUT_TYPE:ARBITRARY_UPPERCASE:NONE:VERIFIED")) {
            assertEquals("COLLECTION_DIAGNOSTIC_INVALID", CollectionDiagnosticOutput.format(payload))
        }
    }
    @Test fun `raw events do not make a collected baseline and incomplete collection is not usable`() {
        assertFalse(DetectionObservationReadiness.collected(0, 0, 0, 0))
        assertFalse(DetectionObservationReadiness.usableBaseline(0, 0, true))
        assertFalse(DetectionObservationReadiness.usableBaseline(1, 0, false))
        assertTrue(DetectionObservationReadiness.collected(0, 0, 0, 1)) // Stop and diagnose, not a pass.
        assertFalse(DetectionObservationReadiness.usableBaseline(0, 0, true))
        assertTrue(DetectionObservationReadiness.usableBaseline(1, 0, true))
    }
}
