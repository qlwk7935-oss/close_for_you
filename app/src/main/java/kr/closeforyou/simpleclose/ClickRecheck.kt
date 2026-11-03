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
     * F48 (C decision): what the person hears when an automatic press stopped at the re-check.
     * - The app is still in front and the popup root is still the same, only the button did not match
     *   ([RecheckGroup.TARGET]): the popup is still there and nothing was pressed, so the failure is said the way a
     *   failed automatic close is (F20, "대신 닫지 못했어요" with the prompt). Not for a bottom banner (F37: quiet).
     * - Another app in front, or the popup root gone or changed: quiet, as before (the screen moved on).
     */
    fun saysFailure(miss: RecheckMiss, bottomBanner: Boolean): Boolean = miss.group == RecheckGroup.TARGET && !bottomBanner

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
