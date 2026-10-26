package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus

/** Presentation/explicit-intent lease only. Never returned by Runtime or used as F9 evidence. */
internal class ManualPromptLifetime(private val clock: () -> Long) {
    private var token: SnapshotToken? = null
    private var shownAt = -1L
    private var generation = 0L
    fun shown(value: SnapshotToken, state: OverlayWindowState) {
        clear()
        if (state.status != OverlayWindowStatus.SHOWING || state.generation <= 0) return
        shownAt = clock()
        if (shownAt < 0) return
        token = value; generation = state.generation
    }
    // HIDDEN is intentionally not checked here: C removes its window before delivering a choice.
    // GuardedChoicePresenter separately checks the callback generation and successful removal.
    fun current(): SnapshotToken? {
        val now = clock()
        if (shownAt < 0 || now < shownAt || now - shownAt >= ChoiceDisplayLease.MAX_DISPLAY_MILLIS) clear()
        return token
    }
    fun eventBelongsToShow(state: OverlayWindowState, eventTime: Long): Boolean = current() != null &&
        state.status == OverlayWindowStatus.SHOWING && state.generation == generation &&
        eventTime >= shownAt && eventTime <= clock()
    fun clear() { token = null; shownAt = -1; generation = 0 }
    override fun toString() = "ManualPromptLifetime(redacted)"
}

/** This is not an event exemption. Raw evidence must still be invalidated on global touch.
 * No claimed app/window identity is reinterpreted as an internal touch. */
internal fun unattributedManualTouch(type: Int, window: Int, pkg: CharSequence?): Boolean =
    window < 0 && pkg == null && type in setOf(
        AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
        AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START, AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END)

/** TalkBack may nest exploration and touch. One END cannot release the other active contact. */
internal class ManualTouchState {
    private var touch = false
    private var exploration = false
    private var unknownTouch = false
    private var unknownExploration = false
    val active: Boolean get() = touch || exploration
    val intentBlocked: Boolean get() = (touch && !unknownTouch) || (exploration && !unknownExploration)
    fun observe(type: Int, unattributed: Boolean) {
        when (type) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> { touch = true; unknownTouch = unattributed }
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_START -> { exploration = true; unknownExploration = unattributed }
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> { touch = false; unknownTouch = false }
            AccessibilityEvent.TYPE_TOUCH_EXPLORATION_GESTURE_END -> { exploration = false; unknownExploration = false }
        }
    }
    fun clear() { touch = false; exploration = false; unknownTouch = false; unknownExploration = false }
}

/** Shared with Android: native View lifetime AND independently enumerated live overlay ownership.
 * An event from a retired generation / reused ID / before this show is never exempted. */
internal fun manualControlEvent(type: Int): Boolean = type in setOf(AccessibilityEvent.TYPE_VIEW_CLICKED,
    AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)

/** A failed native-control proof must not fall back to the older title-only focus policy. */
internal inline fun inspectManualEvent(type: Int, nativeControl: () -> Boolean, otherEvent: () -> Boolean): Boolean =
    if (manualControlEvent(type)) nativeControl() else otherEvent()

internal inline fun inspectManualOwnedInteraction(type: Int, state: OverlayWindowState,
    lifetime: ManualPromptLifetime, eventTime: () -> Long, eventPackage: () -> String?, ownPackage: String,
    eventWindow: () -> Int, nativeWindow: () -> Int?, before: Set<Int>?, live: () -> Set<Int>?,
): Boolean {
    if (!manualControlEvent(type)) return false
    if (!lifetime.eventBelongsToShow(state, eventTime()) || eventPackage() != ownPackage || before == null) return false
    val id = nativeWindow() ?: return false
    return id >= 0 && id !in before && eventWindow() == id && live()?.singleOrNull() == id
}
