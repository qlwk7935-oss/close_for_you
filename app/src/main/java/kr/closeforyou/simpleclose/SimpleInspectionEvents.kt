package kr.closeforyou.simpleclose

import kotlinx.coroutines.delay

internal enum class SimpleInspectionEvent { WINDOW, CONTENT, TALKBACK_FOCUS }

/** The service's event scheduling boundary, shared with JVM regressions. No Android event is kept.
 * appInScope is a cheap scheduling filter; full authorization belongs to the collector's worker.
 * The engine job, prompt and settings are read again, not cached across a service connection. */
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

    fun resetConnection() { lastFocusEventReadAt = 0L }

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
            activeJobs.scheduleInspection {
                // Immediate scheduling, no debounce. Full authorization still runs in the worker.
                if (!activeJobs.busy && !promptShowing() && !inspect(expectedPackage, DetectTrigger.TALKBACK_FOCUS)) {
                    onSearchExhausted()
                }
            }
            return
        }
        if (kind == SimpleInspectionEvent.CONTENT) {
            activeJobs.scheduleInspection {
                delay(SimpleCloseEngineFeature.DEBOUNCE_MILLIS)
                if (!activeJobs.busy && !promptShowing() && !inspect(expectedPackage, DetectTrigger.CONTENT_EVENT)) {
                    onSearchExhausted()
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
                if (inspect(expectedPackage, if (wait == 0L) DetectTrigger.WINDOW_EVENT else DetectTrigger.RECHECK)) return@scheduleInspection
            }
            onSearchExhausted()
        }
    }
}
