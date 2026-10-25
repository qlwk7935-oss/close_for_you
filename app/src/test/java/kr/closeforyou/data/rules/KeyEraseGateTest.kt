package kr.closeforyou.data.rules

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.EvidenceLevel
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.PopupTrigger
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.SnapshotToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 전체 삭제와 지문 생성·저장 경쟁(C #34 리뷰 10/24, A #40 리뷰 10/25).
 * 지문 생성은 지연을 조절할 수 있는 [ControlledProvider]로, 저장은 FakePopupRuleDao로 확인한다. 합성 값만 쓴다.
 */
class KeyEraseGateTest {
    private val gate = KeyEraseGate()
    private val dao = FakePopupRuleDao()
    private val repository = RoomPopupRuleRepository(dao, gate)
    private val fake = ControlledProvider()
    private val provider = KeyRecordingFingerprintProvider(fake, gate)

    @Test
    fun beforeAnyEraseEveryKeySavesAsBefore() = runBlocking {
        repository.save(rule(key("unknown")))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun keyMadeBeforeEraseIsRejectedAfterIt() = runBlocking {
        val key = create(key("old"))
        gate.erase { }
        assertRejected { repository.save(rule(key)) }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun keyMadeAfterEraseSaves() = runBlocking {
        gate.erase { }
        val key = create(key("new"))
        repository.save(rule(key))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun unknownKeyAfterEraseIsRejected() = runBlocking {
        gate.erase { }
        assertRejected { repository.save(rule(key("never-recorded"))) }
        assertTrue(dao.rows.value.isEmpty())
    }

    /**
     * A #40 리뷰의 순서: (1) 삭제가 잠금을 잡고 세대를 올린 뒤 DB 삭제 중, (2) 아직 Keystore 키를 폐기하기 전에
     * createKey가 시작·종료(옛 키 지문), (3) 삭제가 키를 폐기하고 끝남, (4) 저장 → 거부되어야 한다.
     */
    @Test
    fun createKeyStartedAfterEraseBeganButBeforeKeyDestroyedIsRejected() = runBlocking {
        val rowsDeleted = CompletableDeferred<Unit>()
        val destroyKey = CompletableDeferred<Unit>()
        var keyDestroyed = false
        val erase = launch {
            gate.erase {
                dao.deleteAll()
                rowsDeleted.complete(Unit)
                destroyKey.await()
                keyDestroyed = true // AndroidKeystoreHasher.deleteKey() 자리
            }
        }
        rowsDeleted.await()
        assertFalse(keyDestroyed)
        val key = create(key("made-with-old-keystore-key"))

        destroyKey.complete(Unit)
        erase.join()
        assertTrue(keyDestroyed)
        assertRejected { repository.save(rule(key)) }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun createKeyStartedBeforeEraseAndFinishedDuringItIsRejected() = runBlocking {
        fake.hold()
        val creation = async { provider.createKey(SNAPSHOT, TARGET, 1) }
        fake.nextKey = key("overlapping")
        fake.started.await()

        val inside = CompletableDeferred<Unit>()
        val finishErase = CompletableDeferred<Unit>()
        val erase = launch {
            gate.erase {
                inside.complete(Unit)
                finishErase.await()
            }
        }
        inside.await()
        fake.release()
        val key = creation.await() // 삭제 진행 중에 끝남

        finishErase.complete(Unit)
        erase.join()
        assertRejected { repository.save(rule(key)) }
    }

    @Test
    fun createKeyStartedBeforeEraseAndFinishedAfterItIsRejected() = runBlocking {
        fake.hold()
        fake.nextKey = key("spanning")
        val creation = async { provider.createKey(SNAPSHOT, TARGET, 1) }
        fake.started.await()
        gate.erase { }
        fake.release()
        val key = creation.await()
        assertRejected { repository.save(rule(key)) }
    }

    @Test
    fun saveWaitsForARunningEraseAndIsThenRejected() = runBlocking {
        val key = create(key("racing"))
        val eraseStarted = CompletableDeferred<Unit>()
        val releaseErase = CompletableDeferred<Unit>()
        val erase = launch {
            gate.erase {
                eraseStarted.complete(Unit)
                releaseErase.await()
            }
        }
        eraseStarted.await()
        val save = async { runCatching { repository.save(rule(key)) } }
        repeat(5) { yield() }
        assertFalse("save must wait while erase runs", save.isCompleted)
        assertTrue(dao.rows.value.isEmpty())

        releaseErase.complete(Unit)
        erase.join()
        val failure = save.await().exceptionOrNull()
        assertTrue(failure is RuleRejectedException)
        assertEquals(RuleRejection.KEY_FROM_BEFORE_ERASE, (failure as RuleRejectedException).reason)
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun failedEraseRetiresOldKeysAndNewKeysStillSave() = runBlocking {
        val old = create(key("old"))
        try {
            gate.erase { throw IllegalStateException("storage") }
            fail("erase failure should reach the caller")
        } catch (expected: IllegalStateException) {
            // C shows "삭제하지 못했어요".
        }
        assertRejected { repository.save(rule(old)) }

        val fresh = create(key("after-failed-erase"))
        repository.save(rule(fresh))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun cancelledEraseRetiresOldKeysAndReleasesProgress() = runBlocking {
        val old = create(key("old"))
        val inside = CompletableDeferred<Unit>()
        val erase = launch {
            gate.erase {
                inside.complete(Unit)
                awaitCancellation()
            }
        }
        inside.await()
        erase.cancel()
        erase.join()
        assertRejected { repository.save(rule(old)) }

        val fresh = create(key("after-cancelled-erase"))
        repository.save(rule(fresh))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun failedOrCancelledCreateKeyRecordsNothing() = runBlocking {
        gate.erase { }
        fake.hold()
        fake.nextKey = key("cancelled")
        val creation = launch { provider.createKey(SNAPSHOT, TARGET, 1) }
        fake.started.await()
        creation.cancel()
        creation.join()
        assertRejected { repository.save(rule(key("cancelled"))) }

        fake.release()
        fake.failWith = IllegalStateException("fingerprint unavailable")
        try {
            provider.createKey(SNAPSHOT, TARGET, 1)
            fail("delegate failure should reach the caller")
        } catch (expected: IllegalStateException) {
            // FingerprintUnavailableException 자리. 기록 없음.
        }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun forgottenKeysAfterEraseAreRejectedNotTrusted() = runBlocking {
        gate.erase { }
        val first = create(key("first"))
        (1..KeyEraseGate.MAX_TRACKED_KEYS).forEach { create(key("k$it")) }
        assertRejected { repository.save(rule(first)) }
    }

    @Test
    fun repositoryWithoutGateKeepsOldBehaviour() = runBlocking {
        val plain = RoomPopupRuleRepository(dao)
        gate.erase { }
        plain.save(rule(key("any")))
        assertEquals(1, dao.rows.value.size)
    }

    private suspend fun create(key: RuleKey): RuleKey {
        fake.nextKey = key
        return provider.createKey(SNAPSHOT, TARGET, 1)
    }

    private suspend fun assertRejected(block: suspend () -> Unit) {
        try {
            block()
            fail("expected KEY_FROM_BEFORE_ERASE")
        } catch (e: RuleRejectedException) {
            assertEquals(RuleRejection.KEY_FROM_BEFORE_ERASE, e.reason)
        }
    }

    private fun key(target: String) =
        RuleKey("com.example.test", 1, "s".repeat(64), "c".repeat(64), target.padEnd(64, '-'))

    private fun rule(key: RuleKey) = PopupRule(
        id = "rule-${key.targetFingerprint.trimEnd('-')}",
        key = key,
        choice = ChoiceKind.REMEMBER_EXACT_POPUP,
        buttonMeaningConfirmed = true,
        repeatAutomationExplicitlyAllowed = true,
        createdAtEpochMillis = 100,
        expiresAtEpochMillis = RuleRetention.defaultExpiry(100),
    )

    /** 지문 계산 시간을 시험이 정한다. [hold] 뒤에는 [release]까지 끝나지 않는다. */
    private class ControlledProvider : PopupFingerprintProvider {
        var nextKey: RuleKey? = null
        var failWith: Exception? = null
        var started = CompletableDeferred<Unit>()
        private var gateToFinish: CompletableDeferred<Unit>? = null

        fun hold() {
            started = CompletableDeferred()
            gateToFinish = CompletableDeferred()
        }

        fun release() {
            gateToFinish?.complete(Unit)
        }

        override suspend fun createKey(snapshot: PopupSnapshot, target: CloseCandidate, appVersionCode: Long): RuleKey {
            started.complete(Unit)
            gateToFinish?.await()
            failWith?.let { throw it }
            return nextKey ?: error("no key")
        }
    }

    private companion object {
        val SNAPSHOT = PopupSnapshot(
            token = SnapshotToken(1, 2, "com.example.test", "popup", "s", "c", 1),
            capturedAtUptimeMillis = 1_000,
            nodes = persistentListOf(),
            windows = persistentListOf(),
            screenBounds = ScreenBounds(0, 0, 1080, 2400),
            trigger = PopupTrigger.WINDOW_CHANGED,
            completeness = SnapshotCompleteness.COMPLETE,
        )
        val TARGET = CloseCandidate(
            id = "close", nodePath = "0/1", viewId = "btn_close", label = "닫기",
            bounds = ScreenBounds(0, 0, 10, 10), evidence = EvidenceLevel.E2_LABELED_NODE, role = ButtonRole.CLOSE,
        )
    }
}
