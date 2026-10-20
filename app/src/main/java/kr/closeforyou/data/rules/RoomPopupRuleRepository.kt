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
            (rule.expiresAtEpochMillis == null || nowEpochMillis < rule.expiresAtEpochMillis)
}

/**
 * D: F12 사용자 선택 기억의 Room 구현. InMemoryMockPopupRuleRepository를 대체한다.
 * 화면 원문·노드·이미지는 받지도 저장하지도 않는다(RuleKey는 지문만 담는다).
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

    override suspend fun save(rule: PopupRule) {
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
     * @return 갱신된 규칙이 있으면 true
     */
    suspend fun recordFailure(id: String): Boolean = dao.incrementFailure(id) > 0

    /** 만료된 규칙을 실제로 지운다. 조회는 이 호출 없이도 만료 규칙을 무시한다. */
    suspend fun purgeExpired(nowEpochMillis: Long): Int = dao.deleteExpired(nowEpochMillis)
}
