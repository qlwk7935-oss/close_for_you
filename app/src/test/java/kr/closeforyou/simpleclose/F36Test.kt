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

/** F37 (LG 11/2 G마켓 웹 홈: a floating "매일 출첵하고 혜택 받아요! ×" bubble over the tab bar was a BOTTOM_BANNER). */
class F37Test {
    private val lg = ScreenBounds(0, 0, 1080, 2460)

    private fun tabBar(parent: String, first: Int) = listOf("홈", "카테고리", "검색", "마이", "최근").mapIndexed { i, t ->
        node("$parent/${first + i}", "android.view.View", text = t, clickable = true, bounds = ScreenBounds(i * 216, 2300, i * 216 + 216, 2460))
    }

    @Test fun aFloatingBubbleOverTheTabBarIsNotABanner() {
        // Flat: the bubble's words and × inside a full-width fixed footer that also holds the tab bar.
        val flat = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2460)),
            node("0/0/0", text = "오늘의 특가", bounds = ScreenBounds(40, 300, 1040, 400)),
            node("0/0/1", bounds = ScreenBounds(0, 2120, 1080, 2460)),
            node("0/0/1/0", text = "매일 출첵하고 혜택 받아요!", bounds = ScreenBounds(300, 2130, 700, 2200)),
            node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(700, 2130, 780, 2200)),
        ) + tabBar("0/0/1", 2)
        assertNull(PopupRootFinder.find(flat, lg))
        // As a full-width box of its own over the tab bar.
        val boxed = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.webkit.WebView", bounds = ScreenBounds(0, 200, 1080, 2460)),
            node("0/0/0", bounds = ScreenBounds(0, 200, 1080, 2460)),
            node("0/0/0/0", text = "오늘의 특가", bounds = ScreenBounds(40, 300, 1040, 400)),
            node("0/0/1", bounds = ScreenBounds(0, 2200, 1080, 2290)),
            node("0/0/1/0", text = "매일 출첵하고 혜택 받아요!", bounds = ScreenBounds(300, 2210, 700, 2280)),
            node("0/0/1/1", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(700, 2210, 780, 2280)),
        ) + tabBar("0/0", 2)
        assertNull(PopupRootFinder.find(boxed, lg))
        // The same full-width box with its content across the width and nothing under it is a banner (P03 form).
        val banner = boxed.filterNot { it.path.startsWith("0/0/") && it.path.count { c -> c == '/' } == 2 && it.path != "0/0/0" && it.path != "0/0/1" }
            .map { if (it.path == "0/0/1/0") it.copy(bounds = ScreenBounds(40, 2210, 700, 2280)) else it }
            .map { if (it.path == "0/0/1") it.copy(bounds = ScreenBounds(0, 2370, 1080, 2460)) else if (it.path.startsWith("0/0/1/")) it.copy(bounds = ScreenBounds(it.bounds.left, 2380, it.bounds.right, 2450)) else it }
        assertEquals(PopupRootKind.BOTTOM_BANNER, PopupRootFinder.find(banner, lg)?.kind)
    }
}
