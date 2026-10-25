package kr.closeforyou.data.rules

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.RuleKey

/**
 * D: "기억 전체 삭제"와 규칙 저장이 겹칠 때 옛 지문 키로 만든 규칙이 남지 않게 한다(C #34 리뷰 10/24).
 *
 * 지문은 클릭 전에 만들고 저장은 실행 성공 뒤에 한다(팀 합의 10/21). 그 사이에 [전체 삭제]로 지문 키가
 * 폐기되면, 옛 키로 만든 RuleKey가 삭제 뒤에 저장될 수 있다. 그 규칙은 새 키 지문과 절대 일치하지 않지만
 * 목록에 남아 사용자를 헷갈리게 한다.
 *
 * 규칙(fail closed):
 * - 지문 생성은 시작할 때 표([CreationTicket])를 받고 끝날 때 반납한다. **삭제와 조금이라도 겹친 생성은
 *   기록하지 않는다**: 삭제 중에 시작했거나, 삭제 중에 끝났거나, 시작과 끝 사이에 삭제가 있었던 경우.
 *   삭제가 Keystore 키를 폐기하기 전·후 어느 시점에 시작했는지와 상관없이 거부된다(A #40 리뷰 10/25).
 * - 이 프로세스에서 전체 삭제가 한 번이라도 있었으면, 저장은 "삭제와 겹치지 않고 마지막 삭제 뒤에 만든 키"만
 *   받는다. 모르는 키도 거부한다. 전체 삭제 전에는 지금과 같이 저장한다.
 * - 전체 삭제와 저장은 같은 잠금 안에서 돈다. 저장 확인과 쓰기 사이에 삭제가 끼지 않는다.
 * - 삭제가 실패하거나 취소돼도 옛 키는 계속 거부되고, 진행 표시는 풀린다(그 뒤 새 키는 정상 저장).
 * - 기록은 메모리에만, 최대 [MAX_TRACKED_KEYS]개. 지문 값만 담고 화면 원문은 없다. 로그를 남기지 않는다.
 *   앱을 다시 시작하면 기록이 비지만, C가 클릭 전에 만든 키도 메모리에만 있으므로 함께 사라진다.
 */
class KeyEraseGate {

    /** 지문 생성 시작 시점의 상태. [recordKeyCreation] 밖에서는 시험용으로만 쓴다. */
    class CreationTicket internal constructor(
        internal val eraseCount: Long,
        internal val startedDuringErase: Boolean,
    )

    private val mutex = Mutex()
    private val lock = Any()
    private var eraseCount = 0L
    private var eraseInProgress = false
    private var erasedInProcess = false
    private val created = object : LinkedHashMap<RuleKey, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RuleKey, Long>?): Boolean =
            size > MAX_TRACKED_KEYS
    }

    /** [create]로 지문을 만들고, 삭제와 겹치지 않았을 때만 저장 가능한 키로 기록한다. */
    suspend fun recordKeyCreation(create: suspend () -> RuleKey): RuleKey {
        val ticket = beginKeyCreation()
        val key = create()
        finishKeyCreation(key, ticket)
        return key
    }

    fun beginKeyCreation(): CreationTicket = synchronized(lock) { CreationTicket(eraseCount, eraseInProgress) }

    /** @return 저장 가능한 키로 기록했으면 true, 삭제와 겹쳐 기록하지 않았으면 false */
    fun finishKeyCreation(key: RuleKey, ticket: CreationTicket): Boolean = synchronized(lock) {
        val clean = !ticket.startedDuringErase && !eraseInProgress && ticket.eraseCount == eraseCount
        if (clean) created[key] = eraseCount else created.remove(key)
        clean
    }

    /**
     * [write]는 키가 저장 가능할 때만, 전체 삭제와 겹치지 않게 실행된다.
     * @throws RuleRejectedException [RuleRejection.KEY_FROM_BEFORE_ERASE]
     */
    suspend fun <T> save(key: RuleKey, write: suspend () -> T): T = mutex.withLock {
        if (!isSavable(key)) throw RuleRejectedException(RuleRejection.KEY_FROM_BEFORE_ERASE)
        write()
    }

    /**
     * 진행 중인 저장이 끝난 뒤 [erase]를 실행한다. 끝날 때까지 저장은 기다리고, 이 사이에 시작·종료한
     * 지문 생성은 기록되지 않는다. 실패·취소돼도 옛 키는 계속 거부된다.
     */
    suspend fun erase(erase: suspend () -> Unit) {
        mutex.withLock {
            synchronized(lock) {
                eraseCount++
                eraseInProgress = true
                erasedInProcess = true
                created.clear()
            }
            try {
                erase()
            } finally {
                synchronized(lock) { eraseInProgress = false }
            }
        }
    }

    private fun isSavable(key: RuleKey): Boolean = synchronized(lock) {
        !erasedInProcess || (!eraseInProgress && created[key] == eraseCount)
    }

    companion object {
        const val MAX_TRACKED_KEYS = 256
    }
}

/** 만든 키를 [KeyEraseGate]에 기록한다. 지문 값 자체는 바꾸지 않는다. */
class KeyRecordingFingerprintProvider(
    private val delegate: PopupFingerprintProvider,
    private val gate: KeyEraseGate,
) : PopupFingerprintProvider {
    override suspend fun createKey(snapshot: PopupSnapshot, target: CloseCandidate, appVersionCode: Long): RuleKey =
        gate.recordKeyCreation { delegate.createKey(snapshot, target, appVersionCode) }
}
