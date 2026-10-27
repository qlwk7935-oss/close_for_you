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
    fun current(diagnostic: ManualOwnershipProbe? = null): SnapshotToken? {
        val now = clock()
        val lease = when {
            shownAt < 0 -> OwnershipLease.NOT_STARTED
            now < shownAt -> OwnershipLease.CLOCK_REVERSED
            now - shownAt >= ChoiceDisplayLease.MAX_DISPLAY_MILLIS -> OwnershipLease.EXPIRED
            else -> OwnershipLease.ACTIVE
        }
        diagnostic?.lease = lease
        if (lease != OwnershipLease.ACTIVE) clear()
        return token
    }
    fun eventBelongsToShow(state: OverlayWindowState, eventTime: Long, diagnostic: ManualOwnershipProbe? = null): Boolean {
        if (current(diagnostic) == null) return diagnostic?.reject(OwnershipCheck.DISPLAY_LEASE) ?: false
        diagnostic?.display = state.status
        if (state.status != OverlayWindowStatus.SHOWING)
            return diagnostic?.reject(OwnershipCheck.DISPLAY_STATE, ManualOwnership.NOT_OWNED) ?: false
        val sameGeneration = state.generation == generation
        diagnostic?.generation = if (sameGeneration) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH
        if (!sameGeneration) return diagnostic?.reject(OwnershipCheck.GENERATION, ManualOwnership.NOT_OWNED) ?: false
        if (eventTime < shownAt) {
            diagnostic?.time = OwnershipTime.BEFORE_SHOW
            return diagnostic?.reject(OwnershipCheck.EVENT_TIME, ManualOwnership.NOT_OWNED) ?: false
        }
        // Preserve the original second clock read and its exact short-circuit position.
        if (eventTime > clock()) {
            diagnostic?.time = OwnershipTime.FUTURE
            return diagnostic?.reject(OwnershipCheck.EVENT_TIME) ?: false
        }
        diagnostic?.time = OwnershipTime.WITHIN_SHOW
        return true
    }
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
    AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)

/** A failed native-control proof must not fall back to the older title-only focus policy. */
internal inline fun inspectManualEvent(type: Int, nativeControl: () -> Boolean, otherEvent: () -> Boolean): Boolean =
    if (manualControlEvent(type)) nativeControl() else otherEvent()

internal inline fun inspectManualOwnedInteraction(type: Int, state: OverlayWindowState,
    lifetime: ManualPromptLifetime, eventTime: () -> Long, eventPackage: () -> String?, ownPackage: String,
    eventWindow: () -> Int, nativeWindow: () -> Int?, before: Set<Int>?, live: () -> Set<Int>?,
    diagnostic: ManualOwnershipProbe? = null,
): Boolean {
    if (!manualControlEvent(type)) return false
    diagnostic?.check = OwnershipCheck.EVENT_TIME
    if (!lifetime.eventBelongsToShow(state, eventTime(), diagnostic)) return false
    diagnostic?.check = OwnershipCheck.PACKAGE
    val pkg = eventPackage()
    diagnostic?.pkg = when (pkg) { null -> OwnershipRelation.UNAVAILABLE; ownPackage -> OwnershipRelation.MATCH; else -> OwnershipRelation.MISMATCH }
    if (pkg != ownPackage) return diagnostic?.reject(OwnershipCheck.PACKAGE,
        if (pkg == null) ManualOwnership.UNKNOWN else ManualOwnership.NOT_OWNED) ?: false
    diagnostic?.before = if (before == null) OwnershipBefore.UNAVAILABLE else OwnershipBefore.AVAILABLE
    if (before == null) return diagnostic?.reject(OwnershipCheck.BEFORE_SET) ?: false
    diagnostic?.check = OwnershipCheck.NATIVE_LOOKUP
    val id = nativeWindow()
    diagnostic?.lookup = if (id == null || id < 0) OwnershipRelation.UNAVAILABLE else OwnershipRelation.MATCH
    if (id == null) return diagnostic?.reject(OwnershipCheck.NATIVE_LOOKUP,
        if (diagnostic?.nativeGeneration == OwnershipRelation.MISMATCH) ManualOwnership.NOT_OWNED else ManualOwnership.UNKNOWN) ?: false
    if (id < 0) return diagnostic?.reject(OwnershipCheck.NATIVE_ID) ?: false
    val oldWindow = id in before
    diagnostic?.before = if (oldWindow) OwnershipBefore.NATIVE_ALREADY_PRESENT else OwnershipBefore.NATIVE_ABSENT
    if (oldWindow) return diagnostic?.reject(OwnershipCheck.BEFORE_SET, ManualOwnership.NOT_OWNED) ?: false
    diagnostic?.check = OwnershipCheck.EVENT_WINDOW
    val eventId = eventWindow()
    val sameWindow = eventId == id
    diagnostic?.window = if (eventId < 0) OwnershipRelation.UNAVAILABLE else
        if (sameWindow) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH
    if (!sameWindow) return diagnostic?.reject(OwnershipCheck.EVENT_WINDOW,
        if (eventId < 0) ManualOwnership.UNKNOWN else ManualOwnership.NOT_OWNED) ?: false
    diagnostic?.check = OwnershipCheck.LIVE_SET
    val owned = live()
    val single = owned?.singleOrNull()
    diagnostic?.live = when {
        owned == null -> OwnershipLive.UNAVAILABLE
        owned.isEmpty() -> OwnershipLive.EMPTY
        owned.size != 1 -> OwnershipLive.MULTIPLE
        single == id -> OwnershipLive.SINGLE_MATCH
        else -> OwnershipLive.SINGLE_OTHER
    }
    if (single != id) return diagnostic?.reject(OwnershipCheck.LIVE_SET,
        if (single != null) ManualOwnership.NOT_OWNED else ManualOwnership.UNKNOWN) ?: false
    return diagnostic?.accepted() ?: true
}
