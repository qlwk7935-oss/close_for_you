package kr.closeforyou.ui.voice

import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.SnapshotToken

/**
 * Shared cases for the one success rule ([PressSuccess]). attempted = false with CLOSED_CONFIRMED
 * can not be built (ActionResult requires an attempt for any outcome), so its nearest case is
 * attempted = false + MECHANICAL_SUCCESS with no outcome.
 */
internal object PressSuccessCases {
    fun confirmed(token: SnapshotToken) =
        ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED)

    fun notConfirmed(token: SnapshotToken): List<Pair<String, ActionResult>> = listOf(
        "ATTEMPT_FAILED+CLOSED_CONFIRMED" to
            ActionResult(token, ActionStatus.ATTEMPT_FAILED, null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED),
        "OUTCOME_UNKNOWN+CLOSED_CONFIRMED" to
            ActionResult(token, ActionStatus.OUTCOME_UNKNOWN, null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED),
        "MECHANICAL_SUCCESS+no outcome" to
            ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = true, outcome = null),
        "MECHANICAL_SUCCESS not attempted" to
            ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = false, outcome = null),
    )
}
