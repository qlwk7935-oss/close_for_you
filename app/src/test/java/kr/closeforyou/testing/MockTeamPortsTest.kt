package kr.closeforyou.testing

import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.*
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MockTeamPortsTest {
    @Test fun changedDateAppVersionAndTargetCannotReuseAnExactRule() = runBlocking {
        val fixture = MockPopupFixtures.notice()
        val changed = MockPopupFixtures.changedDateNotice()
        val provider = FixtureOnlyFingerprintProvider()
        val originalKey = provider.createKey(fixture.snapshot, fixture.analysis.closeCandidates.single(), 1)
        val changedKey = provider.createKey(changed.snapshot, changed.analysis.closeCandidates.single(), 1)
        val repository = InMemoryMockPopupRuleRepository()
        repository.save(rule(originalKey))

        assertNotNull(repository.findExact(originalKey, 101))
        assertEquals(originalKey.structureFingerprint, changedKey.structureFingerprint)
        assertEquals(originalKey.targetFingerprint, changedKey.targetFingerprint)
        assertNotEquals(originalKey.exactContentFingerprint, changedKey.exactContentFingerprint)
        assertNull(repository.findExact(changedKey, 101))
        assertNull(repository.findExact(originalKey.copy(appVersionCode = 2), 101))
        assertNull(repository.findExact(originalKey.copy(targetFingerprint = "fixture-changed-target"), 101))
    }

    @Test fun expiryRevocationFutureRecordsAndFailuresFailClosed() = runBlocking {
        val fixture = MockPopupFixtures.notice()
        val key = FixtureOnlyFingerprintProvider().createKey(fixture.snapshot, fixture.analysis.closeCandidates.single(), 1)
        val repository = InMemoryMockPopupRuleRepository()
        repository.save(rule(key, expiresAt = 200))
        assertNull(repository.findExact(key, 99))
        assertNotNull(repository.findExact(key, 199))
        assertNull(repository.findExact(key, 200))

        repository.revoke("test-rule")
        assertNull(repository.findExact(key, 101))
        assertFalse(repository.observeRules().first().single().enabled)
        repository.save(rule(key).copy(failureCount = 1))
        assertNull(repository.findExact(key, 101))
        repository.deleteAll()
        assertTrue(repository.observeRules().first().isEmpty())
    }

    @Test fun stopAskingDoesNotGrantRepeatAutomation() = runBlocking {
        val fixture = MockPopupFixtures.notice()
        val choice = UserChoice(
            fixture.snapshot.token,
            ChoiceKind.STOP_ASKING,
            candidateId = fixture.analysis.closeCandidates.single().id,
        )
        val repository = InMemoryMockPopupRuleRepository()
        val remembered = IntegrationExample.saveExplicitChoiceExample(fixture, choice, repository, 100)
        val matching = repository.findExact(remembered.key, 101)
        assertEquals(ChoiceKind.STOP_ASKING, matching?.choice)
        assertFalse(matching!!.repeatAutomationExplicitlyAllowed)
        assertFalse(matching.buttonMeaningConfirmed)
        val decision = DenyAllActionPolicy().decide(
            fixture.analysis,
            DecisionContext(fixture.snapshot.token, choice, matching, false, true),
        )
        assertEquals(ActionAuthorization.DENY, decision.authorization)
    }

    @Test fun replacingAndRevokingAChoiceCannotRestoreAnOlderPermission() = runBlocking {
        val fixture = MockPopupFixtures.notice()
        val key = FixtureOnlyFingerprintProvider().createKey(fixture.snapshot, fixture.analysis.closeCandidates.single(), 1)
        val repository = InMemoryMockPopupRuleRepository()
        repository.save(rule(key))
        repository.save(
            rule(key).copy(
                id = "new-choice",
                choice = ChoiceKind.STOP_ASKING,
                repeatAutomationExplicitlyAllowed = false,
            ),
        )
        assertEquals(1, repository.observeRules().first().size)
        assertEquals(ChoiceKind.STOP_ASKING, repository.findExact(key, 101)?.choice)
        repository.revoke("new-choice")
        assertNull(repository.findExact(key, 101))
    }

    @Test(expected = IllegalArgumentException::class)
    fun stopAskingCannotSmuggleAnAutomationPermission() {
        UserChoice(
            MockPopupFixtures.notice().snapshot.token,
            ChoiceKind.STOP_ASKING,
            candidateId = "fixture-button",
            buttonMeaningConfirmed = true,
            repeatAutomationExplicitlyAllowed = true,
        )
    }

    @Test fun evenExplicitPermissionAndAllowOnceNeverMakeMocksExecute() = runBlocking {
        val fixture = MockPopupFixtures.advertisement()
        val candidate = fixture.analysis.closeCandidates.single()
        val choice = UserChoice(
            fixture.snapshot.token,
            ChoiceKind.REMEMBER_EXACT_POPUP,
            candidate.id,
            buttonMeaningConfirmed = true,
            repeatAutomationExplicitlyAllowed = true,
        )
        val repository = InMemoryMockPopupRuleRepository()
        val remembered = IntegrationExample.saveExplicitChoiceExample(fixture, choice, repository, 100)
        val decision = DenyAllActionPolicy().decide(
            fixture.analysis,
            DecisionContext(fixture.snapshot.token, choice, remembered, false, true),
        )
        assertEquals(ActionAuthorization.DENY, decision.authorization)
        val externallyAllowed = ActionDecision(
            fixture.snapshot.token,
            ActionAuthorization.ALLOW_ONCE,
            persistentSetOf(),
            candidate.id,
        )
        val result = NeverExecuteActionExecutor().execute(ActionRequest(externallyAllowed, candidate))
        assertEquals(ActionStatus.NOT_EXECUTED, result.status)
        assertEquals(SafetyReason.NOT_IMPLEMENTED, result.reason)
        assertFalse(result.attempted)
        assertFalse(result.semanticSafetyConfirmedByUser)
    }

    @Test fun videoAndFixtureAnswersCannotMasqueradeAsAnImplementedClassifier() = runBlocking {
        val classifier = MockPopupClassifier()
        for (fixture in MockPopupFixtures.all()) {
            assertEquals(PopupClassification.UNKNOWN, classifier.classify(fixture.analysis))
        }
        val video = MockPopupFixtures.videoWithoutAdEvidence()
        assertTrue(video.analysis.videoSurfaceObserved)
        assertEquals(PopupClassification.UNKNOWN, video.expectedDisplayClassification)
        assertTrue(MockPopupFixtures.unlabeledButton().analysis.closeCandidates.isEmpty())
    }

    @Test fun mocksCannotClaimSpeechOrCaptureSupport() = runBlocking {
        val fixture = MockPopupFixtures.notice()
        val announcer = MockPopupAnnouncer()
        val receipt = announcer.announce(AnnouncementRequest(fixture.snapshot.token, AnnouncementKind.CONTENT, "시험 안내"))
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, receipt.state)
        assertEquals(AnnouncementState.NOT_IMPLEMENTED, announcer.repeatLast().state)
        assertEquals(VoiceAvailability.NOT_IMPLEMENTED, MockVoiceCommandInput().startFromSpeakButton(fixture.snapshot.token))
        assertEquals(CaptureAvailability.NOT_IMPLEMENTED, MockUnavailableCaptureCapability().availability(fixture.snapshot.token))
    }

    @Test fun privateTextIsRedactedInPrintableDtos() = runBlocking {
        val fixture = MockPopupFixtures.notice()
        val body = fixture.snapshot.nodes.first().text!!
        val key = FixtureOnlyFingerprintProvider().createKey(fixture.snapshot, fixture.analysis.closeCandidates.single(), 1)
        val printed = listOf(
            fixture.toString(), fixture.snapshot.toString(), fixture.snapshot.token.toString(),
            fixture.snapshot.nodes.first().toString(), fixture.analysis.toString(),
            fixture.analysis.texts.first().toString(), fixture.analysis.closeCandidates.single().toString(),
            AnnouncementRequest(fixture.snapshot.token, AnnouncementKind.CONTENT, body).toString(),
            key.toString(), rule(key).toString(),
        ).joinToString()
        assertFalse(printed.contains(body))
        assertFalse(printed.contains(MockPopupFixtures.PACKAGE_NAME))
        assertFalse(printed.contains(key.exactContentFingerprint))
        assertFalse(printed.contains("2026년 10월 18일"))
    }

    @Test(expected = IllegalStateException::class)
    fun fixtureFingerprintProviderRejectsModifiedScreens() = runBlocking {
        val fixture = MockPopupFixtures.notice()
        val changedNode = fixture.snapshot.nodes.first().copy(text = "새로운 본문")
        val changed = fixture.snapshot.copy(nodes = fixture.snapshot.nodes.toPersistentList().set(0, changedNode))
        FixtureOnlyFingerprintProvider().createKey(changed, fixture.analysis.closeCandidates.single(), 1)
        Unit
    }

    private fun rule(key: RuleKey, expiresAt: Long? = null) = PopupRule(
        id = "test-rule",
        key = key,
        choice = ChoiceKind.REMEMBER_EXACT_POPUP,
        buttonMeaningConfirmed = true,
        repeatAutomationExplicitlyAllowed = true,
        createdAtEpochMillis = 100,
        expiresAtEpochMillis = expiresAt,
    )
}
