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
 * 폐기되면, 이미 만든 RuleKey(옛 키 지문)가 삭제 뒤에 저장될 수 있다. 그 규칙은 새 키 지문과 절대 일치하지
 * 않지만 목록에 남아 사용자를 헷갈리게 한다.
 *
 * - 이 프로세스에서 전체 삭제가 한 번이라도 있었으면, 저장은 "현재 세대에 만든 키"만 받는다.
 *   모르는 키도 거부한다(fail closed). 전체 삭제 전에는 지금과 같이 저장한다.
 * - 키를 만들기 시작한 시점의 세대로 기록한다. 만드는 도중에 삭제가 끼면 옛 세대로 남아 거부된다.
 * - 전체 삭제와 저장은 같은 잠금 안에서 돈다. 저장 확인과 쓰기 사이에 삭제가 끼지 않는다.
 * - 삭제가 중간에 실패해도 세대는 이미 넘어간다(옛 키는 계속 거부).
 * - 기록은 메모리에만, 최대 [MAX_TRACKED_KEYS]개. 지문 값만 담고 화면 원문은 없다. 로그를 남기지 않는다.
 *   앱을 다시 시작하면 기록이 비지만, C가 클릭 전에 만든 키도 메모리에만 있으므로 함께 사라진다.
 */
class KeyEraseGate {
    private val mutex = Mutex()
    private val lock = Any()
    private var generation = 0L
    private var erasedInProcess = false
    private val created = object : LinkedHashMap<RuleKey, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RuleKey, Long>?): Boolean =
            size > MAX_TRACKED_KEYS
    }

    /** 지문을 만들기 시작할 때 읽어 두고, 다 만든 뒤 [keyCreated]에 넘긴다. */
    fun generation(): Long = synchronized(lock) { generation }

    fun keyCreated(key: RuleKey, generationAtStart: Long) {
        synchronized(lock) { created[key] = generationAtStart }
    }

    /**
     * [write]는 키가 현재 세대일 때만, 전체 삭제와 겹치지 않게 실행된다.
     * @throws RuleRejectedException [RuleRejection.KEY_FROM_BEFORE_ERASE]
     */
    suspend fun <T> save(key: RuleKey, write: suspend () -> T): T = mutex.withLock {
        if (!isCurrent(key)) throw RuleRejectedException(RuleRejection.KEY_FROM_BEFORE_ERASE)
        write()
    }

    /** 진행 중인 저장이 끝난 뒤 세대를 올리고 [erase]를 실행한다. 끝날 때까지 저장은 기다린다. */
    suspend fun erase(erase: suspend () -> Unit) {
        mutex.withLock {
            synchronized(lock) {
                generation++
                erasedInProcess = true
                created.clear()
            }
            erase()
        }
    }

    private fun isCurrent(key: RuleKey): Boolean = synchronized(lock) {
        !erasedInProcess || created[key] == generation
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
    override suspend fun createKey(snapshot: PopupSnapshot, target: CloseCandidate, appVersionCode: Long): RuleKey {
        val generationAtStart = gate.generation()
        return delegate.createKey(snapshot, target, appVersionCode).also { gate.keyCreated(it, generationAtStart) }
    }
}
