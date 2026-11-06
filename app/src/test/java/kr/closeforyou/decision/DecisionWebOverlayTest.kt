package kr.closeforyou.decision

import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 11번가 mobile web bottom sheet as Chrome exposed it on the API 37 emulator (2026-10-30,
 * WEB_OVERLAY.md 2절): a role="dialog" sheet becomes an android.app.AlertDialog node inside the
 * WebView, and the page footer keeps zero-height links ("로그인", "[당첨자공지] …") below the screen.
 * Structure and bounds follow the dump; texts are the public page's.
 */
class DecisionWebOverlayTest {
    private val token: SnapshotToken = MockPopupFixtures.notice().snapshot.token
    private val screen = ScreenBounds(0, 0, 1080, 2400)
    private val web = "0/1/0"
    private val sheet = "$web/6"

    private fun node(path: String, cls: String, text: String?, bounds: ScreenBounds, clickable: Boolean = false, desc: String? = null) =
        NodeSnapshot(path, null, cls, text, desc, clickable, true, bounds,
            if (clickable) persistentSetOf(NodeAction.CLICK) else persistentSetOf())

    private val nodes = listOf(
        node("0", "android.widget.FrameLayout", null, screen),
        node("0/0", "android.widget.EditText", "m.11st.co.kr/page/main/home", ScreenBounds(211, 136, 691, 283), clickable = true),
        node("0/1", "android.webkit.WebView", "11번가 모바일", ScreenBounds(0, 283, 1080, 2337)),
        node(web, "android.view.View", null, ScreenBounds(0, 283, 1080, 2337)),
        node("$web/0", "android.view.View", null, ScreenBounds(0, 283, 1080, 437), clickable = true),
        node("$web/0/1", "android.widget.TextView", "11번가 앱에서 앱 한정 혜택 받기", ScreenBounds(170, 327, 669, 372)),
        node("$web/0/2", "android.widget.TextView", "앱 열기", ScreenBounds(808, 330, 924, 377)),
        node("$web/0/3", "android.widget.Button", "닫기", ScreenBounds(942, 301, 1039, 401), clickable = true),
        node("$web/1", "android.widget.Button", "검색하기", ScreenBounds(42, 579, 1039, 682), clickable = true),
        // Footer below the WebView's visible area: zero height, never on screen.
        node("$web/5", "android.view.View", null, ScreenBounds(0, 2337, 1080, 2337)),
        node("$web/5/0", "android.widget.TextView", "[당첨자공지] 10월15일 라이브 이벤트", ScreenBounds(123, 2337, 821, 2337)),
        node("$web/5/1", "android.widget.TextView", "로그인", ScreenBounds(160, 2337, 257, 2337)),
        node("$web/5/2", "android.widget.TextView", "개인정보처리방침", ScreenBounds(732, 2337, 950, 2337)),
        node(sheet, "android.app.AlertDialog", null, ScreenBounds(0, 1632, 1080, 2337)),
        node("$sheet/0", "android.widget.Button", "앱설치 안내 레이어 닫기", ScreenBounds(0, 1632, 1080, 1676), clickable = true),
        node("$sheet/1/0", "android.widget.TextView", "앱에서만 제공되는 혜택을 놓치고 계신 건 아닌가요?", ScreenBounds(63, 1747, 756, 1886)),
        node("$sheet/1/1", "android.widget.TextView", "지금 바로 11번가 앱에서 확인해보세요!", ScreenBounds(63, 1905, 756, 2012)),
        node("$sheet/2", "android.view.View", null, ScreenBounds(42, 2062, 1039, 2191), clickable = true, desc = "11번가 앱에서 확인"),
        node("$sheet/2/0", "android.widget.TextView", "11번가 앱에서 확인", ScreenBounds(357, 2102, 721, 2157)),
        node("$sheet/3", "android.widget.Button", "모바일 웹으로 볼래요", ScreenBounds(388, 2230, 690, 2275), clickable = true),
    )

    private fun snapshot(list: List<NodeSnapshot> = nodes) = PopupSnapshot(token, 0, list.toPersistentList(), persistentListOf(), screen,
        PopupTrigger.CONTENT_CHANGED, SnapshotCompleteness.COMPLETE)

    private val detection = PopupDetection(token, persistentSetOf(PopupSignal.SAME_WINDOW_OVERLAY))

    private fun texts(list: List<NodeSnapshot>) =
        list.flatMap { n -> listOfNotNull(n.text, n.contentDescription).map { ExtractedText(it, n.bounds, TextSource.ACCESSIBILITY_TREE, null) } }

    private fun scopedKind(list: List<NodeSnapshot> = nodes): DecisionKind =
        DecisionKinds.of(PopupAnalysis(token, texts = DecisionPopupScope.texts(snapshot(list), detection, texts(list)).toPersistentList()))

    @Test fun chromeSheetIsOneDialogScope() {
        val scope = DecisionCloseCandidateFinder().popupScope(snapshot(), detection.signals)
        assertEquals(sheet, scope?.rootPath)
    }

    @Test fun hiddenFooterNoLongerMakesTheSheetAScamOrADecision() {
        // Before WEB_OVERLAY.md 3-1 the footer's "로그인" decided the sheet's kind. Its "[당첨자공지]" is no scam any more (B_DEMO_RULES.md 12절).
        assertEquals(DecisionKind.USER_DECISION, DecisionKinds.of(PopupAnalysis(token, texts = texts(nodes).toPersistentList())))
        assertEquals(DecisionKind.ADVERTISEMENT, scopedKind())
    }

    @Test fun aVisiblePageWarningStillCounts() {
        val visibleLogin = nodes + node("$web/2", "android.widget.TextView", "로그인하고 결제하기", ScreenBounds(40, 900, 1040, 980))
        assertEquals(DecisionKind.USER_DECISION, scopedKind(visibleLogin))
        val visibleScam = nodes + node("$web/2", "android.widget.TextView", "축하합니다! 경품 당첨", ScreenBounds(40, 900, 1040, 980))
        assertEquals(DecisionKind.SUSPICIOUS, scopedKind(visibleScam))
        // A warning partly on screen is visible.
        val edge = nodes + node("$web/2", "android.widget.TextView", "지금 결제하세요", ScreenBounds(40, 2300, 1040, 2500))
        assertEquals(DecisionKind.USER_DECISION, scopedKind(edge))
    }

    @Test fun appInstallPromptsAreAdvertisements() {
        for (text in listOf("앱에서만 제공되는 혜택", "11번가 앱에서 앱 한정 혜택 받기", "앱 열기", "앱으로 보기", "앱 설치하고 혜택 받기", "앱설치 안내", "앱 다운로드", "앱 전용 쿠폰 특가")) {
            assertTrue(text, DecisionKeywords.isPromotion(text))
        }
        // A notice that only points to the app is not one; an error about the app stays a notice.
        assertEquals(false, DecisionKeywords.isPromotion("주문 내역은 앱에서 확인해 주세요"))
        val notice = PopupAnalysis(token, texts = persistentListOf(ExtractedText("앱 다운로드 오류 안내", ScreenBounds(0, 0, 10, 10), TextSource.ACCESSIBILITY_TREE, null)))
        assertEquals(DecisionKind.NOTICE, DecisionKinds.of(notice))
    }

    @Test fun todaysLabelContractStillFindsNoCloseButtonInTheSheet() {
        // WEB_OVERLAY.md 4절: "앱설치 안내 레이어 닫기" and "모바일 웹으로 볼래요" need contract v2. Until
        // then the sheet is read and asked, never pressed; the page banner's "닫기" lies outside the scope.
        assertEquals(emptyList<CloseCandidate>(), DecisionCloseCandidateFinder().find(snapshot(), detection))
    }
}
