package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.closeforyou.accessibility.action.F9Block
import kr.closeforyou.core.model.*

/** No arm API, Activity, permission override or logger in Release. */
internal object DebugDelayedAutoClose {
    val userChanges = MutableStateFlow(0L).asStateFlow()
    fun ticket(): Any? = null
    fun permits(pkg: String, expected: Any? = null): Boolean = false
    fun finish(expected: Any?) = Unit
    fun report(expected: Any?, phase: DelayedAutoPhase, next: NextStep? = null, reason: SafetyReason? = null,
        block: F9Block? = null, attempted: Boolean = false, outcome: ClickOutcome? = null) = Unit
}
