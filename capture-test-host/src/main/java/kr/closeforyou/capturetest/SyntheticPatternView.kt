package kr.closeforyou.capturetest

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kr.closeforyou.synthetictest.PatternMapping

/** Only drawn for the explicit instrumentation pattern session, never the normal OCR fixture. */
internal class SyntheticPatternView(context: Context) : View(context) {
    private val paint = Paint().apply { isAntiAlias = false }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }
    override fun getAccessibilityClassName(): CharSequence = "android.widget.ImageView"
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(PatternMapping.WHITE)
        PatternMapping.markers(width, height).forEachIndexed { index, rect ->
            paint.color = PatternMapping.colors[index]
            canvas.drawRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), paint)
        }
    }
}
