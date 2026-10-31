package kr.closeforyou.accessibility.testing

import kr.closeforyou.accessibility.action.P01ReaderComparisonFeature
import org.junit.Assert.*
import org.junit.Test

class P01ReaderComparisonReleaseTest {
    @Test fun testReaderEntryDisabledAndDiagnosticServiceAbsent() {
        assertFalse(P01ReaderComparisonFeature.enabled)
        try {
            Class.forName("kr.closeforyou.accessibility.testing.P01ReaderComparisonService")
            fail("DEBUG_SERVICE_IN_RELEASE")
        } catch (_: ClassNotFoundException) { }
    }
}
