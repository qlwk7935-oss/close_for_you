package kr.closeforyou.simpleclose

import kotlinx.coroutines.delay

internal enum class SimpleInspectionEvent { WINDOW, CONTENT, TALKBACK_FOCUS }

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
) {
    private var lastFocusEventReadAt = 0L

    /** F13: when the first read now waiting was first armed ([NOT_PENDING] = none), for which app. */
    private var pendingSince = NOT_PENDING
    private var pendingPackage: String? = null

    /** F14: the app a read is running for now (null = none), and the strongest event folded in meanwhile. */
    private var inFlightPackage: String? = null
    private var foldedIn: SimpleInspectionEvent? = null

    fun resetConnection() {
        lastFocusEventReadAt = 0L
        pendingSince = NOT_PENDING
        inFlightPackage = null
        foldedIn = null
    }

    fun onEvent(eventPackage: String?, kind: SimpleInspectionEvent) {
        val activeJobs = jobs()
        if (activeJobs.busy) return
        if (promptShowing() && now() - lastInspection() < SimpleCloseEngineFeature.PROMPT_RECHECK_MILLIS) return
        if (kind == SimpleInspectionEvent.CONTENT && now() - lastInspection() < SimpleCloseEngineFeature.CONTENT_CHANGE_READ_MILLIS) {
            log.trace(SimpleTrace.CONTENT_READ_THROTTLED)
            return
        }
        // F10: an accessibility focus event is not a window transition. Never replace the
        // pending first read, worker read, or recheck chain just because TalkBack moved focus.
        if (kind == SimpleInspectionEvent.TALKBACK_FOCUS &&
            (promptShowing() || activeJobs.inspecting ||
                now() - lastFocusEventReadAt < SimpleCloseEngineFeature.FOCUS_EVENT_READ_MILLIS)) return
        if (!appInScope(eventPackage)) {
            log.trace(SimpleTrace.SKIPPED_NOT_ALLOWED_APP)
            return
        }
        val expectedPackage = eventPackage ?: return
        onAccepted()
        if (kind == SimpleInspectionEvent.TALKBACK_FOCUS) {
            lastFocusEventReadAt = now()
            pendingSince = NOT_PENDING
            activeJobs.scheduleInspection {
                // Immediate scheduling, no debounce. Full authorization still runs in the worker.
                if (!activeJobs.busy && !promptShowing() && !read(expectedPackage, DetectTrigger.TALKBACK_FOCUS)) {
                    onSearchExhausted()
                }
            }
            return
        }
        val at = now()
        // F14: a read running for this app is never cut short; it is followed up when it finds nothing.
        if (activeJobs.inspecting && inFlightPackage == expectedPackage) {
            if (foldedIn != SimpleInspectionEvent.WINDOW) foldedIn = kind
            log.trace(SimpleTrace.EVENT_COALESCED)
            return
        }
        val pending = activeJobs.inspecting && pendingSince != NOT_PENDING && pendingPackage == expectedPackage
        // F13: the read already waiting will see this change too.
        if (pending && kind == SimpleInspectionEvent.CONTENT) {
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
            var chain = kind != SimpleInspectionEvent.CONTENT
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
                    delay(SimpleCloseEngineFeature.DEBOUNCE_MILLIS)
                }
                if (!chain) {
                    onSearchExhausted()
                    return@scheduleInspection
                }
            }
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
            onSearchExhausted()
        }
    }

    /** One read, marked in flight for [expectedPackage] so events for the same app do not cancel it (F14). */
    private suspend fun read(expectedPackage: String, trigger: DetectTrigger): Boolean {
        inFlightPackage = expectedPackage
        foldedIn = null
        try {
            return inspect(expectedPackage, trigger)
        } finally {
            if (inFlightPackage == expectedPackage) inFlightPackage = null
        }
    }

    private companion object {
        const val NOT_PENDING = Long.MIN_VALUE
    }
}
