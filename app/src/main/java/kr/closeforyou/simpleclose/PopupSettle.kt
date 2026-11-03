package kr.closeforyou.simpleclose

import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.NodeSnapshot
import kr.closeforyou.core.model.ScreenBounds

/**
 * F50 (메인 세션 에뮬레이터 Pixel_8 API 37, c0dbf75, 시험 앱 P01 ×6: closed 2, asked 3 with `POPUP_TEXTS=3 CANDIDATE_NONE`
 * (4 and a close when it closed), quiet 1 with `ROOT_MOVED dl=-42 dt=821`): the Dialog was read while its window was
 * still opening (moving in, its close not in the tree yet), and judged on that. A popup root is judged only once two
 * reads in a row found it with the same bounds and the same number of nodes; until then it is read again every
 * [READ_AGAIN_MILLIS]. At most [MAX_MILLIS] after it was first seen or [MAX_READS] reads: judged as it is then
 * ([SettleVerdict.LIMIT], the old way). Nothing is pressed or said meanwhile. Pure; JVM tested.
 */
object SettleRules {
    /**
     * Android's own short animation time is 200 ms (`config_shortAnimTime`), a Dialog's window comes in about that
     * fast: a read every 150 ms sees it move at least once and then stand still.
     */
    const val READ_AGAIN_MILLIS = 150L

    /** `config_mediumAnimTime` is 400 ms, `config_longAnimTime` 500 ms: twice the longest, then judged anyway. */
    const val MAX_MILLIS = 1_000L
    const val MAX_READS = 5

    /** A popup not seen for this long is a new sighting (it is waited for again, once). */
    const val FORGET_MILLIS = 2_000L

    /** The same popup root across reads: its window, kind and path, never its bounds (they are what may move). */
    fun key(windowId: Int?, kind: PopupRootKind, path: String): String = "$windowId:$kind:$path"

    /** Nodes of the read inside [root]: a close still being added changes it while the bounds stand still. */
    fun members(root: PopupRoot, nodes: List<NodeSnapshot>): Int = nodes.count(root::contains)

    fun next(prev: SettleState?, key: String, bounds: ScreenBounds, members: Int, now: Long): Pair<SettleVerdict, SettleState> {
        if (prev == null || prev.key != key || now - prev.lastAt > FORGET_MILLIS || now < prev.lastAt) {
            return SettleVerdict.WAIT to SettleState(key, bounds, members, firstAt = now, lastAt = now, reads = 1, settled = false)
        }
        if (prev.bounds == bounds && prev.members == members) {
            return (if (prev.settled) SettleVerdict.STILL else SettleVerdict.STABLE) to prev.copy(lastAt = now, settled = true)
        }
        val moved = prev.copy(bounds = bounds, members = members, lastAt = now, reads = prev.reads + 1)
        return if (now - prev.firstAt >= MAX_MILLIS || prev.reads >= MAX_READS) {
            SettleVerdict.LIMIT to moved.copy(settled = true)
        } else {
            SettleVerdict.WAIT to moved.copy(settled = false)
        }
    }
}

/**
 * F50: [WAIT] read again first; [STABLE] just stood still (judged now, `SETTLE_STABLE`); [STILL] stood still before too
 * (judged as always, nothing logged); [LIMIT] still moving when the time was up (judged now, `SETTLE_LIMIT`).
 */
enum class SettleVerdict { WAIT, STABLE, STILL, LIMIT }

/** F50: the last read of one popup root: memory only, no text. */
data class SettleState(
    val key: String,
    val bounds: ScreenBounds,
    val members: Int,
    val firstAt: Long,
    val lastAt: Long,
    val reads: Int,
    val settled: Boolean,
)

/**
 * F50 (same run, the quiet one: `RECHECK_POPUP_GONE` while `dumpsys window` still listed the Dialog's window): the popup
 * was looked for where it had been (its first read was mid-animation, 821 px lower), so the settled Dialog did not
 * overlap that place. Whether it is still there is now told by its window: a popup tied to its own window
 * ([PopupRoot.windowId]) is there while that window is listed and its app is in front. Pure.
 */
object StillShownRules {
    /** [front] the app in front (null = none), [listed] the popup's window still listed (null = unknown). */
    fun byWindow(front: String?, expected: String, listed: Boolean?): Boolean? = when {
        front == null -> null
        front != expected -> false
        else -> listed
    }
}

/**
 * F50 (메인 세션 에뮬레이터, P34 ×8: 3 runs with no `DETECTED_*` in 13 s, 9 reads): one debug line per read that found no
 * popup root, when it differs from the last one. Codes and counts only (no text, no id, no package):
 * how the read went, the probe's outcome, nodes read / on screen, clickables, big clickables with no text (a dim or a
 * layer), named closes, whether whole reads are off after a capped one and for how long still.
 */
object NoRootLine {
    fun line(mode: TreeReadMode, probe: ProbeOutcome, nodes: List<NodeSnapshot>, screen: ScreenBounds, wholeAborted: Boolean, wholeOffMillis: Long?): String {
        val shown = nodes.filter { PopupRootFinder.isVisible(it, screen) }
        val screenArea = screen.width.toLong() * screen.height
        val big = shown.count { n ->
            n.clickable && n.text.isNullOrBlank() && screenArea > 0 && n.bounds.width.toLong() * n.bounds.height >= screenArea * BIG_RATIO
        }
        val closes = shown.count { n -> n.clickable && SimpleLabelRules.roleOf(CloseButtonFinder.nameOf(n, shown)) == ButtonRole.CLOSE }
        val off = wholeOffMillis?.let { " wholeOffMs=$it" } ?: ""
        return "NO_ROOT mode=${mode.name} probe=${probe.name} nodes=${nodes.size} shown=${shown.size} clickable=${shown.count { it.clickable }} " +
            "bigBlank=$big namedClose=$closes aborted=$wholeAborted$off"
    }

    private const val BIG_RATIO = 0.6
}
