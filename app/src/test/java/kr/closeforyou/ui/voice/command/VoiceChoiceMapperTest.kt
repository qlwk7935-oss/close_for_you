package kr.closeforyou.ui.voice.command

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.contract.VoiceCommand
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceChoiceMapperTest {
    private fun candidate(id: String, label: String, role: ButtonRole) =
        CloseCandidate(id, "1", null, label, ScreenBounds(0, 0, 10, 10), EvidenceLevel.E2_LABELED_NODE, role)

    private fun prompt(vararg candidates: CloseCandidate, classification: PopupClassification = PopupClassification.ADVERTISEMENT) =
        ChoicePromptPlanner.plan(
            MockPopupFixtures.advertisement().analysis.copy(closeCandidates = persistentListOf(*candidates)),
            classification,
        )

    private fun choiceKind(command: VoiceCommand, prompt: ChoicePrompt): ChoiceKind? =
        (VoiceChoiceMapper.map(command, prompt) as? VoiceAction.Choose)
            ?.let { ChoicePromptPlanner.toUserChoice(prompt, it.option).kind }

    @Test fun closeAndSkipPickTheirOwnRole() {
        val p = prompt(candidate("c", "닫기", ButtonRole.CLOSE), candidate("s", "건너뛰기", ButtonRole.SKIP))
        assertEquals(ChoiceKind.CLOSE_ONCE, choiceKind(VoiceCommand.CLOSE, p))
        assertEquals(ChoiceKind.SKIP_ONCE, choiceKind(VoiceCommand.SKIP, p))
    }

    @Test fun readAndKeepAlwaysAvailable() {
        val p = prompt()
        assertEquals(ChoiceKind.READ_CONTENT, choiceKind(VoiceCommand.READ_CONTENT, p))
        assertEquals(ChoiceKind.KEEP_OPEN, choiceKind(VoiceCommand.KEEP_OPEN, p))
    }

    @Test fun noVerifiedButtonMeansCannotPress() {
        assertEquals(VoiceAction.CannotPressForUser, VoiceChoiceMapper.map(VoiceCommand.CLOSE, prompt()))
        val skipOnly = prompt(candidate("s", "건너뛰기", ButtonRole.SKIP))
        assertEquals(VoiceAction.CannotPressForUser, VoiceChoiceMapper.map(VoiceCommand.CLOSE, skipOnly))
    }

    @Test fun twoCloseButtonsAreAmbiguousByVoice() {
        val p = prompt(candidate("a", "닫기", ButtonRole.CLOSE), candidate("b", "X 닫기", ButtonRole.CLOSE))
        assertEquals(VoiceAction.CannotPressForUser, VoiceChoiceMapper.map(VoiceCommand.CLOSE, p))
    }

    @Test fun decisionPopupCannotBeClosedOrConfirmedByVoice() {
        val p = prompt(candidate("c", "닫기", ButtonRole.CLOSE), classification = PopupClassification.USER_DECISION)
        assertEquals(VoiceAction.CannotPressForUser, VoiceChoiceMapper.map(VoiceCommand.CLOSE, p))
    }

    @Test fun navigationAndImageCommandsAreNotHandledByC() {
        listOf(VoiceCommand.GO_BACK, VoiceCommand.MOVE_TO_BUTTON, VoiceCommand.DESCRIBE_IMAGE).forEach {
            assertEquals(VoiceAction.NotHandledHere(it), VoiceChoiceMapper.map(it, prompt()))
        }
    }
}
