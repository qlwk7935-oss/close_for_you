package kr.closeforyou.data.rules

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
abstract class PopupRuleDao {

    /** 정확 일치 + 사용 가능 조건. 저장소가 Kotlin에서 같은 조건을 한 번 더 확인한다. */
    @Query(
        """
        SELECT * FROM popup_rules
        WHERE packageName = :packageName
          AND appVersionCode = :appVersionCode
          AND structureFingerprint = :structureFingerprint
          AND contentFingerprint = :contentFingerprint
          AND targetFingerprint = :targetFingerprint
          AND enabled = 1
          AND failureCount = 0
          AND schemaVersion = :schemaVersion
          AND createdAtEpochMillis <= :now
          AND expiresAtEpochMillis IS NOT NULL
          AND expiresAtEpochMillis > :now
        ORDER BY createdAtEpochMillis DESC
        """,
    )
    abstract suspend fun findUsable(
        packageName: String,
        appVersionCode: Long,
        structureFingerprint: String,
        contentFingerprint: String,
        targetFingerprint: String,
        schemaVersion: Int,
        now: Long,
    ): List<PopupRuleEntity>

    @Query("SELECT * FROM popup_rules ORDER BY createdAtEpochMillis DESC, id ASC")
    abstract fun observeAll(): Flow<List<PopupRuleEntity>>

    /** 정리용 전체 읽기. 목록 화면은 [observeAll]을 쓴다. */
    @Query("SELECT * FROM popup_rules")
    protected abstract suspend fun getAll(): List<PopupRuleEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(entity: PopupRuleEntity)

    @Query("DELETE FROM popup_rules WHERE id = :id")
    protected abstract suspend fun deleteById(id: String)

    @Query("DELETE FROM popup_rules WHERE id IN (:ids)")
    protected abstract suspend fun deleteByIds(ids: List<String>): Int

    @Query(
        """
        DELETE FROM popup_rules
        WHERE packageName = :packageName
          AND appVersionCode = :appVersionCode
          AND structureFingerprint = :structureFingerprint
          AND contentFingerprint = :contentFingerprint
          AND targetFingerprint = :targetFingerprint
        """,
    )
    protected abstract suspend fun deleteByKey(
        packageName: String,
        appVersionCode: Long,
        structureFingerprint: String,
        contentFingerprint: String,
        targetFingerprint: String,
    )

    /**
     * 같은 ID 또는 같은 키의 이전 선택을 지우고 새 선택만 남긴다.
     * 새 선택을 철회했을 때 예전 허락이 되살아나지 않게 하기 위함이다.
     */
    @Transaction
    open suspend fun replace(entity: PopupRuleEntity) {
        deleteById(entity.id)
        deleteByKey(
            entity.packageName,
            entity.appVersionCode,
            entity.structureFingerprint,
            entity.contentFingerprint,
            entity.targetFingerprint,
        )
        insert(entity)
    }

    @Query("UPDATE popup_rules SET enabled = 0 WHERE id = :id")
    abstract suspend fun disable(id: String): Int

    /**
     * 실패 표시. 횟수를 더하지 않고 1로 설정해 같은 실패가 여러 번(재시작 뒤 포함) 와도 결과가 같다(멱등).
     * 실패가 한 번이라도 있으면 규칙은 다시 일치하지 않으므로 횟수는 의미가 없다.
     */
    @Query("UPDATE popup_rules SET failureCount = 1 WHERE id = :id")
    abstract suspend fun markFailed(id: String): Int

    /**
     * 만료된 행과 만료일이 없는 행을 지운다. 만료일 없는 행은 저장이 거부되므로 정상 경로로는 생기지 않고,
     * 생겨도 조회에서 절대 일치하지 않는다(팀 합의 10/21: 만료일 필수).
     */
    @Query("DELETE FROM popup_rules WHERE expiresAtEpochMillis IS NULL OR expiresAtEpochMillis <= :now")
    abstract suspend fun deleteExpired(now: Long): Int

    /**
     * 규칙으로 읽을 수 없는 손상 행(알 수 없는 선택 값 등)을 지운다. 이런 행은 조회·목록에 나오지 않아
     * 사용자가 화면에서 지울 방법이 없으므로 저장소가 정리한다. 다른 스키마 버전 행은 읽을 수 있으면 남긴다.
     */
    @Transaction
    open suspend fun deleteUnreadable(): Int {
        val ids = getAll().filter { it.toRuleOrNull() == null }.map { it.id }
        return ids.chunked(DELETE_CHUNK).sumOf { deleteByIds(it) }
    }

    @Query("DELETE FROM popup_rules")
    abstract suspend fun deleteAll()
}

/** 한 번의 DELETE에 넣는 ID 수. SQLite 바인딩 변수 한도보다 작게 둔다. */
private const val DELETE_CHUNK = 500
