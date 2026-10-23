package kr.closeforyou.decision

import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.model.*
import kr.closeforyou.core.model.ActionAuthorization.*
import kr.closeforyou.core.model.SafetyReason.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionActionPolicyTest {
    private val policy = DecisionActionPolicy()
    private val ad = MockPopupFixtures.advertisement().analysis
    private val notice = MockPopupFixtures.notice().analysis

    // A's runtime marks every analysis like this and does not produce close candidates yet.
    private val realLimitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED)

    @Test fun firstSeenPopupWithAVerifiedCloseButtonAsksThePerson() = runBlocking {
        val decision = decide(ad)
        assertEquals(NEEDS_CONFIRMATION, decision.authorization)
        assertEquals(setOf(FIRST_SEEN), decision.reasons)
        assertEquals("fixture-button", decision.candidateId)
    }

    @Test fun realRuntimeAnalysisWithoutCandidatesIsNeverPressed() = runBlocking {
        val real = ad.copy(limitations = realLimitations, closeCandidates = persistentListOf())
        assertDenied(decide(real), FIRST_SEEN, UNVERIFIED_TARGET)
        assertDenied(decide(real, rule = rule()), UNVERIFIED_TARGET, INCOMPLETE_EVIDENCE, EXPLICIT_PERMISSION_REQUIRED)
        assertDenied(decide(real, choice = UserChoice(real.token, ChoiceKind.CLOSE_ONCE, "fixture-button")), UNVERIFIED_TARGET)
    }

    @Test fun importantDecisionsAreDeniedEvenWithARuleOrACommand() = runBlocking {
        val decision = MockPopupFixtures.decision().analysis
        assertTrue(IMPORTANT_DECISION in decide(decision).reasons)
        assertEquals(DENY, decide(decision).authorization)
        assertEquals(DENY, decide(decision, rule = rule()).authorization)
        assertEquals(DENY, decide(decision, choice = choose(decision, confirmed = true)).authorization)

        val summary = ad.copy(
            texts = persistentListOf(ExtractedText("결제 선택 필요", ad.texts.single().bounds, TextSource.OCR, null)),
            limitations = realLimitations,
        )
        assertTrue(IMPORTANT_DECISION in decide(summary, rule = rule()).reasons)
        assertTrue(IMPORTANT_DECISION in decide(ad.copy(classification = PopupClassification.USER_DECISION), choice = choose(ad)).reasons)
        val trialButton = withCandidate(ad) { it.copy(label = "무료 체험 시작") }
        assertTrue(IMPORTANT_DECISION in decide(trialButton, choice = choose(trialButton)).reasons)
    }

    @Test fun aDecisionWordOnlyInABenefitConditionNoLongerBlocksTheClose() = runBlocking {
        // R1 (OVERWARNING_STUDY.md): "가입하면 포인트" is an advertisement, so the person may close it.
        fun withTexts(vararg texts: String) = ad.copy(
            texts = texts.map { ExtractedText(it, ad.texts.single().bounds, TextSource.ACCESSIBILITY_TREE, null) }.toPersistentList(),
            limitations = realLimitations,
        )
        val benefit = withTexts("광고", "가입하면 포인트를 드립니다. 시험용 광고입니다.", "닫기")
        assertEquals(ALLOW_ONCE, decide(benefit, choice = choose(benefit, confirmed = true)).authorization)

        // Everything else that carries a decision word still blocks it.
        for (blocked in listOf(
            withTexts("광고", "지금 구매하면 쿠폰 증정", "구매하기", "닫기"),       // a decision button beside the benefit
            withTexts("광고", "지금 가입하시겠어요? 가입하면 쿠폰 증정", "닫기"),   // a question
            withTexts("결제 선택 필요"),                                         // A's fixed summary
            withTexts("광고", "결제 시 할인", "닫기"),                            // a short text: may be a button
            withTexts("광고 · 가상 상품 구매 안내", "닫기"),                       // a decision word with no benefit condition
            // Review on #30 (A): a real request in the same text as a benefit condition.
            withTexts("광고", "가입하면 포인트를 드립니다. 개인정보 제공에 동의하세요", "닫기"),
            withTexts("광고", "결제하면 추가 할인, 지금 결제하세요", "닫기"),
            withTexts("광고", "동의하면 쿠폰 지급", "닫기"),                       // consent never counts as a condition
            withTexts("광고", "Sign up now and get 10% off. Please agree to the terms", "닫기"),
        )) {
            val decision = decide(blocked, choice = choose(blocked, confirmed = true))
            assertEquals(blocked.texts.joinToString("|") { it.text }, DENY, decision.authorization)
            assertTrue(IMPORTANT_DECISION in decision.reasons)
        }
    }

    @Test fun staleScreensAndOldChoicesAreDenied() = runBlocking {
        assertDenied(decide(ad, choice = choose(ad), currentToken = notice.token), STALE_SCREEN)
        assertDenied(decide(ad, choice = choose(ad), currentToken = null), STALE_SCREEN)
        val oldChoice = UserChoice(notice.token, ChoiceKind.CLOSE_ONCE, "fixture-button", buttonMeaningConfirmed = true)
        assertDenied(decide(ad, choice = oldChoice), STALE_SCREEN)
    }

    @Test fun personInteractingIsDenied() = runBlocking {
        assertDenied(decide(ad, choice = choose(ad), interacting = true), USER_INTERACTION)
    }

    @Test fun pixelOnlyAndIconButtonsAreNotPressed() = runBlocking {
        val pixelOnly = withCandidate(ad) { it.copy(evidence = EvidenceLevel.E4_VISUAL_ONLY, label = null) }
        assertDenied(decide(pixelOnly, choice = choose(pixelOnly)), UNVERIFIED_TARGET)

        // A's gate (PR #10) supports E1/E2 only, so even a confirmed icon (E3) is not requested.
        val icon = withCandidate(ad) { it.copy(evidence = EvidenceLevel.E3_USER_BOUND_NODE, label = null) }
        assertDenied(decide(icon, choice = choose(icon, confirmed = false)), UNVERIFIED_TARGET)
        assertDenied(decide(icon, choice = choose(icon)), UNVERIFIED_TARGET)
        assertDenied(decide(MockPopupFixtures.unlabeledButton().analysis), FIRST_SEEN, UNVERIFIED_TARGET)
    }

    @Test fun fakeCloseButtonsAreNotPressed() = runBlocking {
        // Candidates built elsewhere claim role CLOSE; the policy must not trust that role alone.
        for (label in listOf(
            "닫기 전 저장", "설치", "자세히 보기", "오늘 하루 보지 않기", "오늘 하루 닫기", "7일간 보지 않기",
            "확인", "취소", "나중에", "OK", "Install", "Learn more", "건너뛰기", "X", "×",
        )) {
            val fake = withCandidate(ad) { it.copy(label = label) }
            assertDenied(decide(fake, choice = choose(fake)), UNVERIFIED_TARGET)
        }
        val closeLabelClaimingSkip = withCandidate(ad) { it.copy(role = ButtonRole.SKIP) }
        assertDenied(decide(closeLabelClaimingSkip, choice = choose(closeLabelClaimingSkip, kind = ChoiceKind.SKIP_ONCE)), UNVERIFIED_TARGET)
        val unlabeledE2 = withCandidate(ad) { it.copy(label = null) }
        assertDenied(decide(unlabeledE2, choice = choose(unlabeledE2)), UNVERIFIED_TARGET)
        val navigateRole = withCandidate(ad) { it.copy(role = ButtonRole.NAVIGATE) }
        assertDenied(decide(navigateRole, choice = choose(navigateRole)), UNVERIFIED_TARGET)
    }

    @Test fun personsCommandOnAVerifiedButtonAllowsOnce() = runBlocking {
        val decision = decide(ad, choice = choose(ad))
        assertEquals(ALLOW_ONCE, decision.authorization)
        assertEquals("fixture-button", decision.candidateId)
        assertTrue(decision.reasons.isEmpty())
        // A command is not automation, so A's incomplete-evidence marker does not block it.
        assertEquals(ALLOW_ONCE, decide(ad.copy(limitations = realLimitations), choice = choose(ad)).authorization)
        // A verbatim label with surrounding spaces still says CLOSE.
        val padded = withCandidate(ad) { it.copy(label = " 닫기 ") }
        assertEquals(ALLOW_ONCE, decide(padded, choice = choose(padded)).authorization)
    }

    @Test fun unconfirmedTapAsksAgainAboutTheSameButton() = runBlocking {
        val decision = decide(ad, choice = choose(ad, confirmed = false))
        assertConfirm(decision, EXPLICIT_PERMISSION_REQUIRED)
        assertEquals("fixture-button", decision.candidateId)
    }

    @Test fun skipCommandNeedsASkipButton() = runBlocking {
        assertDenied(decide(ad, choice = choose(ad, kind = ChoiceKind.SKIP_ONCE)), UNVERIFIED_TARGET)
        val skip = withCandidate(ad) { it.copy(label = "건너뛰기", role = ButtonRole.SKIP) }
        assertEquals(ALLOW_ONCE, decide(skip, choice = choose(skip, kind = ChoiceKind.SKIP_ONCE)).authorization)
        assertDenied(decide(skip, choice = choose(skip, kind = ChoiceKind.CLOSE_ONCE)), UNVERIFIED_TARGET)
    }

    @Test fun choicesThatDoNotPressAnythingAreDenied() = runBlocking {
        // Remembering a popup is D's rule, not a press on this screen (A's gate accepts only CLOSE_ONCE / SKIP_ONCE).
        for (kind in listOf(ChoiceKind.KEEP_OPEN, ChoiceKind.READ_CONTENT, ChoiceKind.STOP_ASKING, ChoiceKind.REVOKE_RULE, ChoiceKind.REMEMBER_EXACT_POPUP)) {
            val decision = decide(ad, choice = UserChoice(ad.token, kind))
            assertDenied(decision, EXPLICIT_PERMISSION_REQUIRED)
            assertNull(decision.candidateId)
        }
    }

    @Test fun automaticRepeatStaysOffUntilTheContractCarriesItsConditions() = runBlocking {
        // Even a fully matching rule only asks: the per-app setting and attempt history are not in the contract yet.
        val matching = decide(notice, rule = rule())
        assertConfirm(matching, EXPLICIT_PERMISSION_REQUIRED)
        assertEquals("fixture-button", matching.candidateId)

        // Every other unmet condition is still reported.
        assertConfirm(decide(notice, rule = rule(), announcement = false), EXPLICIT_PERMISSION_REQUIRED, FEEDBACK_UNAVAILABLE)
        assertConfirm(decide(notice.copy(limitations = realLimitations), rule = rule()), EXPLICIT_PERMISSION_REQUIRED, INCOMPLETE_EVIDENCE)
        assertConfirm(decide(notice, rule = rule().copy(failureCount = 1)), EXPLICIT_PERMISSION_REQUIRED, ALREADY_ATTEMPTED)
        assertConfirm(decide(notice, rule = rule(repeat = false)), EXPLICIT_PERMISSION_REQUIRED, RULE_MISMATCH)
        assertConfirm(decide(notice, rule = rule(confirmed = false, repeat = false)), EXPLICIT_PERMISSION_REQUIRED, RULE_MISMATCH)
        assertConfirm(decide(notice, rule = rule().copy(enabled = false)), EXPLICIT_PERMISSION_REQUIRED, RULE_MISMATCH)
    }

    @Test fun personsCurrentChoiceWinsOverARememberedRule() = runBlocking {
        assertDenied(decide(notice, choice = UserChoice(notice.token, ChoiceKind.KEEP_OPEN), rule = rule()), EXPLICIT_PERMISSION_REQUIRED)
        assertEquals(ALLOW_ONCE, decide(notice, choice = choose(notice), rule = rule(repeat = false)).authorization)
    }

    @Test fun firstSeenWithSeveralVerifiedButtonsAsksWithoutPickingOne() = runBlocking {
        val candidate = ad.closeCandidates.single()
        val two = ad.copy(closeCandidates = persistentListOf(candidate, candidate.copy(id = "second")))
        val decision = decide(two)
        assertConfirm(decision, FIRST_SEEN)
        assertNull(decision.candidateId)
    }

    @Test fun onlyALabeledDismissActionIsPressed() = runBlocking {
        val labeled = withCandidate(notice) { it.copy(evidence = EvidenceLevel.E1_DISMISS) }
        assertEquals(ALLOW_ONCE, decide(labeled, choice = choose(labeled)).authorization)
        assertConfirm(decide(labeled, rule = rule()), EXPLICIT_PERMISSION_REQUIRED)
        // A's gate refuses an unlabeled DISMISS, so B does not request it either.
        val unlabeled = withCandidate(notice) { it.copy(evidence = EvidenceLevel.E1_DISMISS, label = null) }
        assertDenied(decide(unlabeled, choice = choose(unlabeled)), UNVERIFIED_TARGET)
        assertDenied(decide(unlabeled, rule = rule()), UNVERIFIED_TARGET, EXPLICIT_PERMISSION_REQUIRED)
    }

    @Test fun stopAskingRuleNeitherClosesNorAsksAgain() = runBlocking {
        assertDenied(decide(notice, rule = rule(choice = ChoiceKind.STOP_ASKING, repeat = false, confirmed = false)), EXPLICIT_PERMISSION_REQUIRED)
    }

    @Test fun ruleWithAnAmbiguousOrUnverifiedTargetIsDenied() = runBlocking {
        val candidate = notice.closeCandidates.single()
        val two = notice.copy(closeCandidates = persistentListOf(candidate, candidate.copy(id = "second")))
        assertDenied(decide(two, rule = rule()), UNVERIFIED_TARGET, EXPLICIT_PERMISSION_REQUIRED)
        val pixelOnly = withCandidate(notice) { it.copy(evidence = EvidenceLevel.E4_VISUAL_ONLY) }
        assertDenied(decide(pixelOnly, rule = rule()), UNVERIFIED_TARGET, EXPLICIT_PERMISSION_REQUIRED)
    }

    private suspend fun decide(
        analysis: PopupAnalysis,
        choice: UserChoice? = null,
        rule: PopupRule? = null,
        interacting: Boolean = false,
        announcement: Boolean = true,
        currentToken: SnapshotToken? = analysis.token,
    ) = policy.decide(analysis, DecisionContext(currentToken, choice, rule, interacting, announcement))

    // Mirrors C's choice planner: a tap on a named button confirms that button's meaning.
    private fun choose(analysis: PopupAnalysis, kind: ChoiceKind = ChoiceKind.CLOSE_ONCE, confirmed: Boolean = true) =
        UserChoice(analysis.token, kind, analysis.closeCandidates.single().id, buttonMeaningConfirmed = confirmed)

    private fun withCandidate(analysis: PopupAnalysis, change: (CloseCandidate) -> CloseCandidate) =
        analysis.copy(closeCandidates = persistentListOf(change(analysis.closeCandidates.single())))

    private fun rule(choice: ChoiceKind = ChoiceKind.REMEMBER_EXACT_POPUP, repeat: Boolean = true, confirmed: Boolean = true) = PopupRule(
        id = "test-rule",
        key = RuleKey(MockPopupFixtures.PACKAGE_NAME, 1, "structure", "content", "target"),
        choice = choice,
        buttonMeaningConfirmed = confirmed,
        repeatAutomationExplicitlyAllowed = repeat,
        createdAtEpochMillis = 100,
        expiresAtEpochMillis = null,
    )

    private fun assertDenied(decision: ActionDecision, vararg reasons: SafetyReason) {
        assertEquals(DENY, decision.authorization)
        assertEquals(reasons.toSet(), decision.reasons)
        assertNull(decision.candidateId)
    }

    private fun assertConfirm(decision: ActionDecision, vararg reasons: SafetyReason) {
        assertEquals(NEEDS_CONFIRMATION, decision.authorization)
        assertEquals(reasons.toSet(), decision.reasons)
    }
}
