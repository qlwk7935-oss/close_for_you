package kr.closeforyou.decision

import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.SnapshotToken
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What A's result means for a press, as agreed for F12 (2026-10-21). */
enum class PressOutcome {
    /** A pressed and observed the expected mechanical success. */
    SUCCEEDED,
    /** A pressed (or may have pressed) and it did not clearly work: ATTEMPT_FAILED, OUTCOME_UNKNOWN. */
    FAILED,
    /** Nothing was pressed: NOT_EXECUTED, BLOCKED, STALE. Neither a success nor a failure. */
    NOT_ATTEMPTED,
}

/**
 * B: after a press that came from a remembered rule, tells D to stop using that rule when the
 * press failed. A passes D's failure recorder in as [recordFailure] (A's decision, 2026-10-21),
 * so B depends only on the shared contract, never on D's repository class.
 *
 * Call it only for presses made because of the rule: a one-time press the person chose on the
 * prompt never touches a rule. A failure is recorded whatever screen the result names: recording
 * only stops the rule from being used again, which is the safe direction.
 *
 * **One failure, one record.** The contract has no attempt id, so an attempt is identified by the
 * rule and the screen token of A's result: A's gate makes at most one attempt per token and never
 * retries. Within one instance, a repeated or concurrent delivery of the same failed attempt is
 * recorded once. If [recordFailure] throws (or is cancelled) the attempt is not marked, so a later
 * delivery records it: at least once, which keeps the rule stopped. Not covered, and therefore
 * preconditions for wiring F12 (agreed in the #23 review): A keeps a single instance for the
 * service's lifetime, A's gate keeps one attempt per token, and a duplicate after a process
 * restart is not detected here. D's rule is unusable once failureCount > 0, so an extra
 * increment never re-enables a rule; it only skews the count.
 */
class DecisionRulePressOutcome(
    private val rememberedAttempts: Int = 256,
    private val recordFailure: suspend (ruleId: String) -> Unit,
) {
    private val lock = Mutex()
    private val recorded = LinkedHashSet<Pair<String, SnapshotToken>>()

    suspend fun afterRulePress(rule: PopupRule, result: ActionResult): PressOutcome {
        val outcome = outcomeOf(result)
        if (outcome == PressOutcome.FAILED) recordOnce(rule.id to result.token)
        return outcome
    }

    private suspend fun recordOnce(attempt: Pair<String, SnapshotToken>) = lock.withLock {
        if (attempt in recorded) return@withLock
        recordFailure(attempt.first)
        recorded += attempt
        // Bounded: only the most recent attempts are kept; nothing else about the screen is held.
        while (recorded.size > rememberedAttempts) recorded.remove(recorded.first())
    }

    companion object {
        fun outcomeOf(result: ActionResult): PressOutcome = when (result.status) {
            ActionStatus.MECHANICAL_SUCCESS -> PressOutcome.SUCCEEDED
            ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN -> PressOutcome.FAILED
            ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE -> PressOutcome.NOT_ATTEMPTED
        }
    }
}
