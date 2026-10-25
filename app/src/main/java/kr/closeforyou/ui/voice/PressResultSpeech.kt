package kr.closeforyou.ui.voice

import kr.closeforyou.R
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.ClickOutcome

/** Why a press the person chose did not end in "pressed". Carries no screen text. */
enum class PressFailure {
    /** BLOCKED / NOT_EXECUTED: B's policy or A's gate refused, nothing was pressed. */
    NOT_PRESSED,
    /** STALE: the screen changed before the press, nothing was pressed. */
    SCREEN_CHANGED,
    /** ATTEMPT_FAILED: A tried once and the press itself failed. No retry (AGENTS 6-6). */
    PRESS_FAILED,
    /**
     * OUTCOME_UNKNOWN, or a press that is not [PressSuccess.isConfirmed] although it returned
     * (no CLOSED_CONFIRMED) or although the popup is gone (ATTEMPT_FAILED + CLOSED_CONFIRMED).
     */
    NOT_CONFIRMED,
}

/**
 * C/F10: what to say after the person chose [ChoiceKind.CLOSE_ONCE] / [ChoiceKind.SKIP_ONCE].
 *
 * - [PressSuccess.isConfirmed] (attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED) → counted by
 *   [AnnouncementBatcher] ("팝업 1개를 닫았어요") like any close. MECHANICAL_SUCCESS alone is not enough.
 * - Anything else → said at once with the next choices, never "닫았어요". It is not batched because
 *   the person is waiting for the result of their own tap.
 *
 * Consistent with [AnnouncementBatcher.outcomeOf]: the same statuses are "not closed" here.
 */
object PressResultSpeech {
    fun failureOf(result: ActionResult): PressFailure? = if (PressSuccess.isConfirmed(result)) null else when (result.status) {
        ActionStatus.MECHANICAL_SUCCESS -> PressFailure.NOT_CONFIRMED
        ActionStatus.BLOCKED, ActionStatus.NOT_EXECUTED -> PressFailure.NOT_PRESSED
        ActionStatus.STALE -> PressFailure.SCREEN_CHANGED
        // The popup went away by itself after a failed press: closed, but not by us.
        ActionStatus.ATTEMPT_FAILED ->
            if (result.outcome == ClickOutcome.CLOSED_CONFIRMED) PressFailure.NOT_CONFIRMED else PressFailure.PRESS_FAILED
        ActionStatus.OUTCOME_UNKNOWN -> PressFailure.NOT_CONFIRMED
    }

    /**
     * Whether the remaining choices can be offered again on the same token. A changed screen means
     * the old analysis must not be reused (AGENTS 6-7), so only A's next detection may ask again.
     */
    fun canOfferFollowUp(failure: PressFailure): Boolean = failure != PressFailure.SCREEN_CHANGED

    /**
     * Sentence for [failure]. [moveToButtonAvailable] adds "버튼으로 이동" only once that choice exists
     * (see `MoveToButtonFeature`). [text] resolves a string resource.
     */
    fun sentence(failure: PressFailure, moveToButtonAvailable: Boolean, text: (resId: Int) -> String): String =
        text(reasonRes(failure)) + " " + text(nextRes(failure, moveToButtonAvailable))

    fun reasonRes(failure: PressFailure): Int = when (failure) {
        PressFailure.NOT_PRESSED -> R.string.ui_result_not_pressed
        PressFailure.SCREEN_CHANGED -> R.string.ui_result_screen_changed
        PressFailure.PRESS_FAILED -> R.string.ui_result_press_failed
        PressFailure.NOT_CONFIRMED -> R.string.ui_result_not_confirmed
    }

    fun nextRes(failure: PressFailure, moveToButtonAvailable: Boolean): Int = when {
        !canOfferFollowUp(failure) -> R.string.ui_result_next_check_screen
        moveToButtonAvailable -> R.string.ui_result_next_with_move
        else -> R.string.ui_result_next
    }

    /** True only for choices that ask A to press something. */
    fun isPressChoice(choice: ChoiceKind): Boolean =
        choice == ChoiceKind.CLOSE_ONCE || choice == ChoiceKind.SKIP_ONCE
}
