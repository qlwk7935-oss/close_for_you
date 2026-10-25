package kr.closeforyou.ui.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.closeforyou.R
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ChoiceKind

/** What the person is told about one handled popup. Carries no screen text. */
enum class HandledOutcome { CLOSED, SKIPPED, NOT_CONFIRMED }

/** Contract v2: what kind of popup an automatic close closed ("광고 2개, 쿠폰 1개를 닫았어요"). */
enum class ClosedKind { AD, COUPON, NOTICE, OTHER }

/**
 * [closedKinds] counts only closes added with a kind (automatic closes A confirmed closed).
 * [onlyTitle] is the popup title when exactly one popup was closed and its title was known:
 * screen text, kept in memory for the window only, never logged.
 */
data class BatchSummary(
    val closed: Int,
    val skipped: Int,
    val notConfirmed: Int,
    val closedKinds: Map<ClosedKind, Int> = emptyMap(),
    val onlyTitle: String? = null,
) {
    val total: Int get() = closed + skipped + notConfirmed

    override fun toString(): String =
        "BatchSummary(closed=$closed, skipped=$skipped, notConfirmed=$notConfirmed, kinds=$closedKinds, redacted)"
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
    private val kindCounts = IntArray(ClosedKind.entries.size)
    private var firstTitle: String? = null
    private var pending: Job? = null

    /** [kind] and [title] are used only for [HandledOutcome.CLOSED]. */
    @Synchronized
    fun add(outcome: HandledOutcome, kind: ClosedKind? = null, title: String? = null) {
        counts[outcome.ordinal]++
        if (outcome == HandledOutcome.CLOSED) {
            if (kind != null) kindCounts[kind.ordinal]++
            if (counts[outcome.ordinal] == 1) firstTitle = title
        }
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
        clearCounts()
    }

    private fun clearCounts() {
        counts.fill(0)
        kindCounts.fill(0)
        firstTitle = null
    }

    @Synchronized
    private fun take(): BatchSummary? {
        pending = null
        val closed = counts[HandledOutcome.CLOSED.ordinal]
        val summary = BatchSummary(
            closed = closed,
            skipped = counts[HandledOutcome.SKIPPED.ordinal],
            notConfirmed = counts[HandledOutcome.NOT_CONFIRMED.ordinal],
            closedKinds = ClosedKind.entries.filter { kindCounts[it.ordinal] > 0 }.associateWith { kindCounts[it.ordinal] },
            onlyTitle = firstTitle.takeIf { closed == 1 },
        )
        clearCounts()
        return summary.takeIf { it.total > 0 }
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS = 2_000L

        /**
         * Maps an executed close/skip to what may be said. Blocked, stale or never-attempted
         * results return null: those are explained by their own announcement, not counted here.
         * Closed / skipped only when [PressSuccess.isConfirmed]; any other attempted press is
         * "확인하지 못했어요". Even a confirmed close only says the popup went away, not that the
         * choice was safe.
         */
        fun outcomeOf(result: ActionResult, choice: ChoiceKind): HandledOutcome? = when {
            PressSuccess.isConfirmed(result) ->
                if (choice == ChoiceKind.SKIP_ONCE) HandledOutcome.SKIPPED else HandledOutcome.CLOSED
            else -> when (result.status) {
                ActionStatus.MECHANICAL_SUCCESS, ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN ->
                    HandledOutcome.NOT_CONFIRMED
                ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE -> null
            }
        }
    }
}

/**
 * Builds the spoken sentence; [text] resolves a string resource with one count argument.
 * [detailed] (안내 길이 "자세히") adds the total only when two or more kinds are mixed — a count C
 * actually has, nothing inferred. [closedLine] replaces the "팝업 N개를 닫았어요" part (contract v2,
 * `AutoDecisionSpeech.batchSentence` says the kinds instead).
 */
object BatchSentence {
    fun build(
        summary: BatchSummary,
        detailed: Boolean = false,
        closedLine: String? = null,
        text: (resId: Int, count: Int) -> String,
    ): String = buildList {
        if (summary.closed > 0) add(closedLine ?: text(R.string.ui_announce_batch_closed, summary.closed))
        if (summary.skipped > 0) add(text(R.string.ui_announce_batch_skipped, summary.skipped))
        if (summary.notConfirmed > 0) {
            add(text(R.string.ui_announce_batch_not_confirmed, summary.notConfirmed))
        }
        if (detailed && size > 1) add(text(R.string.ui_announce_batch_total, summary.total))
    }.joinToString(" ")
}
