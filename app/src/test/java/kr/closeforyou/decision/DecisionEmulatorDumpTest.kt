package kr.closeforyou.decision

import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * B's real classifier, candidate finder and policy on accessibility trees dumped from an API 37
 * emulator (`uiautomator dump`, 2026-10-21) of D's popup test app (PR #16, synthetic screens only).
 *
 * Nodes are built the way A's AndroidSnapshotCollector builds them (paths "0/1/2", text and
 * contentDescription verbatim) and texts the way A's tree evidence does. Two things are assumed
 * because a dump does not carry them: A's detection signal (a Dialog window → NEW_WINDOW, anything
 * drawn in the activity → SAME_WINDOW_OVERLAY) and the CLICK action (from `clickable`). These pin
 * today's behaviour, including the known false refusals and over-warnings listed per screen.
 */
class DecisionEmulatorDumpTest {
    private data class Screen(val analysis: PopupAnalysis, val candidates: List<CloseCandidate>, val classification: PopupClassification)

    private fun screen(file: String, signal: PopupSignal): Screen = runBlocking {
        val token = MockPopupFixtures.notice().snapshot.token.copy(popupInstanceId = file)
        val nodes = parse(file)
        val snapshot = MockPopupFixtures.notice().snapshot.copy(
            token = token, nodes = nodes.toImmutableList(), screenBounds = ScreenBounds(0, 0, 1080, 2400),
        )
        val texts = nodes.flatMap { node ->
            listOfNotNull(node.text, node.contentDescription).filter { it.isNotBlank() }
                .map { ExtractedText(it, node.bounds, TextSource.ACCESSIBILITY_TREE, null) }
        }.distinctBy { it.text to it.bounds }
        val candidates = DecisionCloseCandidateFinder().find(snapshot, PopupDetection(token, persistentSetOf(signal)))
        val analysis = PopupAnalysis(
            token = token, texts = texts.toImmutableList(), closeCandidates = candidates,
            limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED),
        )
        val classification = DecisionPopupClassifier().classify(analysis)
        Screen(analysis.copy(classification = classification), candidates, classification)
    }

    /** The person taps the named close button on C's prompt and confirms it. */
    private fun pressClose(screen: Screen): ActionDecision = runBlocking {
        val token = screen.analysis.token
        val id = screen.candidates.single { it.role == ButtonRole.CLOSE }.id
        DecisionActionPolicy().decide(
            screen.analysis,
            DecisionContext(token, UserChoice(token, ChoiceKind.CLOSE_ONCE, id, buttonMeaningConfirmed = true), null, false, true),
        )
    }

    private fun Screen.summary() = candidates.map { "${it.role}:${it.evidence}:${it.label}:${it.viewId?.substringAfterLast('/')}" }

    @Test fun p01DialogAdvertisementOffersOnlyItsCloseButton() {
        val s = screen("p01_dialog_ad.xml", PopupSignal.NEW_WINDOW)
        assertEquals(listOf("CLOSE:E2_LABELED_NODE:닫기:btn_close"), s.summary())
        // "가을 할인 쿠폰 · 지금 받으면 30퍼센트 할인": a claimable coupon (contract v2 COUPON).
        assertEquals(PopupClassification.COUPON, s.classification)
        val decision = pressClose(s)
        assertEquals("the person's confirmed choice may reach A's gate once", ActionAuthorization.ALLOW_ONCE, decision.authorization)
    }

    @Test fun p02AppDrawnOverlayIsAKnownFalseRefusalAndOverWarning() {
        val s = screen("p02_same_window_overlay.xml", PopupSignal.SAME_WINDOW_OVERLAY)
        // No Dialog node in an app-drawn overlay (checklist P8): no candidate, so nothing can be pressed.
        assertTrue(s.candidates.isEmpty())
        // "신규 가입 이벤트" contains 가입, a decision word: over-warning (CLASSIFIER_EVAL review 6).
        assertEquals(PopupClassification.USER_DECISION, s.classification)
    }

    @Test fun p03BottomBannerHasNoCandidateButIsAnAdvertisement() {
        val s = screen("p03_bottom_banner.xml", PopupSignal.SAME_WINDOW_OVERLAY)
        // No Dialog node, so no popup scope and no candidate.
        assertTrue(s.candidates.isEmpty())
        // "앱 설치하고 혜택 받기" is an app-install advertisement since 10/30 (WEB_OVERLAY.md 3-2).
        assertEquals(PopupClassification.ADVERTISEMENT, s.classification)
    }

    @Test fun p04UnlabeledXIsNotACandidate() {
        val s = screen("p04_unlabeled_x.xml", PopupSignal.NEW_WINDOW)
        assertTrue("the unlabeled ImageButton and 자세히 보기 are not close buttons", s.candidates.isEmpty())
        assertEquals(PopupClassification.ADVERTISEMENT, s.classification)
    }

    @Test fun p13FirstWebViewQueryShowsNoWebContent() {
        val s = screen("p13_webview_first_query.xml", PopupSignal.SAME_WINDOW_OVERLAY)
        assertTrue(s.candidates.isEmpty())
        // Only the test app's title "P13 WebView 모달 광고" is readable: the scaffolding, not the popup.
        assertEquals(PopupClassification.ADVERTISEMENT, s.classification)
    }

    @Test fun p13WebModalCloseIsFoundButPageLinksMakeItADecision() {
        val s = screen("p13_w01_web_modal.xml", PopupSignal.SAME_WINDOW_OVERLAY)
        // role="dialog" is exposed as android.app.AlertDialog, so the modal's own 닫기 is found.
        assertEquals(listOf("CLOSE:E2_LABELED_NODE:닫기:null"), s.summary())
        // The page behind the modal has links "W06 가짜 결제 확인" and "W07 가짜 동의 요청". B reads every
        // text of the snapshot, so the popup is shown as a decision and the close is refused.
        assertEquals(PopupClassification.USER_DECISION, s.classification)
        val decision = pressClose(s)
        assertEquals(ActionAuthorization.DENY, decision.authorization)
        assertTrue(SafetyReason.IMPORTANT_DECISION in decision.reasons)
    }

    @Test fun p13WebDivXsAreNeverCandidates() {
        val unlabeled = screen("p13_w02_web_unlabeled_x.xml", PopupSignal.SAME_WINDOW_OVERLAY)
        assertTrue("only 자세히 보기 is a button; the div X has no label and is not clickable", unlabeled.candidates.isEmpty())
        val fake = screen("p13_w03_web_fake_x.xml", PopupSignal.SAME_WINDOW_OVERLAY)
        assertEquals("the real 닫기, never the fake X", listOf("CLOSE:E2_LABELED_NODE:닫기:null"), fake.summary())
    }

    @Test fun anUncertainSignalNeverOffersAnything() {
        for (file in listOf("p01_dialog_ad.xml", "p13_w01_web_modal.xml")) {
            assertTrue(file, screen(file, PopupSignal.INSUFFICIENT_EVIDENCE).candidates.isEmpty())
        }
    }

    // ---- uiautomator XML → A's node DTOs ----

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
            actions = if (clickable) persistentSetOf(NodeAction.CLICK) else persistentSetOf<NodeAction>().toImmutableSet(),
        )
    }
}
