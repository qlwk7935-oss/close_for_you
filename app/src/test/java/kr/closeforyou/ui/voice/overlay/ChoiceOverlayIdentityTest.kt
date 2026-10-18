package kr.closeforyou.ui.voice.overlay

import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoiceOverlayIdentityTest {
    private val own = "kr.closeforyou"

    @Test fun eventsFromOwnPackageAreOwnOverlay() {
        assertTrue(ChoiceOverlayIdentity.isOwnOverlayEvent(own, own))
        assertFalse(ChoiceOverlayIdentity.isOwnOverlayEvent("com.example.shop", own))
        assertFalse(ChoiceOverlayIdentity.isOwnOverlayEvent(null, own))
    }

    @Test fun onlyOwnAccessibilityOverlayWindowMatches() {
        val overlay = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
        assertTrue(ChoiceOverlayIdentity.isOwnOverlayWindow(overlay, own, own))
        assertFalse(ChoiceOverlayIdentity.isOwnOverlayWindow(AccessibilityWindowInfo.TYPE_APPLICATION, own, own))
        assertFalse(ChoiceOverlayIdentity.isOwnOverlayWindow(overlay, "com.example.other", own))
    }
}
