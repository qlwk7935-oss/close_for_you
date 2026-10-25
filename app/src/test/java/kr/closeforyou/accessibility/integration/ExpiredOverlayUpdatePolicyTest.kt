package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

class ExpiredOverlayUpdatePolicyTest {
    private val state = OverlayWindowState(OverlayWindowStatus.SHOWING, 7)
    private val binding = OwnedOverlayBinding().apply {
        val token = MockPopupFixtures.notice().snapshot.token
        expect(emptySet(), token, 7)
        check(matches(90, setOf(90), token, 7))
    }
    private fun inspect(type: Int = E.TYPE_WINDOW_CONTENT_CHANGED, mask: Int = 0,
        pkg: String? = "OWN", window: Int = 90, live: Set<Int>? = setOf(90),
        display: OverlayWindowState = state, retained: Boolean = true, read: () -> Unit = {}) =
        inspectExpiredOverlayUpdate(display, retained, type, { mask }, { pkg }, "OWN", { window },
            { binding.boundWindowFor(display.generation) }, { read(); live })

    @Test fun exactLiveBoundContentAndPureBoundsAreDisplayOnly() {
        assertTrue(inspect())
        assertTrue(inspect(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, pkg = null))
    }

    @Test fun targetSystemMissingPackageAndUncertainOrMultipleOwnershipCannotBeExcluded() {
        for (pkg in listOf("TARGET", "SYSTEM", null)) assertFalse(inspect(pkg = pkg))
        assertFalse(inspect(window = 91)); assertFalse(inspect(window = -1))
        assertFalse(inspect(live = null)); assertFalse(inspect(live = emptySet()))
        assertFalse(inspect(live = setOf(90, 91)))
        assertFalse(inspect(display = state.copy(generation = 8)))
        assertFalse(inspect(display = OverlayWindowState.HIDDEN))
        assertFalse(inspect(retained = false))
    }

    @Test fun userNavigationInteractionRemovalAndOtherWindowMasksNeverQueryOwnership() {
        val forbidden = listOf(E.TYPE_VIEW_FOCUSED, E.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, E.TYPE_TOUCH_INTERACTION_START,
            E.TYPE_TOUCH_EXPLORATION_GESTURE_START, E.TYPE_VIEW_SCROLLED, E.TYPE_VIEW_CLICKED,
            E.TYPE_WINDOW_STATE_CHANGED, E.TYPE_VIEW_TEXT_CHANGED)
        forbidden.forEach { assertFalse(inspect(type = it, read = { fail("Unexpected ownership query") })) }
        for (mask in listOf(0, E.WINDOWS_CHANGE_REMOVED, E.WINDOWS_CHANGE_ACTIVE,
            E.WINDOWS_CHANGE_FOCUSED, E.WINDOWS_CHANGE_BOUNDS or E.WINDOWS_CHANGE_REMOVED)) {
            assertFalse(inspect(E.TYPE_WINDOWS_CHANGED, mask, read = { fail("Unexpected ownership query") }))
        }
    }

    @Test fun displayLeaseDeadlineCannotBeExtendedByAnyNumberOfOwnUpdates() {
        var now = 0L
        val lease = ChoiceDisplayLease { now }
        lease.shown(MockPopupFixtures.notice().snapshot.token, state)
        assertTrue(lease.detach(state, true))
        for (time in listOf(5_000L, 15_000L, 29_999L)) {
            now = time; assertTrue(inspect(retained = lease.retains(state)))
        }
        now = 30_000; assertFalse(inspect(retained = lease.retains(state)))
        assertNull(lease.speechToken(state))
    }
}
