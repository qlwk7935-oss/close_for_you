package kr.closeforyou.ui.voice.integration

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.data.rules.RuleRetention
import kr.closeforyou.testing.mock.FixtureOnlyFingerprintProvider
import kr.closeforyou.testing.mock.InMemoryMockPopupRuleRepository
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RememberedPopupStoreTest {
    private val fixture = MockPopupFixtures.advertisement()
    private val repository = InMemoryMockPopupRuleRepository()
    private var now = 5_000L
    private val store = RepositoryRememberedPopupStore(
        fingerprints = FixtureOnlyFingerprintProvider(),
        repository = repository,
        appVersionCode = { 1L },
        nowEpochMillis = { now },
        newId = { "rule-1" },
    )

    private fun remember(token: SnapshotToken = fixture.snapshot.token) = UserChoice(
        token = token,
        kind = ChoiceKind.REMEMBER_EXACT_POPUP,
        candidateId = fixture.analysis.closeCandidates.single().id,
        buttonMeaningConfirmed = true,
        repeatAutomationExplicitlyAllowed = true,
    )

    @Test fun sameThirtyDaysAsD() {
        // C keeps its own constant (D's RuleRetention is D's class, not a shared contract).
        assertEquals(RuleRetention.DEFAULT_TTL_MILLIS, RememberRetention.TTL_MILLIS)
        assertEquals(RuleRetention.defaultExpiry(123L), RememberRetention.expiryFor(123L))
    }

    @Test fun committedRuleExpiresThirtyDaysAfterCreation() = runTest {
        val prepared = requireNotNull(store.prepare(remember(), fixture.snapshot, fixture.analysis))
        now = 9_000L
        assertTrue(store.commit(prepared))
        val rule = repository.observeRules().first().single()
        assertEquals(9_000L, rule.createdAtEpochMillis)
        assertEquals(9_000L + 30L * 24 * 60 * 60 * 1000, rule.expiresAtEpochMillis)
        assertTrue(rule.buttonMeaningConfirmed && rule.repeatAutomationExplicitlyAllowed)
    }

    @Test fun prepareSavesNothing() = runTest {
        store.prepare(remember(), fixture.snapshot, fixture.analysis)
        assertTrue(repository.observeRules().first().isEmpty())
    }

    @Test fun prepareRefusesOtherChoicesAndOtherTokens() = runTest {
        val once = remember().copy(kind = ChoiceKind.CLOSE_ONCE, repeatAutomationExplicitlyAllowed = false)
        assertNull(store.prepare(once, fixture.snapshot, fixture.analysis))
        val other = fixture.snapshot.token.copy(screenVersion = fixture.snapshot.token.screenVersion + 1)
        assertNull(store.prepare(remember(other), fixture.snapshot, fixture.analysis))
        assertNull(store.prepare(remember().copy(candidateId = "missing"), fixture.snapshot, fixture.analysis))
    }

    @Test fun keyFailureIsNullNotThrown() = runTest {
        // FixtureOnlyFingerprintProvider rejects version 0, like D's INVALID_APP_VERSION.
        val badVersion = RepositoryRememberedPopupStore(FixtureOnlyFingerprintProvider(), repository, { 0L })
        assertNull(badVersion.prepare(remember(), fixture.snapshot, fixture.analysis))
    }

    @Test fun noneStoreRemembersNothing() = runTest {
        assertNull(RememberedPopupStore.NONE.prepare(remember(), fixture.snapshot, fixture.analysis))
        val prepared = requireNotNull(store.prepare(remember(), fixture.snapshot, fixture.analysis))
        assertFalse(RememberedPopupStore.NONE.commit(prepared))
    }
}
