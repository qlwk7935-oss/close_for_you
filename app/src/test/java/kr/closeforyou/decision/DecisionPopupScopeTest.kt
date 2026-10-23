package kr.closeforyou.decision

import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Test

/** Warnings are read widely, reasons to close narrowly (AUTO_CLOSE.md 5절). */
class DecisionPopupScopeTest {
    private val token: SnapshotToken = MockPopupFixtures.notice().snapshot.token
    private val page = ScreenBounds(0, 0, 1080, 2400)
    private val modal = ScreenBounds(80, 900, 1000, 1500)

    private fun node(path: String, cls: String, text: String?, bounds: ScreenBounds) =
        NodeSnapshot(path, null, cls, text, null, false, true, bounds)

    private val nodes = listOf(
        node("0", "android.widget.FrameLayout", null, page),
        node("0/0", "android.widget.TextView", "쿠폰 모음 페이지", ScreenBounds(40, 100, 1040, 200)),
        node("0/1", "android.widget.TextView", "로그인하고 결제하기", ScreenBounds(40, 200, 1040, 300)),
        // Page text lying under the modal's bounds is still the page's, not the popup's.
        node("0/2", "android.widget.TextView", "오늘의 특가", ScreenBounds(100, 1000, 900, 1100)),
        node("0/3", "android.app.Dialog", null, modal),
        node("0/3/0", "android.widget.TextView", "알림", ScreenBounds(100, 950, 900, 1050)),
        node("0/3/1", "android.widget.TextView", "운영 시간 안내", ScreenBounds(100, 1050, 900, 1150)),
        node("0/3/2", "android.widget.Button", "닫기", ScreenBounds(100, 1350, 900, 1450)),
    )

    private fun snapshot(list: List<NodeSnapshot> = nodes) = PopupSnapshot(token, 0, list.toPersistentList(), persistentListOf(), page,
        PopupTrigger.CONTENT_CHANGED, SnapshotCompleteness.COMPLETE)

    private fun treeTexts(list: List<NodeSnapshot> = nodes) =
        list.mapNotNull { n -> n.text?.let { ExtractedText(it, n.bounds, TextSource.ACCESSIBILITY_TREE, null) } }

    private fun scoped(signal: PopupSignal, list: List<NodeSnapshot> = nodes, texts: List<ExtractedText> = treeTexts(list)) =
        DecisionPopupScope.texts(snapshot(list), PopupDetection(token, persistentSetOf(signal)), texts).map { it.text }

    @Test fun overlayKeepsPopupTextsAndPageWarningsOnly() {
        assertEquals(listOf("로그인하고 결제하기", "알림", "운영 시간 안내", "닫기"), scoped(PopupSignal.SAME_WINDOW_OVERLAY))
        // So the page's "쿠폰"/"특가" no longer make the notice look like a coupon or an ad, while the
        // page's decision words still make the whole screen a decision.
        val withoutPageWarning = nodes.filterNot { it.path == "0/1" }
        val analysis = PopupAnalysis(token, texts = treeTexts(withoutPageWarning).filter { it.text in scoped(PopupSignal.SAME_WINDOW_OVERLAY, withoutPageWarning) }.toPersistentList())
        assertEquals(DecisionKind.NOTICE, DecisionKinds.of(analysis))
        assertEquals(DecisionKind.COUPON, DecisionKinds.of(PopupAnalysis(token, texts = treeTexts(withoutPageWarning).toPersistentList())))
    }

    @Test fun aPopupStringRepeatedOnThePageIsKeptOnlyWherePopupNodesCarryIt() {
        val texts = treeTexts() + ExtractedText("운영 시간 안내", ScreenBounds(40, 300, 1040, 400), TextSource.ACCESSIBILITY_TREE, null)
        assertEquals(1, scoped(PopupSignal.SAME_WINDOW_OVERLAY, texts = texts).count { it == "운영 시간 안내" })
    }

    @Test fun ocrIsKeptOnlyInsideThePopup() {
        val texts = treeTexts() + listOf(
            ExtractedText("광고", ScreenBounds(100, 1200, 900, 1300), TextSource.OCR, 0.9f),
            ExtractedText("특가", ScreenBounds(100, 100, 900, 200), TextSource.OCR, 0.9f),
            ExtractedText("바이러스 감염", ScreenBounds(100, 100, 900, 200), TextSource.OCR, 0.9f),
        )
        val out = scoped(PopupSignal.SAME_WINDOW_OVERLAY, texts = texts)
        assertEquals(listOf("광고", "바이러스 감염"), out.filter { it in setOf("광고", "특가", "바이러스 감염") })
    }

    @Test fun everyPageWarningKindSurvives() {
        for (warning in listOf("구독하기", "개인정보 동의", "당첨되었습니다", "확인되지 않은 버튼", "결제 선택 필요", "예약하시겠어요?", "허 용")) {
            val list = nodes + node("0/9", "android.widget.TextView", warning, ScreenBounds(40, 2000, 1040, 2100))
            assertEquals(warning, true, warning in scoped(PopupSignal.SAME_WINDOW_OVERLAY, list))
        }
        // A page tab or ordinary page question is not a warning about the popup (review round 2).
        for (pageText in listOf("점검 공지", "공지사항", "무엇을 찾으세요?", "이벤트 참여하기")) {
            val list = nodes + node("0/9", "android.widget.TextView", pageText, ScreenBounds(40, 2000, 1040, 2100))
            assertEquals(pageText, false, pageText in scoped(PopupSignal.SAME_WINDOW_OVERLAY, list))
        }
    }

    @Test fun otherScreensAreLeftAlone() {
        val all = treeTexts().map { it.text }
        assertEquals(all, scoped(PopupSignal.NEW_WINDOW))
        assertEquals(all, scoped(PopupSignal.FULL_SCREEN_TRANSITION))
        assertEquals(all, scoped(PopupSignal.INSUFFICIENT_EVIDENCE))
        val noDialog = nodes.map { if (it.path == "0/3") it.copy(className = "android.widget.FrameLayout") else it }
        assertEquals(treeTexts(noDialog).map { it.text }, scoped(PopupSignal.SAME_WINDOW_OVERLAY, noDialog))
        val twoDialogs = nodes + node("0/4", "android.app.Dialog", "다른 창", ScreenBounds(80, 1600, 1000, 1800))
        assertEquals(treeTexts(twoDialogs).map { it.text }, scoped(PopupSignal.SAME_WINDOW_OVERLAY, twoDialogs))
        val other = DecisionPopupScope.texts(snapshot(), PopupDetection(token.copy(screenVersion = 9), persistentSetOf(PopupSignal.SAME_WINDOW_OVERLAY)), treeTexts())
        assertEquals(all, other.map { it.text })
    }
}
