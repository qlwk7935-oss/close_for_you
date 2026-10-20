package kr.closeforyou.data.rules

import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 저장소 로직(매핑·이중 확인·교체·철회) 단위 시험. 실제 SQL은 androidTest의
 * PopupRuleDatabaseTest가 같은 시나리오로 검사한다.
 */
class RoomPopupRuleRepositoryTest {
    private val key = RuleKey("com.example.test", 1, "s".repeat(64), "c".repeat(64), "t".repeat(64))

    @Test
    fun savedRuleIsFoundOnlyByTheExactKey() = runBlocking {
        val repository = RoomPopupRuleRepository(FakePopupRuleDao())
        repository.save(rule())
        assertNotNull(repository.findExact(key, 150))
        assertNull(repository.findExact(key.copy(appVersionCode = 2), 150))
        assertNull(repository.findExact(key.copy(exactContentFingerprint = "x".repeat(64)), 150))
        assertNull(repository.findExact(key.copy(structureFingerprint = "x".repeat(64)), 150))
        assertNull(repository.findExact(key.copy(targetFingerprint = "x".repeat(64)), 150))
        assertNull(repository.findExact(key.copy(packageName = "com.example.other"), 150))
    }

    @Test
    fun expiryAndFutureRecordsFailClosed() = runBlocking {
        val repository = RoomPopupRuleRepository(FakePopupRuleDao())
        repository.save(rule(expiresAt = 200))
        assertNull(repository.findExact(key, 99))
        assertNotNull(repository.findExact(key, 100))
        assertNotNull(repository.findExact(key, 199))
        assertNull(repository.findExact(key, 200))
    }

    @Test
    fun revokedRuleNoLongerMatchesButStaysVisibleAsDisabled() = runBlocking {
        val repository = RoomPopupRuleRepository(FakePopupRuleDao())
        repository.save(rule())
        repository.revoke("rule-1")
        assertNull(repository.findExact(key, 150))
        assertFalse(repository.observeRules().first().single().enabled)
    }

    @Test
    fun replacingAChoiceCannotRestoreAnOlderPermission() = runBlocking {
        val repository = RoomPopupRuleRepository(FakePopupRuleDao())
        repository.save(rule(id = "old", repeat = true))
        repository.save(rule(id = "new", choice = ChoiceKind.STOP_ASKING, repeat = false))
        assertEquals(listOf("new"), repository.observeRules().first().map { it.id })
        repository.revoke("new")
        assertNull(repository.findExact(key, 150))
    }

    @Test
    fun failureDisablesMatching() = runBlocking {
        val repository = RoomPopupRuleRepository(FakePopupRuleDao())
        repository.save(rule())
        assertTrue(repository.recordFailure("rule-1"))
        assertNull(repository.findExact(key, 150))
        assertFalse(repository.recordFailure("missing"))
    }

    @Test
    fun deleteAllAndPurgeRemoveRows() = runBlocking {
        val repository = RoomPopupRuleRepository(FakePopupRuleDao())
        repository.save(rule(id = "a", expiresAt = 200))
        repository.save(rule(id = "b", target = "u".repeat(64)))
        assertEquals(1, repository.purgeExpired(200))
        assertEquals(listOf("b"), repository.observeRules().first().map { it.id })
        repository.deleteAll()
        assertTrue(repository.observeRules().first().isEmpty())
    }

    @Test
    fun corruptedOrOtherSchemaRowsAreIgnored() = runBlocking {
        val dao = FakePopupRuleDao()
        val repository = RoomPopupRuleRepository(dao)
        dao.rows.value = listOf(
            PopupRuleEntity.from(rule(id = "bad")).copy(choice = "NOT_A_CHOICE"),
            PopupRuleEntity.from(rule(id = "future", target = "u".repeat(64))).copy(schemaVersion = 2),
        )
        assertNull(repository.findExact(key, 150))
        assertNull(repository.findExact(key.copy(targetFingerprint = "u".repeat(64)), 150))
        assertEquals(listOf("future"), repository.observeRules().first().map { it.id })
    }

    @Test
    fun entityRoundTripKeepsEveryField() {
        val original = rule(expiresAt = 500, repeat = true).copy(failureCount = 0, enabled = true)
        assertEquals(original, PopupRuleEntity.from(original).toRuleOrNull())
        assertTrue("redacted" in PopupRuleEntity.from(original).toString())
    }

    private fun rule(
        id: String = "rule-1",
        choice: ChoiceKind = ChoiceKind.REMEMBER_EXACT_POPUP,
        repeat: Boolean = true,
        expiresAt: Long? = null,
        target: String = key.targetFingerprint,
    ) = PopupRule(
        id = id,
        key = key.copy(targetFingerprint = target),
        choice = choice,
        buttonMeaningConfirmed = choice == ChoiceKind.REMEMBER_EXACT_POPUP,
        repeatAutomationExplicitlyAllowed = repeat && choice == ChoiceKind.REMEMBER_EXACT_POPUP,
        createdAtEpochMillis = 100,
        expiresAtEpochMillis = expiresAt,
    )
}

/** 메모리에서 Room DAO의 조회 조건을 흉내 낸다. 시험 전용. */
internal class FakePopupRuleDao : PopupRuleDao() {
    val rows = MutableStateFlow<List<PopupRuleEntity>>(emptyList())

    override suspend fun findUsable(
        packageName: String,
        appVersionCode: Long,
        structureFingerprint: String,
        contentFingerprint: String,
        targetFingerprint: String,
        schemaVersion: Int,
        now: Long,
    ): List<PopupRuleEntity> = rows.value.filter {
        it.packageName == packageName && it.appVersionCode == appVersionCode &&
            it.structureFingerprint == structureFingerprint && it.contentFingerprint == contentFingerprint &&
            it.targetFingerprint == targetFingerprint && it.enabled && it.failureCount == 0 &&
            it.schemaVersion == schemaVersion && it.createdAtEpochMillis <= now &&
            (it.expiresAtEpochMillis ?: Long.MAX_VALUE) > now
    }.sortedByDescending { it.createdAtEpochMillis }

    override fun observeAll(): Flow<List<PopupRuleEntity>> =
        rows.map { list -> list.sortedWith(compareByDescending<PopupRuleEntity> { it.createdAtEpochMillis }.thenBy { it.id }) }

    override suspend fun insert(entity: PopupRuleEntity) {
        check(rows.value.none { it.id == entity.id || sameKey(it, entity) }) { "unique constraint" }
        rows.value = rows.value + entity
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteByKey(
        packageName: String,
        appVersionCode: Long,
        structureFingerprint: String,
        contentFingerprint: String,
        targetFingerprint: String,
    ) {
        rows.value = rows.value.filterNot {
            it.packageName == packageName && it.appVersionCode == appVersionCode &&
                it.structureFingerprint == structureFingerprint && it.contentFingerprint == contentFingerprint &&
                it.targetFingerprint == targetFingerprint
        }
    }

    override suspend fun disable(id: String): Int = update(id) { it.copy(enabled = false) }

    override suspend fun incrementFailure(id: String): Int = update(id) { it.copy(failureCount = it.failureCount + 1) }

    override suspend fun deleteExpired(now: Long): Int {
        val before = rows.value.size
        rows.value = rows.value.filterNot { row -> row.expiresAtEpochMillis.let { it != null && it <= now } }
        return before - rows.value.size
    }

    override suspend fun deleteAll() {
        rows.value = emptyList()
    }

    private fun update(id: String, change: (PopupRuleEntity) -> PopupRuleEntity): Int {
        var count = 0
        rows.value = rows.value.map { if (it.id == id) { count++; change(it) } else it }
        return count
    }

    private fun sameKey(a: PopupRuleEntity, b: PopupRuleEntity) =
        a.packageName == b.packageName && a.appVersionCode == b.appVersionCode &&
            a.structureFingerprint == b.structureFingerprint && a.contentFingerprint == b.contentFingerprint &&
            a.targetFingerprint == b.targetFingerprint
}
