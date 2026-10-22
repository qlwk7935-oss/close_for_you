package kr.closeforyou.data.rules

import kr.closeforyou.core.contract.PopupRuleRepository
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 현재 규칙 스키마. 다른 버전의 규칙은 일치시키지 않는다. */
const val CURRENT_RULE_SCHEMA_VERSION = 1

/** 팀 합의(A·B·C·D, 10/21): 기억 규칙 기본 만료 30일. 만료일 없는 규칙은 저장하지 않는다. */
object RuleRetention {
    const val DEFAULT_TTL_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

    /** 저장 시각에서 30일 뒤. 저장할 규칙의 expiresAtEpochMillis에 그대로 넣는다. */
    fun defaultExpiry(createdAtEpochMillis: Long): Long = createdAtEpochMillis + DEFAULT_TTL_MILLIS
}

/**
 * 팀 합의(10/21): 자동 반복 **실행**은 지금 OFF.
 * 사용자의 명시적 허락([PopupRule.repeatAutomationExplicitlyAllowed])은 저장하되, 그 허락으로 실제 자동 실행을
 * 할지는 B 정책·A 게이트가 이 값과 함께 판단한다. 저장소는 허락 저장과 실행 금지를 섞지 않는다.
 */
object RepeatAutomation {
    const val EXECUTION_ENABLED: Boolean = false
}

/** 저장 거부 사유. 원문 없이 고정 코드만 담는다. */
enum class RuleRejection {
    /** 만료일이 없음. */
    MISSING_EXPIRY,
    /** 만료일이 생성 시각에서 30일을 넘음. */
    EXPIRY_TOO_LONG,
}

class RuleRejectedException(val reason: RuleRejection) :
    IllegalArgumentException("Popup rule rejected: $reason")

/** [RoomPopupRuleRepository.cleanUp] 결과. 지운 행 개수만 담는다(화면 원문·지문 없음). */
data class RuleCleanUpResult(
    /** 만료됐거나 만료일이 없는 행. */
    val expired: Int,
    /** 규칙으로 읽을 수 없는 손상 행. */
    val unreadable: Int,
) {
    val total: Int get() = expired + unreadable
}

/**
 * 규칙을 쓸 수 있는지 판단한다. 일치해도 실행 허가가 아니며 B가 정책을 다시 확인한다.
 * 정확한 키, 활성, 실패 0회, 현재 스키마, 생성 이후, 만료 전이어야 한다.
 */
object RuleValidity {
    fun isUsable(rule: PopupRule, key: RuleKey, nowEpochMillis: Long): Boolean =
        rule.key == key &&
            rule.enabled &&
            rule.failureCount == 0 &&
            rule.schemaVersion == CURRENT_RULE_SCHEMA_VERSION &&
            nowEpochMillis >= rule.createdAtEpochMillis &&
            rule.expiresAtEpochMillis != null &&
            nowEpochMillis < rule.expiresAtEpochMillis

    /** 저장 전에 검사한다. 통과하지 못하면 [RuleRejectedException]. */
    fun checkStorable(rule: PopupRule) {
        val expiresAt = rule.expiresAtEpochMillis ?: throw RuleRejectedException(RuleRejection.MISSING_EXPIRY)
        if (expiresAt - rule.createdAtEpochMillis > RuleRetention.DEFAULT_TTL_MILLIS) {
            throw RuleRejectedException(RuleRejection.EXPIRY_TOO_LONG)
        }
    }
}

/**
 * D: F12 사용자 선택 기억의 Room 구현. InMemoryMockPopupRuleRepository를 대체한다.
 * 화면 원문·노드·이미지는 받지도 저장하지도 않는다(RuleKey는 지문만 담는다).
 *
 * 팀 합의(10/21):
 * - 만료일은 필수이며 최대 30일([RuleRetention]). 철회·전체 삭제는 유지한다.
 * - 사용자 반복 허락은 그대로 저장한다. 자동 반복 실행은 [RepeatAutomation.EXECUTION_ENABLED]가 false인 동안
 *   B 정책·A 게이트가 막는다(저장소는 실행하지 않는다).
 * - 실제 클릭이 필요한 기억은 A가 실행 성공을 확인한 뒤에만 [save]를 호출한다(호출하는 쪽 책임).
 */
class RoomPopupRuleRepository(private val dao: PopupRuleDao) : PopupRuleRepository {

    override suspend fun findExact(key: RuleKey, nowEpochMillis: Long): PopupRule? =
        dao.findUsable(
            packageName = key.packageName,
            appVersionCode = key.appVersionCode,
            structureFingerprint = key.structureFingerprint,
            contentFingerprint = key.exactContentFingerprint,
            targetFingerprint = key.targetFingerprint,
            schemaVersion = CURRENT_RULE_SCHEMA_VERSION,
            now = nowEpochMillis,
        )
            .asSequence()
            .mapNotNull { it.toRuleOrNull() }
            .firstOrNull { RuleValidity.isUsable(it, key, nowEpochMillis) }

    /** @throws RuleRejectedException 만료일이 없거나 30일을 넘는 경우 */
    override suspend fun save(rule: PopupRule) {
        RuleValidity.checkStorable(rule)
        dao.replace(PopupRuleEntity.from(rule))
    }

    override suspend fun revoke(id: String) {
        dao.disable(id)
    }

    override suspend fun deleteAll() {
        dao.deleteAll()
    }

    override fun observeRules(): Flow<ImmutableList<PopupRule>> =
        dao.observeAll().map { rows -> rows.mapNotNull { it.toRuleOrNull() }.toImmutableList() }

    /**
     * 기억한 규칙으로 실행했는데 실패·오동작한 경우 호출한다. 이후 그 규칙은 다시 일치하지 않는다.
     *
     * 멱등: 같은 실패가 중복 전달되거나 앱 재시작 뒤 다시 와도 결과가 같다(`failureCount = 1`).
     * 그래서 호출하는 쪽(B)이 시도 identity를 저장소에 넘길 필요가 없다(A 리뷰 #23, 10/22).
     * @return 그 id의 규칙이 있어 실패 상태가 되었으면(이미 실패였어도) true, 규칙이 없으면 false
     */
    suspend fun recordFailure(id: String): Boolean = dao.markFailed(id) > 0

    /** 만료됐거나 만료일이 없는 규칙을 실제로 지운다. 조회는 이 호출 없이도 이런 규칙을 무시한다. */
    suspend fun purgeExpired(nowEpochMillis: Long): Int = dao.deleteExpired(nowEpochMillis)

    /**
     * 정리: 만료(만료일 없음 포함) 행과 읽을 수 없는 손상 행을 지운다.
     * 해제됨·실패로 꺼짐 규칙은 사용자가 목록에서 볼 수 있도록 만료 전까지 남긴다.
     *
     * 호출 시점(팀 제안 10/22): A 서비스 시작 시 한 번, C 설정 화면을 열 때 한 번. 조회 안전성은 이 호출과
     * 무관하다(조회는 늘 만료·손상 행을 무시한다). 로그를 남기지 않는다.
     */
    suspend fun cleanUp(nowEpochMillis: Long): RuleCleanUpResult = RuleCleanUpResult(
        expired = dao.deleteExpired(nowEpochMillis),
        unreadable = dao.deleteUnreadable(),
    )
}
