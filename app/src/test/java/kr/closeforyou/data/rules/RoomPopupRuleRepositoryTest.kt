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
import org.junit.Assert.fail
import org.junit.Test

/**
 * 저장소 로직(매핑·이중 확인·교체·철회·정리) 단위 시험. 실제 SQL은 androidTest의
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
        repository.save(rule(id = "old"))
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
    fun ruleWithoutExpiryIsRejected() {
        assertRejected(RuleRejection.MISSING_EXPIRY) { it.save(rule(expiresAt = null)) }
    }

    @Test
    fun expiryLongerThanThirtyDaysIsRejected() {
        assertRejected(RuleRejection.EXPIRY_TOO_LONG) {
            it.save(rule(expiresAt = RuleRetention.defaultExpiry(100) + 1))
        }
    }

    @Test
    fun defaultExpiryIsThirtyDaysAndAccepted() = runBlocking {
        assertEquals(100L + 30L * 24 * 60 * 60 * 1000, RuleRetention.defaultExpiry(100))
        val repository = RoomPopupRuleRepository(FakePopupRuleDao())
        repository.save(rule(expiresAt = RuleRetention.defaultExpiry(100)))
        assertNotNull(repository.findExact(key, RuleRetention.defaultExpiry(100) - 1))
        assertNull(repository.findExact(key, RuleRetention.defaultExpiry(100)))
    }

    /** 팀 합의: 사용자 반복 허락은 저장하고, 자동 반복 실행만 OFF로 둔다. */
    @Test
    fun repeatPermissionIsStoredWhileExecutionStaysOff() = runBlocking {
        val repository = RoomPopupRuleRepository(FakePopupRuleDao())
        repository.save(rule(repeat = true))
        assertTrue(repository.findExact(key, 150)!!.repeatAutomationExplicitlyAllowed)
        assertFalse(RepeatAutomation.EXECUTION_ENABLED)
    }

    @Test
    fun rejectedRuleIsNotStored() = runBlocking {
        val dao = FakePopupRuleDao()
        try {
            RoomPopupRuleRepository(dao).save(rule(expiresAt = null))
        } catch (expected: RuleRejectedException) {
            // 저장 거부
        }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun storedRowWithoutExpiryNeverMatches() = runBlocking {
        val dao = FakePopupRuleDao()
        dao.rows.value = listOf(PopupRuleEntity.from(rule(expiresAt = null)))
        assertNull(RoomPopupRuleRepository(dao).findExact(key, 150))
    }

    @Test
    fun entityRoundTripKeepsEveryField() {
        val original = rule(expiresAt = 500, repeat = true).copy(failureCount = 0, enabled = true)
        assertEquals(original, PopupRuleEntity.from(original).toRuleOrNull())
        assertTrue("redacted" in PopupRuleEntity.from(original).toString())
    }

    // ---- 정리(cleanUp), 팀 제안 10/22: A 서비스 시작·C 설정 화면 열 때 호출 ----

    @Test
    fun cleanUpRemovesExpiredMissingExpiryAndUnreadableRows() = runBlocking {
        val dao = FakePopupRuleDao()
        dao.rows.value = listOf(
            row("valid", expiresAt = 300),
            row("expired", expiresAt = 200),
            row("no-expiry", expiresAt = null),
            row("bad-choice", expiresAt = 300).copy(choice = "NOT_A_CHOICE"),
        )
        val result = RoomPopupRuleRepository(dao).cleanUp(200)
        assertEquals(RuleCleanUpResult(expired = 2, unreadable = 1), result)
        assertEquals(3, result.total)
        assertEquals(listOf("valid"), dao.rows.value.map { it.id })
    }

    @Test
    fun cleanUpKeepsRevokedFailedAndOtherSchemaRowsUntilExpiry() = runBlocking {
        val dao = FakePopupRuleDao()
        dao.rows.value = listOf(
            row("revoked", expiresAt = 300).copy(enabled = false),
            row("failed", expiresAt = 300).copy(failureCount = 1),
            row("future-schema", expiresAt = 300).copy(schemaVersion = 2),
        )
        val repository = RoomPopupRuleRepository(dao)
        assertEquals(RuleCleanUpResult(expired = 0, unreadable = 0), repository.cleanUp(200))
        assertEquals(3, repository.observeRules().first().size)

        // 만료되면 상태와 상관없이 지운다.
        assertEquals(RuleCleanUpResult(expired = 3, unreadable = 0), repository.cleanUp(300))
        assertTrue(dao.rows.value.isEmpty())
    }

    /** C 리뷰 ①: 손상 행만 남으면 목록은 비어 보이지만 행은 남는다. 정리 후에는 실제로도 비어야 한다. */
    @Test
    fun cleanUpMakesAnEmptyListMatchTheStorage() = runBlocking {
        val dao = FakePopupRuleDao()
        dao.rows.value = listOf(row("bad", expiresAt = 300).copy(choice = "NOT_A_CHOICE"))
        val repository = RoomPopupRuleRepository(dao)
        assertTrue(repository.observeRules().first().isEmpty())
        assertEquals(1, dao.rows.value.size)

        assertEquals(1, repository.cleanUp(200).unreadable)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun cleanUpOnAnEmptyStoreDoesNothing() = runBlocking {
        assertEquals(RuleCleanUpResult(0, 0), RoomPopupRuleRepository(FakePopupRuleDao()).cleanUp(200))
    }

    @Test
    fun unreadableRowsAreDeletedInSmallBatches() = runBlocking {
        val dao = FakePopupRuleDao()
        dao.rows.value = (1..1_200).map { row("bad-$it", expiresAt = 300).copy(choice = "NOT_A_CHOICE") }
        assertEquals(1_200, RoomPopupRuleRepository(dao).cleanUp(200).unreadable)
        assertTrue(dao.rows.value.isEmpty())
        assertTrue(dao.largestDeleteBatch in 1..500)
    }

    private fun assertRejected(expected: RuleRejection, block: suspend (RoomPopupRuleRepository) -> Unit) {
        val dao = FakePopupRuleDao()
        try {
            runBlocking { block(RoomPopupRuleRepository(dao)) }
            fail("Expected $expected")
        } catch (e: RuleRejectedException) {
            assertEquals(expected, e.reason)
        }
        assertTrue(dao.rows.value.isEmpty())
    }

    /** 키가 겹치지 않는 행. 정리 시험에서 DAO 행을 직접 넣을 때 쓴다. */
    private fun row(id: String, expiresAt: Long?) =
        PopupRuleEntity.from(rule(id = id, target = id.padEnd(64, '-'))).copy(expiresAtEpochMillis = expiresAt)

    private fun rule(
        id: String = "rule-1",
        choice: ChoiceKind = ChoiceKind.REMEMBER_EXACT_POPUP,
        repeat: Boolean = false,
        expiresAt: Long? = RuleRetention.defaultExpiry(100),
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

    /** [deleteByIds] 한 번에 받은 ID 수의 최댓값. */
    var largestDeleteBatch = 0
        private set

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
            (it.expiresAtEpochMillis ?: Long.MIN_VALUE) > now
    }.sortedByDescending { it.createdAtEpochMillis }

    override fun observeAll(): Flow<List<PopupRuleEntity>> =
        rows.map { list -> list.sortedWith(compareByDescending<PopupRuleEntity> { it.createdAtEpochMillis }.thenBy { it.id }) }

    override suspend fun getAll(): List<PopupRuleEntity> = rows.value

    override suspend fun insert(entity: PopupRuleEntity) {
        check(rows.value.none { it.id == entity.id || sameKey(it, entity) }) { "unique constraint" }
        rows.value = rows.value + entity
    }

    override suspend fun deleteById(id: String) {
        rows.value = rows.value.filterNot { it.id == id }
    }

    override suspend fun deleteByIds(ids: List<String>): Int {
        largestDeleteBatch = maxOf(largestDeleteBatch, ids.size)
        val before = rows.value.size
        val doomed = ids.toSet()
        rows.value = rows.value.filterNot { it.id in doomed }
        return before - rows.value.size
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
        rows.value = rows.value.filterNot { row -> row.expiresAtEpochMillis.let { it == null || it <= now } }
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
