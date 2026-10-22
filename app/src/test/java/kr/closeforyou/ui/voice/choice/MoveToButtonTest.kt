package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.contract.VoiceCommand
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.PressFailure
import kr.closeforyou.ui.voice.command.VoiceAction
import kr.closeforyou.ui.voice.command.VoiceChoiceMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MoveToButtonTest {
    private fun candidate(id: String, label: String?, role: ButtonRole, nodePath: String? = "1") =
        CloseCandidate(id, nodePath, null, label, ScreenBounds(0, 0, 10, 10), EvidenceLevel.E2_LABELED_NODE, role)

    private fun analysis(vararg c: CloseCandidate) =
        MockPopupFixtures.advertisement().analysis.copy(closeCandidates = persistentListOf(*c))

    private fun kinds(prompt: ChoicePrompt) = prompt.options.map { it.kind }

    @Test fun featureIsOffByDefault() {
        assertFalse(MoveToButtonFeature.enabled)
        val prompt = ChoicePromptPlanner.plan(analysis(candidate("ok", "확인", ButtonRole.ACKNOWLEDGE)), PopupClassification.NOTICE)
        assertTrue(PromptOptionKind.MOVE_TO_BUTTON !in kinds(prompt))
    }

    @Test fun okButtonIsOfferedAsMoveNotPress() {
        val prompt = ChoicePromptPlanner.plan(
            analysis(candidate("ok", "확인", ButtonRole.ACKNOWLEDGE)),
            PopupClassification.NOTICE,
            moveToButtonEnabled = true,
        )
        assertEquals(
            listOf(PromptOptionKind.MOVE_TO_BUTTON, PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN),
            kinds(prompt),
        )
        assertTrue(prompt.cannotPressForUser)
    }

    @Test fun pressableButtonIsNotAlsoAMoveOption() {
        val prompt = ChoicePromptPlanner.plan(
            analysis(candidate("c", "닫기", ButtonRole.CLOSE)),
            PopupClassification.ADVERTISEMENT,
            moveToButtonEnabled = true,
        )
        assertEquals(1, prompt.options.count { it.candidateId == "c" })
        assertEquals(PromptOptionKind.PRESS_CANDIDATE, prompt.options.first().kind)
    }

    @Test fun decisionPopupOffersMoveOnlyNeverPress() {
        val prompt = ChoicePromptPlanner.plan(
            analysis(candidate("pay", "결제하기", ButtonRole.USER_DECISION), candidate("c", "닫기", ButtonRole.CLOSE)),
            PopupClassification.USER_DECISION,
            moveToButtonEnabled = true,
        )
        assertTrue(PromptOptionKind.PRESS_CANDIDATE !in kinds(prompt))
        assertEquals(2, kinds(prompt).count { it == PromptOptionKind.MOVE_TO_BUTTON })
    }

    @Test fun needsNodeAndLabel() {
        val prompt = ChoicePromptPlanner.plan(
            analysis(candidate("noNode", "확인", ButtonRole.ACKNOWLEDGE, nodePath = null), candidate("noLabel", null, ButtonRole.UNKNOWN)),
            PopupClassification.NOTICE,
            moveToButtonEnabled = true,
        )
        assertTrue(PromptOptionKind.MOVE_TO_BUTTON !in kinds(prompt))
    }

    @Test fun selectGivesMoveRequestNotUserChoice() {
        val prompt = ChoicePromptPlanner.plan(
            analysis(candidate("ok", "확인", ButtonRole.ACKNOWLEDGE)), PopupClassification.NOTICE, moveToButtonEnabled = true,
        )
        val move = prompt.options.first { it.kind == PromptOptionKind.MOVE_TO_BUTTON }
        val selection = ChoicePromptPlanner.select(prompt, move)
        assertEquals(PromptSelection.MoveToButton(MoveToButtonRequest(prompt.token, "ok")), selection)
        val keep = prompt.options.first { it.kind == PromptOptionKind.KEEP_OPEN }
        assertTrue(ChoicePromptPlanner.select(prompt, keep) is PromptSelection.Choice)
    }

    @Test(expected = IllegalArgumentException::class)
    fun moveOptionCannotBecomeAUserChoice() {
        val prompt = ChoicePromptPlanner.plan(
            analysis(candidate("ok", "확인", ButtonRole.ACKNOWLEDGE)), PopupClassification.NOTICE, moveToButtonEnabled = true,
        )
        ChoicePromptPlanner.toUserChoice(prompt, prompt.options.first { it.kind == PromptOptionKind.MOVE_TO_BUTTON })
    }

    @Test fun failedPressTurnsIntoMoveWhenEnabled() {
        val prompt = ChoicePromptPlanner.plan(analysis(candidate("c", "닫기", ButtonRole.CLOSE)), PopupClassification.ADVERTISEMENT)
        val followUp = ChoicePromptPlanner.afterFailedPress(prompt, PressFailure.NOT_PRESSED, moveToButtonEnabled = true)!!
        assertEquals(
            listOf(PromptOptionKind.MOVE_TO_BUTTON, PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN),
            kinds(followUp),
        )
    }

    @Test fun voiceMovesOnlyWhenExactlyOneTarget() {
        val one = ChoicePromptPlanner.plan(
            analysis(candidate("ok", "확인", ButtonRole.ACKNOWLEDGE)), PopupClassification.NOTICE, moveToButtonEnabled = true,
        )
        val action = VoiceChoiceMapper.map(VoiceCommand.MOVE_TO_BUTTON, one)
        assertEquals(PromptOptionKind.MOVE_TO_BUTTON, (action as VoiceAction.Choose).option.kind)

        val two = ChoicePromptPlanner.plan(
            analysis(candidate("ok", "확인", ButtonRole.ACKNOWLEDGE), candidate("later", "나중에", ButtonRole.UNKNOWN)),
            PopupClassification.NOTICE,
            moveToButtonEnabled = true,
        )
        assertEquals(VoiceAction.NotHandledHere(VoiceCommand.MOVE_TO_BUTTON), VoiceChoiceMapper.map(VoiceCommand.MOVE_TO_BUTTON, two))
        val off = ChoicePromptPlanner.plan(analysis(candidate("ok", "확인", ButtonRole.ACKNOWLEDGE)), PopupClassification.NOTICE)
        assertEquals(VoiceAction.NotHandledHere(VoiceCommand.MOVE_TO_BUTTON), VoiceChoiceMapper.map(VoiceCommand.MOVE_TO_BUTTON, off))
    }
}
