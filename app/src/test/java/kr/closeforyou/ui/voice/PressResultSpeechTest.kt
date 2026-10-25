package kr.closeforyou.ui.voice

import kr.closeforyou.R
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOptionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PressResultSpeechTest {
    private val token = MockPopupFixtures.advertisement().snapshot.token
    /** Shaped like A's: a returned press is observed closed; failed / unknown attempted, not observed. */
    private fun result(status: ActionStatus) = when (status) {
        ActionStatus.MECHANICAL_SUCCESS ->
            ActionResult(token, status, reason = null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED)
        ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN -> ActionResult(token, status, reason = null, attempted = true)
        else -> ActionResult(token, status, reason = null)
    }

    @Test fun onlyConfirmedSuccessIsNotAFailure() {
        assertNull(PressResultSpeech.failureOf(result(ActionStatus.MECHANICAL_SUCCESS)))
        ActionStatus.entries.filter { it != ActionStatus.MECHANICAL_SUCCESS }.forEach {
            assertTrue(it.name, PressResultSpeech.failureOf(result(it)) != null)
        }
    }

    /** 우진 #34: attempted + MECHANICAL_SUCCESS + CLOSED_CONFIRMED, nothing less. */
    @Test fun notConfirmedPressesAreNotSuccess() {
        PressSuccessCases.notConfirmed(token).forEach { (name, result) ->
            assertFalse(name, PressSuccess.isConfirmed(result))
            assertEquals(name, PressFailure.NOT_CONFIRMED, PressResultSpeech.failureOf(result))
        }
        assertTrue(PressSuccess.isConfirmed(PressSuccessCases.confirmed(token)))
        assertNull(PressResultSpeech.failureOf(PressSuccessCases.confirmed(token)))
    }

    @Test fun failedPressNotObservedIsStillPressFailed() = assertEquals(
        PressFailure.PRESS_FAILED,
        PressResultSpeech.failureOf(ActionResult(token, ActionStatus.ATTEMPT_FAILED, null, attempted = true, outcome = ClickOutcome.STILL_PRESENT)),
    )

    /** The contract itself rejects an observed outcome without an attempt. */
    @Test(expected = IllegalArgumentException::class)
    fun outcomeWithoutAttemptCannotExist() {
        ActionResult(token, ActionStatus.MECHANICAL_SUCCESS, null, attempted = false, outcome = ClickOutcome.CLOSED_CONFIRMED)
    }

    @Test fun statusesMapToReasons() {
        assertEquals(PressFailure.NOT_PRESSED, PressResultSpeech.failureOf(result(ActionStatus.BLOCKED)))
        assertEquals(PressFailure.NOT_PRESSED, PressResultSpeech.failureOf(result(ActionStatus.NOT_EXECUTED)))
        assertEquals(PressFailure.SCREEN_CHANGED, PressResultSpeech.failureOf(result(ActionStatus.STALE)))
        assertEquals(PressFailure.PRESS_FAILED, PressResultSpeech.failureOf(result(ActionStatus.ATTEMPT_FAILED)))
        assertEquals(PressFailure.NOT_CONFIRMED, PressResultSpeech.failureOf(result(ActionStatus.OUTCOME_UNKNOWN)))
    }

    /** Same split as the batcher: what it does not count as closed is a failure here. */
    @Test fun consistentWithBatcher() {
        ActionStatus.entries.forEach { status ->
            val counted = AnnouncementBatcher.outcomeOf(result(status), ChoiceKind.CLOSE_ONCE)
            val failure = PressResultSpeech.failureOf(result(status))
            assertEquals(status.name, counted == HandledOutcome.CLOSED, failure == null)
        }
    }

    @Test fun sentenceNeverSaysClosedAndOffersNextChoices() {
        val said = mutableListOf<Int>()
        PressFailure.entries.forEach { failure ->
            said.clear()
            PressResultSpeech.sentence(failure, moveToButtonAvailable = false) { said += it; "" }
            assertFalse(R.string.ui_announce_batch_closed in said)
            assertEquals(PressResultSpeech.reasonRes(failure), said.first())
        }
        assertEquals(R.string.ui_result_next, PressResultSpeech.nextRes(PressFailure.NOT_PRESSED, false))
        assertEquals(R.string.ui_result_next_with_move, PressResultSpeech.nextRes(PressFailure.NOT_PRESSED, true))
        assertEquals(R.string.ui_result_next_check_screen, PressResultSpeech.nextRes(PressFailure.SCREEN_CHANGED, true))
    }

    @Test fun followUpDropsPressButtonsAndKeepsReadAndKeep() {
        val prompt = ChoicePromptPlanner.plan(MockPopupFixtures.advertisement().analysis, PopupClassification.ADVERTISEMENT)
        val followUp = requireNotNull(ChoicePromptPlanner.afterFailedPress(prompt, PressFailure.NOT_CONFIRMED))
        assertEquals(listOf(PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN), followUp.options.map { it.kind })
        assertEquals(PressFailure.NOT_CONFIRMED, followUp.pressFailure)
        assertEquals(prompt.token, followUp.token)
        assertFalse(followUp.cannotPressForUser)
    }

    @Test fun noFollowUpOnChangedScreen() {
        val prompt = ChoicePromptPlanner.plan(MockPopupFixtures.advertisement().analysis, PopupClassification.ADVERTISEMENT)
        assertNull(ChoicePromptPlanner.afterFailedPress(prompt, PressFailure.SCREEN_CHANGED))
    }

    @Test fun onlyCloseAndSkipArePressChoices() {
        assertEquals(setOf(ChoiceKind.CLOSE_ONCE, ChoiceKind.SKIP_ONCE), ChoiceKind.entries.filter(PressResultSpeech::isPressChoice).toSet())
    }
}
