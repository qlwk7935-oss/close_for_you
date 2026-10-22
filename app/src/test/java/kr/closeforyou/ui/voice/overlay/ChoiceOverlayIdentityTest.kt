package kr.closeforyou.ui.voice.overlay

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review #18-3: the package name alone never identifies C's prompt window. */
class ChoiceOverlayIdentityTest {
    private val own = "kr.closeforyou"
    private val title = "팝업 안내"
    private val overlayType = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
    private val showing = OverlayWindowState(OverlayWindowStatus.SHOWING, 3)
    private val ours = WindowFacts(id = 42, type = overlayType, title = title, rootPackageName = own)

    private fun matches(window: WindowFacts, overlay: OverlayWindowState = showing) =
        ChoiceOverlayIdentity.isOwnOverlayWindow(window, own, title, overlay)

    @Test fun onlyAllConditionsTogetherMatch() {
        assertTrue(matches(ours))
        assertFalse("app window of our package (A's settings)", matches(ours.copy(type = AccessibilityWindowInfo.TYPE_APPLICATION)))
        assertFalse("another service's overlay", matches(ours.copy(rootPackageName = "com.google.android.marvin.talkback")))
        assertFalse("our package, other title (debug or A window)", matches(ours.copy(title = "다른 창")))
        assertFalse("no title", matches(ours.copy(title = null)))
        assertFalse("no root package", matches(ours.copy(rootPackageName = null)))
        assertFalse("unknown id", matches(ours.copy(id = -1)))
        assertFalse("C says no prompt is up", matches(ours, OverlayWindowState.HIDDEN))
    }

    @Test fun stuckWindowStillCountsAsOurs() {
        assertTrue(matches(ours, OverlayWindowState(OverlayWindowStatus.REMOVE_FAILED, 3)))
    }

    @Test fun trackerMatchesOnlyTheRememberedWindowOfTheSameGeneration() {
        val tracker = OwnOverlayWindowTracker(own, title)
        val app = WindowFacts(7, AccessibilityWindowInfo.TYPE_APPLICATION, "쇼핑", "com.example.shop")
        tracker.update(listOf(app, ours), showing)

        assertTrue(tracker.isOwnOverlayWindowEvent(42, showing))
        assertFalse("target app events are never C's", tracker.isOwnOverlayWindowEvent(7, showing))
        assertFalse("event without a window id", tracker.isOwnOverlayWindowEvent(-1, showing))
        assertFalse("prompt closed since", tracker.isOwnOverlayWindowEvent(42, OverlayWindowState.HIDDEN))
        assertFalse(
            "a newer prompt needs a fresh update",
            tracker.isOwnOverlayWindowEvent(42, OverlayWindowState(OverlayWindowStatus.SHOWING, 4)),
        )
    }

    @Test fun trackerTrustsNothingWhenAmbiguousOrMissing() {
        val tracker = OwnOverlayWindowTracker(own, title)
        tracker.update(listOf(ours, ours.copy(id = 43)), showing)
        assertFalse(tracker.isOwnOverlayWindowEvent(42, showing))
        assertFalse(tracker.isOwnOverlayWindowEvent(43, showing))

        tracker.update(emptyList(), showing)
        assertFalse(tracker.isOwnOverlayWindowEvent(42, showing))

        tracker.update(listOf(ours), OverlayWindowState.HIDDEN)
        assertFalse(tracker.isOwnOverlayWindowEvent(42, showing))
    }

    @Test fun samePackageEventIsNotEnough() {
        val tracker = OwnOverlayWindowTracker(own, title)
        // A's own service window: same package, but not the prompt window.
        val aWindow = WindowFacts(9, overlayType, "다른 창", own)
        tracker.update(listOf(aWindow), showing)
        assertFalse(tracker.isOwnOverlayWindowEvent(9, showing))
    }
}
