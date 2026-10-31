package kr.closeforyou.simpleclose

import kotlinx.coroutines.delay

/** The service's event scheduling boundary, shared with JVM regressions. No Android event is kept.
 * appInScope is a cheap scheduling filter; full authorization belongs to the collector's worker.
 * The engine job, prompt and settings are read again, not cached across a service connection.
 *
 * C (integration, F5/F10): [onTrigger] is told which kind of read is about to run (debug log
 * `DETECTED_BY_*`), [onSearchEnded] when a search ended without finding a popup (resets the
 * latency clock), and [onTalkBackFocus] starts an immediate read when TalkBack focuses something in
 * a helped app. None of them widens what may be collected: every read still goes through [appInScope]
 * here and the collector's full check. */
internal class SimpleInspectionEvents(
    private val jobs: () -> EngineJobs,
    private val promptShowing: () -> Boolean,
    private val lastInspection: () -> Long,
    private val now: () -> Long,
    private val appInScope: (String?) -> Boolean,
    private val onAccepted: () -> Unit,
    private val inspect: suspend (String) -> Boolean,
    private val log: SimpleTraceSink,
    private val onTrigger: (DetectTrigger) -> Unit = {},
    private val onSearchEnded: () -> Unit = {},
) {
    private var lastFocusRead = Long.MIN_VALUE

    fun onEvent(eventPackage: String?, contentChanged: Boolean) {
        val activeJobs = jobs()
        if (activeJobs.busy) return
        if (promptShowing() && now() - lastInspection() < SimpleCloseEngineFeature.PROMPT_RECHECK_MILLIS) return
        if (contentChanged && now() - lastInspection() < SimpleCloseEngineFeature.CONTENT_CHANGE_READ_MILLIS) {
            log.trace(SimpleTrace.CONTENT_READ_THROTTLED)
            return
        }
        if (!appInScope(eventPackage)) {
            log.trace(SimpleTrace.SKIPPED_NOT_ALLOWED_APP)
            return
        }
        val expectedPackage = eventPackage ?: return
        onAccepted()
        if (contentChanged) {
            activeJobs.scheduleInspection {
                delay(SimpleCloseEngineFeature.DEBOUNCE_MILLIS)
                if (!activeJobs.busy && !promptShowing()) {
                    onTrigger(DetectTrigger.CONTENT_EVENT)
                    if (!inspect(expectedPackage)) onSearchEnded()
                }
            }
            return
        }
        activeJobs.scheduleInspection {
            delay(SimpleCloseEngineFeature.DEBOUNCE_MILLIS)
            for (wait in listOf(0L) + SimpleCloseEngineFeature.RECHECK_DELAYS_MILLIS) {
                if (wait > 0) {
                    log.trace(SimpleTrace.RECHECK_SCHEDULED)
                    delay(wait)
                }
                if (activeJobs.busy || promptShowing()) return@scheduleInspection
                onTrigger(if (wait == 0L) DetectTrigger.WINDOW_EVENT else DetectTrigger.RECHECK)
                if (inspect(expectedPackage)) return@scheduleInspection
            }
            onSearchEnded()
        }
    }

    /**
     * F10 (LG 10/31: TalkBack read the 11번가 sheet twice before our window): TalkBack just focused
     * something in [eventPackage], often the popup it is about to read. One read at once, without the
     * debounce, at most every [SimpleCloseEngineFeature.FOCUS_EVENT_READ_MILLIS]; never while a press
     * runs, the prompt is up or another read/re-check chain is in flight (that one catches it too).
     * Same scope check as [onEvent].
     */
    fun onTalkBackFocus(eventPackage: String?) {
        val activeJobs = jobs()
        if (activeJobs.busy || promptShowing() || activeJobs.inspecting) return
        val at = now()
        if (lastFocusRead != Long.MIN_VALUE && at - lastFocusRead < SimpleCloseEngineFeature.FOCUS_EVENT_READ_MILLIS) return
        if (!appInScope(eventPackage)) {
            log.trace(SimpleTrace.SKIPPED_NOT_ALLOWED_APP)
            return
        }
        val expectedPackage = eventPackage ?: return
        lastFocusRead = at
        onAccepted()
        activeJobs.scheduleInspection {
            if (activeJobs.busy || promptShowing()) return@scheduleInspection
            onTrigger(DetectTrigger.TALKBACK_FOCUS)
            if (!inspect(expectedPackage)) onSearchEnded()
        }
    }
}
