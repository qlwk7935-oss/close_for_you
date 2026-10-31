package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.simpleclose.SimpleNodes.SCREEN
import kr.closeforyou.simpleclose.SimpleNodes.node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** F18: a video ad's skip button, and nothing else on the player. Trees are synthetic (structure of the YouTube app). */
class VideoAdSkipTest {
    private fun player(vararg extra: NodeSnapshot): List<NodeSnapshot> = listOf(
        node("0", "android.widget.FrameLayout", bounds = SCREEN),
        node("0/0", "android.widget.FrameLayout", bounds = ScreenBounds(0, 0, 1080, 610), viewId = "com.google.android.youtube:id/player_view"),
        node("0/0/0", "android.widget.TextView", text = "광고", bounds = ScreenBounds(20, 540, 80, 590)),
        node("0/1", "android.widget.Button", text = "구독", clickable = true, bounds = ScreenBounds(800, 700, 1060, 800)),
        node("0/2", "android.widget.Button", text = "지금 구매하기", clickable = true, bounds = ScreenBounds(20, 450, 400, 530)),
        node("0/3", text = "영상 제목", clickable = true, bounds = ScreenBounds(0, 620, 1080, 700)),
    ) + extra

    @Test fun theNamedSkipButtonIsFoundAndNothingElse() {
        val nodes = player(node("0/0/1", desc = "광고 건너뛰기", clickable = true, bounds = ScreenBounds(780, 440, 1080, 530), viewId = "com.google.android.youtube:id/skip_ad_button"))
        val found = VideoAdSkip.find(nodes, SCREEN) as VideoAdSkipResult.Found
        assertEquals("node:0/0/1", found.candidate.id)
        assertEquals(ButtonRole.SKIP, found.candidate.role)
    }

    @Test fun skipNamedByItsChildTextAndEnglishForms() {
        val box = listOf(
            node("0/0/1", clickable = true, bounds = ScreenBounds(780, 440, 1080, 530)),
            node("0/0/1/0", "android.widget.TextView", text = "건너뛰기", bounds = ScreenBounds(800, 450, 1060, 520)),
        )
        assertEquals("node:0/0/1", (VideoAdSkip.find(player(*box.toTypedArray()), SCREEN) as VideoAdSkipResult.Found).candidate.id)
        listOf("Skip ad", "Skip ads", "Skip").forEach { label ->
            val nodes = player(node("0/0/1", desc = label, clickable = true, bounds = ScreenBounds(780, 440, 1080, 530)))
            assertTrue(label, VideoAdSkip.find(nodes, SCREEN) is VideoAdSkipResult.Found)
        }
    }

    @Test fun aCountdownWaitsForTheButton() {
        val nodes = player(node("0/0/1", text = "5초 후 광고 건너뛰기", bounds = ScreenBounds(780, 440, 1080, 530)))
        assertEquals(VideoAdSkipResult.Waiting, VideoAdSkip.find(nodes, SCREEN))
        assertEquals(VideoAdSkipResult.Waiting, VideoAdSkip.find(SimpleNodes.youTubeSkip().filter { it.path != "0/0/1" && it.path != "0/0/1/0" }, SCREEN))
    }

    @Test fun aTutorialSkipWithoutAnAdMarkIsNeverPressed() {
        val nodes = listOf(
            node("0", "android.widget.FrameLayout", bounds = SCREEN),
            node("0/0", text = "새 기능 둘러보기", bounds = ScreenBounds(0, 200, 1080, 400)),
            node("0/1", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(780, 2100, 1060, 2250)),
        )
        assertNull(VideoAdSkip.find(nodes, SCREEN))
        assertNull(VideoAdSkip.find(player(), SCREEN))
    }

    @Test fun twoSkipButtonsAreAGuess() {
        val nodes = player(
            node("0/0/1", desc = "광고 건너뛰기", clickable = true, bounds = ScreenBounds(780, 440, 1080, 530)),
            node("0/4", "android.widget.Button", text = "건너뛰기", clickable = true, bounds = ScreenBounds(780, 2100, 1060, 2250)),
        )
        assertNull(VideoAdSkip.find(nodes, SCREEN))
    }

    @Test fun riskyOrDisabledOrHiddenSkipsAreNotPressed() {
        assertTrue(!VideoAdSkip.isSkip("구독하고 건너뛰기"))
        val disabled = player(node("0/0/1", desc = "광고 건너뛰기", clickable = true, enabled = false, bounds = ScreenBounds(780, 440, 1080, 530)))
        assertNull(VideoAdSkip.find(disabled, SCREEN))
        val hidden = player(node("0/0/1", desc = "광고 건너뛰기", clickable = true, bounds = ScreenBounds(780, 2400, 1080, 2500)))
        assertNull(VideoAdSkip.find(hidden, SCREEN))
    }

    @Test fun theYouTubeDumpSkipIsFound() {
        assertEquals("node:0/0/1", (VideoAdSkip.find(SimpleNodes.youTubeSkip(), SCREEN) as VideoAdSkipResult.Found).candidate.id)
    }
}
