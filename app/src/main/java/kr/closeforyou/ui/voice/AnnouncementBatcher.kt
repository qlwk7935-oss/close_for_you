package kr.closeforyou.ui.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.closeforyou.R
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.ClickOutcome

/** What the person is told about one handled popup. Carries no screen text. */
enum class HandledOutcome { CLOSED, SKIPPED, NOT_CONFIRMED }

data class BatchSummary(val closed: Int, val skipped: Int, val notConfirmed: Int) {
    val total: Int get() = closed + skipped + notConfirmed
}

/**
 * C/F10: groups results that arrive within [windowMillis] into one sentence ("팝업 3개를 닫았어요")
 * so several quick closes do not talk over TalkBack. The window is fixed from the first result, so
 * a steady stream can never postpone the announcement for longer than [windowMillis].
 */
class AnnouncementBatcher(
    private val scope: CoroutineScope,
    private val onFlush: suspend (BatchSummary) -> Unit,
    private val windowMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    init {
        require(windowMillis > 0)
    }

    private val counts = IntArray(HandledOutcome.entries.size)
    private var pending: Job? = null

    @Synchronized
    fun add(outcome: HandledOutcome) {
        counts[outcome.ordinal]++
        if (pending == null) {
            pending = scope.launch {
                delay(windowMillis)
                take()?.let { onFlush(it) }
            }
        }
    }

    /** Drops anything not yet announced, e.g. when the service stops. */
    @Synchronized
    fun cancel() {
        pending?.cancel()
        pending = null
        counts.fill(0)
    }

    @Synchronized
    private fun take(): BatchSummary? {
        pending = null
        val summary = BatchSummary(
            closed = counts[HandledOutcome.CLOSED.ordinal],
            skipped = counts[HandledOutcome.SKIPPED.ordinal],
            notConfirmed = counts[HandledOutcome.NOT_CONFIRMED.ordinal],
        )
        counts.fill(0)
        return summary.takeIf { it.total > 0 }
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 2_000L

        /**
         * Maps an executed close/skip to what may be said. Blocked, stale or never-attempted
         * results return null: those are explained by their own announcement, not counted here.
         * MECHANICAL_SUCCESS only says the button was pressed, not that the choice was safe.
         */
        fun outcomeOf(result: ActionResult, choice: ChoiceKind): HandledOutcome? {
            if (PressResultSpeech.failureOf(result) == null && result.outcome == ClickOutcome.CLOSED_CONFIRMED) {
                return if (choice == ChoiceKind.SKIP_ONCE) HandledOutcome.SKIPPED else HandledOutcome.CLOSED
            }
            return when (result.status) {
                ActionStatus.MECHANICAL_SUCCESS, ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN -> HandledOutcome.NOT_CONFIRMED
                ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE -> null
            }
        }
    }
}

/**
 * Builds the spoken sentence; [text] resolves a string resource with one count argument.
 * [detailed] (안내 길이 "자세히") adds the total only when two or more kinds are mixed — a count C
 * actually has, nothing inferred.
 */
object BatchSentence {
    fun build(
        summary: BatchSummary,
        detailed: Boolean = false,
        text: (resId: Int, count: Int) -> String,
    ): String = buildList {
        if (summary.closed > 0) add(text(R.string.ui_announce_batch_closed, summary.closed))
        if (summary.skipped > 0) add(text(R.string.ui_announce_batch_skipped, summary.skipped))
        if (summary.notConfirmed > 0) {
            add(text(R.string.ui_announce_batch_not_confirmed, summary.notConfirmed))
        }
        if (detailed && size > 1) add(text(R.string.ui_announce_batch_total, summary.total))
    }.joinToString(" ")
}
