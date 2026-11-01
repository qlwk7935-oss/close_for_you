package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.node
import kr.closeforyou.ui.voice.settings.HandlingPreset
import kr.closeforyou.ui.voice.settings.autoCloseKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F34-1 (LG 11/2, 시험 앱 32종 정답지 비교): detection. The trees are the test app's layouts as the service reads them
 * (no FLAG_INCLUDE_NOT_IMPORTANT_VIEWS: a plain LinearLayout is dropped and its views hang from the next node kept),
 * and as the emulator dump shows them (every layout kept). Device unverified.
 */
class F34DetectionTest {
    private val lg = ScreenBounds(0, 0, 1080, 2460)

    // P14 "일반 화면 전환(팝업 아님)": expected NONE, was DETECTED_DIM_WINDOW.
    @Test fun p14AnOrdinaryPageIsNoPopupEvenWithAHint() {
        assertFalse(PopupActivityName.looksLikePopup("kr.closeforyou.popuptest.PopupScenarioActivity"))
        val page = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.widget.TextView", text = "첫 번째 화면", bounds = ScreenBounds(52, 330, 1028, 434)),
            node("0/1", "android.widget.TextView", text = "팝업이 없는 일반 화면입니다.", bounds = ScreenBounds(52, 434, 1028, 516)),
            node("0/2", "android.widget.Button", text = "다음 화면", clickable = true, bounds = ScreenBounds(52, 516, 1028, 642)),
            node("0/3", "android.widget.TextView", text = "P14", bounds = ScreenBounds(42, 170, 300, 248)),
        )
        assertNull(PopupRootFinder.find(page, lg))
        assertNull(PopupRootFinder.find(page, lg, popupActivityHint = true))
        val second = page.take(3).map { if (it.path == "0/2") it.copy(clickable = false) else it }
        assertNull(PopupRootFinder.find(second, lg, popupActivityHint = true))
    }

    @Test fun activityHintNeedsTheWordRightBeforeActivity() {
        assertTrue(PopupActivityName.looksLikePopup("com.elevenst.ads.AdsOneDayPopupActivity"))
        assertTrue(PopupActivityName.looksLikePopup("x.y.EventDialogActivity"))
        assertTrue(PopupActivityName.looksLikePopup("x.y.AdActivity"))
        for (name in listOf("x.y.PopupScenarioActivity", "x.y.DialogSettingsActivity", "x.y.AdsListActivity")) {
            assertFalse(name, PopupActivityName.looksLikePopup(name))
        }
    }

    /** P02 as read on the device: the card's layout is dropped, its views hang from the clickable dim. */
    private fun p02(overlay: ScreenBounds = ScreenBounds(0, 80, 1080, 2330)) = listOf(
        node("0", "android.widget.FrameLayout", bounds = lg),
        node("0/0", "android.widget.TextView", text = "시험 본문 화면", bounds = ScreenBounds(63, 363, 1017, 447)),
        node("0/1", "android.widget.TextView", text = "팝업을 닫은 뒤 이 첫 문단을 찾을 수 있어야 합니다.", bounds = ScreenBounds(63, 447, 1017, 569)),
        node("0/2", "android.widget.FrameLayout", clickable = true, bounds = overlay),
        node("0/2/0", "android.widget.TextView", text = "신규 가입 이벤트", bounds = ScreenBounds(115, 985, 965, 1081)),
        node("0/2/1", "android.widget.TextView", text = "가입하면 포인트를 드립니다. 시험용 광고입니다.", bounds = ScreenBounds(115, 1081, 965, 1163)),
        node("0/2/2", "android.widget.Button", text = "자세히 보기", clickable = true, bounds = ScreenBounds(115, 1163, 965, 1289), viewId = "x:id/btn_ad_open"),
        node("0/2/3", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(115, 1289, 965, 1415), viewId = "x:id/btn_close"),
        node("0/3", "android.widget.TextView", text = "P02", bounds = ScreenBounds(42, 170, 300, 248)),
    )

    // P02 "같은 창 덮개 광고": expected CLOSE, was NONE.
    @Test fun p02ADimHoldingItsCardIsAPopup() {
        val nodes = p02()
        val root = PopupRootFinder.find(nodes, lg)!!
        assertEquals(PopupRootKind.DIM_OVERLAY, root.kind)
        assertEquals("0/2", root.path)
        val popup = SimplePopupAnalysis.build(nodes, root, "kr.closeforyou.popuptest", 1, lg, 1, 0)
        assertEquals(listOf("닫기"), popup.scan.candidates.map { it.label })
        assertFalse(popup.analysis.texts.any { it.text == "시험 본문 화면" })
        val decided = SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, popup.scan.candidates, HandlingPreset.CLOSE_ALL.autoCloseKinds(),
            popup.scan.forbiddenCount, riskCount = popup.scan.riskCount)
        assertEquals("닫기", (decided as SimpleDecision.AutoClose).candidate.label)
    }

    @Test fun p02AsTheEmulatorDumpToo() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.widget.LinearLayout", bounds = lg),
            node("0/0/0", "android.widget.TextView", text = "시험 본문 화면", bounds = ScreenBounds(63, 63, 1017, 147)),
            node("0/1", "android.widget.FrameLayout", clickable = true, bounds = lg),
            node("0/1/0", "android.widget.LinearLayout", bounds = ScreenBounds(63, 943, 1017, 1457)),
            node("0/1/0/0", "android.widget.TextView", text = "신규 가입 이벤트", bounds = ScreenBounds(115, 985, 965, 1081)),
            node("0/1/0/1", "android.widget.Button", text = "자세히 보기", clickable = true, bounds = ScreenBounds(115, 1163, 965, 1289)),
            node("0/1/0/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(115, 1289, 965, 1415)),
        )
        val root = PopupRootFinder.find(nodes, lg)!!
        assertTrue(root.kind == PopupRootKind.DIM_OVERLAY || root.kind == PopupRootKind.MODAL_CARD)
        assertEquals(listOf("닫기"), CloseButtonFinder.scan(nodes, root).candidates.map { it.label })
    }

    @Test fun aClickableContainerHoldingAWholePageIsNot() {
        // The content fills the container: a page, not a card.
        val page = p02().map { if (it.path == "0/2/0") it.copy(bounds = ScreenBounds(40, 120, 1040, 2300)) else it }
        assertNull(PopupRootFinder.find(page, lg))
        // Nothing drawn under it: the screen itself, not a layer over a page.
        assertNull(PopupRootFinder.find(p02().filterNot { it.path == "0/0" || it.path == "0/1" }, lg))
        // No named close: not ours to call a popup here.
        assertNull(PopupRootFinder.find(p02().filterNot { it.path == "0/2/3" }, lg))
    }

    // P03 "작은 하단 배너": expected CLOSE, was NONE. Found when the strip has a node of its own.
    @Test fun p03ABottomPromotionStripWithItsOwnNode() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.widget.LinearLayout", bounds = lg),
            node("0/0/0", "android.widget.TextView", text = "시험 본문 화면", bounds = ScreenBounds(63, 363, 1017, 447)),
            node("0/1", "android.widget.LinearLayout", bounds = ScreenBounds(0, 2292, 1080, 2460)),
            node("0/1/0", "android.widget.TextView", text = "앱 설치하고 혜택 받기(시험용)", bounds = ScreenBounds(31, 2335, 587, 2417)),
            node("0/1/1", "android.widget.Button", text = "설치", clickable = true, bounds = ScreenBounds(587, 2313, 818, 2439)),
            node("0/1/2", "android.widget.Button", text = "닫기", clickable = true, bounds = ScreenBounds(818, 2313, 1049, 2439)),
        )
        val root = PopupRootFinder.find(nodes, lg)!!
        assertEquals(PopupRootKind.BOTTOM_BANNER, root.kind)
        val scan = CloseButtonFinder.scan(nodes, root)
        assertEquals(listOf("닫기"), scan.candidates.map { it.label })
        // A snackbar with "닫기" and no promotion words is not a banner.
        val snackbar = nodes.map { if (it.path == "0/1/0") it.copy(text = "저장했어요") else it }.filterNot { it.path == "0/1/1" }
        assertNull(PopupRootFinder.find(snackbar, lg))
        // Flat (the strip's layout dropped, as on the device): F36 finds it as a row ([PopupRootFinder.flatBanner]).
        val flat = nodes.filterNot { it.path == "0/1" || it.path == "0/0" }.map { it.copy(path = it.path.replace("0/1/", "0/9")) }
        assertEquals(setOf("0/90", "0/91", "0/92"), PopupRootFinder.find(flat, lg)?.members)
    }

    /** P16 as read on the device: the black cover is clickable, the corner's layout is dropped. */
    private fun p16(skip: Boolean) = listOf(
        node("0", "android.widget.FrameLayout", bounds = lg),
        node("0/0", "android.widget.TextView", text = "시험 본문 화면", bounds = ScreenBounds(63, 363, 1017, 447)),
        node("0/1", "android.widget.FrameLayout", clickable = true, bounds = ScreenBounds(0, 80, 1080, 2330)),
        node("0/1/0", "android.widget.VideoView", bounds = ScreenBounds(0, 1105, 1080, 1305), viewId = "x:id/video_surface"),
        node("0/1/1", "android.widget.TextView", text = "영상 광고(시험용)", bounds = ScreenBounds(52, 330, 1028, 426)),
        node("0/1/2", "android.widget.TextView", text = if (skip) "재생 중 0:06" else "재생 중 0:02", bounds = ScreenBounds(52, 426, 1028, 508)),
        node("0/1/3", "android.widget.Button", text = "자세히 보기", clickable = true, bounds = ScreenBounds(52, 508, 1028, 634)),
        if (skip) {
            node("0/1/4", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(807, 2160, 1038, 2286))
        } else {
            node("0/1/4", "android.widget.TextView", text = "3초 후 건너뛸 수 있어요", bounds = ScreenBounds(807, 2190, 1038, 2260))
        },
    )

    // P16 "영상 광고 건너뛰기 카운트다운" outside YouTube: expected ASK, was NONE.
    @Test fun p16AVideoAdCoverIsWaitedForThenAsked() {
        assertNull(PopupRootFinder.find(p16(skip = false), lg))
        assertEquals(VideoCover.Waiting, VideoAdSkip.cover(p16(skip = false), lg))
        val nodes = p16(skip = true)
        assertNull(PopupRootFinder.find(nodes, lg))
        val root = (VideoAdSkip.cover(nodes, lg) as VideoCover.Shown).root
        assertEquals("0/1", root.path)
        assertEquals(PopupRootKind.VIDEO_COVER, root.kind)
        val popup = SimplePopupAnalysis.build(nodes, root, "kr.closeforyou.popuptest", 1, lg, 1, 0)
        assertEquals(listOf(ButtonRole.SKIP), popup.scan.candidates.map { it.role })
        // No close to press by itself: asked, the skip offered as the press.
        assertEquals(SimpleDecision.Ask, SimpleCloseDecider.decide(PopupClassification.ADVERTISEMENT, false, popup.scan.candidates,
            HandlingPreset.CLOSE_ALL.autoCloseKinds(), popup.scan.forbiddenCount, riskCount = popup.scan.riskCount))
    }

    @Test fun noCoverWithoutAllThree() {
        // A shopping page: an "광고" label and a video in a list, no skip and no countdown.
        val shop = listOf(
            node("0", "android.widget.FrameLayout", bounds = lg),
            node("0/0", "android.widget.FrameLayout", bounds = ScreenBounds(0, 80, 1080, 2330)),
            node("0/0/0", "android.view.TextureView", bounds = ScreenBounds(0, 300, 1080, 900)),
            node("0/0/1", "android.widget.TextView", text = "광고", bounds = ScreenBounds(20, 920, 120, 980)),
        )
        assertNull(VideoAdSkip.cover(shop, lg))
        // A tutorial's "건너뛰기" with a video and no ad mark.
        val tutorial = p16(skip = true).filterNot { it.path == "0/1/1" }
        assertNull(VideoAdSkip.cover(tutorial, lg))
    }
}
