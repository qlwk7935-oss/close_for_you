package kr.closeforyou.decision

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.ButtonRole
import java.text.Normalizer

/**
 * B keyword dictionary for display classification. Matching a keyword never authorizes an action.
 * Korean terms use substring matching; English terms use word boundaries so "read" is not "ad".
 */
internal object DecisionKeywords {
    // English word boundaries in ASCII, not `\b`: next to Hangul, `\b` holds on the JDK but not on
    // Android's ICU engine, so "sign up하기" or "AD광고" were missed on phones while JVM tests passed.
    // `_` stays a word character as in `\b`, so identifiers such as "AD_banner" or "pay_day" still
    // do not match (PR #37 review).
    private const val EN_START = "(?<![a-z0-9_])"
    private const val EN_END = "(?![a-z0-9_])"

    // Design 3-1 grade D, F7 decision signals (계정·개인정보) and A's fixed summaries
    // (e.g. "결제 선택 필요"). "설치" is grade P (navigation), so an install advertisement stays one.
    private val userDecisionKo = listOf(
        "결제", "구매", "구독", "동의", "권한", "허용", "삭제", "가입", "로그인", "계정", "개인정보",
        "승인", "송금", "이체", "해지", "탈퇴",
    )
    private val userDecisionEn = Regex(
        "$EN_START(pay|payment|purchase|buy|subscribe|subscription|consent|agree|accept|permission|allow|delete|login|log in|sign in|sign up|account|personal (information|data))$EN_END",
        RegexOption.IGNORE_CASE,
    )

    // Grade D on a button only: popup bodies such as "체험 상품 안내" are often advertisements.
    private val decisionButtonOnlyKo = listOf("체험")
    private val decisionButtonOnlyEn = Regex("${EN_START}trial$EN_END", RegexOption.IGNORE_CASE)

    // Design 3-1 grades K (취소·거절·나중에·오늘 하루 보지 않기·7일간 닫기), P (설치·자세히·받기·열기)
    // and O (확인·OK): a button labelled like this is not a close or skip target, whatever its role says.
    private val riskyButtonKo = listOf(
        "취소", "거절", "나중에", "보지 않기", "다시 보지", "그만", "오늘", "하루", "일간",
        "설치", "자세히", "받기", "열기", "바로가기", "이동", "다운로드",
        "확인", "알겠",
    )
    private val riskyButtonEn = Regex(
        "$EN_START(cancel|decline|later|install|open|more|get|download|ok|okay|confirm|continue|got it|today|don't show|do not show)$EN_END",
        RegexOption.IGNORE_CASE,
    )

    private val advertisementKo = listOf("광고", "스폰서", "협찬", "프로모션", "특가", "쿠폰")
    // Upper-case only, so ordinary words containing "ad" are not advertisements.
    private val advertisementUpperEn = Regex("(?<![A-Za-z0-9_])AD(?![A-Za-z0-9_])")
    private val advertisementEn = Regex("$EN_START(ads|sponsored|advertisement)$EN_END", RegexOption.IGNORE_CASE)

    private val noticeKo = listOf("점검", "공지", "안내", "알림", "업데이트", "변경")

    // Wording that says the popup's own meaning is unconfirmed. Not "여부" alone: "참여 여부" is ordinary.
    private val uncertainKo = listOf("미확인", "확인되지 않", "알 수 없", "불명")

    fun isUserDecision(text: String): Boolean =
        userDecisionKo.any { it in text } || userDecisionEn.containsMatchIn(text)

    // R1 (OVERWARNING_STUDY.md), narrowed after A's review of #30. Only purchase and membership words
    // count, and only as the condition itself: "가입하면", "결제 시", "구매하면", "sign up and get".
    // Consent, permission, personal data, deletion, cancellation and transfers never do.
    private val conditionedDecision = Regex(
        """(결제|구매|구독|가입|로그인)\s?(하면|하시면|하고|할 때|하실 때|시(?=[\s,.!]|$))""" +
            """|$EN_START(sign up|subscribe|buy|purchase|pay|log in|sign in)$EN_END(\s+now)?\s+(and|to)\s+get$EN_END""" +
            """|$EN_START(when|if)\s+you\s+(sign up|subscribe|buy|purchase|pay|log in|sign in)$EN_END""",
        RegexOption.IGNORE_CASE,
    )
    // B_DEMO_RULES.md 1-7 (재호 11/1, LG 올리브영): a benefit *of* paying, named as a noun ("결제 혜택", "결제 할인",
    // "카드 결제 캐시백"), is a promotion of a payment method, not a payment step. Only the phrase is left out.
    private val paymentBenefit = Regex("""((카드|간편|계좌|현금|앱|[가-힣a-z]+페이)\s?)?결제\s?(시\s?)?(혜택|할인|적립|캐시백|이벤트|프로모션|쿠폰|리워드)""")

    // B_DEMO_RULES.md 9-1 (P02, real shops): a benefit *of* signing up, named as a noun ("신규 가입 이벤트", "회원가입 혜택",
    // "첫 가입 쿠폰"), names the promotion. It asks nothing; "가입하기", "가입하시겠어요?" stay decisions.
    private val memberBenefit = Regex("""(회원\s?)?가입\s?(이벤트|혜택|축하|쿠폰|선물|기념|포인트|적립금?)""")

    /** [text] without its benefit conditions (R1), payment and sign-up benefit phrases; meaningful only for a mention. */
    private fun stripBenefitConditions(text: String): String =
        memberBenefit.replace(paymentBenefit.replace(conditionedDecision.replace(text, " "), " "), " ")

    /** [text] as the decision checks should read it: benefit conditions left out only when it is a mention. */
    fun withoutBenefitConditions(text: String): String = if (isBenefitMention(text)) stripBenefitConditions(text) else text

    // The payment method named in front goes with it ("카드 결제 시 10% 할인"): the method is what is promoted.
    private val paymentCondition = Regex("""((카드|간편|계좌|현금|앱|[가-힣a-z]+페이)\s?)?결제\s?(하면|하시면|할 때|하실 때|시(?=[\s,.!]|$))""")

    /**
     * [text] without payment-method benefits only ("결제 혜택", "카드 결제 시 할인"), for a mention. Purchase and
     * membership conditions ("지금 구매하면 쿠폰", over-warning de-08) stay: they urge the decision itself.
     */
    fun withoutPaymentBenefits(text: String): String =
        if (isBenefitMention(text)) paymentBenefit.replace(paymentCondition.replace(text, " "), " ") else text

    private val benefitWord = Regex("""할인|쿠폰|포인트|혜택|이벤트|특가|무료|증정|적립|배송|$EN_START(off|free|bonus|coupon)$EN_END""", RegexOption.IGNORE_CASE)
    // A question or A's fixed summary ("결제 선택 필요") is always a decision, never a mention.
    private val asksForDecision = Regex("""\?|하시겠|할까요|시겠어요|필요""")

    /**
     * True only when every decision word in [text] sits inside a purchase or membership condition
     * of a benefit ("가입하면 포인트를 드립니다"). Any other decision word in the same text, such as
     * "개인정보 제공에 동의하세요" or "지금 결제하세요", keeps it a decision, as do questions, A's fixed
     * summaries and texts no longer than a button name (10 chars).
     */
    fun isBenefitMention(text: String): Boolean {
        // A short text is usually a button name, except a sign-up benefit named as a noun: it names no action to take.
        if ((text.length <= 10 && !memberBenefit.containsMatchIn(text)) || !benefitWord.containsMatchIn(text) || asksForDecision.containsMatchIn(text)) return false
        val rest = stripBenefitConditions(text)
        return rest != text && !isUserDecision(rest)
    }

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

    // ---- Auto-close kinds (AUTO_CLOSE.md 3절). Not used by the four-way display classifier yet. ----
    // Safety warnings (decision, scam, uncertain) are matched widely: on the text as written, on its
    // NFKC lower-case form without invisible characters, and on that form without any spaces, so
    // "허 용", "Ａｌｌｏｗ", "당​첨" or "당ㅤ첨" still count. Reasons to close (coupon, ad) and the
    // notice guard are matched on the spaced forms only, so removing spaces never invents a word
    // ("보 안경" is not 보안). English words use ASCII boundaries, not `\b`, whose meaning next to
    // Hangul differs between the JDK and Android (ICU): "upgrade하세요" must match on both.

    /** The text as written, its folded form, and the folded form with no spaces. */
    fun guardForms(text: String): List<String> {
        val folded = fold(text)
        return listOf(text, folded, folded.replace(" ", "")).distinct()
    }

    // Format characters (U+200B, U+FEFF...) and Hangul fillers that render as nothing.
    private val invisible = setOf('ᅟ', 'ᅠ', 'ㅤ', 'ﾠ', '⠀')

    private fun fold(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        .filterNot { Character.getType(it) == Character.FORMAT.toInt() || it in invisible }
        .replace(Regex("\\s+"), " ").trim()

    private fun spaced(text: String): List<String> = listOf(text, fold(text)).distinct()

    /** English words between ASCII boundaries, the same on the JDK and Android. */
    private fun en(words: String) = Regex("$EN_START(?:$words)$EN_END", RegexOption.IGNORE_CASE)

    // Today's English decision words (userDecisionEn) with ASCII boundaries, plus switching things on.
    private val decisionEn = en(
        "pay|payment|purchase|buy|subscribe|subscription|consent|agree|accept|permission|allow|delete|login|log in|" +
            "sign in|sign up|signup|account|personal (information|data)|turn on|enable",
    )
    // Consent-like wording that is not in today's list: 수락, 켜기, 선택하세요, bare 신청, 무료 체험.
    private val consentKo = Regex(
        "수락|켜\\s?(기|주세요|세요|시겠|두세요)|활성화\\s?(하기|하세요|해\\s?주세요)|선택\\s?(하세요|해\\s?주세요)|" +
            "(지금|바로|간편|무료)\\s?(신청|가입|구독|예약|주문)|무료\\s?체험|체험\\s?(시작|하기|신청)",
    )
    // Asking the person to do something with consequences (주문하기, 예약하시겠습니까, 신청하세요). Not
    // ordinary ad endings such as 참여하기, 시작하기, 이용해 보세요 or 다운로드하기: closing those declines nothing.
    private val actionRequestKo = Regex(
        "(주문|예약|신청|등록|갱신|연장|업그레이드|인증|충전|가입|구매|결제|구독|동의|로그인|체험|해지|취소|변경|수락|승인|송금|이체|탈퇴|삭제|허용)" +
            "\\s?(하기|하시겠|할까요|하세요|해\\s?주세요|하실래요|해\\s?보세요|하시려면|하러\\s?가기|합시다)",
    )
    private val actionRequestEn = en(
        "order now|place (an |your )?order|checkout|check out|proceed|upgrade|renew|book now|reserve|register|" +
            "apply now|install now|start (your )?(free )?trial|free trial|continue to|join now|enroll",
    )
    // Money taken later without asking again.
    private val recurringCharge = Regex("자동\\s?(갱신|연장|결제|이체)|정기\\s?(결제|구독)|${EN_START}auto[- ]?renew", RegexOption.IGNORE_CASE)
    // A polite question asking the person to act or accept ("주문하시겠어요?", "보러 가실래요?"). A bare
    // "?" is not one: "아직도 정가로 사세요?" or "오늘 뭐 먹지?" are only ad copy.
    private val askToAct = Regex("하시겠|겠습니까|시겠어요|시겠나요|할까요|드릴까요|실래요")

    /**
     * A decision in any wide form. A benefit condition ("가입하면 쿠폰") stays allowed as in R1, judged
     * on the spaced form (removing spaces would make every sentence look as short as a button);
     * whatever is left after removing those conditions is then checked in every form, so a decision
     * word hidden by spacing ("동 의") next to a benefit condition still counts.
     */
    fun isDecisionWide(text: String): Boolean {
        val forms = guardForms(text)
        val folded = fold(text)
        fun decisionWord(f: String) = isUserDecision(f) || decisionEn.containsMatchIn(f)
        val word = if (isBenefitMention(folded)) {
            guardForms(stripBenefitConditions(folded)).any(::decisionWord)
        } else {
            forms.any(::decisionWord)
        }
        return word || forms.any { f ->
            consentKo.containsMatchIn(f) || actionRequestKo.containsMatchIn(f) || actionRequestEn.containsMatchIn(f) ||
                recurringCharge.containsMatchIn(f) || askToAct.containsMatchIn(f)
        }
    }

    fun isDecisionLabelWide(label: String): Boolean = guardForms(label).any { f ->
        isDecisionButtonLabel(f) || decisionEn.containsMatchIn(f) || consentKo.containsMatchIn(f) ||
            actionRequestKo.containsMatchIn(f) || actionRequestEn.containsMatchIn(f)
    }

    // A concrete benefit the person can claim or already holds. Bare "포인트", "혜택", "보상" and
    // "증정" are not enough: "핵심 포인트", "혜택 받기", "광고를 보고 보상" and "1+1 증정" are not coupons.
    private val couponKo = Regex(
        "쿠폰|적립금|마일리지|캐시백|상품권|이용권|할인권|교환권|관람권|기프티콘|스탬프|룰렛|출석\\s?(체크|보상|이벤트)|" +
            "포인트\\s?(를|가|을)?\\s?(지급|적립|증정|선물|충전|드려|드립|받|쌓)|" +
            "([0-9][0-9,]*|천|만)\\s?(원|포인트|p)\\s?(이|가)?\\s?(적립|지급|캐시백)|" +
            "(적립|지급|증정|드려요|드립니다)\\S*\\s?([0-9][0-9,]*|천|만)\\s?포인트",
        RegexOption.IGNORE_CASE,
    )
    private val couponEn = en("coupons?|vouchers?|cashback|gift ?cards?|promo codes?|(bonus|reward) points|earn [0-9,]+ points")

    // Selling or promoting without naming a claimable benefit. "할인", "이벤트" and "세일" alone also
    // appear in notices ("할인 적용 오류 안내", "이벤트 종료 안내"), so they count only next to selling words.
    private val extraAdKo = Regex(
        // Not "출시" ("새로운 버전이 출시되었습니다", 82-sample no-06) and not bare "신상" ("신상정보 변경").
        "세일|사전\\s?예약|입점|신상품|신제품|신상\\s?(입고|출시|컬렉션)|오픈\\s?기념|" +
            "([0-9]+\\s?(%|퍼센트|원)|최대|전\\s?메뉴|특별)\\s?할인|할인\\s?(이벤트|행사|중)|" +
            // Getting the person into the shop's app (WEB_OVERLAY.md 3-2): "앱 열기", "앱으로 보기",
            // "앱 설치", "앱에서만 제공되는 혜택", "앱 한정 혜택". Not bare "앱에서 확인": "주문 내역은 앱에서
            // 확인해 주세요" is a notice. Buttons with these words stay unpressable (grades P and O).
            "앱\\s?(에서|으로)?\\s?(열기|보기)|앱\\s?(설치|다운로드)|앱\\s?(에서만|전용|한정)",
    )
    private val extraAdEn = en("sale|limited time offer|[0-9]+% off|free shipping")

    // A notice about the service or the person's own account: wins over coupon and ad words, so
    // "마일리지 소멸 안내", "쿠폰 적용 오류 안내" or "점검 보상 쿠폰" is told (or closed only by preset ①).
    private val strongNoticeKo = Regex(
        "점검|공지|오류|장애|지연|소멸|만료|환불|중단|보안|정책|약관|수신\\s?거부|처리\\s?(완료|되었|됐|됩)|" +
            "종료\\s?(되|됨|됐|됩|했|합니다|안내|예정)|변경\\s?(되|됨|됐|됩|했|합니다|안내|사항|예정)",
    )
    private val strongNoticeEn = en("maintenance|outage|terms of (service|use)|privacy policy|expired|expires?|expiring")

    // Prize-win, infection, hacking, cleaner and urgent-install claims: never a coupon or an ad to close.
    // "축하", "선정" and "1등" alone are ordinary ("생일 축하 쿠폰", "브랜드 대상 선정", "1등급 한우").
    private val suspiciousKo = Regex(
        "당첨|상금|경품\\s?(수령|당첨)|선정되셨|1등\\s?(에\\s?)?(당첨|선정)|축하.{0,20}(당첨|선정|경품)|(당첨|선정|경품).{0,20}축하|" +
            "감염|바이러스|악성\\s?코드|해킹|(휴대폰|휴대전화|기기|배터리|폰)(이|가)\\s?(위험|손상|느려)|(손상|감염)\\s?(되었|됐)|" +
            "(즉시|긴급)\\s?(설치|정리|치료|업데이트)|긴급\\s?업데이트|(메모리|저장\\s?공간|용량|캐시|정크)(이|가|을|를)?\\s?(부족|정리|청소|최적화)|" +
            "(휴대폰|폰|기기)(을|를)?\\s?(즉시|지금|바로)?\\s?(정리|청소|최적화)|무료\\s?(아이폰|갤럭시)",
    )
    private val suspiciousEn = en(
        "you('ve| have)? won|winner|congratulations|congrats|you('ve| have)? been selected|you are selected|viruse?s?|" +
            "infected|malware|hacked|claim (your )?(prize|reward)|clean (your )?phone|storage (is )?(almost )?full",
    )

    fun isCoupon(text: String): Boolean = spaced(text).any { couponKo.containsMatchIn(it) || couponEn.containsMatchIn(it) }

    /** The display classifier's advertisement words ("광고성 정보" excluded) plus selling words that are not coupons. */
    fun isPromotion(text: String): Boolean = spaced(text).any { f ->
        isAdvertisement(f.replace("광고성", "")) || extraAdKo.containsMatchIn(f) || extraAdEn.containsMatchIn(f)
    }

    fun isStrongNotice(text: String): Boolean =
        spaced(text).any { strongNoticeKo.containsMatchIn(it) || strongNoticeEn.containsMatchIn(it) }

    fun isSuspicious(text: String): Boolean =
        guardForms(text).any { suspiciousKo.containsMatchIn(it) || suspiciousEn.containsMatchIn(it) }

    fun isUncertainWide(text: String): Boolean = guardForms(text).any { isUncertain(it) }
}
