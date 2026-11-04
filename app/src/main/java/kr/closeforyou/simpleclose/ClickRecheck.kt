package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ScreenBounds

/**
 * F48 (동준 에뮬레이터 API 37, 76245ff, 시험 앱 P34 큰 레이어, TalkBack 끔: DECIDED_AUTO_CLOSE_STRONG → RECHECK_TARGET_GONE, nothing
 * said, the layer stayed; b9827d3 closed it): the re-check right before the press said "gone" as one code. Which of its three
 * parts failed is told apart here, codes only (no text, no id): the app in front ([RecheckGroup.PACKAGE]), the popup root
 * at its path with the same bounds ([RecheckGroup.ROOT]), the button at its path with the same name ([RecheckGroup.TARGET]).
 * Nothing about the check itself changes: all three must hold, as before (AGENTS 6-5, 6-7).
 */
enum class RecheckGroup { PACKAGE, ROOT, TARGET }

enum class RecheckMiss(val group: RecheckGroup) {
    /** No active window at that moment (one screen going, the next coming). */
    NO_ACTIVE_WINDOW(RecheckGroup.PACKAGE),
    OTHER_APP(RecheckGroup.PACKAGE),
    /** The tree could not be read (no active window for the root, or a binder error). */
    ROOT_UNREADABLE(RecheckGroup.ROOT),
    ROOT_MISSING(RecheckGroup.ROOT),
    ROOT_HIDDEN(RecheckGroup.ROOT),
    /** The node at the root's path has other bounds (moved, resized, or another node there now). */
    ROOT_MOVED(RecheckGroup.ROOT),
    ROOT_NOT_DIALOG(RecheckGroup.ROOT),
    TARGET_MISSING(RecheckGroup.TARGET),
    TARGET_HIDDEN(RecheckGroup.TARGET),
    TARGET_DISABLED(RecheckGroup.TARGET),
    TARGET_NOT_CLICKABLE(RecheckGroup.TARGET),
    /** Another name at the button's path (never which one). */
    TARGET_NAME_CHANGED(RecheckGroup.TARGET),
    TARGET_UNREADABLE(RecheckGroup.TARGET),
}

/** F48: the live root's look ([miss] null = still there); [live] its bounds when the node was found, for [ClickRecheck.line]. */
data class RootRecheck(val miss: RecheckMiss?, val live: ScreenBounds? = null)

/** F48: pure parts of the re-check right before a press, JVM tested. */
object ClickRecheck {
    /** The app in front: null = still [expected]. [front] null = no active window. */
    fun packageMiss(front: String?, expected: String): RecheckMiss? = when (front) {
        null -> RecheckMiss.NO_ACTIVE_WINDOW
        expected -> null
        else -> RecheckMiss.OTHER_APP
    }

    /** The node at the root's path: same bounds, visible, (a Dialog root) still a Dialog class. Same rule as before F48. */
    fun rootMiss(visible: Boolean, live: ScreenBounds, expected: ScreenBounds, mustBeDialog: Boolean, isDialog: Boolean): RecheckMiss? = when {
        !visible -> RecheckMiss.ROOT_HIDDEN
        live != expected -> RecheckMiss.ROOT_MOVED
        mustBeDialog && !isDialog -> RecheckMiss.ROOT_NOT_DIALOG
        else -> null
    }

    /** The node at the button's path: visible, enabled, clickable itself (F27), the same name. Same rule as before F48. */
    fun targetMiss(visible: Boolean, enabled: Boolean, clickable: Boolean, sameName: Boolean): RecheckMiss? = when {
        !visible -> RecheckMiss.TARGET_HIDDEN
        !enabled -> RecheckMiss.TARGET_DISABLED
        !clickable -> RecheckMiss.TARGET_NOT_CLICKABLE
        !sameName -> RecheckMiss.TARGET_NAME_CHANGED
        else -> null
    }

    /**
     * F49 (동준 7a3912c ×5: 2/5 `ROOT_MOVED dl=0 dt=-53 dr=0 db=-53` / `dt=16`, the #73 insets moving the layer just after
     * the first frame; a web page loading does the same): the popup root kept its size and only moved. Then the screen is
     * read again and judged from the start; the old reading is never pressed at the new place (AGENTS 6-5, 6-7).
     * F50 (P01 `dl=-42 dt=821 dr=-42 db=821`, a Dialog read while its window was coming in): any move of the same size,
     * sideways or far too. How far it moved does not make the press any less safe: nothing is pressed where it was, the
     * fresh judgement and its own re-check decide. A change of size stays out (another node, or still opening): asked.
     */
    fun sameSizeMove(miss: RecheckMiss, expected: ScreenBounds, live: ScreenBounds?): Boolean {
        if (miss != RecheckMiss.ROOT_MOVED || live == null) return false
        val dx = live.left - expected.left
        val dy = live.top - expected.top
        return live.right - expected.right == dx && live.bottom - expected.bottom == dy && (dx != 0 || dy != 0)
    }

    /**
     * F49: what follows an automatic press the re-check stopped (nothing was pressed).
     * - Another app in front ([RecheckGroup.PACKAGE]): quiet.
     * - A move of the same size ([sameSizeMove]) the first time for this popup ([rereadDone] false): read again
     *   ([MissStep.REREAD]); the fresh judgement presses only the same close button ([sameTarget]).
     * - Otherwise (C decision, 재호 11/3 "팝업 그대로면 항상 묻기"): the popup still on screen ([stillShown], the button
     *   mismatch counts as shown, its root was just found): said as a failed close and asked ([MissStep.ASK]); gone or
     *   unknown: quiet. A bottom banner is never asked about (F37).
     */
    fun afterMiss(miss: RecheckMiss, shift: Boolean, rereadDone: Boolean, stillShown: Boolean?, bottomBanner: Boolean): MissStep = when {
        miss.group == RecheckGroup.PACKAGE -> MissStep.QUIET
        shift && !rereadDone -> MissStep.REREAD
        bottomBanner -> MissStep.QUIET
        miss.group == RecheckGroup.TARGET || stillShown == true -> MissStep.ASK
        else -> MissStep.QUIET
    }

    /**
     * F49: the fresh judgement after a [MissStep.REREAD] picked the same close button: the same name, and the same place
     * inside its popup root (offset and size), wherever the root now is.
     */
    fun sameTarget(oldLabel: String?, oldButton: ScreenBounds, oldRoot: ScreenBounds, newLabel: String?, newButton: ScreenBounds, newRoot: ScreenBounds): Boolean =
        oldLabel != null && oldLabel == newLabel &&
            oldButton.left - oldRoot.left == newButton.left - newRoot.left && oldButton.top - oldRoot.top == newButton.top - newRoot.top &&
            oldButton.width == newButton.width && oldButton.height == newButton.height

    /**
     * One debug line: the reason, and for [RecheckMiss.ROOT_MOVED] how far each edge moved (pixels; no text, no id),
     * to tell a layout still settling from another node at that path.
     */
    fun line(miss: RecheckMiss, expected: ScreenBounds? = null, live: ScreenBounds? = null): String {
        val delta = if (miss == RecheckMiss.ROOT_MOVED && expected != null && live != null) {
            " dl=${live.left - expected.left} dt=${live.top - expected.top} dr=${live.right - expected.right} db=${live.bottom - expected.bottom}"
        } else {
            ""
        }
        return "RECHECK_MISS reason=${miss.name}$delta"
    }

    fun trace(group: RecheckGroup): SimpleTrace = when (group) {
        RecheckGroup.PACKAGE -> SimpleTrace.RECHECK_GONE_PACKAGE
        RecheckGroup.ROOT -> SimpleTrace.RECHECK_GONE_ROOT
        RecheckGroup.TARGET -> SimpleTrace.RECHECK_GONE_TARGET
    }
}

/** F49: what follows a stopped automatic press ([ClickRecheck.afterMiss]). */
enum class MissStep { QUIET, REREAD, ASK }

/**
 * F49: one pending re-read after a pure up/down move: the popup ([words], its window and words) and the close button the
 * stopped press was for (its name and place inside the root, memory only), until [until] (uptime ms).
 */
class ShiftReread(val words: String, val label: String?, val button: ScreenBounds, val root: ScreenBounds, val until: Long)
