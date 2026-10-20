package kr.closeforyou.decision

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole

/**
 * B keyword dictionary for display classification. Matching a keyword never authorizes an action.
 * Korean terms use substring matching; English terms use word boundaries so "read" is not "ad".
 */
internal object DecisionKeywords {
    // Design 3-1 grade D, F7 decision signals (계정·개인정보) and A's fixed summaries
    // (e.g. "결제 선택 필요"). "설치" is grade P (navigation), so an install advertisement stays one.
    private val userDecisionKo = listOf(
        "결제", "구매", "구독", "동의", "권한", "허용", "삭제", "가입", "로그인", "계정", "개인정보",
        "승인", "송금", "이체", "해지", "탈퇴",
    )
    private val userDecisionEn = Regex(
        "\\b(pay|payment|purchase|buy|subscribe|subscription|consent|agree|accept|permission|allow|delete|login|log in|sign in|sign up|account|personal (information|data))\\b",
        RegexOption.IGNORE_CASE,
    )

    // Grade D on a button only: popup bodies such as "체험 상품 안내" are often advertisements.
    private val decisionButtonOnlyKo = listOf("체험")
    private val decisionButtonOnlyEn = Regex("\\btrial\\b", RegexOption.IGNORE_CASE)

    // Design 3-1 grades K (취소·거절·나중에·오늘 하루 보지 않기·7일간 닫기), P (설치·자세히·받기·열기)
    // and O (확인·OK): a button labelled like this is not a close or skip target, whatever its role says.
    private val riskyButtonKo = listOf(
        "취소", "거절", "나중에", "보지 않기", "다시 보지", "그만", "오늘", "하루", "일간",
        "설치", "자세히", "받기", "열기", "바로가기", "이동", "다운로드",
        "확인", "알겠",
    )
    private val riskyButtonEn = Regex(
        "\\b(cancel|decline|later|install|open|more|get|download|ok|okay|confirm|continue|got it|today|don't show|do not show)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val advertisementKo = listOf("광고", "스폰서", "협찬", "프로모션", "특가", "쿠폰")
    // Upper-case only, so ordinary words containing "ad" are not advertisements.
    private val advertisementUpperEn = Regex("\\bAD\\b")
    private val advertisementEn = Regex("\\b(ads|sponsored|advertisement)\\b", RegexOption.IGNORE_CASE)

    private val noticeKo = listOf("점검", "공지", "안내", "알림", "업데이트", "변경")

    // Wording that says the popup's own meaning is unconfirmed. Not "여부" alone: "참여 여부" is ordinary.
    private val uncertainKo = listOf("미확인", "확인되지 않", "알 수 없", "불명")

    fun isUserDecision(text: String): Boolean =
        userDecisionKo.any { it in text } || userDecisionEn.containsMatchIn(text)

    fun isDecisionButtonLabel(label: String): Boolean =
        isUserDecision(label) || decisionButtonOnlyKo.any { it in label } ||
            decisionButtonOnlyEn.containsMatchIn(label)

    fun isRiskyButtonLabel(label: String): Boolean =
        isDecisionButtonLabel(label) || riskyButtonKo.any { it in label } || riskyButtonEn.containsMatchIn(label)

    /**
     * CLOSE or SKIP only when the whole label says so, by the team's shared label contract, so
     * "오늘 하루 닫기" (grade K), "닫기 전 저장" or a bare "X" are neither. Used by the policy's
     * recheck of a supplied role; the finder judges whole nodes with [ButtonLabelRules.judgeNode].
     */
    fun buttonRoleOf(label: String): ButtonRole? = ButtonLabelRules.roleOf(label)

    fun isAdvertisement(text: String): Boolean =
        advertisementKo.any { it in text } ||
            advertisementUpperEn.containsMatchIn(text) ||
            advertisementEn.containsMatchIn(text)

    fun isNotice(text: String): Boolean = noticeKo.any { it in text }

    fun isUncertain(text: String): Boolean = uncertainKo.any { it in text }
}
