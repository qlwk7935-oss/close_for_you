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
    fun purgeAndDeleteAll() = runBlocking {
        repository.save(rule(id = "a", expiresAt = 200))
        repository.save(rule(id = "b").copy(key = key.copy(targetFingerprint = "u".repeat(64))))
        assertEquals(1, repository.purgeExpired(200))
        repository.deleteAll()
        assertTrue(repository.observeRules().first().isEmpty())
    }

    private fun rule(
        id: String = "rule-1",
        choice: ChoiceKind = ChoiceKind.REMEMBER_EXACT_POPUP,
        expiresAt: Long? = null,
    ) = PopupRule(
        id = id,
        key = key,
        choice = choice,
        buttonMeaningConfirmed = choice == ChoiceKind.REMEMBER_EXACT_POPUP,
        repeatAutomationExplicitlyAllowed = choice == ChoiceKind.REMEMBER_EXACT_POPUP,
        createdAtEpochMillis = 100,
        expiresAtEpochMillis = expiresAt,
    )
}
