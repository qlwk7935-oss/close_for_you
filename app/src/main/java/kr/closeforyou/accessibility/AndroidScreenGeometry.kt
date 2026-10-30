package kr.closeforyou.accessibility

import java.util.concurrent.CancellationException
import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import kr.closeforyou.core.model.ScreenBounds

internal data class ScreenGeometryRead(val frame: ScreenBounds?, val status: ScreenFrameStatus, val rotation: Int? = null) {
    override fun toString(): String = "ScreenGeometryRead(status=$status)"
}

/** A service is not a visual container. Obtain metrics from an explicit primary-display window
 * context, then cross-check the logical display size. No window is added and no image is captured.
 * Only a zero-origin, full logical display is supported; partition/compat ambiguity fails closed.
 * getRealSize is a conservative cross-check, not a fallback nor a physical-resolution claim. */
internal class AndroidScreenGeometry(private val context: Context) {
    private data class ConfigurationKey(val width: Int, val height: Int, val rotation: Int)
    private var key: ConfigurationKey? = null
    private var windowContext: Context? = null

    @Suppress("DEPRECATION") // Logical display cross-check, never UI layout or fallback dimensions.
    fun read(): ScreenGeometryRead = try {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        if (display == null || !display.isValid) unavailable() else {
            val before = Point().also(display::getRealSize)
            val configuration = ConfigurationKey(before.x, before.y, display.rotation)
            if (key != configuration || windowContext == null) {
                windowContext = if (Build.VERSION.SDK_INT >= 31) {
                    context.createWindowContext(display, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                } else {
                    // API 30-compatible overload. A display context alone is not a UI context.
                    context.createDisplayContext(display)
                        .createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null)
                }
                key = configuration
            }
            val bounds = requireNotNull(windowContext).getSystemService(WindowManager::class.java)
                .maximumWindowMetrics.bounds
            val frame = ScreenBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
            val after = Point().also(display::getRealSize)
            if (!display.isValid || before != after || display.rotation != configuration.rotation) {
                reset()
                ScreenGeometryRead(null, ScreenFrameStatus.DISPLAY_CHANGED)
            } else {
                val status = ScreenFramePolicy.validate(frame, after.x, after.y)
                if (status != ScreenFrameStatus.VERIFIED) reset()
                ScreenGeometryRead(frame.takeIf { status == ScreenFrameStatus.VERIFIED }, status,
                    configuration.rotation.takeIf { status == ScreenFrameStatus.VERIFIED })
            }
        }
    } catch (cancelled: CancellationException) {
        reset()
        throw cancelled
    } catch (_: Exception) {
        // Framework exceptions may include identifiers or coordinates. Return a fixed code only.
        reset()
        unavailable()
    }
    fun reset() { windowContext = null; key = null }
    private fun unavailable() = ScreenGeometryRead(null, ScreenFrameStatus.UNAVAILABLE)
}
