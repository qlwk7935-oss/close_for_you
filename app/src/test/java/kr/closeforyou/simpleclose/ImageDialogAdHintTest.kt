package kr.closeforyou.simpleclose

import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDialogAdHintTest {
    /** 옥션 10/31 second visit: the banner image has a neutral alt text, so B has no words to go on. */
    private fun imageOnlyDialog(alt: String = "이벤트 배너") = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2340)),
        node("0/0/0", "android.app.AlertDialog", bounds = ScreenBounds(90, 600, 990, 1700)),
        node("0/0/0/0", "android.widget.Image", desc = alt, clickable = true, bounds = ScreenBounds(90, 600, 990, 1550)),
        node("0/0/0/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(90, 1550, 990, 1700)),
    )

    private fun popup(nodes: List<kr.closeforyou.core.model.NodeSnapshot>) =
        SimplePopupAnalysis.build(nodes, PopupRootFinder.find(nodes, SCREEN)!!, "p", 7, SCREEN, 1L, 0L)

    @Test fun imageOnlyDialogUnknownToBIsHintedAsAd() = runTest {
        val p = popup(imageOnlyDialog())
        val b = DecisionPopupClassifier().classify(p.analysis)
        assertEquals(PopupClassification.UNKNOWN, b)
        assertTrue(ImageDialogAdHint.applies(p, b, suspicious = false))
    }

    @Test fun hintNeverOverridesBOrASuspiciousPopup() {
        val p = popup(imageOnlyDialog())
        assertFalse(ImageDialogAdHint.applies(p, PopupClassification.NOTICE, suspicious = false))
        assertFalse(ImageDialogAdHint.applies(p, PopupClassification.USER_DECISION, suspicious = false))
        assertFalse(ImageDialogAdHint.applies(p, PopupClassification.UNKNOWN, suspicious = true))
    }

    @Test fun needsACloseButtonAndABigImage() {
        val noClose = imageOnlyDialog().filter { it.path != "0/0/0/1" } + node("0/0/0/1", "android.widget.Button", text = "자세히 보기", clickable = true, bounds = ScreenBounds(90, 1550, 990, 1700))
        assertFalse(ImageDialogAdHint.applies(popup(noClose), PopupClassification.UNKNOWN, suspicious = false))
        val smallImage = imageOnlyDialog().map { if (it.path == "0/0/0/0") it.copy(bounds = ScreenBounds(90, 600, 290, 700)) else it }
        assertFalse(ImageDialogAdHint.applies(popup(smallImage), PopupClassification.UNKNOWN, suspicious = false))
    }

    @Test fun textHeavyDialogIsLeftToB() {
        val wordy = imageOnlyDialog() + node("0/0/0/2", "android.widget.TextView", text = "서비스 점검으로 10월 28일 새벽 2시부터 4시까지 주문 기능이 일시 중단됩니다. 이용에 불편을 드려 죄송합니다. 자세한 내용은 공지사항을 확인해 주세요.", bounds = ScreenBounds(90, 1400, 990, 1550))
        assertFalse(ImageDialogAdHint.applies(popup(wordy), PopupClassification.UNKNOWN, suspicious = false))
    }

    @Test fun elevenStreetSheetIsNotAnImageDialog() {
        val p = popup(SimpleNodes.elevenStreet())
        assertFalse(ImageDialogAdHint.applies(p, PopupClassification.UNKNOWN, suspicious = false))
    }
}
