package kr.closeforyou.decision

import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.PopupRule

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
 * prompt never touches a rule. A failure is always recorded, whatever screen the result names:
 * recording only stops the rule from being used again, which is the safe direction.
 */
class DecisionRulePressOutcome(
    private val recordFailure: suspend (ruleId: String) -> Unit,
) {
    suspend fun afterRulePress(rule: PopupRule, result: ActionResult): PressOutcome {
        val outcome = outcomeOf(result)
        if (outcome == PressOutcome.FAILED) recordFailure(rule.id)
        return outcome
    }

    companion object {
        fun outcomeOf(result: ActionResult): PressOutcome = when (result.status) {
            ActionStatus.MECHANICAL_SUCCESS -> PressOutcome.SUCCEEDED
            ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN -> PressOutcome.FAILED
            ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE -> PressOutcome.NOT_ATTEMPTED
        }
    }
}
