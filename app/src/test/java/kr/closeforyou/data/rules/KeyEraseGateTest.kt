package kr.closeforyou.data.rules

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 전체 삭제와 저장 경쟁(C #34 리뷰 10/24). 저장소는 FakePopupRuleDao로 확인한다. */
class KeyEraseGateTest {
    private val gate = KeyEraseGate()
    private val dao = FakePopupRuleDao()
    private val repository = RoomPopupRuleRepository(dao, gate)

    @Test
    fun beforeAnyEraseEveryKeySavesAsBefore() = runBlocking {
        repository.save(rule(key("unknown")))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun keyMadeBeforeEraseIsRejectedAfterIt() = runBlocking {
        val key = key("old")
        gate.keyCreated(key, gate.generation())
        gate.erase { }
        assertRejected { repository.save(rule(key)) }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun keyMadeAfterEraseSaves() = runBlocking {
        gate.erase { }
        val key = key("new")
        gate.keyCreated(key, gate.generation())
        repository.save(rule(key))
        assertEquals(1, dao.rows.value.size)
    }

    @Test
    fun unknownKeyAfterEraseIsRejected() = runBlocking {
        gate.erase { }
        assertRejected { repository.save(rule(key("never-recorded"))) }
        assertTrue(dao.rows.value.isEmpty())
    }

    /** 지문을 만드는 도중 삭제가 끼면, 다 만든 키는 시작 시점(옛 세대)으로 기록되어 거부된다. */
    @Test
    fun keyStartedBeforeEraseButFinishedAfterIsRejected() = runBlocking {
        val startedAt = gate.generation()
        gate.erase { }
        val key = key("mid")
        gate.keyCreated(key, startedAt)
        assertRejected { repository.save(rule(key)) }
    }

    @Test
    fun saveWaitsForARunningEraseAndIsThenRejected() = runBlocking {
        val key = key("racing")
        gate.keyCreated(key, gate.generation())
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
    fun failedEraseStillRetiresOldKeys() = runBlocking {
        val key = key("old")
        gate.keyCreated(key, gate.generation())
        try {
            gate.erase { throw IllegalStateException("storage") }
            fail("erase failure should reach the caller")
        } catch (expected: IllegalStateException) {
            // C shows "삭제하지 못했어요"; the old key stays retired.
        }
        assertRejected { repository.save(rule(key)) }
    }

    @Test
    fun forgottenKeysAfterEraseAreRejectedNotTrusted() = runBlocking {
        gate.erase { }
        val first = key("first")
        gate.keyCreated(first, gate.generation())
        (1..KeyEraseGate.MAX_TRACKED_KEYS).forEach { gate.keyCreated(key("k$it"), gate.generation()) }
        assertRejected { repository.save(rule(first)) }
    }

    @Test
    fun repositoryWithoutGateKeepsOldBehaviour() = runBlocking {
        val plain = RoomPopupRuleRepository(dao)
        gate.erase { }
        plain.save(rule(key("any")))
        assertEquals(1, dao.rows.value.size)
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
}
