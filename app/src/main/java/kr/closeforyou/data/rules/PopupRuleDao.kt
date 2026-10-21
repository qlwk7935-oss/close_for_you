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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(entity: PopupRuleEntity)

    @Query("DELETE FROM popup_rules WHERE id = :id")
    protected abstract suspend fun deleteById(id: String)

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

    @Query("UPDATE popup_rules SET failureCount = failureCount + 1 WHERE id = :id")
    abstract suspend fun incrementFailure(id: String): Int

    @Query("DELETE FROM popup_rules WHERE expiresAtEpochMillis IS NOT NULL AND expiresAtEpochMillis <= :now")
    abstract suspend fun deleteExpired(now: Long): Int

    @Query("DELETE FROM popup_rules")
    abstract suspend fun deleteAll()
}
