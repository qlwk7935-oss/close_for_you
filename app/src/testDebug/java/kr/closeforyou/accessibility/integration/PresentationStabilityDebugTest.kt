package kr.closeforyou.accessibility.integration

import org.junit.Assert.*
import org.junit.Test

class PresentationStabilityDebugTest {
    @Test fun onlyBoundedApplicationPopupUsesFreshStabilityNotAFullScreenPage() {
        assertEquals(PresentationAssurance.OBSERVATION_ONLY_DEMO, ManualPresentationStabilityFeature.contract)
        assertTrue(ManualPresentationStabilityFeature.enabled)
        val f = P01StabilityFixture.sample(0)
        assertTrue(PrePresentationStability.applies(f.frame))
        assertFalse(PrePresentationStability.applies(f.frame.copy(windowBounds = f.frame.screenBounds)))
    }
}
