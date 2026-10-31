package kr.closeforyou.simpleclose

import kotlinx.coroutines.delay

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
    private val inspect: suspend (String) -> Boolean,
    private val log: SimpleTraceSink,
) {
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
                if (!activeJobs.busy && !promptShowing()) inspect(expectedPackage)
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
                if (inspect(expectedPackage)) return@scheduleInspection
            }
        }
    }
}
