package kr.closeforyou.popuptest

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P16 빈 영상 표면이 빠지지 않게 막는 정적 점검. 실제 접근성 트리 관측은 기기에서
 * `adb shell uiautomator dump`로 확인한다(docs/POPUP_TEST_APP.md P16). 빈 표면은 영상 재생·광고 판정의 증거가 아니다.
 */
class VideoSurfaceFixtureTest {
    private fun source(path: String): String {
        val file = listOf(File(path), File("popup-test-app/$path")).firstOrNull { it.exists() }
        assertTrue("$path not found", file != null)
        return file!!.readText(Charsets.UTF_8)
    }

    @Test
    fun videoSurfaceIdIsDeclared() {
        assertTrue(source("src/main/res/values/ids.xml").contains("name=\"${ScenarioCatalog.VIDEO_SURFACE_ID}\""))
    }

    @Test
    fun p16PlacesAnEmptyVideoViewThatIsExposedToAccessibility() {
        val activity = source("src/main/java/kr/closeforyou/popuptest/PopupScenarioActivity.kt")
        assertTrue(activity.contains("VideoView(this)"))
        assertTrue(activity.contains("R.id.video_surface"))
        assertTrue(activity.contains("IMPORTANT_FOR_ACCESSIBILITY_YES"))
        // 빈 표면: 재생 소스를 주지 않는다.
        assertTrue(!activity.contains("setVideoURI") && !activity.contains("setVideoPath"))
    }
}
