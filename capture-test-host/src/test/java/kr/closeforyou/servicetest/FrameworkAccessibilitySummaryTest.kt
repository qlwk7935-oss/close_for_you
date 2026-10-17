package kr.closeforyou.servicetest

import org.junit.Assert.*
import org.junit.Test

class FrameworkAccessibilitySummaryTest {
    private val target = "kr.closeforyou/.accessibility.CloseForYouAccessibilityService"
    private fun dump(bound: String = "", binding: String = "", crashed: String = "") = """
        User state[attributes:{id=0}]
          Bound services:{$bound}
          Enabled services:{$target}
          Binding services:{$binding}
          Crashed services:{$crashed}
    """.trimIndent()

    @Test fun actualBoundSectionIsSeparatedFromEnabledOnly() {
        assertEquals(FrameworkAccessibilityState.BOUND, FrameworkAccessibilitySummary.parse(dump("Service[label=synthetic, id=$target]")))
        assertEquals(FrameworkAccessibilityState.NOT_BOUND, FrameworkAccessibilitySummary.parse(dump()))
    }

    @Test fun bindingAndCrashHaveDistinctCodes() {
        assertEquals(FrameworkAccessibilityState.BINDING, FrameworkAccessibilitySummary.parse(dump(binding = "ComponentInfo{$target}")))
        assertEquals(FrameworkAccessibilityState.CRASHED, FrameworkAccessibilitySummary.parse(dump(crashed = target)))
    }

    @Test fun fullyQualifiedComponentAndNestedContainersAreSupported() {
        val full = target.replace("/.accessibility", "/kr.closeforyou.accessibility")
        assertEquals(FrameworkAccessibilityState.BOUND, FrameworkAccessibilitySummary.parse(dump("Service[id=ComponentInfo{$full}, label=synthetic]")))
    }

    @Test fun unrelatedServicesAndPrefixMatchesNeverCountAsOurBoundService() {
        assertEquals(FrameworkAccessibilityState.NOT_BOUND, FrameworkAccessibilitySummary.parse(dump("org.example.synthetic/.OtherService")))
        assertEquals(FrameworkAccessibilityState.NOT_BOUND, FrameworkAccessibilitySummary.parse(dump(target + "Other")))
    }

    @Test fun unknownAndMultiUserDumpsStayUnknownInsteadOfInventingADiagnosis() {
        assertEquals(FrameworkAccessibilityState.UNKNOWN, FrameworkAccessibilitySummary.parse("unsupported format"))
        assertEquals(FrameworkAccessibilityState.UNKNOWN, FrameworkAccessibilitySummary.parse(dump() + "\nUser state[id=10]"))
        assertEquals(FrameworkAccessibilityState.UNKNOWN, FrameworkAccessibilitySummary.parse("Bound services:{unterminated"))
    }

    @Test fun onlyAFixedEnumEscapesEvenWhenLabelsContainPrivateSyntheticMarkers() {
        val marker = "SYNTHETIC_PRIVATE_MARKER"
        val result = FrameworkAccessibilitySummary.parse(dump("Service[label=$marker, id=$target]"))
        assertEquals(FrameworkAccessibilityState.BOUND, result)
        assertFalse(result.toString().contains(marker))
    }
}
