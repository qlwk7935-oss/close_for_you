package kr.closeforyou.accessibility

import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PopupCandidateDetectorTest {
    @Test fun boundedApplicationWindowScopesFirstDialogWithoutADialogClassOrBaseline() {
        val detector = PopupCandidateDetector()
        val dialog = frame().copy(windowBounds = ScreenBounds(50, 500, 950, 1_100))
        assertEquals(setOf(PopupSignal.NEW_WINDOW), detector.detect(dialog))
        assertTrue(detector.detect(dialog).isEmpty())
        detector.clear()
        assertEquals(setOf(PopupSignal.NEW_WINDOW), detector.detect(dialog))
        // Status/navigation insets on an ordinary Activity cannot earn popup scope.
        assertFalse(PopupSignal.NEW_WINDOW in PopupCandidateDetector().detect(
            frame().copy(windowBounds = ScreenBounds(0, 60, 1_000, 1_940))))
    }
    private val screen = ScreenBounds(0, 0, 1_000, 2_000)
    private fun frame(
        id: Int = 1,
        application: String = "salted-test-app",
        structure: String = "base",
        outlines: List<DetectionOutline> = emptyList(),
        trigger: PopupTrigger = PopupTrigger.WINDOW_CHANGED,
    ) = DetectionFrame(application, id, setOf(id), structure, screen, outlines, trigger)

    @Test fun `ordinary initial full screen is unknown candidate not ad verdict`() {
        val signals = PopupCandidateDetector().detect(frame())
        assertEquals(setOf(PopupSignal.FULL_SCREEN_TRANSITION, PopupSignal.INSUFFICIENT_EVIDENCE), signals)
    }

    @Test fun `new application window yields candidate and unchanged content does not duplicate`() {
        val detector = PopupCandidateDetector()
        detector.detect(frame())
        assertEquals(setOf(PopupSignal.NEW_WINDOW), detector.detect(frame(id = 2, structure = "dialog")))
        assertTrue(detector.detect(frame(id = 2, structure = "dialog", trigger = PopupTrigger.CONTENT_CHANGED)).isEmpty())
    }

    @Test fun `new bounded subtree in same window yields overlay candidate`() {
        val detector = PopupCandidateDetector()
        detector.detect(frame())
        val overlay = DetectionOutline("new-container-digest", ScreenBounds(50, 200, 950, 1_500), 3, false)
        assertEquals(setOf(PopupSignal.SAME_WINDOW_OVERLAY), detector.detect(
            frame(structure = "overlay", outlines = listOf(overlay), trigger = PopupTrigger.CONTENT_CHANGED)))
    }

    @Test fun `small dialog is detected but new standalone button and full screen are not overlays`() {
        val detector = PopupCandidateDetector()
        detector.detect(frame())
        val small = DetectionOutline("small-dialog", ScreenBounds(400, 900, 600, 1_000), 1, true)
        assertTrue(PopupSignal.SAME_WINDOW_OVERLAY in detector.detect(
            frame(structure = "small", outlines = listOf(small), trigger = PopupTrigger.CONTENT_CHANGED)))
        val standalone = DetectionOutline("button", ScreenBounds(400, 900, 600, 1_000), 0, false)
        val fullScreen = DetectionOutline("full", screen, 5, true)
        assertTrue(detector.detect(frame(structure = "next", outlines = listOf(standalone, fullScreen),
            trigger = PopupTrigger.CONTENT_CHANGED)).isEmpty())
    }

    @Test fun `app change and privacy clearing prevent reuse of previous app baseline`() {
        val detector = PopupCandidateDetector()
        detector.detect(frame())
        assertFalse(PopupSignal.NEW_WINDOW in detector.detect(frame(id = 2, application = "other-app")))
        detector.clear()
        assertFalse(PopupSignal.NEW_WINDOW in detector.detect(frame(id = 3, application = "other-app")))
    }

    @Test fun `every interaction makes previous screen generation stale`() {
        val freshness = ScreenFreshness()
        val captured = freshness.invalidate()
        assertTrue(freshness.isCurrent(captured))
        freshness.invalidate()
        assertFalse(freshness.isCurrent(captured))
        assertTrue(freshness.isCurrent(freshness.version))
    }
}
