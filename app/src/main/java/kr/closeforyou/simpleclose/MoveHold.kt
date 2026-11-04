package kr.closeforyou.simpleclose

/** F57: why the quiet time after a move ended, or that it goes on. */
enum class HoldStep { KEEP, END_TIMEOUT, END_COVER_GONE, END_OTHER_POPUP, END_POPUP_GONE }

/**
 * F57/F58: the person chose a move on this popup's prompt: [words] (the popup), [root], and (an overlap, F57) [cover], the popup
 * taken to be in front, are left alone for a while.
 */
class MoveHold(val words: String, val root: PopupRoot, val cover: PopupRoot?, val startedAt: Long)

/**
 * F57 (우진 SM-F966N 4d9a134 canvas.suwon: USER_CHOSE_MOVE, MOVED_TO_BUTTON, 0.5 s later the same prompt again, the focus taken before
 * a double tap). F58: every [버튼으로 이동], not only an overlap's. The move's own focus event starts a read; the 10 s cooldown had run
 * out while the prompt was up (it is not refreshed then), so the popup was asked about again. After a move nothing is asked, pressed
 * or focused for this popup for [QUIET_MILLIS] after the person's last touch (finding a button by swiping, hearing it, a double tap:
 * the 10 s cooldown was too short on the device; each touch gives more time), at most [MAX_MILLIS]. It ends sooner when the screen
 * really changes: the cover gone (the person closed it: the popup behind is judged again), another popup, or the popup gone.
 * Pure; JVM tested.
 */
object MoveHoldRules {
    const val QUIET_MILLIS = 30_000L
    const val MAX_MILLIS = 120_000L

    fun timedOut(hold: MoveHold, now: Long, lastTouchAt: Long): Boolean =
        now - maxOf(hold.startedAt, lastTouchAt) >= QUIET_MILLIS || now - hold.startedAt >= MAX_MILLIS

    /** A read found [root] with [words]. The cover itself picked as the popup is still the same two popups. [coverPresent] null = unknown or no cover. */
    fun step(hold: MoveHold, now: Long, lastTouchAt: Long, words: String, root: PopupRoot, coverPresent: Boolean?): HoldStep {
        val cover = hold.cover
        return when {
            timedOut(hold, now, lastTouchAt) -> HoldStep.END_TIMEOUT
            cover != null && (root.path == cover.path || root.bounds == cover.bounds) -> HoldStep.KEEP
            words != hold.words -> HoldStep.END_OTHER_POPUP
            coverPresent == false -> HoldStep.END_COVER_GONE
            else -> HoldStep.KEEP
        }
    }

    /** A read found no popup: only the popup's own node going away ends it ([rootPresent] null = unknown). */
    fun stepNoRoot(hold: MoveHold, now: Long, lastTouchAt: Long, rootPresent: Boolean?): HoldStep = when {
        timedOut(hold, now, lastTouchAt) -> HoldStep.END_TIMEOUT
        rootPresent == false -> HoldStep.END_POPUP_GONE
        else -> HoldStep.KEEP
    }

    /**
     * F58: whether the popup may be pressed by itself again. Only when what the person was dealing with went away. After the time is
     * up it is asked, never pressed (AGENTS 6-7: they chose to go to it themselves); another popup says nothing about this one.
     */
    fun releasesAuto(step: HoldStep): Boolean = step == HoldStep.END_COVER_GONE || step == HoldStep.END_POPUP_GONE

    fun line(step: HoldStep, afterMillis: Long): String = "MOVE_HOLD_END reason=${step.name} afterMs=$afterMillis"
}

/**
 * F58: popups kept from an automatic press in [autoFailed], telling a hold (a move chose it, or something lay over the button;
 * nothing failed) from a real failure (AGENTS 6-6, never released). Pure; JVM tested.
 */
class AutoPressHolds(private val autoFailed: MutableSet<String>) {
    private val held = LinkedHashSet<String>()

    /** Not pressed by itself for now; a popup that already failed stays a failure. */
    fun hold(key: String) {
        if (key in autoFailed) return
        autoFailed += key
        held += key
        if (held.size > MAX) held.remove(held.first())
    }

    fun release(key: String) {
        if (held.remove(key)) autoFailed.remove(key)
    }

    /** A real failure on it: never released. */
    fun failed(key: String) {
        held.remove(key)
    }

    fun clear() = held.clear()

    private companion object {
        const val MAX = 32
    }
}
