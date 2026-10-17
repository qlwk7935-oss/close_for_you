package kr.closeforyou.accessibility.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.closeforyou.accessibility.AndroidScreenGeometry
import kr.closeforyou.accessibility.ScreenFrameStatus
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Run separately on API 35 and API 37. Reads geometry only; no screenshot or screen text. */
@RunWith(AndroidJUnit4::class)
class ScreenGeometryInstrumentedTest {
    @Test fun nonVisualContextObtainsVerifiedWindowContextFrame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val source = AndroidScreenGeometry(context)
        try {
            val result = source.read()
            assertTrue("WINDOW_CONTEXT_FRAME_UNVERIFIED", result.status == ScreenFrameStatus.VERIFIED)
            val frame = result.frame
            assertTrue("WINDOW_CONTEXT_FRAME_INVALID", frame != null && frame.left == 0 && frame.top == 0 && frame.width > 0 && frame.height > 0)
        } finally { source.reset() }
    }
}
