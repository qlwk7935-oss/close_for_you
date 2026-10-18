package kr.closeforyou.decision

import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.*
import kr.closeforyou.core.model.PopupSignal.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionCloseCandidateFinderTest {
    private val finder = DecisionCloseCandidateFinder()
    private val base = MockPopupFixtures.notice().snapshot
    private val dialog = ScreenBounds(80, 400, 1000, 1450)
    private val inDialog = ScreenBounds(760, 440, 960, 550)
    private val behindDialog = ScreenBounds(20, 1600, 200, 1700)

    @Test fun fixturesYieldOnlyTheLabeledCloseButton() {
        val notice = MockPopupFixtures.notice().snapshot
        val candidate = finder.find(notice, detection(notice, NEW_WINDOW)).single()
        assertEquals("node:1", candidate.id)
        assertEquals("1", candidate.nodePath)
        assertEquals("fixture:button", candidate.viewId)
        assertEquals("닫기", candidate.label)
        assertEquals(EvidenceLevel.E2_LABELED_NODE, candidate.evidence)
        assertEquals(ButtonRole.CLOSE, candidate.role)

        for (fixture in listOf(MockPopupFixtures.decision(), MockPopupFixtures.unlabeledButton())) {
            assertTrue(fixture.scenarioId, finder.find(fixture.snapshot, detection(fixture.snapshot, NEW_WINDOW)).isEmpty())
        }
    }

    @Test fun closeAndSkipLabelsMatchAsWholeLabels() {
        for (label in listOf("닫기", "광고 닫기", "[닫기]", "닫기 버튼", "Close", "close ad")) {
            assertEquals(label, ButtonRole.CLOSE, find(node("0", text = label)).single().role)
        }
        for (label in listOf("건너뛰기", "광고 건너뛰기", "스킵", "Skip", "Skip Ad")) {
            assertEquals(label, ButtonRole.SKIP, find(node("0", text = label)).single().role)
        }
        assertEquals(ButtonRole.CLOSE, find(node("0", description = "닫기")).single().role)
    }

    @Test fun lookalikeButtonsAreNotCandidates() {
        // A bare X/× is refused by A's gate until its meaning can be proven, so it is not offered either.
        val lookalikes = listOf(
            "X", "x", "×", "✕",
            "오늘 하루 닫기", "7일간 보지 않기", "다시 보지 않기", "오늘 그만 보기", "닫기 전 저장",
            "설치", "자세히 보기", "확인", "OK", "취소", "동의", "무료 체험", "Close and install", "Don't show again",
        )
        for (label in lookalikes) {
            assertTrue(label, find(node("0", text = label)).isEmpty())
        }
    }

    @Test fun labelIsKeptVerbatimForTheGate() {
        // A's gate compares the candidate label with the node text exactly, so trimming here would
        // make it refuse a button B and C offered.
        val padded = find(node("0", text = " 닫기 ")).single()
        assertEquals(" 닫기 ", padded.label)
        assertEquals(ButtonRole.CLOSE, padded.role)
        assertEquals("닫기", find(node("0", text = "닫기", description = " 닫기")).single().label)
        assertTrue(find(node("0", text = "   ")).isEmpty())
    }

    @Test fun textAndDescriptionMustAgree() {
        assertTrue(find(node("0", text = "X", description = "앱 설치")).isEmpty())
        assertTrue(find(node("0", text = "닫기", description = "가상 상품 보기")).isEmpty())
        assertEquals("닫기", find(node("0", text = "닫기", description = "닫기 버튼")).single().label)
    }

    @Test fun nodesThatCannotBePressedAreNotCandidates() {
        assertTrue(find(node("0", text = "닫기", clickable = false)).isEmpty())
        assertTrue(find(node("0", text = "닫기", enabled = false)).isEmpty())
        assertTrue(find(node("0", text = "닫기", actions = emptySet())).isEmpty())
        assertTrue(find(node("0", text = "닫기", bounds = ScreenBounds(760, 440, 760, 550))).isEmpty())
        assertTrue(find(node("0", text = "닫기", bounds = ScreenBounds(1000, 440, 1200, 550))).isEmpty())
    }

    @Test fun labeledDismissActionIsE1Evidence() {
        val labeled = find(container("0", description = "광고 닫기", actions = setOf(NodeAction.DISMISS))).single()
        assertEquals(EvidenceLevel.E1_DISMISS, labeled.evidence)
        assertEquals(ButtonRole.CLOSE, labeled.role)
        assertEquals("광고 닫기", labeled.label)
        // An unlabeled DISMISS is refused by A's gate, so it is not offered as pressable.
        assertTrue(find(container("0", actions = setOf(NodeAction.DISMISS))).isEmpty())
        assertTrue(find(container("0", description = "가상 공지 본문", actions = setOf(NodeAction.DISMISS))).isEmpty())
        assertTrue(find(container("0", description = "오늘 하루 닫기", actions = setOf(NodeAction.DISMISS))).isEmpty())
    }

    @Test fun sameWindowOverlayIsLimitedToOneDialog() {
        val dialogNode = container("0", className = "test.ConfirmDialog")
        val popupClose = node("0/0", text = "닫기")
        val pageClose = node("1", text = "닫기", bounds = behindDialog)

        assertEquals(listOf("node:0/0"), find(dialogNode, popupClose, pageClose, signal = SAME_WINDOW_OVERLAY).map { it.id })
        // Without exactly one Dialog, the page's own buttons cannot be told apart.
        assertTrue(find(popupClose, pageClose, signal = SAME_WINDOW_OVERLAY).isEmpty())
        val otherDialog = container("2", className = "test.OtherDialog", bounds = ScreenBounds(0, 0, 500, 300))
        assertTrue(find(dialogNode, otherDialog, popupClose, pageClose, signal = SAME_WINDOW_OVERLAY).isEmpty())
    }

    @Test fun pageButtonUnderTheDialogBoundsIsNotTheDialogs() {
        // PR #8 review: a page node drawn inside the Dialog's area but outside its subtree.
        val dialogNode = container("0/1", className = "test.ConfirmDialog")
        val popupClose = node("0/1/0", text = "닫기")
        val overlappingPageClose = node("0/0", text = "닫기", bounds = inDialog)
        assertEquals(listOf("node:0/1/0"), find(dialogNode, popupClose, overlappingPageClose, signal = SAME_WINDOW_OVERLAY).map { it.id })
        // A sibling whose path only starts with the same digits is not a descendant either.
        val lookalikeSibling = node("0/10", text = "닫기", bounds = inDialog)
        assertEquals(listOf("node:0/1/0"), find(dialogNode, popupClose, lookalikeSibling, signal = SAME_WINDOW_OVERLAY).map { it.id })
    }

    @Test fun dialogClassesNestedInOneDialogStillCount() {
        val dialogNode = container("0", className = "test.ConfirmDialog")
        val dialogLayout = container("0/0", className = "androidx.appcompat.app.AlertDialogLayout", bounds = inDialog)
        val popupClose = node("0/0/0", text = "닫기")
        assertEquals(listOf("node:0/0/0"), find(dialogNode, dialogLayout, popupClose, signal = SAME_WINDOW_OVERLAY).map { it.id })
    }

    @Test fun onlyANewWindowIsSearchedWhole() {
        val pageClose = node("1", text = "닫기", bounds = behindDialog)
        assertEquals(1, find(pageClose, signal = NEW_WINDOW).size)
        // PR #8 review: a full-screen transition alone may be an ordinary Activity change.
        assertTrue(find(pageClose, signal = FULL_SCREEN_TRANSITION).isEmpty())
    }

    @Test fun uncertainDetectionsGiveNothing() {
        // A always pairs an uncertain full-screen transition with INSUFFICIENT_EVIDENCE: it may be
        // an ordinary page change, so its own close buttons must not be offered as the popup's.
        val snapshot = snapshot(node("0", text = "닫기"))
        assertTrue(finder.find(snapshot, detection(snapshot, FULL_SCREEN_TRANSITION, INSUFFICIENT_EVIDENCE)).isEmpty())
        assertTrue(finder.find(snapshot, detection(snapshot, NEW_WINDOW, INSUFFICIENT_EVIDENCE)).isEmpty())
    }

    @Test fun staleDetectionMissingSignalsOrNoNodesGiveNothing() {
        val snapshot = snapshot(node("0", text = "닫기"))
        val stale = PopupDetection(MockPopupFixtures.advertisement().snapshot.token, persistentSetOf(NEW_WINDOW))
        assertTrue(finder.find(snapshot, stale).isEmpty())
        assertTrue(finder.find(snapshot, detection(snapshot, INSUFFICIENT_EVIDENCE)).isEmpty())
        // A's decision-summary path publishes a snapshot without nodes.
        assertTrue(find().isEmpty())
    }

    @Test fun foundCandidatesFeedTheSafetyPolicy() = runBlocking {
        val policy = DecisionActionPolicy()
        val fixture = MockPopupFixtures.notice()
        val candidates = finder.find(fixture.snapshot, detection(fixture.snapshot, NEW_WINDOW))
        val analysis = fixture.analysis.copy(
            closeCandidates = candidates,
            limitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED),
        )
        val ask = policy.decide(analysis, DecisionContext(analysis.token, null, null, false, true))
        assertEquals(ActionAuthorization.NEEDS_CONFIRMATION, ask.authorization)
        assertEquals("node:1", ask.candidateId)

        val choice = UserChoice(analysis.token, ChoiceKind.CLOSE_ONCE, "node:1", buttonMeaningConfirmed = true)
        val allowed = policy.decide(analysis, DecisionContext(analysis.token, choice, null, false, true))
        assertEquals(ActionAuthorization.ALLOW_ONCE, allowed.authorization)
        assertEquals("node:1", allowed.candidateId)
    }

    private fun find(vararg nodes: NodeSnapshot, signal: PopupSignal = NEW_WINDOW): List<CloseCandidate> {
        val snapshot = snapshot(*nodes)
        return finder.find(snapshot, detection(snapshot, signal))
    }

    private fun snapshot(vararg nodes: NodeSnapshot) = base.copy(nodes = nodes.toList().toPersistentList())

    private fun detection(snapshot: PopupSnapshot, vararg signals: PopupSignal) =
        PopupDetection(snapshot.token, signals.toSet().toPersistentSet())

    private fun node(
        path: String,
        text: String? = null,
        description: String? = null,
        clickable: Boolean = true,
        enabled: Boolean = true,
        bounds: ScreenBounds = inDialog,
        actions: Set<NodeAction> = setOf(NodeAction.CLICK),
    ) = NodeSnapshot(path, "test:$path", "android.widget.Button", text, description, clickable, enabled, bounds, actions.toPersistentSet())

    private fun container(
        path: String,
        description: String? = null,
        className: String = "android.widget.FrameLayout",
        bounds: ScreenBounds = dialog,
        actions: Set<NodeAction> = emptySet(),
    ) = NodeSnapshot(path, null, className, null, description, false, true, bounds, actions.toPersistentSet())
}
