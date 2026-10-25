package kr.closeforyou.accessibility.action

import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.PopupAnnouncer
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.ui.voice.PressSuccess

/** Legacy formatter retained for compatibility tests. Automatic #51 handling never calls it:
 * F9ServiceConnection sends reports only to SpeechHandlingListener through F9FeedbackRouter.
 * Uses C's three-condition PressSuccess. No screen text.
 * Result speech is about a completed attempt, so it does not reuse the now invalid popup token.
 * The service assembly must still cancel its feedback scope when that service session ends.
 */
internal object F9ResultFeedback {
    fun request(result: ActionResult) = AnnouncementRequest(null, AnnouncementKind.ACTION_RESULT, when {
        !result.attempted -> "자동 닫기를 실행하지 않았어요."
        PressSuccess.isConfirmed(result) -> "팝업을 닫았어요."
        result.outcome == ClickOutcome.STILL_PRESENT -> "닫기를 한 번 시도했지만 팝업이 남아 있어요."
        result.outcome == ClickOutcome.LEFT_APP -> "다른 화면으로 이동했어요. 이 앱의 자동 닫기를 중지했어요."
        else -> "닫기를 한 번 시도했지만 닫혔는지 확인하지 못했어요."
    })
    suspend fun deliver(result: ActionResult, announcer: PopupAnnouncer) = announcer.announce(request(result))
}
