package kr.closeforyou.accessibility

import org.junit.Assert.assertEquals
import org.junit.Test

class EnabledEnginePolicyTest {
    private val a = "kr.closeforyou/.accessibility.CloseForYouAccessibilityService"
    private val c = "kr.closeforyou/.simpleclose.SimpleCloseAccessibilityService"

    @Test fun onlyAAndOtherServicesProveCAbsent() {
        assertEquals(OtherEngineState.DISABLED, EnabledEnginePolicy.fromSecureSetting(a))
        assertEquals(OtherEngineState.DISABLED, EnabledEnginePolicy.fromSecureSetting("$a:com.example.reader/.Service"))
        assertEquals(OtherEngineState.DISABLED, EnabledEnginePolicy.fromSecureSetting(""))
    }

    @Test fun fullAndShortCComponentBothBlockWithOrWithoutA() {
        for (component in listOf(c, "${EnabledEnginePolicy.C_PACKAGE}/${EnabledEnginePolicy.C_SERVICE}")) {
            for (value in listOf(component, "$a:$component", "$component:$a")) {
                assertEquals(OtherEngineState.ENABLED, EnabledEnginePolicy.fromSecureSetting(value))
            }
        }
    }

    @Test fun matchingIsExactNotSubstringLabelOrPackageAlone() {
        for (value in listOf("$c.Other", "kr.closeforyou.debug/${EnabledEnginePolicy.C_SERVICE}",
            "kr.closeforyou/.other.SimpleCloseAccessibilityService")) {
            assertEquals(OtherEngineState.DISABLED, EnabledEnginePolicy.fromSecureSetting(value))
        }
        for (value in listOf("팝업처리도우미", "kr.closeforyou", EnabledEnginePolicy.C_SERVICE)) {
            assertEquals(OtherEngineState.UNKNOWN, EnabledEnginePolicy.fromSecureSetting(value))
        }
    }

    @Test fun unreadableOrMalformedSettingNeverProvesAbsence() {
        for (value in listOf(null, " ", ":", "$a:", "$a:invalid", "/missing", "kr.closeforyou/", "$a/extra")) {
            assertEquals(OtherEngineState.UNKNOWN, EnabledEnginePolicy.fromSecureSetting(value))
        }
    }

    @Test fun positiveCMatchStillBlocksWhenAnotherEntryIsMalformed() {
        assertEquals(OtherEngineState.ENABLED, EnabledEnginePolicy.fromSecureSetting("bad:$c"))
    }
}
