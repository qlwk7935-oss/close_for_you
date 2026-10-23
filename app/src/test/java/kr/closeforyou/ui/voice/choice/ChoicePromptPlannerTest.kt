package kr.closeforyou.ui.voice.choice

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChoicePromptPlannerTest {
    private fun pressOptions(prompt: ChoicePrompt) =
        prompt.options.filter { it.kind == PromptOptionKind.PRESS_CANDIDATE }

    private fun candidate(
        id: String = "c1",
        label: String? = "닫기",
        role: ButtonRole = ButtonRole.CLOSE,
        evidence: EvidenceLevel = EvidenceLevel.E2_LABELED_NODE,
    ) = CloseCandidate(id, "1", null, label, ScreenBounds(0, 0, 10, 10), evidence, role)

    @Test fun advertisementOffersNamedCloseButtonFirstThenReadAndKeep() {
        val fixture = MockPopupFixtures.advertisement()
        val prompt = ChoicePromptPlanner.plan(fixture.analysis, PopupClassification.ADVERTISEMENT)
        assertEquals(PromptSummary.LIKELY_AD, prompt.summary)
        assertFalse(prompt.cannotPressForUser)
        assertEquals(
            listOf(PromptOptionKind.PRESS_CANDIDATE, PromptOptionKind.READ_CONTENT, PromptOptionKind.KEEP_OPEN),
            prompt.options.map { it.kind },
        )
        assertEquals("닫기", prompt.options.first().buttonLabel)
    }

    @Test fun userDecisionNeverOffersPressEvenWithCloseRoleCandidate() {
        val analysis = MockPopupFixtures.notice().analysis.copy(closeCandidates = persistentListOf(candidate()))
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.USER_DECISION)
        assertTrue(pressOptions(prompt).isEmpty())
        assertEquals(PromptSummary.NEEDS_REVIEW, prompt.summary)
        // Decision popups get "직접 확인" wording, not the "cannot close" sentence.
        assertFalse(prompt.cannotPressForUser)
    }

    @Test fun decisionFixtureWithConsentButtonOffersNoPress() {
        val fixture = MockPopupFixtures.decision()
        val prompt = ChoicePromptPlanner.plan(fixture.analysis, fixture.expectedDisplayClassification)
        assertTrue(pressOptions(prompt).isEmpty())
    }

    @Test fun unlabeledButtonCannotBePressedForUser() {
        val fixture = MockPopupFixtures.unlabeledButton()
        val prompt = ChoicePromptPlanner.plan(fixture.analysis, PopupClassification.UNKNOWN)
        assertTrue(pressOptions(prompt).isEmpty())
        assertTrue(prompt.cannotPressForUser)
    }

    @Test fun onlyCloseOrSkipRolesWithE1E2EvidenceAndLabelArePressable() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(
                candidate(id = "ack", label = "확인", role = ButtonRole.ACKNOWLEDGE),
                candidate(id = "decision", label = "구매", role = ButtonRole.USER_DECISION),
                candidate(id = "unknown", label = "보기", role = ButtonRole.UNKNOWN),
                candidate(id = "visual", label = "닫기", evidence = EvidenceLevel.E4_VISUAL_ONLY),
                candidate(id = "bound", label = "닫기", evidence = EvidenceLevel.E3_USER_BOUND_NODE),
                candidate(id = "blank", label = "  "),
                candidate(id = "skip", label = "건너뛰기", role = ButtonRole.SKIP),
                candidate(id = "dismiss", label = "닫기", evidence = EvidenceLevel.E1_DISMISS),
            ),
        )
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE)
        assertEquals(listOf("skip", "dismiss"), pressOptions(prompt).map { it.candidateId })
    }

    @Test fun duplicateCandidateIdsAreOfferedOnce() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(candidate(), candidate()),
        )
        assertEquals(1, pressOptions(ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE)).size)
    }

    @Test fun pressingCloseProducesConfirmedOneTimeChoiceWithoutRepeatPermission() {
        val fixture = MockPopupFixtures.notice()
        val prompt = ChoicePromptPlanner.plan(fixture.analysis, PopupClassification.NOTICE)
        val choice = ChoicePromptPlanner.toUserChoice(prompt, pressOptions(prompt).single())
        assertEquals(ChoiceKind.CLOSE_ONCE, choice.kind)
        assertEquals("fixture-button", choice.candidateId)
        assertTrue(choice.buttonMeaningConfirmed)
        assertFalse(choice.repeatAutomationExplicitlyAllowed)
        assertEquals(fixture.snapshot.token, choice.token)
    }

    @Test fun pressingSkipCandidateProducesSkipOnceNotCloseOnce() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(candidate(id = "skip", label = "건너뛰기", role = ButtonRole.SKIP)),
        )
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.ADVERTISEMENT)
        val option = pressOptions(prompt).single()
        assertEquals("건너뛰기", option.buttonLabel)
        val choice = ChoicePromptPlanner.toUserChoice(prompt, option)
        assertEquals(ChoiceKind.SKIP_ONCE, choice.kind)
        assertEquals("skip", choice.candidateId)
        assertTrue(choice.buttonMeaningConfirmed)
        assertFalse(choice.repeatAutomationExplicitlyAllowed)
    }

    @Test fun closeAndSkipCandidatesTogetherEachMapToTheirOwnKind() {
        val analysis = MockPopupFixtures.notice().analysis.copy(
            closeCandidates = persistentListOf(
                candidate(id = "close", label = "닫기", role = ButtonRole.CLOSE),
                candidate(id = "skip", label = "건너뛰기", role = ButtonRole.SKIP),
            ),
        )
        val prompt = ChoicePromptPlanner.plan(analysis, PopupClassification.ADVERTISEMENT)
        val kinds = pressOptions(prompt).associate {
            it.candidateId to ChoicePromptPlanner.toUserChoice(prompt, it).kind
        }
        assertEquals(mapOf("close" to ChoiceKind.CLOSE_ONCE, "skip" to ChoiceKind.SKIP_ONCE), kinds)
    }

    @Test fun pressOptionsCarryCandidateRoleAndOthersDoNot() {
        val prompt = ChoicePromptPlanner.plan(MockPopupFixtures.advertisement().analysis, PopupClassification.ADVERTISEMENT)
        prompt.options.forEach {
            if (it.kind == PromptOptionKind.PRESS_CANDIDATE) assertEquals(ButtonRole.CLOSE, it.candidateRole)
            else assertNull(it.candidateRole)
        }
    }

    @Test fun readAndKeepChoicesCarryNoTargetOrPermission() {
        val prompt = ChoicePromptPlanner.plan(MockPopupFixtures.advertisement().analysis, PopupClassification.ADVERTISEMENT)
        val byKind = prompt.options.associateBy { it.kind }
        val read = ChoicePromptPlanner.toUserChoice(prompt, byKind.getValue(PromptOptionKind.READ_CONTENT))
        val keep = ChoicePromptPlanner.toUserChoice(prompt, byKind.getValue(PromptOptionKind.KEEP_OPEN))
        assertEquals(ChoiceKind.READ_CONTENT, read.kind)
        assertEquals(ChoiceKind.KEEP_OPEN, keep.kind)
        listOf(read, keep).forEach {
            assertNull(it.candidateId)
            assertFalse(it.buttonMeaningConfirmed)
            assertFalse(it.repeatAutomationExplicitlyAllowed)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun optionFromAnotherPromptIsRejected() {
        val ad = ChoicePromptPlanner.plan(MockPopupFixtures.advertisement().analysis, PopupClassification.ADVERTISEMENT)
        val decision = ChoicePromptPlanner.plan(MockPopupFixtures.decision().analysis, PopupClassification.USER_DECISION)
        ChoicePromptPlanner.toUserChoice(decision, pressOptions(ad).single())
    }

    @Test fun summariesAreHedgedPerClassification() {
        val analysis = MockPopupFixtures.notice().analysis
        assertEquals(PromptSummary.LIKELY_NOTICE, ChoicePromptPlanner.plan(analysis, PopupClassification.NOTICE).summary)
        assertEquals(PromptSummary.UNKNOWN, ChoicePromptPlanner.plan(analysis, PopupClassification.UNKNOWN).summary)
        // Contract v2: COUPON has its own hedged summary ("쿠폰으로 보이는 팝업이에요."), not the ad one.
        assertEquals(PromptSummary.LIKELY_COUPON, ChoicePromptPlanner.plan(analysis, PopupClassification.COUPON).summary)
        assertEquals(kr.closeforyou.R.string.ui_choice_summary_coupon, PromptSummary.LIKELY_COUPON.textRes())
    }

    @Test fun everyFixtureAlwaysOffersReadAndKeepAndRedactsText() {
        MockPopupFixtures.all().forEach { fixture ->
            val prompt = ChoicePromptPlanner.plan(fixture.analysis, fixture.expectedDisplayClassification)
            val kinds = prompt.options.map { it.kind }
            assertTrue(PromptOptionKind.READ_CONTENT in kinds && PromptOptionKind.KEEP_OPEN in kinds)
            val printed = prompt.toString() + prompt.options.joinToString()
            assertFalse(printed.contains("닫기") || printed.contains("동의"))
        }
    }
}
