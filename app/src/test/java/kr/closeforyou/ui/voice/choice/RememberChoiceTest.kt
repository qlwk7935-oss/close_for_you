package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.R
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.PressFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [앞으로 항상 닫기]·[다시 묻지 않기] behind [RememberChoiceFeature] (Mock: nothing is saved). */
class RememberChoiceTest {
    private fun candidate(
        id: String = "c1",
        label: String? = "닫기",
        role: ButtonRole = ButtonRole.CLOSE,
        evidence: EvidenceLevel = EvidenceLevel.E2_LABELED_NODE,
    ) = CloseCandidate(id, "1", null, label, ScreenBounds(0, 0, 10, 10), evidence, role)

    private fun plan(
        vararg candidates: CloseCandidate,
        classification: PopupClassification = PopupClassification.ADVERTISEMENT,
        enabled: Boolean = true,
    ) = ChoicePromptPlanner.plan(
        MockPopupFixtures.advertisement().analysis.copy(closeCandidates = persistentListOf(*candidates)),
        classification,
        rememberChoicesEnabled = enabled,
        // These cases describe both buttons; [다시 묻지 않기] itself stays off in the app.
        stopAskingEnabled = enabled,
        suspicious = false,
    )

    private fun ChoicePrompt.kinds() = options.map { it.kind }

    @Test fun demoModeShowsAlwaysCloseButNotStopAsking() {
        assertTrue(RememberChoiceFeature.enabled)
        assertFalse(RememberChoiceFeature.stopAskingEnabled)
        val prompt = ChoicePromptPlanner.plan(
            MockPopupFixtures.advertisement().analysis.copy(closeCandidates = persistentListOf(candidate())),
            PopupClassification.ADVERTISEMENT,
        )
        assertTrue(PromptOptionKind.REMEMBER_ALWAYS in prompt.kinds())
        assertFalse(PromptOptionKind.STOP_ASKING in prompt.kinds())
    }

    @Test fun switchedOffAddsNothing() {
        val prompt = plan(candidate(), enabled = false)
        assertFalse(PromptOptionKind.REMEMBER_ALWAYS in prompt.kinds())
        assertFalse(PromptOptionKind.STOP_ASKING in prompt.kinds())
    }

    @Test fun alwaysCloseFollowsEachPressableButtonAndNamesIt() {
        val prompt = plan(candidate("c", "닫기", ButtonRole.CLOSE), candidate("s", "건너뛰기", ButtonRole.SKIP))
        assertEquals(
            listOf(
                PromptOptionKind.PRESS_CANDIDATE, PromptOptionKind.PRESS_CANDIDATE,
                PromptOptionKind.REMEMBER_ALWAYS, PromptOptionKind.REMEMBER_ALWAYS,
                PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN, PromptOptionKind.STOP_ASKING,
            ),
            prompt.kinds(),
        )
        val remember = prompt.options.filter { it.kind == PromptOptionKind.REMEMBER_ALWAYS }
        assertEquals(listOf("닫기", "건너뛰기"), remember.map { it.buttonLabel })
    }

    @Test fun alwaysCloseBuildsTheFullRepeatPermission() {
        val prompt = plan(candidate())
        val option = prompt.options.single { it.kind == PromptOptionKind.REMEMBER_ALWAYS }
        val choice = ChoicePromptPlanner.toUserChoice(prompt, option)
        assertEquals(ChoiceKind.REMEMBER_EXACT_POPUP, choice.kind)
        assertEquals("c1", choice.candidateId)
        assertTrue(choice.buttonMeaningConfirmed)
        assertTrue(choice.repeatAutomationExplicitlyAllowed)
    }

    @Test fun alwaysCloseNeverForButtonsThatCannotBePressed() {
        listOf(
            candidate(label = null),
            candidate(label = "확인", role = ButtonRole.ACKNOWLEDGE),
            candidate(label = "X"),
            candidate(evidence = EvidenceLevel.E3_USER_BOUND_NODE),
            candidate(evidence = EvidenceLevel.E4_VISUAL_ONLY),
            candidate(label = "결제하기", role = ButtonRole.USER_DECISION),
            candidate(label = "닫기", role = ButtonRole.SKIP), // label and role disagree
        ).forEach { c ->
            assertFalse(c.toString(), PromptOptionKind.REMEMBER_ALWAYS in plan(c).kinds())
        }
    }

    @Test fun userDecisionGetsNeitherAlwaysNorStopAsking() {
        val prompt = plan(candidate(), classification = PopupClassification.USER_DECISION)
        assertFalse(PromptOptionKind.REMEMBER_ALWAYS in prompt.kinds())
        assertFalse(PromptOptionKind.STOP_ASKING in prompt.kinds())
    }

    @Test fun stopAskingShownWithoutPressableButtonsOutsideUserDecision() {
        listOf(PopupClassification.ADVERTISEMENT, PopupClassification.NOTICE, PopupClassification.UNKNOWN).forEach {
            val prompt = plan(candidate(label = null), classification = it)
            assertTrue(it.name, PromptOptionKind.STOP_ASKING in prompt.kinds())
            val choice = ChoicePromptPlanner.toUserChoice(prompt, prompt.options.last())
            assertEquals(ChoiceKind.STOP_ASKING, choice.kind)
            assertFalse(choice.repeatAutomationExplicitlyAllowed)
        }
    }

    @Test fun failedPressDropsAlwaysCloseButKeepsStopAsking() {
        val followUp = ChoicePromptPlanner.afterFailedPress(plan(candidate()), PressFailure.PRESS_FAILED, moveToButtonEnabled = false)!!
        assertEquals(
            listOf(PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN, PromptOptionKind.STOP_ASKING),
            followUp.kinds(),
        )
    }

    @Test fun autoCloseOffSaysWeWillAskAgain() {
        assertEquals(R.string.ui_remember_auto_close_off, RememberChoiceSpeech.afterRememberRes(false))
        assertEquals(R.string.ui_remember_auto_close_on, RememberChoiceSpeech.afterRememberRes(true))
    }

    @Test fun optionToStringStaysRedacted() {
        plan(candidate(label = "광고 닫기")).options.forEach { assertFalse(it.toString().contains("닫기")) }
    }
}
