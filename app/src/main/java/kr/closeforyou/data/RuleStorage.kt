package kr.closeforyou.data

import android.content.Context
import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.PopupFingerprintProvider
import kr.closeforyou.data.fingerprint.AndroidKeystoreHasher
import kr.closeforyou.data.fingerprint.DeviceKeyedFingerprintProvider
import kr.closeforyou.data.rules.CloseForYouDatabase
import kr.closeforyou.data.rules.RoomPopupRuleRepository
import kr.closeforyou.data.rules.RuleCleanUpResult

/**
 * D: F12 저장소 조립 지점. 실제 서비스 경로 연결(A/B)은 별도 PR에서 합의 후 진행한다.
 *
 * ```
 * val repository = RuleStorage.repository(context)
 * val fingerprints = RuleStorage.fingerprintProvider(evidenceExtractor)
 * RuleStorage.cleanUp(context) // A 서비스 시작 시, C 설정 화면 열 때
 * ```
 */
object RuleStorage {
    fun repository(context: Context): RoomPopupRuleRepository =
        RoomPopupRuleRepository(CloseForYouDatabase.get(context).popupRuleDao())

    fun fingerprintProvider(evidenceExtractor: EvidenceExtractor): PopupFingerprintProvider =
        DeviceKeyedFingerprintProvider(evidenceExtractor, AndroidKeystoreHasher())

    /**
     * 만료(만료일 없음 포함)·손상 규칙 정리. A 서비스 시작 시, C 설정 화면을 열 때 한 번씩 부른다.
     * 코루틴에서 호출한다(Room이 IO 스레드에서 실행). 결과는 지운 개수뿐이다.
     */
    suspend fun cleanUp(
        context: Context,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): RuleCleanUpResult = repository(context).cleanUp(nowEpochMillis)

    /**
     * 사용자의 "기억 전체 삭제". 규칙을 모두 지우고 지문 키도 폐기해,
     * 혹시 남은 지문(예: 삭제 전 복사본)이 앞으로 만들어질 지문과 절대 일치하지 않게 한다.
     */
    suspend fun eraseAll(context: Context) {
        repository(context).deleteAll()
        AndroidKeystoreHasher().deleteKey()
    }
}
