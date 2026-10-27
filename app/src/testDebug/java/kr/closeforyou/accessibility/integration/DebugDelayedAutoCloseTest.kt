package kr.closeforyou.accessibility.integration

import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.ui.voice.settings.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class DebugDelayedAutoCloseTest {
    @After fun off() { DebugDelayedAutoClose.userOff() }
    @Test fun explicitArmIsProcessLocalAndOnlyExactSyntheticPackageIsPermitted() {
        DebugDelayedAutoClose.userOff()
        assertNull(DebugDelayedAutoClose.ticket())
        assertFalse(DebugDelayedAutoClose.permits("kr.closeforyou.popuptest"))
        DebugDelayedAutoClose.arm()
        assertTrue(DebugDelayedAutoClose.permits("kr.closeforyou.popuptest"))
        for (pkg in listOf("", "kr.closeforyou", "kr.closeforyou.popuptest.other", "other.app"))
            assertFalse(DebugDelayedAutoClose.permits(pkg))
    }
    @Test fun rearmInvalidatesOldTicketAndLateCompletionCannotDisableNewTrial() {
        DebugDelayedAutoClose.arm(); val old = DebugDelayedAutoClose.ticket()
        DebugDelayedAutoClose.arm(); val next = DebugDelayedAutoClose.ticket()
        assertNotSame(old, next)
        assertFalse(DebugDelayedAutoClose.permits("kr.closeforyou.popuptest", old))
        DebugDelayedAutoClose.finish(old)
        assertSame(next, DebugDelayedAutoClose.ticket())
        DebugDelayedAutoClose.finish(next); assertNull(DebugDelayedAutoClose.ticket())
    }
    @Test fun internalReportsAndFinishesCannotTriggerTheUserChangeCollector() {
        DebugDelayedAutoClose.arm(); val revision = DebugDelayedAutoClose.userChanges.value
        val ticket = DebugDelayedAutoClose.ticket()
        DebugDelayedAutoClose.report(ticket, DelayedAutoPhase.F9_RESULT, attempted = true, outcome = ClickOutcome.UNKNOWN)
        DebugDelayedAutoClose.finish(ticket)
        assertEquals(revision, DebugDelayedAutoClose.userChanges.value)
        assertEquals(1, DebugDelayedAutoClose.observation.value.attempts)
        assertEquals(ClickOutcome.UNKNOWN, DebugDelayedAutoClose.observation.value.outcome)
    }
    @Test fun lateOldRunReportCannotBeJoinedToNewObservation() {
        DebugDelayedAutoClose.arm(); val old = DebugDelayedAutoClose.ticket()
        DebugDelayedAutoClose.arm()
        DebugDelayedAutoClose.report(old, DelayedAutoPhase.F9_RESULT, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED)
        assertEquals(0, DebugDelayedAutoClose.observation.value.attempts)
        assertNull(DebugDelayedAutoClose.observation.value.outcome)
        assertEquals(setOf(DelayedAutoPhase.ARMED), DebugDelayedAutoClose.observation.value.seen)
    }
    @Test fun developerArmCannotReplaceOnboardingSwitchOrConfirmedPresetConsent() {
        DebugDelayedAutoClose.arm()
        assertFalse(UiVoiceSettings().autoCloseConsentNow(live = true).granted)
        val agreed = UiVoiceSettings().completedOnboarding(HandlingPreset.CLOSE_ADS_AND_COUPONS, true, 1)
        assertTrue(agreed.autoCloseConsentNow(live = true).granted)
        assertFalse(agreed.copy(onboardingDone = false).autoCloseConsentNow(live = true).granted)
        assertFalse(agreed.copy(autoCloseConsent = false).autoCloseConsentNow(live = true).granted)
        assertFalse(agreed.copy(autoCloseConsentPreset = null).autoCloseConsentNow(live = true).granted)
        // Global production rollout remains OFF, including the actual C source's default.
        assertFalse(F9ServiceExecutionFeature.enabled)
        assertFalse(agreed.autoCloseConsentNow().granted)
    }
}
