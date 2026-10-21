package kr.closeforyou.data.rules

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 Room SQL 검사. 메모리 DB만 사용하며 기기 저장소에 파일을 남기지 않는다. */
@RunWith(AndroidJUnit4::class)
class PopupRuleDatabaseTest {
    private lateinit var database: CloseForYouDatabase
    private lateinit var repository: RoomPopupRuleRepository
    private val key = RuleKey("com.example.test", 1, "s".repeat(64), "c".repeat(64), "t".repeat(64))

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, CloseForYouDatabase::class.java).build()
        repository = RoomPopupRuleRepository(database.popupRuleDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun exactKeyExpiryRevocationAndFailure() = runBlocking {
        repository.save(rule(expiresAt = 200))
        assertNull(repository.findExact(key, 99))
        assertNotNull(repository.findExact(key, 150))
        assertNull(repository.findExact(key, 200))
        assertNull(repository.findExact(key.copy(exactContentFingerprint = "x".repeat(64)), 150))
        assertNull(repository.findExact(key.copy(appVersionCode = 2), 150))

        assertTrue(repository.recordFailure("rule-1"))
        assertNull(repository.findExact(key, 150))

        repository.save(rule())
        repository.revoke("rule-1")
        assertNull(repository.findExact(key, 150))
        assertFalse(repository.observeRules().first().single().enabled)
    }

    @Test
    fun replaceKeepsOnlyTheNewestChoiceForAKey() = runBlocking {
        repository.save(rule(id = "old"))
        repository.save(rule(id = "new", choice = ChoiceKind.STOP_ASKING))
        assertEquals(listOf("new"), repository.observeRules().first().map { it.id })
        repository.revoke("new")
        assertNull(repository.findExact(key, 150))
    }

    @Test
    fun rulesBreakingTeamPolicyAreNotStored() = runBlocking {
        listOf(rule(expiresAt = null), rule(expiresAt = RuleRetention.defaultExpiry(100) + 1)).forEach {
            try {
                repository.save(it)
            } catch (expected: RuleRejectedException) {
                // 저장 거부
            }
        }
        assertTrue(repository.observeRules().first().isEmpty())
    }

    @Test
    fun purgeAndDeleteAll() = runBlocking {
        repository.save(rule(id = "a", expiresAt = 200))
        repository.save(rule(id = "b").copy(key = key.copy(targetFingerprint = "u".repeat(64))))
        assertEquals(1, repository.purgeExpired(200))
        repository.deleteAll()
        assertTrue(repository.observeRules().first().isEmpty())
    }

    /** 정리: 만료·만료일 없음·손상 행은 지우고, 해제·실패 규칙은 만료 전까지 남긴다. */
    @Test
    fun cleanUpRemovesExpiredAndUnreadableRowsOnly() = runBlocking {
        val dao = database.popupRuleDao()
        dao.replace(row("valid", expiresAt = 300))
        dao.replace(row("expired", expiresAt = 200))
        dao.replace(row("no-expiry", expiresAt = null))
        dao.replace(row("bad-choice", expiresAt = 300).copy(choice = "NOT_A_CHOICE"))
        dao.replace(row("revoked", expiresAt = 300).copy(enabled = false))
        dao.replace(row("failed", expiresAt = 300).copy(failureCount = 1))

        assertEquals(RuleCleanUpResult(expired = 2, unreadable = 1), repository.cleanUp(200))
        assertEquals(
            listOf("failed", "revoked", "valid"),
            repository.observeRules().first().map { it.id }.sorted(),
        )
        assertEquals(RuleCleanUpResult(expired = 3, unreadable = 0), repository.cleanUp(300))
        assertTrue(repository.observeRules().first().isEmpty())
    }

    private fun row(id: String, expiresAt: Long?) =
        PopupRuleEntity.from(rule(id = id).copy(key = key.copy(targetFingerprint = id.padEnd(64, '-'))))
            .copy(expiresAtEpochMillis = expiresAt)

    private fun rule(
        id: String = "rule-1",
        choice: ChoiceKind = ChoiceKind.REMEMBER_EXACT_POPUP,
        expiresAt: Long? = RuleRetention.defaultExpiry(100),
    ) = PopupRule(
        id = id,
        key = key,
        choice = choice,
        buttonMeaningConfirmed = choice == ChoiceKind.REMEMBER_EXACT_POPUP,
        repeatAutomationExplicitlyAllowed = false,
        createdAtEpochMillis = 100,
        expiresAtEpochMillis = expiresAt,
    )
}
