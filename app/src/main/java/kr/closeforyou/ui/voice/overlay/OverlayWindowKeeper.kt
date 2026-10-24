package kr.closeforyou.ui.voice.overlay

/** Where C's prompt window stands. A can read it through [ChoicePromptPresenter.windowState]. */
enum class OverlayWindowStatus {
    /** No C window on screen. */
    HIDDEN,

    /** One prompt is on screen and its taps and voice results are accepted. */
    SHOWING,

    /**
     * A asked C to wait for a re-check ([RevalidatingFeature], off by default): the same window stays
     * on screen with its content cleared and every button disabled. No answer is accepted. It ends
     * as SHOWING (same popup: new prompt, new generation, same window) or HIDDEN (end, timeout, dismiss).
     */
    REVALIDATING,

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

    /**
     * Keeps the window added but clears its prompt, disables every button, stops listening and
     * speech, and shows the one "다시 확인하고 있어요" line. Never moves TalkBack focus.
     */
    fun enterRevalidating(window: W)
}

/** Schedules the REVALIDATING timeout on the main thread; the returned function cancels it. */
internal fun interface RevalidationTimer {
    fun schedule(delayMillis: Long, action: () -> Unit): () -> Unit

    companion object {
        /** Never fires: for keepers that are never asked to revalidate (JVM fakes). */
        val NEVER = RevalidationTimer { _, _ -> {} }
    }
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
 * - REVALIDATING (issue #29, off by default): [beginRevalidation] takes a new generation at once, so
 *   every tap, voice result and controller callback of the old prompt is dropped. The window is not
 *   removed (its window id stays, so A sees fewer window events). [confirmRevalidation] puts a new
 *   prompt in the same window under another new generation; [endRevalidation], the timeout,
 *   [dismiss] and [show] close it like a showing window (REMOVE_FAILED rules unchanged).
 *
 * Main thread only.
 */
internal class OverlayWindowKeeper<W : Any>(
    private val ops: OverlayWindowOps<W>,
    private val timer: RevalidationTimer = RevalidationTimer.NEVER,
    private val revalidationTimeoutMillis: Long = RevalidatingFeature.TIMEOUT_MILLIS,
) {
    private class Entry<W>(val window: W, var generation: Long) {
        /** The generation the window was added with; unchanged by revalidation. */
        val windowNumber = generation
        var stopped = false
        var destroyed = false
    }

    private var lastGeneration = NO_GENERATION
    private var showing: Entry<W>? = null
    private var revalidating: Entry<W>? = null
    private var stuck: Entry<W>? = null
    private var cancelTimeout: (() -> Unit)? = null

    val status: OverlayWindowStatus
        get() = when {
            showing != null -> OverlayWindowStatus.SHOWING
            revalidating != null -> OverlayWindowStatus.REVALIDATING
            stuck != null -> OverlayWindowStatus.REMOVE_FAILED
            else -> OverlayWindowStatus.HIDDEN
        }

    /** Generation whose callbacks are accepted, or [NO_GENERATION]. */
    val currentGeneration: Long get() = showing?.generation ?: NO_GENERATION

    private val onScreen: Entry<W>? get() = showing ?: revalidating ?: stuck

    /** Generation of the window still on screen (showing, revalidating or stuck), or [NO_GENERATION]. */
    val windowGeneration: Long get() = onScreen?.generation ?: NO_GENERATION

    /** Which added window is on screen ([Entry.windowNumber]); the same through revalidation. */
    val windowNumber: Long get() = onScreen?.windowNumber ?: NO_GENERATION

    fun isCurrent(generation: Long): Boolean =
        generation != NO_GENERATION && showing?.generation == generation

    /** Replaces the current window with one made by [create] for a new generation. */
    fun show(create: (generation: Long) -> W): PromptShowResult {
        closeOpen()
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
            closeOpen()
            return PromptShowResult.FAILED
        }
        return PromptShowResult.SHOWN
    }

    /**
     * SHOWING → REVALIDATING. The old generation stops being current *before* the window changes.
     * True when the window is now (or already was) revalidating; false when nothing was showing,
     * or clearing the window failed (it is then closed). A second call does not restart the timeout,
     * so repeated invalidations cannot keep the window up. [onTimeout] runs once the timeout has
     * closed the window, only for this revalidation.
     */
    fun beginRevalidation(onTimeout: () -> Unit = {}): Boolean {
        if (revalidating != null) return true
        val entry = showing ?: return false
        showing = null
        val generation = ++lastGeneration
        entry.generation = generation
        revalidating = entry
        try {
            ops.enterRevalidating(entry.window)
        } catch (e: RuntimeException) {
            closeOpen()
            return false
        }
        cancelTimeout = timer.schedule(revalidationTimeoutMillis) {
            // A late timer of an older revalidation must not touch this one (or its cancel handle).
            if (revalidating?.generation != generation) return@schedule
            cancelTimeout = null
            closeOpen()
            onTimeout()
        }
        return true
    }

    /**
     * REVALIDATING → SHOWING: [refill] puts the new prompt into the same window under a new
     * generation (no add, no remove). FAILED when not revalidating (the caller shows a new window
     * instead) or when [refill] threw (the window is then closed).
     */
    fun confirmRevalidation(refill: (window: W, generation: Long) -> Unit): PromptShowResult {
        val entry = revalidating ?: return PromptShowResult.FAILED
        cancelRevalidationTimeout()
        val generation = ++lastGeneration
        try {
            refill(entry.window, generation)
        } catch (e: RuntimeException) {
            closeOpen()
            return PromptShowResult.FAILED
        }
        entry.generation = generation
        revalidating = null
        showing = entry
        return PromptShowResult.SHOWN
    }

    /** REVALIDATING → HIDDEN (or REMOVE_FAILED). False when nothing was revalidating. */
    fun endRevalidation(): Boolean {
        if (revalidating == null) return false
        closeOpen()
        return true
    }

    /**
     * Passes a tap or voice [answer] on, only if its generation is current. When [closesWindow],
     * the window is closed first; if it could not be removed the choice becomes
     * [PromptAnswer.Lost], so nothing reaches B or A while a stale window may still be on screen.
     */
    fun deliver(answer: PromptAnswer, closesWindow: Boolean, onAnswer: (PromptAnswer) -> Unit) {
        if (!isCurrent(answer.generation)) return
        if (closesWindow) {
            closeOpen()
            if (stuck != null) {
                onAnswer(PromptAnswer.Lost(answer.generation))
                return
            }
        }
        onAnswer(answer)
    }

    /** Closes the current or revalidating window, or retries removing a stuck one. Safe to call any number of times. */
    fun dismiss() {
        if (showing != null || revalidating != null) closeOpen() else retryStuck()
    }

    private fun closeOpen() {
        val entry = showing ?: revalidating ?: return
        showing = null // from here on nothing from this window reaches B or A
        revalidating = null
        cancelRevalidationTimeout()
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

    private fun cancelRevalidationTimeout() {
        val cancel = cancelTimeout ?: return
        cancelTimeout = null
        safely { cancel() }
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
