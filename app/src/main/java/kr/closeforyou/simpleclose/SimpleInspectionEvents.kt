package kr.closeforyou.simpleclose

import kotlinx.coroutines.delay

/**
 * [CONTENT_LIKELY_POPUP] (F26): a repaint that likely added a popup (a large non-list container's subtree changed,
 * as when an app adds a dim overlay): read like a repaint, but the read budget starts again (LG 11/1 올리브영 앱:
 * the popup waited 3.2 s for a budgeted read).
 */
internal enum class SimpleInspectionEvent { WINDOW, CONTENT, TALKBACK_FOCUS, CONTENT_LIKELY_POPUP;
    val repaint: Boolean get() = this == CONTENT || this == CONTENT_LIKELY_POPUP
}

/** The service's event scheduling boundary, shared with JVM regressions. No Android event is kept.
 * appInScope is a cheap scheduling filter; full authorization belongs to the collector's worker.
 * The engine job, prompt and settings are read again, not cached across a service connection.
 *
 * F13 (LG 11/1 11번가: DETECT_LATENCY 6.4 s, no read at all in between): Chrome repaints every
 * ~100 ms and each content event cancelled and re-armed the 200 ms debounce, so the first read only
 * ran once the page went quiet. A first read already waiting for the same app is now kept: a content
 * event never replaces it, and a window event re-arms it only up to
 * [SimpleCloseEngineFeature.FIRST_READ_MAX_WAIT_MILLIS] after it was first armed. Only waits change;
 * every read still passes [appInScope] here and the worker's full check.
 *
 * F14 (LG 11/1 01:37: one read of 11번가 took 1.75 s, longer than the 1.5 s content throttle): a content
 * event 1.5 s after a read started cancelled that read while its walk was still running, and the next
 * read started next to it. A read in flight for the same app is now never cancelled by an event: the
 * event is folded in ([EVENT_COALESCED]) and, when the read found nothing, one more read follows (a
 * window event during it turns that into the re-check chain). Another app's event still replaces it. */
internal class SimpleInspectionEvents(
    private val jobs: () -> EngineJobs,
    private val promptShowing: () -> Boolean,
    private val lastInspection: () -> Long,
    private val now: () -> Long,
    private val appInScope: (String?) -> Boolean,
    private val onAccepted: () -> Unit,
    private val inspect: suspend (String, DetectTrigger) -> Boolean,
    private val log: SimpleTraceSink,
    private val onSearchExhausted: () -> Unit = {},
    /** F22: the code logged when [appInScope] says no (the reason, never the package). */
    private val scopeMissTrace: (String?) -> SimpleTrace = { SimpleTrace.SKIPPED_NOT_ALLOWED_APP },
) {
    private var lastFocusEventReadAt = 0L

    /**
     * F24 (LG 11/1 G마켓 웹: 25 reads in 25 s, a probe read every 0.2 s on a page that never had a popup): searches
     * in a row that found nothing ([ReadBudget]). Repaint reads wait longer each time; a window change, a new
     * window, a TalkBack focus move or a found popup start again from 0. Repaints alone (carousels) never do.
     */
    private var quiet = 0

    /** F26: what the last read cost (ms); a repaint gap is never shorter than [ReadBudget.COST_FACTOR] times it. */
    private var lastReadCost = 0L

    /** F24: the app whose window re-check chain is running now (null = none). */
    private var chainPackage: String? = null

    /** F13: when the first read now waiting was first armed ([NOT_PENDING] = none), for which app. */
    private var pendingSince = NOT_PENDING
    private var pendingPackage: String? = null

    /** F14: the app a read is running for now (null = none), and the strongest event folded in meanwhile. */
    private var inFlightPackage: String? = null
    private var foldedIn: SimpleInspectionEvent? = null

    /** F24: after a close the page settles: repaint reads go straight into the budget ([ReadBudget.AFTER_CLOSE]). */
    fun backOff(level: Int = ReadBudget.AFTER_CLOSE) {
        quiet = maxOf(quiet, level)
    }

    fun resetConnection() {
        quiet = 0
        chainPackage = null
        lastFocusEventReadAt = 0L
        pendingSince = NOT_PENDING
        inFlightPackage = null
        foldedIn = null
    }

    fun onEvent(eventPackage: String?, kind: SimpleInspectionEvent) {
        val activeJobs = jobs()
        if (activeJobs.busy) return
        if (promptShowing() && now() - lastInspection() < SimpleCloseEngineFeature.PROMPT_RECHECK_MILLIS) return
        if (kind == SimpleInspectionEvent.CONTENT && now() - lastInspection() < ReadBudget.contentGap(quiet, lastReadCost)) {
            log.trace(SimpleTrace.CONTENT_READ_THROTTLED)
            return
        }
        // F10: an accessibility focus event is not a window transition. Never replace the
        // pending first read, worker read, or recheck chain just because TalkBack moved focus.
        if (kind == SimpleInspectionEvent.TALKBACK_FOCUS &&
            (promptShowing() || activeJobs.inspecting ||
                now() - lastFocusEventReadAt < SimpleCloseEngineFeature.FOCUS_EVENT_READ_MILLIS)) return
        if (!appInScope(eventPackage)) {
            log.trace(scopeMissTrace(eventPackage))
            return
        }
        val expectedPackage = eventPackage ?: return
        // F24: a popup is likely new after one of these; the budget starts again.
        if (kind != SimpleInspectionEvent.CONTENT) quiet = 0
        onAccepted()
        if (kind == SimpleInspectionEvent.TALKBACK_FOCUS) {
            lastFocusEventReadAt = now()
            pendingSince = NOT_PENDING
            activeJobs.scheduleInspection {
                // Immediate scheduling, no debounce. Full authorization still runs in the worker.
                if (!activeJobs.busy && !promptShowing() && !read(expectedPackage, DetectTrigger.TALKBACK_FOCUS)) {
                    exhausted()
                }
            }
            return
        }
        val at = now()
        // F24: a window change's re-check chain is running for this app: its next read covers a repaint.
        if (kind.repaint && chainPackage == expectedPackage && activeJobs.inspecting) {
            log.trace(SimpleTrace.EVENT_COALESCED)
            return
        }
        // F14: a read running for this app is never cut short; it is followed up when it finds nothing.
        if (activeJobs.inspecting && inFlightPackage == expectedPackage) {
            if (foldedIn != SimpleInspectionEvent.WINDOW) foldedIn = kind
            log.trace(SimpleTrace.EVENT_COALESCED)
            return
        }
        val pending = activeJobs.inspecting && pendingSince != NOT_PENDING && pendingPackage == expectedPackage
        // F13: the read already waiting will see this change too.
        if (pending && kind.repaint) {
            log.trace(SimpleTrace.EVENT_COALESCED)
            return
        }
        val since = if (pending) pendingSince else at
        val firstWait = minOf(SimpleCloseEngineFeature.DEBOUNCE_MILLIS, since + SimpleCloseEngineFeature.FIRST_READ_MAX_WAIT_MILLIS - at).coerceAtLeast(0L)
        pendingSince = since
        pendingPackage = expectedPackage
        activeJobs.scheduleInspection {
            delay(firstWait)
            pendingSince = NOT_PENDING
            var chain = !kind.repaint
            if (!chain) {
                // One read per repaint burst; repaints during it bring one more, a window event the chain.
                while (true) {
                    if (activeJobs.busy || promptShowing()) return@scheduleInspection
                    if (read(expectedPackage, DetectTrigger.CONTENT_EVENT)) return@scheduleInspection
                    val more = foldedIn.also { foldedIn = null } ?: break
                    if (more == SimpleInspectionEvent.WINDOW) {
                        chain = true
                        break
                    }
                    // F24 (LG 11/1 G마켓: a carousel repaints during every read, so this loop read every 0.2 s for
                    // good): each further repaint read waits the budget, longer every time nothing is found.
                    quiet = minOf(quiet + 1, ReadBudget.MAX_LEVEL)
                    delay(ReadBudget.contentGap(quiet, lastReadCost))
                }
                if (!chain) {
                    exhausted()
                    return@scheduleInspection
                }
            }
            chainPackage = expectedPackage
            try {
                for (wait in listOf(0L) + SimpleCloseEngineFeature.RECHECK_DELAYS_MILLIS) {
                    if (wait > 0) {
                        log.trace(SimpleTrace.RECHECK_SCHEDULED)
                        delay(wait)
                    }
                    if (activeJobs.busy || promptShowing()) return@scheduleInspection
                    if (read(expectedPackage, if (wait == 0L) DetectTrigger.WINDOW_EVENT else DetectTrigger.RECHECK)) return@scheduleInspection
                    // The chain's next read covers whatever came in during this one.
                    foldedIn = null
                }
            } finally {
                if (chainPackage == expectedPackage) chainPackage = null
            }
            exhausted()
        }
    }

    /** A search found nothing: the next repaint read waits longer (F24). */
    private fun exhausted() {
        quiet = minOf(quiet + 1, ReadBudget.MAX_LEVEL)
        onSearchExhausted()
    }

    /** One read, marked in flight for [expectedPackage] so events for the same app do not cancel it (F14). */
    private suspend fun read(expectedPackage: String, trigger: DetectTrigger): Boolean {
        inFlightPackage = expectedPackage
        foldedIn = null
        val start = now()
        try {
            return inspect(expectedPackage, trigger).also { found -> if (found) quiet = 0 }
        } finally {
            lastReadCost = (now() - start).coerceAtLeast(0)
            if (inFlightPackage == expectedPackage) inFlightPackage = null
        }
    }

    private companion object {
        const val NOT_PENDING = Long.MIN_VALUE
    }
}

/**
 * F24 read budget, pure. Repaint reads after [quiet] empty searches in a row wait [contentGap]: 0.2, 0.4, 0.8, 1.6,
 * then 3 s. At 3 s a probe read of about 0.3 s is 10% of the time. [plan]: whether a read walks the whole tree when
 * the probe finds nothing; a page whose whole read took over [SLOW_WHOLE_MILLIS] (예스24 3.6 s) walks it only on a
 * window change, and every [SLOW_WHOLE_EVERY]-th read instead of every [SimpleCloseEngineFeature.WHOLE_READ_EVERY]-th.
 */
object ReadBudget {
    const val MAX_LEVEL = 8
    const val AFTER_CLOSE = 3
    const val SLOW_WHOLE_MILLIS = 1_000L
    const val SLOW_WHOLE_EVERY = 9
    private const val FIRST_GAP_MILLIS = 200L
    /** F26 (LG 11/1 올리브영 앱: a popup waited 3.2 s for a budgeted read): 3 → 1 s. */
    const val MAX_GAP_MILLIS = 1_000L

    /**
     * F26: a repaint gap is at least this many times the last read's cost, so reading stays near 1/(1+factor) of the
     * time (a 0.3 s read waits 1.8 s: 14%; a 0.1 s read waits the 1 s cap: 9%).
     */
    const val COST_FACTOR = 6L

    fun contentGap(quiet: Int, lastCost: Long = 0L): Long {
        val budget = minOf(FIRST_GAP_MILLIS shl quiet.coerceIn(0, MAX_LEVEL), MAX_GAP_MILLIS)
        return if (quiet == 0) budget else maxOf(budget, minOf(lastCost * COST_FACTOR, COST_GAP_CAP_MILLIS))
    }

    /** F26: the cost stretch stops here (a 1.75 s whole read would otherwise wait 10 s); F24's old cap. */
    const val COST_GAP_CAP_MILLIS = 3_000L

    /**
     * F26: a repaint that likely added a popup: a subtree change of a large (60% of the screen) container that is
     * not a list, pager, scroll view or web view (those repaint on every scroll and carousel turn).
     */
    fun likelyPopup(subtreeChanged: Boolean, className: String?, areaRatio: Double): Boolean =
        subtreeChanged && areaRatio >= LIKELY_POPUP_AREA && className != null && NOT_POPUP_CONTAINERS.none { className.contains(it, ignoreCase = true) }

    private const val LIKELY_POPUP_AREA = 0.6
    private val NOT_POPUP_CONTAINERS = listOf("WebView", "RecyclerView", "ListView", "ScrollView", "ViewPager", "GridView", "AbsListView")

    /**
     * [sinceAbortedWhole] (F25): ms since a whole read of this page stopped at [WholeReadCap] (null = none lately);
     * then a whole read again only after [WholeReadCap.ABORTED_RETRY_MILLIS], whatever the trigger.
     */
    fun wholeRead(trigger: DetectTrigger, readIndex: Int, slowPage: Boolean, videoWeb: Boolean, sinceAbortedWhole: Long? = null): Boolean {
        if (sinceAbortedWhole != null && sinceAbortedWhole < WholeReadCap.ABORTED_RETRY_MILLIS) return false
        if (videoWeb || trigger == DetectTrigger.WINDOW_EVENT) return true
        if (slowPage) return readIndex % SLOW_WHOLE_EVERY == SLOW_WHOLE_EVERY - 1
        return trigger == DetectTrigger.TALKBACK_FOCUS ||
            readIndex % SimpleCloseEngineFeature.WHOLE_READ_EVERY == SimpleCloseEngineFeature.WHOLE_READ_EVERY - 1
    }
}
