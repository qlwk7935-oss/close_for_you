package kr.closeforyou.ui.voice.overlay

/** Where C's prompt window stands. A can read it through [ChoicePromptPresenter.windowState]. */
enum class OverlayWindowStatus {
    /** No C window on screen. */
    HIDDEN,

    /** One prompt is on screen and its taps and voice results are accepted. */
    SHOWING,

    /**
     * Removing the window failed: it may still be on screen, blanked and with every callback dead.
     * The next show/dismiss retries removal; until it succeeds no new prompt is shown.
     */
    REMOVE_FAILED,
}

/** What [ChoicePromptPresenter.present] did. Anything but [SHOWN] means: no prompt, no choice. */
enum class PromptShowResult {
    SHOWN,

    /** Creating, adding or announcing the window failed; everything made for it was released. */
    FAILED,

    /** An older window could not be removed, so a new prompt was not shown on top of it. */
    BLOCKED,
}

/** Result of one removal attempt. */
enum class RemoveOutcome {
    REMOVED,

    /** WindowManager does not know the view (never added, or already removed). */
    NOT_ATTACHED,
    FAILED,
}

/**
 * Android-specific steps for one prompt window [W]. [OverlayWindowKeeper] calls them in a fixed
 * order and catches every RuntimeException, so a fake can throw anywhere in JVM tests.
 */
internal interface OverlayWindowOps<W : Any> {
    /** WindowManager.addView. */
    fun add(window: W)

    /** OverlayListener.onShown (may start the delayed announcement). */
    fun announceShown(window: W)

    /** OverlayListener.onDismissed (stops speech). */
    fun announceDismissed(window: W)

    /** Stops listening and cancels the window's coroutine scope. */
    fun stop(window: W)

    /** removeView, or removeViewImmediate when [immediate]. */
    fun remove(window: W, immediate: Boolean): RemoveOutcome

    /** Empties a window that could not be removed, so nothing on it can be chosen. */
    fun blank(window: W)

    /** Ends the window's lifecycle (OverlayLifecycleOwner.destroy). */
    fun destroy(window: W)
}

/**
 * The window bookkeeping of [AccessibilityChoiceOverlay], free of Android so it runs on the JVM.
 *
 * - Every show gets a new generation. Only the generation on screen is "current"; a tap, voice
 *   result or callback from any other generation is dropped by the caller via [isCurrent].
 * - A window stops being current *before* it is removed, so a removal failure never leaves live
 *   callbacks behind (review #18-1).
 * - "Hidden" only after removal succeeded (or WindowManager no longer knows the view). A failed
 *   removal is kept as REMOVE_FAILED: blanked, retried with removeViewImmediate on the next
 *   show/dismiss, and no new prompt is shown while it stays.
 * - stop and destroy run exactly once per window, whether removal succeeds or not; a failed
 *   create/add/announce releases everything made so far (review #18-2).
 *
 * Main thread only.
 */
internal class OverlayWindowKeeper<W : Any>(private val ops: OverlayWindowOps<W>) {
    private class Entry<W>(val window: W, val generation: Long) {
        var stopped = false
        var destroyed = false
    }

    private var lastGeneration = NO_GENERATION
    private var showing: Entry<W>? = null
    private var stuck: Entry<W>? = null

    val status: OverlayWindowStatus
        get() = when {
            showing != null -> OverlayWindowStatus.SHOWING
            stuck != null -> OverlayWindowStatus.REMOVE_FAILED
            else -> OverlayWindowStatus.HIDDEN
        }

    /** Generation whose callbacks are accepted, or [NO_GENERATION]. */
    val currentGeneration: Long get() = showing?.generation ?: NO_GENERATION

    /** Generation of the window still on screen (showing or stuck), or [NO_GENERATION]. */
    val windowGeneration: Long get() = (showing ?: stuck)?.generation ?: NO_GENERATION

    fun isCurrent(generation: Long): Boolean =
        generation != NO_GENERATION && showing?.generation == generation

    /** Replaces the current window with one made by [create] for a new generation. */
    fun show(create: (generation: Long) -> W): PromptShowResult {
        closeShowing()
        if (!retryStuck()) return PromptShowResult.BLOCKED
        val generation = ++lastGeneration
        // create releases its own partial work when it throws (AccessibilityChoiceOverlay).
        val window = try {
            create(generation)
        } catch (e: RuntimeException) {
            return PromptShowResult.FAILED
        }
        val entry = Entry(window, generation)
        try {
            ops.add(window)
        } catch (e: RuntimeException) {
            stop(entry)
            // WindowManagerGlobal undoes a failed add itself; removing again only confirms it.
            finishRemoval(entry, remove(window, immediate = true))
            return PromptShowResult.FAILED
        }
        showing = entry
        try {
            ops.announceShown(window)
        } catch (e: RuntimeException) {
            closeShowing()
            return PromptShowResult.FAILED
        }
        return PromptShowResult.SHOWN
    }

    /**
     * Passes a tap or voice [answer] on, only if its generation is current. When [closesWindow],
     * the window is closed first; if it could not be removed the choice becomes
     * [PromptAnswer.Lost], so nothing reaches B or A while a stale window may still be on screen.
     */
    fun deliver(answer: PromptAnswer, closesWindow: Boolean, onAnswer: (PromptAnswer) -> Unit) {
        if (!isCurrent(answer.generation)) return
        if (closesWindow) {
            closeShowing()
            if (stuck != null) {
                onAnswer(PromptAnswer.Lost(answer.generation))
                return
            }
        }
        onAnswer(answer)
    }

    /** Closes the current window, or retries removing a stuck one. Safe to call any number of times. */
    fun dismiss() {
        if (showing != null) closeShowing() else retryStuck()
    }

    private fun closeShowing() {
        val entry = showing ?: return
        showing = null // from here on nothing from this window reaches B or A
        safely { ops.announceDismissed(entry.window) }
        stop(entry)
        finishRemoval(entry, remove(entry.window, immediate = false))
    }

    private fun finishRemoval(entry: Entry<W>, outcome: RemoveOutcome) {
        if (outcome == RemoveOutcome.FAILED) {
            safely { ops.blank(entry.window) }
            stuck = entry
        }
        if (!entry.destroyed) {
            entry.destroyed = true
            safely { ops.destroy(entry.window) }
        }
    }

    /** True when nothing stuck is left on screen. */
    private fun retryStuck(): Boolean {
        val entry = stuck ?: return true
        if (remove(entry.window, immediate = true) == RemoveOutcome.FAILED) return false
        stuck = null
        return true
    }

    private fun stop(entry: Entry<W>) {
        if (entry.stopped) return
        entry.stopped = true
        safely { ops.stop(entry.window) }
    }

    private fun remove(window: W, immediate: Boolean): RemoveOutcome = try {
        ops.remove(window, immediate)
    } catch (e: RuntimeException) {
        RemoveOutcome.FAILED
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: RuntimeException) {
            // Cleanup must go on; the failure itself is visible through status.
        }
    }

    companion object {
        const val NO_GENERATION = 0L
    }
}
