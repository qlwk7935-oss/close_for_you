package kr.closeforyou.accessibility.testing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.SystemClock
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.accessibility.CaptureAttemptDiagnostic
import kr.closeforyou.accessibility.CaptureDiagnostics
import kr.closeforyou.accessibility.capture.CaptureRegion
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.synthetictest.*

/** One-shot, signature-gated provider session. Expected OWNED geometry is IPC-only, never returned.
 * No buffer, Bitmap, pixel array or coordinate transform survives inspect(). */
internal class SyntheticPatternProbe(private val preferences: CorePreferences) {
    @Volatile private var closed = false
    private var plan: PatternPlan? = null
    private var armedAt = 0L
    private var version: Long? = null
    private var result = PatternReport(PatternCode.NOT_OBSERVED)
    private var released = false
    private var diagnostic: CaptureAttemptDiagnostic? = null
    @Synchronized fun arm(expected: PatternPlan): Boolean {
        if (closed || plan != null || version != null || !expected.valid()) return false
        plan = expected; armedAt = SystemClock.uptimeMillis()
        return true
    }
    @Synchronized fun inspect(snapshot: PopupSnapshot, region: CaptureRegion, buffer: HardwareBuffer, colorSpace: ColorSpace) {
        val expected = plan ?: return
        if (snapshot.token.packageName != HOST || snapshot.token.windowId != expected.windowId) return
        plan = null // consume once, including all rejections
        version = snapshot.token.screenVersion
        diagnostic = CaptureDiagnostics.observer?.snapshot()
        var wrapped: Bitmap? = null
        var pixels: Bitmap? = null
        var argb: IntArray? = null
        val deadline = SystemClock.uptimeMillis() + 500
        try {
            result = when {
                !allowed(snapshot) || SystemClock.uptimeMillis() - armedAt !in 0..10_000 -> PatternReport(PatternCode.STALE_OR_NOT_ALLOWED)
                !same(region.window.bounds, expected.decor) || snapshot.nodes.none {
                    it.className == "android.widget.ImageView" && same(it.bounds, expected.content)
                } -> PatternReport(PatternCode.OWNED_WINDOW_MISMATCH)
                buffer.width.toLong() * buffer.height > PatternMapping.MAX_PIXELS -> PatternReport(PatternCode.BUFFER_LIMIT)
                else -> {
                    wrapped = Bitmap.wrapHardwareBuffer(buffer, colorSpace)
                    pixels = wrapped?.copy(Bitmap.Config.ARGB_8888, true)
                    val image = pixels
                    if (image == null) PatternReport(PatternCode.INSPECTION_FAILED)
                    else {
                        val samples = IntArray(image.width * image.height).also { argb = it }
                        image.getPixels(samples, 0, image.width, 0, 0, image.width, image.height)
                        PatternMapping.inspect(expected, image.width, image.height,
                            stillValid = { SystemClock.uptimeMillis() < deadline && allowed(snapshot) }) { x, y -> samples[y * image.width + x] }
                    }
                }
            }
            if (!allowed(snapshot)) result = PatternReport(PatternCode.STALE_OR_NOT_ALLOWED)
        } catch (_: OutOfMemoryError) { result = PatternReport(PatternCode.BUFFER_LIMIT) }
        catch (_: Exception) { result = PatternReport(PatternCode.INSPECTION_FAILED) }
        finally {
            argb?.fill(0)
            pixels?.let { try { it.eraseColor(Color.TRANSPARENT) } finally { it.recycle() } }
            wrapped?.recycle()
        }
    }
    @Synchronized fun released(snapshot: PopupSnapshot) {
        if (!closed && version == snapshot.token.screenVersion && snapshot.token.packageName == HOST) {
            released = true
            diagnostic = diagnostic?.let { it.copy(buffer = it.buffer?.copy(released = true)) }
        }
    }
    @Synchronized fun report(): Pair<PatternReport, Boolean> = result to released
    @Synchronized fun diagnostic(): CaptureAttemptDiagnostic? = diagnostic
    fun close() {
        closed = true // Interrupt inspection even if END/expiry is waiting for the probe lock.
        synchronized(this) {
            plan = null; version = null; diagnostic = null; result = PatternReport(PatternCode.NOT_OBSERVED); released = false
        }
    }
    private fun allowed(snapshot: PopupSnapshot) = !closed && syntheticSettingsReady(preferences.currentSettings()) &&
        AccessibilityRuntime.status.value.connected && AccessibilityRuntime.currentToken() == snapshot.token
    private fun same(bounds: kr.closeforyou.core.model.ScreenBounds, expected: PatternRect) =
        bounds.left == expected.left && bounds.top == expected.top && bounds.right == expected.right && bounds.bottom == expected.bottom
    private companion object { const val HOST = "kr.closeforyou.capturetest" }
}
