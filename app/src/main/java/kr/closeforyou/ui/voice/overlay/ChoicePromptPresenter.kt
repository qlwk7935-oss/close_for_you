package kr.closeforyou.ui.voice.overlay

import android.view.accessibility.AccessibilityWindowInfo
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.MoveToButtonRequest

/** How the person made a choice. C-internal (not in core/contract); A may log the kind, never words. */
enum class ChoiceOrigin { TAP, VOICE }

/**
 * One answer from the prompt shown as [generation]. Delivered only while that generation is
 * current: an answer from a closed, replaced or failed window never arrives.
 */
sealed interface PromptAnswer {
    val generation: Long

    data class Picked(val choice: UserChoice, val origin: ChoiceOrigin, override val generation: Long) : PromptAnswer

    data class Move(val request: MoveToButtonRequest, val origin: ChoiceOrigin, override val generation: Long) : PromptAnswer

    /**
     * The person chose, but the prompt window could not be removed. The choice is dropped (nothing
     * goes to B or A) and the window stays [OverlayWindowStatus.REMOVE_FAILED].
     */
    data class Lost(override val generation: Long) : PromptAnswer
}

/**
 * Read-only view of C's window for A. [generation] is that of the prompt on screen (showing,
 * revalidating or stuck). [windowNumber] names the added window itself: it equals the generation
 * the window was added with and stays the same through REVALIDATING, when [generation] changes.
 */
data class OverlayWindowState(
    val status: OverlayWindowStatus,
    val generation: Long,
    val windowNumber: Long = generation,
) {
    companion object {
        val HIDDEN = OverlayWindowState(OverlayWindowStatus.HIDDEN, OverlayWindowKeeper.NO_GENERATION)
    }
}

/**
 * C → A proposal (not yet in core/contract): the one call A's service makes to show "닫을까요?".
 *
 * A builds the [ChoicePrompt] with ChoicePromptPlanner and passes the resulting [UserChoice] to B's
 * policy and A's gate. The presenter never clicks, never moves TalkBack focus and never reads the
 * screen. Call every method on the main thread.
 */
interface ChoicePromptPresenter {
    /** True only while a prompt is on screen and accepting answers. */
    val isShowing: Boolean

    val windowState: OverlayWindowState
        get() = if (isShowing) OverlayWindowState(OverlayWindowStatus.SHOWING, OverlayWindowKeeper.NO_GENERATION) else OverlayWindowState.HIDDEN

    /**
     * Shows [prompt], replacing any prompt already on screen, and returns whether it is on screen.
     * [onAnswer] runs once per tap or voice result of *this* prompt while it is current.
     * Press and keep choices close the prompt first; READ_CONTENT keeps it open so the person
     * can still choose after listening. Move (feature off for now) closes the prompt; A's
     * ButtonFocusMover moves focus and it never reaches the action gate.
     * Not [PromptShowResult.SHOWN]: nothing is on screen for this prompt and [onAnswer] never runs.
     */
    fun present(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult

    /** Older form of [present] without generation or origin. [PromptAnswer.Lost] is dropped. */
    fun show(
        prompt: ChoicePrompt,
        // Before onChoice so `show(prompt) { choice -> }` still binds the trailing lambda to onChoice.
        onMoveToButton: (MoveToButtonRequest) -> Unit = {},
        onChoice: (UserChoice) -> Unit,
    ): PromptShowResult = present(prompt) { answer ->
        when (answer) {
            is PromptAnswer.Picked -> onChoice(answer.choice)
            is PromptAnswer.Move -> onMoveToButton(answer.request)
            is PromptAnswer.Lost -> Unit
        }
    }

    /**
     * A calls this when the token is invalidated or the popup disappears. Safe to call any number
     * of times; after a failed removal each call retries it ([windowState]).
     */
    fun dismiss()

    /**
     * Signal 1 of 3 (REVALIDATING, [RevalidatingFeature], off by default): A invalidated the token
     * but will re-check whether the popup is the same. C keeps the window, takes a new generation
     * (every old tap and voice result is dropped), clears the prompt, disables the buttons, stops
     * speech and listening, and shows "다시 확인하고 있어요" once (liveRegion). Focus is not moved.
     * Returns true when the window is now revalidating. Feature off, or nothing showing: same as
     * [dismiss] and false. Without a confirmation in [RevalidatingFeature.TIMEOUT_MILLIS] C closes it.
     */
    fun beginRevalidation(cause: RevalidationCause): Boolean {
        dismiss()
        return false
    }

    /**
     * Signal 2 of 3: A's F0 re-check found the same popup ("same" is A's rule). [prompt], planned
     * from the new analysis, goes into the same window under a new generation; only a short line
     * is said, not the whole prompt again. Not revalidating any more (timed out, ended): same as
     * [present], a new window.
     */
    fun confirmSamePopup(prompt: ChoicePrompt, onAnswer: (PromptAnswer) -> Unit): PromptShowResult =
        present(prompt, onAnswer)

    /**
     * Signal 3 of 3: the re-check failed (different popup, screen changed, sensitive screen). The
     * window closes and, except for [RevalidationEnd.BLOCKED], one short sentence says why.
     * Not revalidating: same as [dismiss].
     */
    fun endRevalidation(end: RevalidationEnd) = dismiss()
}

/**
 * Facts A copies from one [AccessibilityWindowInfo] (`getId()`, `getType()`, `getTitle()`,
 * `getRoot()?.packageName`). Plain data so the matching rules are JVM-tested.
 */
data class WindowFacts(
    val id: Int,
    val type: Int,
    val title: CharSequence?,
    val rootPackageName: CharSequence?,
)

/**
 * How A can recognise C's own prompt window (design F11). Proposal for the contract in
 * docs/c-notes/overlay-identity-contract.md; A decides how to use it.
 *
 * A match means only "this event happened inside C's current prompt window". It is NOT a reason to
 * skip token invalidation as a whole: events from the target app's windows, the person's actions
 * outside C's window, sensitive-screen checks and A's own currency checks stay exactly as they are.
 * A may leave out only C-window events from new-popup detection and baseline resets.
 *
 * The package name alone is never enough: A's service, C's window and the debug test service all
 * share it.
 */
object ChoiceOverlayIdentity {
    /** Every condition must hold; a window that only shares the package is not C's prompt. */
    fun isOwnOverlayWindow(
        window: WindowFacts,
        ownPackageName: String,
        ownWindowTitle: String,
        overlay: OverlayWindowState,
    ): Boolean =
        overlay.status != OverlayWindowStatus.HIDDEN &&
            window.id >= 0 &&
            window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            window.rootPackageName?.toString() == ownPackageName &&
            window.title?.toString() == ownWindowTitle

    @Deprecated(
        "Package name alone also matches A's own service events (review #18-3). Use OwnOverlayWindowTracker.",
        ReplaceWith("OwnOverlayWindowTracker"),
    )
    fun isOwnOverlayEvent(eventPackageName: CharSequence?, ownPackageName: String): Boolean =
        eventPackageName?.toString() == ownPackageName

    @Deprecated(
        "Does not check the window title, id or whether C's prompt is on screen. Use isOwnOverlayWindow(WindowFacts, ...).",
        ReplaceWith("isOwnOverlayWindow"),
    )
    fun isOwnOverlayWindow(windowType: Int, windowPackageName: CharSequence?, ownPackageName: String): Boolean =
        windowType == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY &&
            windowPackageName?.toString() == ownPackageName
}

/**
 * Remembers which window id is C's prompt window, for A's event filter. A calls [update] with
 * `getWindows()` facts when windows change (TYPE_WINDOWS_CHANGED) and right after a show; then
 * [isOwnOverlayWindowEvent] with `event.windowId` for each event. Needs A's service to retrieve
 * interactive windows (flagRetrieveInteractiveWindows). Device behaviour unverified.
 *
 * Conservative by design: no match, two look-alike windows, an unknown window id (-1), a hidden
 * prompt or a different added window all answer false, so the event is treated as not C's.
 * It compares [OverlayWindowState.windowNumber], so the same window stays recognised while it is
 * REVALIDATING and after a same-popup refill (it is the same added window); a new window needs
 * [update] again.
 */
class OwnOverlayWindowTracker(
    private val ownPackageName: String,
    /** The fixed window title, `getString(R.string.ui_choice_pane_title)`; never popup text. */
    private val ownWindowTitle: String,
) {
    private var windowId: Int? = null
    private var windowNumber = OverlayWindowKeeper.NO_GENERATION

    fun update(windows: List<WindowFacts>, overlay: OverlayWindowState) {
        val match = windows.filter { ChoiceOverlayIdentity.isOwnOverlayWindow(it, ownPackageName, ownWindowTitle, overlay) }
            .singleOrNull()
        windowId = match?.id
        windowNumber = if (match != null) overlay.windowNumber else OverlayWindowKeeper.NO_GENERATION
    }

    fun isOwnOverlayWindowEvent(eventWindowId: Int, overlay: OverlayWindowState): Boolean {
        val id = windowId ?: return false
        return overlay.status != OverlayWindowStatus.HIDDEN &&
            overlay.windowNumber != OverlayWindowKeeper.NO_GENERATION &&
            overlay.windowNumber == windowNumber &&
            eventWindowId >= 0 &&
            eventWindowId == id
    }
}
