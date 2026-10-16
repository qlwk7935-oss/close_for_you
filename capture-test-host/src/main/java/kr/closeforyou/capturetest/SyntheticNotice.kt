package kr.closeforyou.capturetest

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

// Intentionally independent of the instrumentation APK and the production application.
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
