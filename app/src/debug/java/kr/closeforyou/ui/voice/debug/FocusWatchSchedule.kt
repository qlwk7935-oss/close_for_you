package kr.closeforyou.ui.voice.debug

import kr.closeforyou.ui.voice.overlay.OverlayWindowKeeper
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus

/**
 * Debug-only bookkeeping for [OverlayNodeDump]'s focus watch (PR #34 review), free of Android so it runs
 * on the JVM. The caller owns the Handler; this class only decides what is current.
 *
 * - One watch at a time. A new request replaces the old one; the caller cancels the old callbacks first.
 * - The watch has a deadline; the caller posts a timeout for it and [stop]s at the deadline even when no
 *   focus event arrives.
 * - Moving TalkBack's focus is off by default: [start] with `moveFocus = true` (explicit adb extra) only.
 * - A follow-up (node dump, and the focus move when asked for) is bound to the watch, the service session
 *   and the C window generation that was shown. When it runs, [check] answers [Verdict.RUN] only if all
 *   three are still the same and the window is still showing.
 *
 * Main thread only.
 */
internal class FocusWatchSchedule {
    data class Watch(val id: Int, val serviceSession: Int, val deadline: Long, val moveFocus: Boolean)

    /** One follow-up after C's prompt was shown with [windowGeneration]. */
    data class FollowUp(val watchId: Int, val serviceSession: Int, val windowGeneration: Long, val moveFocus: Boolean)

    enum class Verdict {
        RUN,
        NO_WATCH,

        /** A newer watch or a newer show replaced this follow-up. */
        REPLACED,
        SERVICE_CHANGED,
        WINDOW_NOT_SHOWING,
        WINDOW_CHANGED,
    }

    var watch: Watch? = null
        private set
    var followUp: FollowUp? = null
        private set
    private var lastId = 0

    /** Starts a new watch. Whatever ran before is forgotten (its follow-up can no longer pass [check]). */
    fun start(now: Long, millis: Long, moveFocus: Boolean, serviceSession: Int): Watch {
        lastId += 1
        followUp = null
        return Watch(lastId, serviceSession, now + millis, moveFocus).also { watch = it }
    }

    fun isExpired(now: Long): Boolean = watch?.let { now >= it.deadline } ?: false

    /**
     * C's prompt is on screen as [overlay]. Returns the follow-up to post, replacing an earlier one, or null
     * when no watch is running or the window is not a real shown generation.
     */
    fun onPromptShown(overlay: OverlayWindowState): FollowUp? {
        val current = watch ?: return null
        if (overlay.status != OverlayWindowStatus.SHOWING || overlay.generation == OverlayWindowKeeper.NO_GENERATION) {
            followUp = null
            return null
        }
        return FollowUp(current.id, current.serviceSession, overlay.generation, current.moveFocus).also { followUp = it }
    }

    /** Right before a follow-up runs: may it still touch the window? */
    fun check(candidate: FollowUp, serviceSession: Int, overlay: OverlayWindowState): Verdict {
        val current = watch ?: return Verdict.NO_WATCH
        return when {
            candidate != followUp || candidate.watchId != current.id -> Verdict.REPLACED
            serviceSession == NO_SESSION ||
                candidate.serviceSession != serviceSession ||
                current.serviceSession != serviceSession -> Verdict.SERVICE_CHANGED
            overlay.status != OverlayWindowStatus.SHOWING -> Verdict.WINDOW_NOT_SHOWING
            overlay.generation != candidate.windowGeneration -> Verdict.WINDOW_CHANGED
            else -> Verdict.RUN
        }
    }

    /** A follow-up ran or was skipped; it is not pending any more. */
    fun finish(candidate: FollowUp) {
        if (followUp == candidate) followUp = null
    }

    /** Ends the watch. True when one was running (so the caller restores the service info). */
    fun stop(): Boolean {
        val wasRunning = watch != null
        watch = null
        followUp = null
        return wasRunning
    }

    companion object {
        /** No connected service (before onServiceConnected, after onUnbind/onDestroy). */
        const val NO_SESSION = 0
    }
}
