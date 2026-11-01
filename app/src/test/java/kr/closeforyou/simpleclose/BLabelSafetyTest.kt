package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B review 11/1 (준영): holes found in 강력 모드 with JVM probes, before the fix each of these was pressed
 * without asking. Synthetic wording; structure like [SimpleNodes.elevenStreet].
 */
class BLabelSafetyTest {
    private val closeAll = HandlingPreset.CLOSE_ALL.autoCloseKinds()
    private val small = ScreenBounds(900, 1320, 1060, 1400)
    private val wide = ScreenBounds(40, 2100, 1040, 2250)

    private fun sheet(vararg buttons: Pair<String, ScreenBounds>, body: String): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", text = "페이지 본문", bounds = ScreenBounds(0, 200, 1080, 900)),
        node("0/0/1", "android.app.AlertDialog", bounds = ScreenBounds(0, 1300, 1080, 2340)),
        node("0/0/1/0", text = body, bounds = ScreenBounds(40, 1350, 1040, 1500)),
    ) + buttons.mapIndexed { i, (label, b) -> node("0/0/1/${i + 1}", "android.widget.Button", text = label, clickable = true, bounds = b) }

    /** What 강력 모드 presses on this popup for an UNKNOWN or NOTICE popup (B's words do not matter here), or null. */
    private fun pressed(nodes: List<NodeSnapshot>, kind: PopupClassification = PopupClassification.UNKNOWN): String? {
        val root = requireNotNull(PopupRootFinder.find(nodes, SCREEN))
        val scan = SimplePopupAnalysis.build(nodes, root, "com.android.chrome", 1, SCREEN, 1, 0).scan
        val d = SimpleCloseDecider.decide(kind, false, scan.candidates, closeAll, scan.forbiddenCount, riskCount = scan.riskCount)
        return (d as? SimpleDecision.AutoClose)?.candidate?.label
    }

    @Test fun anActionBeforeTheCloseIsNotAClose() {
        for (label in listOf("앱 설치하고 닫기", "알림 받고 닫기", "알림 켜고 닫기", "응모하고 닫기", "쿠폰 받고 닫기", "확인 후 닫기",
            "참여하고 닫기", "신청하고 닫기", "광고 보고 닫기", "앱 다운받고 닫기", "자동충전 켜고 닫기", "마케팅 수신하고 닫기")) {
            assertNull(label, SimpleLabelRules.roleOf(label))
        }
        // A front that names what is closed is still a close.
        for (label in listOf("앱설치 안내 레이어 닫기", "배너 닫기", "레이어 닫기", "속보 닫기", "이벤트 팝업 닫기", "[닫기]", "오늘 하루 닫기")) {
            assertEquals(label, ButtonRole.CLOSE, SimpleLabelRules.roleOf(label))
        }
    }

    @Test fun strongModePressesThePlainCloseNotTheActionBeforeIt() {
        assertEquals("닫기", pressed(sheet("앱 설치하고 닫기" to wide, "닫기" to small, body = "앱 전용 혜택")))
        assertNull(pressed(sheet("앱 설치하고 닫기" to wide, body = "앱 전용 혜택")))
        assertNull(pressed(sheet("알림 받고 닫기" to wide, body = "새 소식을 놓치지 마세요"), PopupClassification.NOTICE))
        // Switching notifications on is a choice: the popup is asked, even with a polite "괜찮아요" beside it.
        assertNull(pressed(sheet("알림 켜고 닫기" to wide, "괜찮아요" to small, body = "새 소식을 놓치지 마세요")))
    }

    @Test fun bareRequestWordsKeepThePopupAsked() {
        for (label in listOf("신청", "예약", "충전", "알림 수신", "켜기", "본인 인증", "카드 등록", "기간 연장", "업그레이드")) {
            assertTrue(label, SimpleLabelRules.isRisky(label))
        }
        assertNull(pressed(sheet("신청" to wide, "닫기" to small, body = "프리미엄 이용권 오늘만 특가")))
        assertNull(pressed(sheet("예약" to wide, "닫기" to small, body = "인기 공연 좌석 오픈")))
        assertNull(pressed(sheet("충전" to wide, "다음에" to small, body = "포인트가 부족해요")))
        // Plain close words stay unrisky.
        for (label in listOf("닫기", "오늘 하루 보지 않기", "모바일 웹으로 볼래요", "괜찮아요", "건너뛰기")) {
            assertEquals(label, false, SimpleLabelRules.isRisky(label))
        }
    }

    @Test fun aLinkCardIsNotNamedACloseByTheWordsInsideIt() {
        val card = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
            node("0/0/0", text = "페이지 본문", bounds = ScreenBounds(0, 200, 1080, 900)),
            node("0/0/1", "android.app.AlertDialog", bounds = ScreenBounds(0, 1300, 1080, 2340)),
            node("0/0/1/0", clickable = true, bounds = ScreenBounds(0, 1300, 1080, 2340)),
            node("0/0/1/0/0", text = "특가 상품 보러 가기", bounds = ScreenBounds(40, 1400, 1040, 1600)),
            node("0/0/1/0/1", text = "닫기", bounds = ScreenBounds(900, 1320, 1060, 1380)),
        )
        assertNull(pressed(card, PopupClassification.ADVERTISEMENT))
        // An unnamed box whose only words are a whole close phrase (YouTube's "건너뛰기" box) still counts.
        val box = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", "android.app.AlertDialog", bounds = ScreenBounds(0, 1300, 1080, 2340)),
            node("0/0/0", text = "광고", bounds = ScreenBounds(40, 1350, 400, 1450)),
            node("0/0/1", clickable = true, bounds = small),
            node("0/0/1/0", text = "닫기", bounds = small),
        )
        assertEquals("닫기", pressed(box, PopupClassification.ADVERTISEMENT))
    }
}
