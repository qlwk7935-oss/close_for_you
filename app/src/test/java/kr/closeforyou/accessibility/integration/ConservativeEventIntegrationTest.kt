package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as Event
import kr.closeforyou.accessibility.EventCapturePhase
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import org.junit.Assert.*
import org.junit.Test

/** No verified status-bar identity, user-origin receipt or retired-window generation is supplied.
 * Old timing and a plausible window class must therefore never become new exemptions. */
class ConservativeEventIntegrationTest {
    private fun decision(type: Int, id: Int, pkg: String? = "SYNTHETIC_HOST", mask: Int = 0,
        live: Set<Int>? = setOf(20), debug: Boolean = true,
        classification: OverlayEventWindowClass = OverlayEventWindowClass.UNKNOWN): Boolean {
        val token = MockPopupFixtures.notice().snapshot.token.copy(windowId = 10)
        val binding = OwnedOverlayBinding(); binding.expect(emptySet(), token, 1)
        check(binding.matches(20, setOf(20), token, 1))
        val diagnostic = if (debug) OverlayEventDiagnostic(capturePhase = { EventCapturePhase.BEFORE_INITIAL_F0 }) {
                _, _, _, _, _, _, _, _, _, _, phase -> assertEquals(EventCapturePhase.BEFORE_INITIAL_F0, phase)
        }.also { it.arm() } else null
        return inspectOwnedOverlayEvent(OverlayWindowState(OverlayWindowStatus.SHOWING, 1),
            { type }, { mask }, { pkg }, { token },
            { current, trace -> binding.matches(id, live, current, 1, trace) },
            { "SYNTHETIC_HOST" }, diagnostic, { classification }, { 1L })
    }

    @Test fun statusBarContentClaimWithoutVerifiedRegionAndIdentityStillInvalidates() {
        for (debug in listOf(false, true)) assertFalse(decision(Event.TYPE_WINDOW_CONTENT_CHANGED,
            30, "com.android.systemui", debug = debug, classification = OverlayEventWindowClass.SYSTEM))
    }
    @Test fun permissionAndSystemDialogsNeverInheritCurrentCOwnershipFromAnIdMatch() {
        for (pkg in listOf("com.android.permissioncontroller", "com.android.systemui"))
            for (type in listOf(Event.TYPE_WINDOW_CONTENT_CHANGED, Event.TYPE_WINDOW_STATE_CHANGED, Event.TYPE_WINDOWS_CHANGED))
                for (id in listOf(10, 20, 30)) for (debug in listOf(false, true)) {
                    assertFalse(decision(type, id, pkg, mask = Event.WINDOWS_CHANGE_BOUNDS,
                        debug = debug, classification = OverlayEventWindowClass.SYSTEM))
                    assertFalse(decision(type, id, pkg, live = null, debug = debug,
                        classification = OverlayEventWindowClass.UNKNOWN))
                }
    }
    @Test fun targetActiveOrFocusedMaskIsNotIgnoredByPreCaptureTimeAlone() {
        for (mask in listOf(Event.WINDOWS_CHANGE_ACTIVE, Event.WINDOWS_CHANGE_FOCUSED,
            Event.WINDOWS_CHANGE_ACTIVE or Event.WINDOWS_CHANGE_LAYER))
            for (debug in listOf(false, true)) assertFalse(decision(Event.TYPE_WINDOWS_CHANGED,
                10, mask = mask, debug = debug, classification = OverlayEventWindowClass.TARGET_APP))
    }
    @Test fun talkBackTargetFocusAndUserExplorationRemainConservative() {
        for (type in listOf(Event.TYPE_VIEW_ACCESSIBILITY_FOCUSED, Event.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
            Event.TYPE_VIEW_FOCUSED, Event.TYPE_TOUCH_EXPLORATION_GESTURE_START,
            Event.TYPE_TOUCH_EXPLORATION_GESTURE_END, Event.TYPE_TOUCH_INTERACTION_START,
            Event.TYPE_VIEW_SCROLLED)) for (debug in listOf(false, true))
            assertFalse(decision(type, 10, debug = debug, classification = OverlayEventWindowClass.TARGET_APP))
    }
    @Test fun onlyCurrentBoundLiveCFocusRetainsOriginalUiBookkeepingExclusion() {
        for (debug in listOf(false, true)) {
            assertTrue(decision(Event.TYPE_VIEW_ACCESSIBILITY_FOCUSED, 20, debug = debug))
            assertFalse(decision(Event.TYPE_VIEW_ACCESSIBILITY_FOCUSED, 20, live = emptySet(), debug = debug))
        }
    }
    @Test fun retiredMissingOrReusedIdWithoutLiveCurrentOwnershipCannotBeExempted() {
        for (debug in listOf(false, true)) {
            assertFalse(decision(Event.TYPE_WINDOW_STATE_CHANGED, 99, debug = debug,
                classification = OverlayEventWindowClass.NOT_IN_LIST))
            assertFalse(decision(Event.TYPE_WINDOW_STATE_CHANGED, 20, live = emptySet(), debug = debug,
                classification = OverlayEventWindowClass.NOT_IN_LIST))
            assertFalse(decision(Event.TYPE_WINDOWS_CHANGED, 20, mask = Event.WINDOWS_CHANGE_REMOVED,
                debug = debug, classification = OverlayEventWindowClass.NOT_IN_LIST))
        }
    }
    @Test fun otherAppSwitchAndActualTargetContentChangeAlwaysReachInvalidationBoundary() {
        for (debug in listOf(false, true)) {
            assertFalse(decision(Event.TYPE_WINDOW_STATE_CHANGED, 40, "SYNTHETIC_OTHER", debug = debug,
                classification = OverlayEventWindowClass.OTHER_APP))
            assertFalse(decision(Event.TYPE_WINDOW_CONTENT_CHANGED, 10, debug = debug,
                classification = OverlayEventWindowClass.TARGET_APP))
        }
    }
}
