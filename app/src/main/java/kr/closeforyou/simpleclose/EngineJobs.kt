package kr.closeforyou.simpleclose

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The engine's two kinds of work, kept apart so a window event can never cut a press short:
 * - the inspection (read the tree, decide): a new event replaces the one in flight;
 * - the action (re-check, click, confirm, speak): runs to its end whatever events arrive, and only
 *   one at a time. LG 10/31 옥션: the auto close ran inside the inspection job, and an unrelated
 *   package's event 200 ms after CLICKED cancelled its confirmation poll, so no CLOSED_CONFIRMED and
 *   no "광고를 닫았어요".
 * Pure Kotlin over a [CoroutineScope]; the service owns the scope.
 */
class EngineJobs(private val scope: CoroutineScope) {
    private var inspection: Job? = null
    private var action: Job? = null

    /** An action is running: events are ignored and no inspection starts. */
    val busy: Boolean get() = action?.isActive == true

    /** An inspection (a read, or a re-check chain) is in flight. */
    val inspecting: Boolean get() = inspection?.isActive == true

    /** Replaces the inspection in flight. Does nothing while an action runs. */
    fun scheduleInspection(block: suspend () -> Unit) {
        if (busy) return
        inspection?.cancel()
        inspection = scope.launch { block() }
    }

    /** Starts the action unless one is running; it is never cancelled by [scheduleInspection]. */
    fun runAction(block: suspend () -> Unit): Boolean {
        if (busy) return false
        inspection?.cancel()
        inspection = null
        action = scope.launch { block() }
        return true
    }

    /** F28: drops the inspection in flight (its analysis is of a screen that is gone); an action runs on. */
    fun cancelInspection() {
        inspection?.cancel()
        inspection = null
    }

    fun cancelAll() {
        inspection?.cancel()
        inspection = null
        action?.cancel()
        action = null
    }
}
