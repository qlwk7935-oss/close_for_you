package kr.closeforyou.ui.voice.overlay

import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.MoveToButtonRequest

/**
 * C → A proposal (not yet in core/contract): the one call A's service makes to show "닫을까요?".
 *
 * A builds the [ChoicePrompt] with ChoicePromptPlanner and passes the resulting [UserChoice] to B's
 * policy and A's gate. The presenter never clicks, never moves TalkBack focus and never reads the
 * screen. Call every method on the main thread.
 */
interface ChoicePromptPresenter {
    val isShowing: Boolean

    /**
     * Shows [prompt], replacing any prompt already on screen. [onChoice] runs once per tap.
     * Press and keep choices close the prompt first; READ_CONTENT keeps it open so the person
     * can still choose after listening. [onMoveToButton] (feature off for now) closes the prompt
     * and asks A's ButtonFocusMover to move focus; it never reaches the action gate.
     */
    fun show(
        prompt: ChoicePrompt,
        // Before onChoice so `show(prompt) { choice -> }` still binds the trailing lambda to onChoice.
        onMoveToButton: (MoveToButtonRequest) -> Unit = {},
        onChoice: (UserChoice) -> Unit,
    )

    /** A calls this when the token is invalidated or the popup disappears. Safe to call twice. */
    fun dismiss()
}

/**
 * How A can recognise C's own prompt window, so its events are not treated as "a new popup" or as
 * a user interaction on the target app (design F11). Proposal only; A decides how to use it.
 *
 * - Events from the prompt carry this app's own package name (A's service shares the package).
 * - The window is [AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY].
 */
object ChoiceOverlayIdentity {
    fun isOwnOverlayEvent(eventPackageName: CharSequence?, ownPackageName: String): Boolean =
        eventPackageName?.toString() == ownPackageName

    fun isOwnOverlayWindow(windowType: Int, windowPackageName: CharSequence?, ownPackageName: String): Boolean =
        windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            isOwnOverlayEvent(windowPackageName, ownPackageName)
}
