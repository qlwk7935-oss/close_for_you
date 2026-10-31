package kr.closeforyou.simpleclose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.widget.TextView

/**
 * F11 (LG 10/31: "광고를 닫았어요" from our TTS played over TalkBack's own speech and was lost): with
 * TalkBack on, the result sentence goes through TalkBack itself. A small TYPE_ACCESSIBILITY_OVERLAY
 * card shows the sentence (also readable on screen for low vision) in a polite live region, set
 * right after the window is attached, so TalkBack says it in its own queue after what it is saying
 * now, instead of a second voice on top. `announceForAccessibility()` is deprecated and not used
 * (C rule 3-7). The card is not focusable and goes away after [SHOW_MILLIS]; TalkBack focus is never
 * moved to it. Only fixed sentences from our resources, never popup text.
 * Main thread. Device check needed (does TalkBack read a live region in an accessibility overlay
 * window; LG Android 12 / TalkBack version).
 */
class TalkBackResultBanner(private val service: AccessibilityService) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var shown: View? = null

    /** TalkBack (touch exploration) is on: results should go through it. */
    fun talkBackOn(): Boolean = service.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /** Shows [sentence]; false when the window could not be added (the caller then uses its TTS). */
    fun show(sentence: String): Boolean {
        dismiss()
        val density = service.resources.displayMetrics.density
        val view = TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(0xFFFFFFFF.toInt())
            val pad = (16 * density).toInt()
            setPadding(pad, pad / 2, pad, pad / 2)
            background = GradientDrawable().apply {
                setColor(0xE6202A44.toInt())
                cornerRadius = 16 * density
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (96 * density).toInt()
        }
        return try {
            windowManager.addView(view, params)
            shown = view
            // A live region speaks a change, not the first layout: set the text once attached.
            view.post { view.text = sentence }
            handler.postDelayed({ if (shown === view) dismiss() }, SHOW_MILLIS)
            true
        } catch (e: RuntimeException) {
            shown = null
            false
        }
    }

    fun dismiss() {
        val view = shown ?: return
        shown = null
        try {
            windowManager.removeView(view)
        } catch (_: RuntimeException) {
        }
    }

    private companion object {
        const val SHOW_MILLIS = 4_000L
    }
}
