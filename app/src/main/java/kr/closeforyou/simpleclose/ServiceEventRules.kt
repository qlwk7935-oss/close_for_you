package kr.closeforyou.simpleclose

import android.view.accessibility.AccessibilityEvent

/**
 * F49 (메인 세션 에뮬레이터 Pixel_8 API 37, WebView 145, 7a3912c, 시험 앱 P13 웹 모달: our service alone 3/3 CLICKED_LABELED_CLOSE
 * → NOT_CLOSED_STILL_THERE, the page never saw the click; with TalkBack or A's service (it takes typeViewScrolled) on, 1/1
 * closed): Chromium serves its full web accessibility only while a running service's event mask has TYPE_VIEW_SELECTED,
 * TYPE_VIEW_SCROLLED or TYPE_ANNOUNCEMENT (`AccessibilityStateDelegateImpl.isComplexUserInteractionServiceEnabled`), or it
 * performs gestures / touch exploration is on. So the service takes TYPE_VIEW_SCROLLED, and nothing more is done with it.
 */
object ServiceEventRules {
    /** An event taken only for Chromium's sake: dropped first thing (no tree read, no look-up scheduled). */
    fun dropAtOnce(eventType: Int): Boolean = eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
}
