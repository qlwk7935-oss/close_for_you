package kr.closeforyou.accessibility.integration

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.closeforyou.accessibility.action.F9Block
import kr.closeforyou.core.model.*

/** Explicit UI-only, process-memory, one-trial switch. No intent extra, setting, provider or
 * production feature flag can arm it. Scope and current ticket are reread at the final F9 gate. */
internal object DebugDelayedAutoClose {
    private class Ticket(val sequence: Long)
    @Volatile private var ticket: Ticket? = null
    private var serial = 0L
    private var captureSerial = 0L
    private val revision = MutableStateFlow(0L)
    val userChanges = revision.asStateFlow()
    private val observed = MutableStateFlow(DebugAutoObservation())
    val observation = observed.asStateFlow()
    fun ticket(): Any? = ticket
    fun permits(pkg: String, expected: Any? = ticket): Boolean = pkg == "kr.closeforyou.popuptest" && expected != null && ticket === expected
    fun arm() { serial++; ticket = Ticket(serial); observed.value = DebugAutoObservation(); report(ticket, DelayedAutoPhase.ARMED); revision.value++ }
    fun userOff() { report(ticket, DelayedAutoPhase.OFF); ticket = null; revision.value++ }
    fun finish(expected: Any?) { if (ticket === expected) ticket = null }
    fun trace(): DelayedAutoTrace? {
        val run = ticket ?: return null
        val capture = ++captureSerial
        return DelayedAutoTrace { point, event, read, page, block, next, reason ->
            Log.i("A_DelayedAuto", "A_DELAYED_TRACE;RUN=${run.sequence};ATTEMPT=$capture;PHASE=${point.name}" +
                ";EVENT=${event?.name ?: "NONE"};READ=${read?.name ?: "NONE"};PAGE=${page?.name ?: "NONE"}" +
                ";BLOCK=${block?.name ?: "NONE"};NEXT=${next?.name ?: "NONE"};REASON=${reason?.name ?: "NONE"}")
        }
    }
    fun report(expected: Any?, phase: DelayedAutoPhase, next: NextStep? = null, reason: SafetyReason? = null,
        block: F9Block? = null, attempted: Boolean = false, outcome: ClickOutcome? = null) {
        val id = expected as? Ticket ?: return
        if (id === ticket) observed.value = observed.value.copy(seen = observed.value.seen + phase,
            attempts = observed.value.attempts + if (attempted) 1 else 0, outcome = outcome ?: observed.value.outcome,
            pages = observed.value.pages + if (phase == DelayedAutoPhase.PAGE_VERIFIED) 1 else 0)
        kr.closeforyou.accessibility.diagnosticWrite {
            Log.i("A_DelayedAuto", "A_DELAYED_AUTO;RUN=${id.sequence};PHASE=${phase.name};NEXT=${next?.name ?: "NONE"}" +
                ";REASON=${reason?.name ?: "NONE"};BLOCK=${block?.name ?: "NONE"};ATTEMPTED=${if (attempted) "YES" else "NO"};OUTCOME=${outcome?.name ?: "NONE"}")
        }
    }
}
internal data class DebugAutoObservation(val seen: Set<DelayedAutoPhase> = emptySet(), val attempts: Int = 0,
    val outcome: ClickOutcome? = null, val pages: Int = 0)
