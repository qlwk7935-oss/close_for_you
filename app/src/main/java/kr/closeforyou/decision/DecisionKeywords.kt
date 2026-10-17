package kr.closeforyou.decision

/**
 * B keyword dictionary for display classification. Matching a keyword never authorizes an action.
 * Korean terms use substring matching; English terms use word boundaries so "read" is not "ad".
 */
internal object DecisionKeywords {
    // Design 3-1 grade D plus A's fixed summaries (e.g. "결제 선택 필요"). "설치" is grade P
    // (navigation), so an install advertisement stays an advertisement.
    private val userDecisionKo = listOf(
        "결제", "구매", "구독", "동의", "권한", "허용", "삭제", "가입", "로그인",
        "승인", "송금", "이체", "해지", "탈퇴",
    )
    private val userDecisionEn = Regex(
        "\\b(pay|payment|purchase|buy|subscribe|subscription|consent|agree|permission|allow|delete|login|log in|sign in|sign up)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Grade D on a button only: popup bodies such as "체험 상품 안내" are often advertisements.
    private val decisionButtonOnlyKo = listOf("체험")
    private val decisionButtonOnlyEn = Regex("\\btrial\\b", RegexOption.IGNORE_CASE)

    private val advertisementKo = listOf("광고", "스폰서", "협찬", "프로모션", "특가", "쿠폰")
    // Upper-case only, so ordinary words containing "ad" are not advertisements.
    private val advertisementUpperEn = Regex("\\bAD\\b")
    private val advertisementEn = Regex("\\b(ads|sponsored|advertisement)\\b", RegexOption.IGNORE_CASE)

    private val noticeKo = listOf("점검", "공지", "안내", "알림", "업데이트", "변경")

    // Wording that says the popup's own meaning is unconfirmed.
    private val uncertainKo = listOf("미확인", "여부", "확인되지 않", "알 수 없")

    fun isUserDecision(text: String): Boolean =
        userDecisionKo.any { it in text } || userDecisionEn.containsMatchIn(text)

    fun isDecisionButtonLabel(label: String): Boolean =
        isUserDecision(label) || decisionButtonOnlyKo.any { it in label } ||
            decisionButtonOnlyEn.containsMatchIn(label)

    fun isAdvertisement(text: String): Boolean =
        advertisementKo.any { it in text } ||
            advertisementUpperEn.containsMatchIn(text) ||
            advertisementEn.containsMatchIn(text)

    fun isNotice(text: String): Boolean = noticeKo.any { it in text }

    fun isUncertain(text: String): Boolean = uncertainKo.any { it in text }
}
