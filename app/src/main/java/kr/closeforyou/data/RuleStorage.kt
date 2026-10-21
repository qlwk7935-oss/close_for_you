package kr.closeforyou.data

import android.content.Context
import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.data.fingerprint.AndroidKeystoreHasher
import kr.closeforyou.data.fingerprint.DeviceKeyedFingerprintProvider
import kr.closeforyou.data.rules.CloseForYouDatabase
import kr.closeforyou.data.rules.RoomPopupRuleRepository

/**
 * D: F12 저장소 조립 지점. 실제 서비스 경로 연결(A/B)은 별도 PR에서 합의 후 진행한다.
 *
 * ```
 * val repository = RuleStorage.repository(context)
 * val fingerprints = RuleStorage.fingerprintProvider(evidenceExtractor)
 * ```
 */
object RuleStorage {
    fun repository(context: Context): RoomPopupRuleRepository =
        RoomPopupRuleRepository(CloseForYouDatabase.get(context).popupRuleDao())

    fun fingerprintProvider(evidenceExtractor: EvidenceExtractor): PopupFingerprintProvider =
        DeviceKeyedFingerprintProvider(evidenceExtractor, AndroidKeystoreHasher())

    /**
     * 사용자의 "기억 전체 삭제". 규칙을 모두 지우고 지문 키도 폐기해,
     * 혹시 남은 지문(예: 삭제 전 복사본)이 앞으로 만들어질 지문과 절대 일치하지 않게 한다.
     */
    suspend fun eraseAll(context: Context) {
        repository(context).deleteAll()
        AndroidKeystoreHasher().deleteKey()
    }
}
