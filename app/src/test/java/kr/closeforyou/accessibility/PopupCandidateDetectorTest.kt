package kr.closeforyou.accessibility

import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PopupCandidateDetectorTest {
    private val screen = ScreenBounds(0, 0, 1_000, 2_000)
    private val dialog = ScreenBounds(100, 500, 900, 1_200)
    private fun frame(id: Int = 1, application: String = "salted-app", structure: String = "base",
        outlines: List<DetectionOutline> = emptyList(), trigger: PopupTrigger = PopupTrigger.CONTENT_CHANGED,
        window: ScreenBounds = screen) = DetectionFrame(application, id, setOf(id), structure, screen, outlines, trigger, window)
    private fun node(key: String, path: String, bounds: ScreenBounds = dialog, semantic: Boolean = false,
        pane: Boolean = false, scroll: Boolean = false, visible: Boolean = true, children: Int = 2) =
        DetectionOutline(key, bounds, children, semantic, path, pane, visible, scroll)
    private fun assertNotCandidate(result: DetectionAssessment) = assertNotEquals(DetectionDisposition.POPUP_CANDIDATE, result.disposition)

    @Test fun `ordinary first screen establishes baseline without popup`() {
        assertEquals(DetectionReason.BASELINE_ONLY, PopupCandidateDetector().assess(frame()).reason)
    }
    @Test fun `new full screen Activity remains an uncertain transition not a popup`() {
        val detector = PopupCandidateDetector()
        detector.assess(frame())
        val result = detector.assess(frame(id = 2, structure = "new-page", trigger = PopupTrigger.WINDOW_CHANGED))
        assertEquals(DetectionDisposition.UNCERTAIN, result.disposition)
        assertEquals(setOf(PopupSignal.FULL_SCREEN_TRANSITION, PopupSignal.INSUFFICIENT_EVIDENCE), result.signals)
    }
    @Test fun `bounded native window works without Dialog class or interactive background`() {
        val result = PopupCandidateDetector().assess(frame(id = 9, window = dialog))
        assertEquals(DetectionDisposition.POPUP_CANDIDATE, result.disposition)
        assertEquals(setOf(PopupSignal.NEW_WINDOW), result.signals)
    }
    @Test fun `two sequential native Dialog windows are each detected once`() {
        val detector = PopupCandidateDetector()
        detector.assess(frame())
        for (id in listOf(2, 3)) {
            assertEquals(DetectionDisposition.POPUP_CANDIDATE, detector.assess(frame(id, window = dialog)).disposition)
            assertNotCandidate(detector.assess(frame(id, structure = "changed-size-or-children", window = dialog)))
        }
    }
    @Test fun `restored Activity is not another popup`() {
        val detector = PopupCandidateDetector()
        detector.assess(frame())
        detector.assess(frame(id = 2, window = dialog))
        assertNotCandidate(detector.assess(frame(id = 1)))
    }
    @Test fun `ordinary inset Activity window is not a dialog`() {
        val result = PopupCandidateDetector().assess(frame(window = ScreenBounds(0, 30, 1_000, 1_950)))
        assertNotCandidate(result)
    }
    @Test fun `new generic container no longer becomes an overlay from child count alone`() {
        val detector = PopupCandidateDetector()
        detector.assess(frame())
        val result = detector.assess(frame(structure = "layout", outlines = listOf(node("container", "0/1"))))
        assertEquals(DetectionDisposition.UNCERTAIN, result.disposition)
        assertEquals(DetectionReason.UNCONFIRMED_STRUCTURE, result.reason)
    }
    @Test fun `new pane needs a stable overlapping peer underlay`() {
        val detector = PopupCandidateDetector()
        val underlay = node("background", "0/0", screen)
        detector.assess(frame(outlines = listOf(underlay)))
        val result = detector.assess(frame(structure = "overlay", outlines = listOf(underlay, node("pane", "0/1", pane = true))))
        assertEquals(DetectionReason.PANE_WITH_UNDERLAY, result.reason)
        assertEquals(setOf(PopupSignal.SAME_WINDOW_OVERLAY), result.signals)
    }
    @Test fun `pane title on ordinary page or ancestor alone cannot prove popup`() {
        val detector = PopupCandidateDetector()
        detector.assess(frame(outlines = listOf(node("root", "0", screen))))
        assertNotCandidate(detector.assess(frame(structure = "page", outlines = listOf(
            node("root", "0", screen), node("page-pane", "0/0", pane = true)))))
    }
    @Test fun `moving stable semantic dialog or adding children does not rediscover it`() {
        val detector = PopupCandidateDetector()
        val semantic = node("stable", "0/1", semantic = true)
        assertEquals(DetectionDisposition.POPUP_CANDIDATE, detector.assess(frame(outlines = listOf(semantic))).disposition)
        assertNotCandidate(detector.assess(frame(structure = "moved", outlines = listOf(semantic.copy(
            bounds = ScreenBounds(90, 520, 910, 1_250), childCount = 4)))))
    }
    @Test fun `removed then reappearing same window dialog is detected again`() {
        val detector = PopupCandidateDetector()
        val modal = node("stable", "0/1", semantic = true)
        detector.assess(frame(outlines = listOf(modal)))
        detector.assess(frame(structure = "removed"))
        assertEquals(DetectionDisposition.POPUP_CANDIDATE, detector.assess(frame(structure = "returned", outlines = listOf(modal))).disposition)
    }
    @Test fun `scroll descendants and invisible or offscreen dialogs cannot become candidates`() {
        for (outlines in listOf(
            listOf(node("scroll", "0/0", screen, scroll = true), node("row-dialog", "0/0/1", semantic = true)),
            listOf(node("hidden", "0/1", semantic = true, visible = false)),
            listOf(node("outside", "0/1", ScreenBounds(-100, 20, 300, 300), semantic = true)),
        )) assertNotCandidate(PopupCandidateDetector().assess(frame(outlines = outlines)))
    }
    @Test fun `button or full screen pane is not a bounded semantic popup`() {
        assertNotCandidate(PopupCandidateDetector().assess(frame(outlines = listOf(
            node("button", "0/0", children = 0), node("full", "0/1", screen, semantic = true)))))
    }
    @Test fun `invalid native bounds fail closed and discard baseline`() {
        val detector = PopupCandidateDetector()
        detector.assess(frame())
        assertEquals(DetectionReason.INVALID_GEOMETRY, detector.assess(frame(window = ScreenBounds(-1, 0, 1_000, 2_000))).reason)
        assertEquals(DetectionReason.BASELINE_ONLY, detector.assess(frame()).reason)
    }
    @Test fun `app change privacy clear and screen rotation do not reuse an old baseline`() {
        val detector = PopupCandidateDetector()
        detector.assess(frame())
        assertEquals(DetectionReason.BASELINE_ONLY, detector.assess(frame(id = 2, application = "other")).reason)
        detector.clear()
        assertEquals(DetectionReason.BASELINE_ONLY, detector.assess(frame()).reason)
        assertEquals(DetectionReason.BASELINE_ONLY, detector.assess(frame().copy(screenBounds = ScreenBounds(0, 0, 2_000, 1_000),
            windowBounds = ScreenBounds(0, 0, 2_000, 1_000))).reason)
    }
    @Test fun `unchanged notification or scroll snapshot cannot manufacture a popup`() {
        val detector = PopupCandidateDetector()
        detector.assess(frame())
        repeat(10) { assertEquals(DetectionReason.UNCHANGED, detector.assess(frame()).reason) }
    }
    @Test fun `every interaction makes previous screen generation stale`() {
        val freshness = ScreenFreshness()
        val captured = freshness.invalidate()
        assertTrue(freshness.isCurrent(captured))
        freshness.invalidate()
        assertFalse(freshness.isCurrent(captured))
    }
}
