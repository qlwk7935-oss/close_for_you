package kr.closeforyou.accessibility.integration

import org.junit.Assert.*
import org.junit.Test

class DelayedAutoReleaseTest {
    @Test fun releaseHasNoArmApiAndCannotAuthorizeAnyPackageOrTicket() {
        assertNull(DebugDelayedAutoClose.ticket())
        assertNull(DebugDelayedAutoClose.trace())
        for (pkg in listOf("kr.closeforyou.popuptest", "kr.closeforyou", "other")) {
            assertFalse(DebugDelayedAutoClose.permits(pkg))
            assertFalse(DebugDelayedAutoClose.permits(pkg, Any()))
        }
        assertFalse(DebugDelayedAutoClose::class.java.declaredMethods.any { it.name == "arm" })
        assertFalse(F9ServiceExecutionFeature.enabled)
    }
}
