package kr.closeforyou.accessibility.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled ML Kit, synthetic pixels only. Compiling this class is not execution evidence. */
@RunWith(AndroidJUnit4::class)
class BundledKoreanOcrTest {
    @Test fun bundledModelReadsKoreanNoticeAndMapsToScreenCoordinates() = runBlocking {
        val bitmap = syntheticNotice()
        val bounds = ScreenBounds(100, 400, 1380, 720)
        var released = false
        val frame = BitmapFrame(bitmap, bounds) { released = true }
        try {
            val lines = withTimeout(20_000) { KoreanMlKitOcr().recognize(frame) }
            assertTrue(lines.any { it.text.contains("점검") })
            assertTrue(lines.any { it.text.contains("2026") })
            assertTrue(lines.all { PopupRegionResolver.contains(bounds, it.bounds) })
            assertTrue(lines.all { it.confidence == null })
            assertEquals(PrivacyReview.TEXT_CHECK_PASSED, OcrPrivacyReview.review(lines, bounds))
        } finally { frame.close() }
        assertTrue(released)
        assertTrue(bitmap.isRecycled)
    }

    @Test fun bitmapLeaseKeepsNativeInputAliveUntilItsLastReaderCompletes() {
        val bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888)
        var releases = 0
        val frame = BitmapFrame(bitmap, ScreenBounds(0, 0, 80, 80)) { releases++ }
        val nativeReader = frame.retain()
        frame.close()
        assertFalse(bitmap.isRecycled)
        nativeReader.close(); nativeReader.close()
        assertTrue(bitmap.isRecycled)
        assertEquals(1, releases)
    }

    @Test fun imageWithoutTextDoesNotBecomeAnAdvertisementClassification() = runBlocking {
        val bitmap = Bitmap.createBitmap(640, 320, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint().apply { color = Color.BLUE }
        canvas.drawCircle(320f, 160f, 80f, paint)
        val bounds = ScreenBounds(0, 0, 640, 320)
        val frame = BitmapFrame(bitmap, bounds) {}
        try {
            val lines = withTimeout(20_000) { KoreanMlKitOcr().recognize(frame) }
            assertEquals(PrivacyReview.UNCERTAIN, OcrPrivacyReview.review(lines, bounds))
        } finally { frame.close() }
    }
}

internal fun syntheticNotice(): Bitmap {
    val bitmap = Bitmap.createBitmap(1280, 320, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 60f
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    canvas.drawText("점검 공지 2026년 10월 18일", 40f, 110f, paint)
    canvas.drawText("02시부터 04시까지 점검", 40f, 220f, paint)
    return bitmap
}
