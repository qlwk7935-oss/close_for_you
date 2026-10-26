package kr.closeforyou.accessibility

import android.view.accessibility.AccessibilityEvent

/** Focus origin cannot establish automatic vs user focus. Always invalidate, then carry ONLY
 * an interrupted screen review into a new full F0/F1 attempt. No standalone focus starts one. */
internal fun pendingFocusReview(type: Int, capturePending: Boolean, evidencePending: Boolean,
    presentationPending: Boolean): Boolean =
    (capturePending || evidencePending || presentationPending) && isReviewFocusEvent(type)

internal fun isReviewFocusEvent(type: Int): Boolean = type in setOf(
        AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
        AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
