package kr.closeforyou.accessibility.integration

import org.junit.Assert.*
import org.junit.Test

class PresentationStabilityReleaseTest {
    @Test fun releaseDoesNotEnableAdditionalCollectionOrFormatDiagnostic() {
        assertEquals(PresentationAssurance.STRICT_GENERATION_REQUIRED, ManualPresentationStabilityFeature.contract)
        assertFalse(ManualPresentationStabilityFeature.enabled)
        assertFalse(ManualDemoBoundsFeature.enabled)
        for (code in PresentationStabilityEnd.entries) ManualPresentationStabilityFeature.report(code)
    }
}
