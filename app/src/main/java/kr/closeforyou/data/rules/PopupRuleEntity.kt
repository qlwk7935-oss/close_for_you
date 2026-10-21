package kr.closeforyou.data.rules

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey

/**
 * D: F12 규칙 한 줄. 화면 원문·노드·이미지는 저장하지 않고 기기 내부 키로 만든 지문만 저장한다.
 * 패키지 이름은 사용자가 규칙 목록에서 확인·철회할 수 있도록 평문으로 둔다.
 * 앱 백업·기기 이전은 manifest의 data_extraction_rules에서 이미 제외되어 있다.
 */
@Entity(
    tableName = PopupRuleEntity.TABLE,
    indices = [
        Index(
            value = ["packageName", "appVersionCode", "structureFingerprint", "contentFingerprint", "targetFingerprint"],
            unique = true,
        ),
    ],
)
data class PopupRuleEntity(
    @PrimaryKey val id: String,
    val packageName: String,
    val appVersionCode: Long,
    val structureFingerprint: String,
    val contentFingerprint: String,
    val targetFingerprint: String,
    val choice: String,
    val buttonMeaningConfirmed: Boolean,
    val repeatAutomationExplicitlyAllowed: Boolean,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val schemaVersion: Int,
    val failureCount: Int,
    val enabled: Boolean,
) {
    override fun toString(): String = "PopupRuleEntity(enabled=$enabled, schemaVersion=$schemaVersion, redacted)"

    /** 손상되었거나 알 수 없는 값이 있는 행은 규칙으로 쓰지 않는다(fail closed). */
    fun toRuleOrNull(): PopupRule? = runCatching {
        PopupRule(
            id = id,
            key = RuleKey(
                packageName = packageName,
                appVersionCode = appVersionCode,
                structureFingerprint = structureFingerprint,
                exactContentFingerprint = contentFingerprint,
                targetFingerprint = targetFingerprint,
            ),
            choice = ChoiceKind.valueOf(choice),
            buttonMeaningConfirmed = buttonMeaningConfirmed,
            repeatAutomationExplicitlyAllowed = repeatAutomationExplicitlyAllowed,
            createdAtEpochMillis = createdAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            schemaVersion = schemaVersion,
            failureCount = failureCount,
            enabled = enabled,
        )
    }.getOrNull()

    companion object {
        const val TABLE = "popup_rules"

        fun from(rule: PopupRule): PopupRuleEntity = PopupRuleEntity(
            id = rule.id,
            packageName = rule.key.packageName,
            appVersionCode = rule.key.appVersionCode,
            structureFingerprint = rule.key.structureFingerprint,
            contentFingerprint = rule.key.exactContentFingerprint,
            targetFingerprint = rule.key.targetFingerprint,
            choice = rule.choice.name,
            buttonMeaningConfirmed = rule.buttonMeaningConfirmed,
            repeatAutomationExplicitlyAllowed = rule.repeatAutomationExplicitlyAllowed,
            createdAtEpochMillis = rule.createdAtEpochMillis,
            expiresAtEpochMillis = rule.expiresAtEpochMillis,
            schemaVersion = rule.schemaVersion,
            failureCount = rule.failureCount,
            enabled = rule.enabled,
        )
    }
}
