package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F36 (LG 11/2 시험 앱 P03: NO_POPUP_ROOT ×10; P16: VIDEO_SKIP_WAITING ×3 then nothing). The trees are as the service
 * reads them: the banner's LinearLayout is not in the tree, its views hang from the window root with the page.
 */
class F36Test {
    private val lg = ScreenBounds(0, 0, 1080, 2460)

    private fun page(): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = lg),
        node("0/0", "android.widget.TextView", text = "시험 본문 화면", bounds = ScreenBounds(63, 363, 1017, 447)),
        node("0/1", "android.widget.TextView", text = "팝업을 닫은 뒤 이 첫 문단을 찾을 수 있어야 합니다.", bounds = ScreenBounds(63, 447, 1017, 569)),
    )

    /** P03 flat: text, 설치, 닫기 straight under the root, at the bottom; the action bar title after them. */
    private fun p03(): List<NodeSnapshot> = page() + listOf(
        node("0/2", "android.widget.TextView", text = "앱 설치하고 혜택 받기(시험용)", bounds = ScreenBounds(31, 2275, 587, 2357)),
        node("0/3", "android.widget.Button", text = "설치", clickable = true, bounds = ScreenBounds(587, 2253, 818, 2379)),
        node("0/4", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(818, 2253, 1049, 2379)),
        node("0/5", "android.widget.TextView", text = "P03", bounds = ScreenBounds(42, 170, 300, 248)),
    )

    @Test fun p03AFlatBottomBannerIsFound() {
        val nodes = p03()
        val root = PopupRootFinder.find(nodes, lg)!!
        assertEquals(PopupRootKind.BOTTOM_BANNER, root.kind)
        assertEquals("0/4", root.path)
        assertEquals(setOf("0/2", "0/3", "0/4"), root.members)
        val popup = SimplePopupAnalysis.build(nodes, root, "kr.closeforyou.popuptest", 1, lg, 1, 0)
        assertEquals(listOf("닫기"), popup.scan.candidates.map { it.label })
        assertTrue(popup.analysis.texts.none { it.text.startsWith("시험 본문") || it.text == "P03" })
        val d = SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, popup.scan.candidates, HandlingPreset.CLOSE_ALL.autoCloseKinds(),
            popup.scan.forbiddenCount, riskCount = popup.scan.riskCount)
        assertEquals("닫기", (d as SimpleDecision.AutoClose).candidate.label)
    }

    @Test fun bottomMenusAndTabBarsAreNotBanners() {
        // A tab bar: five buttons, no close.
        val tabs = page() + listOf("홈", "검색", "쿠폰", "찜", "마이").mapIndexed { i, t ->
            node("0/${i + 2}", "android.widget.Button", text = t, clickable = true, bounds = ScreenBounds(i * 216, 2300, i * 216 + 216, 2460))
        }
        assertNull(PopupRootFinder.find(tabs, lg))
        assertNull(PopupRootFinder.flatBanner(tabs, lg))
        // A bottom bar with a close among many buttons (a menu): too many buttons.
        val menu = page() + listOf("이벤트", "할인", "쿠폰", "설정", "닫기").mapIndexed { i, t ->
            node("0/${i + 2}", "android.widget.Button", text = t, clickable = true, bounds = ScreenBounds(i * 216, 2300, i * 216 + 216, 2460))
        }
        assertNull(PopupRootFinder.find(menu, lg))
        assertEquals(FlatBanner.Miss(BannerMiss.TOO_MANY_BUTTONS), PopupRootFinder.flatBanner(menu, lg))
        // A snackbar "저장했어요 [닫기]": no promotion words.
        val snack = page() + listOf(
            node("0/2", "android.widget.TextView", text = "저장했어요", bounds = ScreenBounds(31, 2275, 700, 2357)),
            node("0/3", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(818, 2253, 1049, 2379)),
        )
        assertNull(PopupRootFinder.find(snack, lg))
        assertEquals(FlatBanner.Miss(BannerMiss.NO_PROMOTION), PopupRootFinder.flatBanner(snack, lg))
        // A promotion row with a close, but page content below it: not the bottom-most.
        val middle = p03() + node("0/6", "android.widget.TextView", text = "하단 안내", bounds = ScreenBounds(31, 2400, 600, 2450))
        assertEquals(FlatBanner.Miss(BannerMiss.NOT_BOTTOM_MOST), PopupRootFinder.flatBanner(middle.filter { PopupRootFinder.isVisible(it, lg) }, lg))
        // A close alone at the bottom.
        val alone = page() + node("0/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(818, 2253, 1049, 2379))
        assertEquals(FlatBanner.Miss(BannerMiss.ROW_ALONE), PopupRootFinder.flatBanner(alone, lg))
        // No close in the bottom fifth: nothing to say.
        assertNull(PopupRootFinder.flatBanner(page(), lg))
    }

    @Test fun p16CoverReasons() {
        val video = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/1", "android.widget.FrameLayout", clickable = true, bounds = ScreenBounds(0, 80, 1080, 2330)),
            node("0/1/0", "android.widget.VideoView", bounds = ScreenBounds(0, 1105, 1080, 1305)),
            node("0/1/1", "android.widget.TextView", text = "영상 광고(시험용)", bounds = ScreenBounds(52, 330, 1028, 426)),
            node("0/1/2", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(807, 2160, 1038, 2286)),
        )
        assertTrue(VideoAdSkip.coverCheck(video, lg).first is VideoCover.Shown)
        assertEquals(VideoCoverMiss.NO_AD_MARK, VideoAdSkip.coverCheck(video.filterNot { it.path == "0/1/1" }, lg).second)
        assertEquals(VideoCoverMiss.NO_SKIP_OR_COUNTDOWN, VideoAdSkip.coverCheck(video.filterNot { it.path == "0/1/2" }, lg).second)
        assertEquals(null to null, VideoAdSkip.coverCheck(video.filterNot { it.path == "0/1/0" }, lg))
    }
}
