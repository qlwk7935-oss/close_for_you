package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.NativeActionPresence
import org.junit.Assert.*
import org.junit.Test

class ManualInteractiveBoundsReleaseTest {
    @Test fun releaseVariantDoesNotClassifyEvenIfADiagnosticProbeIsExplicitlyRequested() {
        val r = ManualDemoBoundsRejectionTest.Rig(); r.expand(reflow = true)
        assertNull(ManualInteractiveBoundsDiagnostics.unknown())
        assertNull(ManualInteractiveBoundsDiagnostics.describe(r.source, r.fresh, r.analysis!!.closeCandidates, 7, NativeActionPresence.PRESENT))
        r.probe.recordInteractiveBounds(r.source, r.fresh, r.analysis!!.closeCandidates, 7, NativeActionPresence.PRESENT)
        assertNull(r.probe.interactiveBounds)
    }
    @Test fun productionReleaseKeepsStrictPolicyAndHasNoTraceOrDisplayException() {
        assertNull(ManualPromptLog.create()); assertFalse(ManualDemoBoundsFeature.enabled)
        val r = ManualDemoBoundsRejectionTest.Rig()
        assertEquals(ManualDisplayVerdict.CHANGED, r.run(enabled = ManualDemoBoundsFeature.enabled))
        assertNull(r.probe.interactiveBounds); assertEquals(0, r.f.clicks); assertEquals(1, r.removed)
    }
}
