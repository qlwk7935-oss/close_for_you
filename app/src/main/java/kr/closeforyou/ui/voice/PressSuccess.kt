package kr.closeforyou.ui.voice

import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ClickOutcome

/**
 * The one rule for "the press worked" (#34 review, 우진 10/25): A attempted it, the click returned
 * ([ActionStatus.MECHANICAL_SUCCESS]) and A saw the popup close ([ClickOutcome.CLOSED_CONFIRMED]).
 * Only then C says "닫았어요"/"건너뛰었어요", counts it in the batch, or saves an F12 rule.
 * A popup that vanished after a failed or unknown press is not ours to claim (AGENTS 6-4).
 * Used by [PressResultSpeech], [AnnouncementBatcher], [AutoDecisionSpeech] and PopupChoiceFlow.
 */
object PressSuccess {
    fun isConfirmed(result: ActionResult): Boolean =
        result.attempted &&
            result.status == ActionStatus.MECHANICAL_SUCCESS &&
            result.outcome == ClickOutcome.CLOSED_CONFIRMED
}
