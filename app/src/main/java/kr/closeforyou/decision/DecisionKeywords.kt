package kr.closeforyou.decision

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

    // R1 (OVERWARNING_STUDY.md): a benefit condition such as "가입하면 포인트", "결제 시 5% 할인".
    private val benefitCondition = Regex("""하면|하고|\S+\s*시\s|할 때|\band get\b|\bwhen you\b|\bif you\b""", RegexOption.IGNORE_CASE)
    private val benefitWord = Regex("""할인|쿠폰|포인트|혜택|이벤트|특가|무료|증정|적립|배송|\boff\b|\bfree\b|\bbonus\b|\bcoupon\b""", RegexOption.IGNORE_CASE)
    // A question or A's fixed summary ("결제 선택 필요") is always a decision, never a mention.
    private val asksForDecision = Regex("""\?|하시겠|할까요|시겠어요|필요""")

    /**
     * A sentence that only mentions a decision word as the condition of a benefit: it has a
     * condition and a benefit word, asks nothing, and is longer than a button name (10 chars).
     */
    fun isBenefitMention(text: String): Boolean =
        text.length > 10 && benefitCondition.containsMatchIn(text) && benefitWord.containsMatchIn(text) &&
            !asksForDecision.containsMatchIn(text)

    fun isDecisionButtonLabel(label: String): Boolean =
        isUserDecision(label) || decisionButtonOnlyKo.any { it in label } ||
            decisionButtonOnlyEn.containsMatchIn(label)

    fun isRiskyButtonLabel(label: String): Boolean =
        isDecisionButtonLabel(label) || riskyButtonKo.any { it in label } || riskyButtonEn.containsMatchIn(label)

    /**
     * CLOSE or SKIP only when the whole label says so, so "오늘 하루 닫기" (grade K) or "닫기 전 저장"
     * are neither. Shared by the candidate finder and the policy's recheck of a supplied role.
     */
    fun buttonRoleOf(label: String): ButtonRole? {
        if (isRiskyButtonLabel(label)) return null
        val normalized = label.lowercase().filterNot { it.isWhitespace() || it in "[]()<>·." }
        return when (normalized) {
            in closeLabels -> ButtonRole.CLOSE
            in skipLabels -> ButtonRole.SKIP
            else -> null
        }
    }

    // A bare "X"/"×" is not a close label: A's gate keeps refusing it until the button's meaning and
    // popup membership can be proven (agreed after PR #11), so B must not offer it as pressable.
    private val closeLabels = setOf(
        "닫기", "닫음", "창닫기", "팝업닫기", "광고닫기", "공지닫기", "안내닫기", "닫기버튼",
        "close", "closead", "closepopup", "closedialog", "closebutton", "dismiss",
    )
    private val skipLabels = setOf("건너뛰기", "광고건너뛰기", "건너뛰기버튼", "스킵", "광고스킵", "skip", "skipad", "skipads", "skipbutton")

    fun isAdvertisement(text: String): Boolean =
        advertisementKo.any { it in text } ||
            advertisementUpperEn.containsMatchIn(text) ||
            advertisementEn.containsMatchIn(text)

    fun isNotice(text: String): Boolean = noticeKo.any { it in text }

    fun isUncertain(text: String): Boolean = uncertainKo.any { it in text }
}
