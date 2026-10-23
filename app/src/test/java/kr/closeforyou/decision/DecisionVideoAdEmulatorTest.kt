package kr.closeforyou.decision

import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * F5 on trees dumped from an API 37 emulator (`uiautomator dump`, 2026-10-22) of D's popup test app
 * P16~P18 (PR #26, synthetic screens): the skip countdown is read only where it really is one.
 *
 * Built like A's collector (paths "0/1/2", text and description verbatim, tree texts). Assumed
 * because a dump does not carry them: A's signal (Dialog window → NEW_WINDOW, activity-drawn →
 * SAME_WINDOW_OVERLAY), CLICK from `clickable`, and `videoSurfaceObserved` = false because the
 * test app's "video" has no SurfaceView/TextureView/VideoView node, so A could not see a video.
 */
class DecisionVideoAdEmulatorTest {
    private data class Screen(val analysis: PopupAnalysis, val report: VideoAdReport)

    private fun screen(file: String, signal: PopupSignal): Screen {
        val token = MockPopupFixtures.notice().snapshot.token.copy(popupInstanceId = file)
        val nodes = parse(file)
        val snapshot = MockPopupFixtures.notice().snapshot.copy(token = token, nodes = nodes.toImmutableList(), screenBounds = ScreenBounds(0, 0, 1080, 2400))
        val texts = nodes.flatMap { node ->
            listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }
                .map { ExtractedText(it, node.bounds, TextSource.ACCESSIBILITY_TREE, null) }
        }.distinctBy { it.text to it.bounds }
        val candidates = DecisionCloseCandidateFinder().find(snapshot, PopupDetection(token, persistentSetOf(signal)))
        val analysis = PopupAnalysis(token = token, texts = texts.toImmutableList(), closeCandidates = candidates, videoSurfaceObserved = false)
        val classified = analysis.copy(classification = DecisionPopupClassifier().displayClassification(analysis))
        return Screen(classified, DecisionVideoAd.inspect(classified))
    }

    @Test fun p16CountdownIsReadButTheTestVideoHasNoVideoSurface() {
        val s = screen("p16_countdown.xml", PopupSignal.SAME_WINDOW_OVERLAY)
        assertEquals("\"3초 후 건너뛸 수 있어요\" is a skip wait", 3, s.report.countdownSeconds)
        assertEquals(TextSource.ACCESSIBILITY_TREE, s.report.countdownSource)
        assertTrue("\"영상 광고(시험용)\" is advertisement wording", s.report.advertisementEvidence)
        // No video surface node in the test app, so this is not (yet) a "video ad" for C to announce.
        assertFalse(s.report.videoObserved)
        assertFalse(s.report.isVideoAd)
        assertTrue(s.report.skipCandidateIds.isEmpty())
    }

    @Test fun p16SkipButtonAfterTheCountdownIsNotOfferedInAnActivityDrawnOverlay() {
        val s = screen("p16_skip_shown.xml", PopupSignal.SAME_WINDOW_OVERLAY)
        assertNull("the countdown is gone; \"재생 중 0:08\" is not a wait", s.report.countdownSeconds)
        // The 건너뛰기 button exists, but an activity-drawn overlay has no Dialog node (checklist P8),
        // so B offers no candidate: a known false refusal, never a wrong press.
        assertTrue(s.analysis.closeCandidates.isEmpty())
        assertTrue(s.report.skipCandidateIds.isEmpty())
    }

    @Test fun p17DialogWithSkipAndCloseOffersBothAndListsTheSkip() = runBlocking {
        val s = screen("p17_skip_and_close.xml", PopupSignal.NEW_WINDOW)
        assertEquals(setOf(ButtonRole.SKIP to "건너뛰기", ButtonRole.CLOSE to "닫기"), s.analysis.closeCandidates.map { it.role to it.label }.toSet())
        assertEquals(listOf(s.analysis.closeCandidates.single { it.role == ButtonRole.SKIP }.id), s.report.skipCandidateIds)
        assertNull(s.report.countdownSeconds)
        assertEquals(PopupClassification.ADVERTISEMENT, s.analysis.classification)
        // First seen with two verified buttons: ask, never pick one.
        val firstSeen = DecisionActionPolicy().decide(s.analysis, DecisionContext(s.analysis.token, null, null, false, true))
        assertEquals(ActionAuthorization.NEEDS_CONFIRMATION, firstSeen.authorization)
        assertNull(firstSeen.candidateId)
    }

    @Test fun p18TimeSaleWordingIsNeverASkipCountdown() {
        val s = screen("p18_time_sale.xml", PopupSignal.NEW_WINDOW)
        // "세일 종료까지 04:57" and "5초 안에 결제하면 추가 할인" are not skip waits (review on #20).
        assertNull(s.report.countdownSeconds)
        assertNull(s.report.countdownSource)
        assertTrue(s.report.skipCandidateIds.isEmpty())
        // "5초 안에 결제하면 추가 할인" mentions 결제 only as a benefit condition (R1), so it is no longer
        // a decision; with no word from B's advertisement dictionary it is "unknown", never a decision.
        assertEquals(PopupClassification.UNKNOWN, s.analysis.classification)
        assertFalse(s.report.advertisementEvidence)
    }

    // ---- uiautomator XML → A's node DTOs (same rules as A's AndroidSnapshotCollector) ----

    private fun parse(file: String): List<NodeSnapshot> {
        val stream = requireNotNull(javaClass.classLoader!!.getResourceAsStream("kr/closeforyou/decision/emulator/$file")) { file }
        val document = stream.use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
        val out = mutableListOf<NodeSnapshot>()
        fun visit(element: Element, path: String) {
            out += node(element, path)
            element.childElements().forEachIndexed { index, child -> visit(child, "$path/$index") }
        }
        visit(document.documentElement.childElements().single(), "0")
        return out
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == "node" }

    private fun node(e: Element, path: String): NodeSnapshot {
        val (l, t, r, b) = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(e.getAttribute("bounds"))!!.destructured
        val clickable = e.getAttribute("clickable") == "true"
        return NodeSnapshot(
            path = path,
            viewId = e.getAttribute("resource-id").ifEmpty { null },
            className = e.getAttribute("class").ifEmpty { null },
            text = e.getAttribute("text").ifEmpty { null },
            contentDescription = e.getAttribute("content-desc").ifEmpty { null },
            clickable = clickable,
            enabled = e.getAttribute("enabled") == "true",
            bounds = ScreenBounds(l.toInt(), t.toInt(), r.toInt(), b.toInt()),
            actions = if (clickable) persistentSetOf(NodeAction.CLICK) else persistentSetOf(),
        )
    }
}
