package kr.closeforyou.simpleclose

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * F11 (LG 10/31: "광고를 닫았어요" from our TTS played over TalkBack's own speech and was lost): with
 * TalkBack on, the result sentence goes through TalkBack itself. A small TYPE_ACCESSIBILITY_OVERLAY
 * card shows the sentence (also readable on screen for low vision) in a polite live region, set
 * right after the window is attached, so TalkBack says it in its own queue after what it is saying
 * now, instead of a second voice on top. `announceForAccessibility()` is deprecated and not used
 * (C rule 3-7). The card is not focusable and goes away after [SHOW_MILLIS]; TalkBack focus is never
 * moved to it. Only fixed sentences from our resources, never popup text.
 *
 * F17 ([SimpleCloseEngineFeature.RESULT_BANNER_TAKES_FOCUS], demo mode 6-0; LG 11/1 옥션: the focus moved
 * three times after a close and TalkBack said each place): with `takeFocus` the card takes TalkBack's
 * accessibility focus itself once it is attached, so TalkBack drops what it was saying and reads only the
 * sentence; no live region then (it would say it twice). If the focus action is refused, the live region
 * speaks it as before. When a focused card goes away, [beforeFocusedDismiss] first puts the focus on the
 * page (never back into the old popup), unless the person already moved it.
 * Main thread. Device check needed (does TalkBack read a live region in an accessibility overlay
 * window, does it follow a focus requested from the overlay's own view; LG Android 12 / TalkBack version).
 *
 * F41: with `hearClosed` the card also has a [방금 닫은 내용 듣기] button under the sentence (TalkBack reaches it
 * with one swipe; its label is its own text). [onGone] runs when a shown card goes away (timeout, [dismiss],
 * or replaced by the next card), so what the button would say is forgotten with it.
 *
 * F42: a card with that button no longer goes after a fixed time: [ResultCardClock] keeps it while TalkBack's
 * focus is inside it (up to 15 s), takes it away 2 s after the focus leaves, and otherwise waits as long as the
 * person's accessibility timeout setting asks. [trace] / [timeoutMillis] are debug codes and a number only.
 */
class TalkBackResultBanner(
    private val service: AccessibilityService,
    /** F17: called right before a card that still holds TalkBack's focus is removed. */
    private val beforeFocusedDismiss: () -> Unit = {},
    /** F41: a shown card went away. */
    private val onGone: () -> Unit = {},
    /** F42: debug codes (no words). */
    private val trace: (SimpleTrace) -> Unit = {},
    /** F42: the card's first timeout in ms (debug line `RESULT_CARD_TIMEOUT_MS`). */
    private val timeoutMillis: (Long) -> Unit = {},
) {
    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    /** The card's window root: the sentence itself, or (F41) the sentence and its button. */
    private var shown: View? = null
    /** The sentence's view, which takes TalkBack's focus. */
    private var sentenceView: TextView? = null
    private var buttonView: View? = null
    private var focusMode = false

    /** TalkBack (touch exploration) is on: results should go through it. */
    fun talkBackOn(): Boolean = service.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true

    /**
     * Shows [sentence]; false when the window could not be added (the caller then uses its TTS).
     * [onShown] says, once the text is set, whether the card is really on screen (debug log, F12), and
     * (F17, [takeFocus]) whether it took TalkBack's focus.
     */
    fun show(
        sentence: String,
        takeFocus: Boolean = false,
        /** F41: the button's label and what it does; null = the sentence only, as before. */
        hearClosed: Pair<String, () -> Unit>? = null,
        onShown: (shown: Boolean, focused: Boolean) -> Unit = { _, _ -> },
    ): Boolean {
        dismiss()
        val density = service.resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val cardBackground = GradientDrawable().apply {
            setColor(0xE6202A44.toInt())
            cornerRadius = 16 * density
        }
        val view = TextView(service).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(pad, pad / 2, pad, pad / 2)
            if (hearClosed == null) background = cardBackground
            if (!takeFocus) accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            if (takeFocus) text = sentence
        }
        val button = hearClosed?.let { (label, onPress) ->
            Button(service).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                minHeight = (48 * density).toInt()
                setOnClickListener { onPress() }
            }
        }
        val root: View = if (button == null) view else LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBackground
            setPadding(0, 0, pad / 2, pad / 2)
            addView(view)
            addView(button, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = pad
            })
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
            windowManager.addView(root, params)
            shown = root
            sentenceView = view
            buttonView = button
            focusMode = takeFocus
            view.post {
                val onScreen = view.isAttachedToWindow && view.isShown
                if (!takeFocus) {
                    // A live region speaks a change, not the first layout: set the text once attached.
                    view.text = sentence
                    onShown(onScreen, false)
                    return@post
                }
                val focused = onScreen && view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
                if (!focused) {
                    // Not taken: say it through the live region instead (a text change after attach).
                    focusMode = false
                    view.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
                    view.text = ""
                    view.text = sentence
                }
                onShown(onScreen, focused)
            }
            if (button == null) {
                handler.postDelayed({ if (shown === root) dismiss() }, ResultCardClock.baseMillis(takeFocus))
            } else {
                val timeout = ResultCardClock.timeoutMillis(takeFocus, hasButton = true, ::recommendedTimeout)
                timeoutMillis(timeout)
                watch(root, ResultCardClock(timeout), SystemClock.uptimeMillis())
            }
            true
        } catch (e: RuntimeException) {
            shown = null
            sentenceView = null
            buttonView = null
            false
        }
    }

    /** F42: checks the card every [ResultCardClock.CHECK_MILLIS] until its clock says it goes (or it is replaced). */
    private fun watch(root: View, clock: ResultCardClock, shownAt: Long) {
        handler.postDelayed({
            if (shown !== root) return@postDelayed
            val step = clock.tick(SystemClock.uptimeMillis() - shownAt, focusInside())
            when (step) {
                ResultCardClock.Step.KEEP -> Unit
                ResultCardClock.Step.HELD_FOCUS -> trace(SimpleTrace.RESULT_CARD_HELD_FOCUS)
                ResultCardClock.Step.FOCUS_LEFT -> trace(SimpleTrace.RESULT_CARD_FOCUS_LEFT)
                ResultCardClock.Step.DISMISS_HOLD_MAX -> trace(SimpleTrace.RESULT_CARD_HOLD_MAX)
                ResultCardClock.Step.DISMISS -> trace(SimpleTrace.RESULT_CARD_TIMED_OUT)
            }
            if (step == ResultCardClock.Step.DISMISS || step == ResultCardClock.Step.DISMISS_HOLD_MAX) dismiss()
            else watch(root, clock, shownAt)
        }, ResultCardClock.CHECK_MILLIS)
    }

    private fun focusInside(): Boolean =
        listOfNotNull(sentenceView, buttonView).any(::hasAccessibilityFocus)

    /** The person's accessibility timeout ("조치를 취할 시간") for a card with words and a button. */
    private fun recommendedTimeout(original: Int): Int = try {
        service.getSystemService(AccessibilityManager::class.java)?.getRecommendedTimeoutMillis(
            original, AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_CONTROLS,
        ) ?: original
    } catch (_: RuntimeException) {
        original
    }

    /**
     * F42: the next popup is being handled: a card with [방금 닫은 내용 듣기] goes now (its words are forgotten too).
     * The focus is not moved to the page first: the next prompt or press takes care of it.
     */
    fun dismissForNextPopup() {
        if (shown == null || buttonView == null) return
        trace(SimpleTrace.RESULT_CARD_REPLACED_BY_NEXT)
        dismiss(focusPage = false)
    }

    /** F17: TalkBack's focus back on the card (the page took it during the reclaim watch); false when there is no card. */
    fun refocus(): Boolean {
        val view = sentenceView ?: return false
        return focusMode && view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
    }

    /** A card shown with the focus is on screen: the reclaim watch puts the focus back here. */
    val holdsFocusTarget: Boolean get() = shown != null && focusMode

    fun dismiss(focusPage: Boolean = true) {
        val view = shown ?: return
        shown = null
        val sentence = sentenceView
        val button = buttonView
        sentenceView = null
        buttonView = null
        // F17: removed under TalkBack's focus, the focus could fall back into the old popup: the page first.
        // F41: also when the person had swiped onto the card's button.
        if (focusPage && ((focusMode && sentence != null && hasAccessibilityFocus(sentence)) || (button != null && hasAccessibilityFocus(button)))) {
            try { beforeFocusedDismiss() } catch (_: RuntimeException) {}
        }
        focusMode = false
        try {
            windowManager.removeView(view)
        } catch (_: RuntimeException) {
        }
        try { onGone() } catch (_: RuntimeException) {}
    }

    private fun hasAccessibilityFocus(view: View): Boolean = try {
        view.createAccessibilityNodeInfo().isAccessibilityFocused
    } catch (_: RuntimeException) {
        false
    }
}
