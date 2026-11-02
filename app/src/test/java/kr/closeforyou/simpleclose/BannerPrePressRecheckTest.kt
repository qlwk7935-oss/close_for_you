package kr.closeforyou.simpleclose

import kotlinx.coroutines.runBlocking
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.settings.HandlingPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** B_DEMO_RULES.md 6-3: the re-read right before a press sees a flat banner's words, not only its close button. */
class BannerPrePressRecheckTest {
    private val screen = ScreenBounds(0, 0, 1080, 2460)
    private val adsOnly = HandlingPreset.CLOSE_ADS_ONLY.autoCloseKinds()

    private fun banner(words: String): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = screen),
        node("0/0", "android.widget.TextView", text = "시험 본문 화면", bounds = ScreenBounds(63, 363, 1017, 447)),
        node("0/2", "android.widget.TextView", text = words, bounds = ScreenBounds(31, 2275, 587, 2357)),
        node("0/3", "android.widget.Button", text = "설치", clickable = true, bounds = ScreenBounds(587, 2253, 818, 2379)),
        node("0/4", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(818, 2253, 1049, 2379)),
    )

    private val root = PopupRootFinder.find(banner("앱 설치하고 혜택 받기(시험용)"), screen)!!

    private fun readAgain(nodes: List<NodeSnapshot>, paths: Set<String>) =
        SimplePopupAnalysis.build(nodes.filter { it.path in paths }, root, "kr.closeforyou.popuptest", 1, screen, 1, 0)

    private fun check(fresh: SimplePopup) =
        runBlocking { PrePressRecheck.check(PopupClassification.ADVERTISEMENT, fresh, adsOnly) }

    @Test fun readingOnlyTheCloseButtonLooksLikeAnotherKind() {
        assertEquals(PrePressBlock.KIND_CHANGED, check(readAgain(banner("앱 설치하고 혜택 받기(시험용)"), setOf(root.path))))
    }

    @Test fun theWholeRowIsReadAgainAndStaysAnAd() {
        assertEquals(setOf("0/2", "0/3", "0/4"), root.rereadPaths())
        assertNull(check(readAgain(banner("앱 설치하고 혜택 받기(시험용)"), root.rereadPaths())))
    }

    @Test fun aBannerThatTurnedIntoADecisionIsStopped() {
        assertNotNull(check(readAgain(banner("자동 결제에 동의해 주세요"), root.rereadPaths())))
    }

    @Test fun anOrdinaryPopupIsReadAtItsOwnPath() {
        val popup = PopupRootFinder.find(SimpleNodes.auction(), SimpleNodes.SCREEN)!!
        assertEquals(setOf(popup.path), popup.rereadPaths())
    }
}
