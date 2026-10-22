package kr.closeforyou.decision

import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Study for the "advertisement shown as a decision" over-warning (CLASSIFIER_EVAL review 6; seen on
 * the emulator in P02 "가입하면 포인트" and P18 "5초 안에 결제하면 추가 할인"). Nothing in B's
 * production code changes here: the candidate rules live in this test so the team can compare
 * numbers before deciding (OVERWARNING_STUDY.md).
 *
 * A new held-out set, written for this question and not taken from the 82-sample evaluation set.
 * Each sample is the popup's tree texts in reading order, labelled by a person before running.
 * Button labels are texts too (A's analysis carries no clickability for texts).
 */
class DecisionOverWarningStudyTest {
    private enum class Truth { AD, NOTICE, DECISION }
    private data class Sample(val id: String, val truth: Truth, val texts: List<String>)

    private val samples = listOf(
        // Advertisements whose body mentions a decision word as a benefit condition.
        Sample("ad-01", Truth.AD, listOf("신규 가입 이벤트", "가입하면 포인트를 드립니다. 시험용 광고입니다.", "자세히 보기", "닫기")),
        Sample("ad-02", Truth.AD, listOf("타임세일(시험용)", "세일 종료까지 04:57", "오늘 주문하면 내일 오전 10시 도착. 5초 안에 결제하면 추가 할인(시험용).", "자세히 보기", "닫기")),
        Sample("ad-03", Truth.AD, listOf("광고", "지금 구매하면 쿠폰 2장 증정", "닫기")),
        Sample("ad-04", Truth.AD, listOf("광고", "앱 첫 결제 시 30% 할인 쿠폰", "쿠폰 받기", "닫기")),
        Sample("ad-05", Truth.AD, listOf("광고", "회원가입하고 웰컴 혜택 받으세요", "닫기")),
        Sample("ad-06", Truth.AD, listOf("Sponsored", "Buy one get one free", "Close")),
        Sample("ad-07", Truth.AD, listOf("프로모션", "구독하면 첫 달 무료", "자세히 보기", "닫기")),
        Sample("ad-08", Truth.AD, listOf("특가", "카드 결제 시 5% 추가 할인", "닫기")),
        Sample("ad-09", Truth.AD, listOf("이벤트 광고", "로그인하고 쿠폰 받기", "닫기")),
        Sample("ad-10", Truth.AD, listOf("광고", "친구 초대하고 가입하면 1,000포인트 적립", "닫기")),
        Sample("ad-11", Truth.AD, listOf("Ad", "Sign up now and get 10% off", "Close")),
        Sample("ad-12", Truth.AD, listOf("광고", "지금 구매하면 무료 배송", "자세히 보기", "닫기")),
        // Notices that mention a decision word in a statement.
        Sample("no-01", Truth.NOTICE, listOf("서비스 점검 안내", "10월 25일 02:00~04:00 동안 결제 기능을 쓸 수 없습니다.", "닫기")),
        Sample("no-02", Truth.NOTICE, listOf("결제 수단 점검 공지", "일부 카드 결제가 지연될 수 있습니다.", "닫기")),
        Sample("no-03", Truth.NOTICE, listOf("로그인 서버 점검 공지", "오늘 밤 로그인이 잠시 안 됩니다.", "닫기")),
        Sample("no-04", Truth.NOTICE, listOf("업데이트 안내", "새 버전에서 결제 화면이 바뀌었어요.", "닫기")),
        Sample("no-05", Truth.NOTICE, listOf("개인정보 처리방침 변경 안내", "11월 1일부터 적용됩니다.", "확인")),
        // Real decisions, including advertisements that carry a real decision button.
        Sample("de-01", Truth.DECISION, listOf("결제 확인", "시험용 상품 12,900원을 결제할까요?", "취소", "결제하기")),
        Sample("de-02", Truth.DECISION, listOf("마케팅 정보 수신 동의", "이벤트 소식을 받으시겠어요?", "거부", "동의")),
        Sample("de-03", Truth.DECISION, listOf("프리미엄 구독", "월 9,900원으로 구독하시겠습니까?", "나중에", "구독하기")),
        Sample("de-04", Truth.DECISION, listOf("권한 요청", "위치 권한을 허용하시겠어요?", "허용 안 함", "허용")),
        Sample("de-05", Truth.DECISION, listOf("계정 삭제", "정말 삭제하시겠습니까?", "취소", "삭제")),
        Sample("de-06", Truth.DECISION, listOf("결제 선택 필요")),
        Sample("de-07", Truth.DECISION, listOf("동의 선택 필요")),
        Sample("de-08", Truth.DECISION, listOf("광고", "지금 구매하면 쿠폰 증정", "구매하기", "닫기")),
        Sample("de-09", Truth.DECISION, listOf("무료 체험 시작", "7일 무료 후 월 4,900원 결제", "체험 시작하기", "닫기")),
        Sample("de-10", Truth.DECISION, listOf("Subscribe to Premium?", "Cancel", "Subscribe")),
        Sample("de-11", Truth.DECISION, listOf("Allow notifications?", "Don't allow", "Allow")),
        Sample("de-12", Truth.DECISION, listOf("광고", "가입하면 포인트 지급", "가입하기", "닫기")),
        Sample("de-13", Truth.DECISION, listOf("로그인이 필요합니다", "로그인하시겠어요?", "로그인", "취소")),
        Sample("de-14", Truth.DECISION, listOf("광고", "결제하면 추가 할인!", "결제하기", "닫기")),
        Sample("de-15", Truth.DECISION, listOf("자동 결제 동의", "다음 달부터 자동 결제됩니다. 동의하시겠어요?", "동의", "닫기")),
        Sample("de-16", Truth.DECISION, listOf("Purchase for $4.99?", "Buy", "Cancel")),
        Sample("de-17", Truth.DECISION, listOf("쿠폰 받으려면 가입이 필요해요", "지금 가입하시겠어요?", "가입하기", "닫기")),
        Sample("de-18", Truth.DECISION, listOf("광고", "구독하고 혜택 받기", "구독", "닫기")),
    )

    private fun analysis(texts: List<String>): PopupAnalysis {
        val base = MockPopupFixtures.notice().analysis
        val bounds = base.texts.first().bounds
        return base.copy(
            texts = texts.map { ExtractedText(it, bounds, TextSource.ACCESSIBILITY_TREE, null) }.toImmutableList(),
            closeCandidates = kotlinx.collections.immutable.persistentListOf(),
            limitations = kotlinx.collections.immutable.persistentSetOf(),
        )
    }

    private fun Truth.matches(c: PopupClassification) = when (this) {
        Truth.AD -> c == PopupClassification.ADVERTISEMENT
        Truth.NOTICE -> c == PopupClassification.NOTICE
        Truth.DECISION -> c == PopupClassification.USER_DECISION
    }

    // ---- candidate rule R1: a decision word only as a benefit condition inside an advertisement ----

    private val decisionWord = Regex("결제|구매|구독|동의|권한|허용|삭제|가입|로그인|계정|개인정보|승인|송금|이체|해지|탈퇴|체험|\\b(pay|payment|purchase|buy|subscribe|subscription|consent|agree|accept|permission|allow|delete|login|log in|sign in|sign up|account|trial)\\b", RegexOption.IGNORE_CASE)
    private val condition = Regex("하면|하고|\\S+\\s*시\\s|할 때|\\band get\\b|\\bwhen you\\b|\\bif you\\b", RegexOption.IGNORE_CASE)
    private val benefit = Regex("할인|쿠폰|포인트|혜택|이벤트|특가|무료|증정|적립|배송|\\boff\\b|\\bfree\\b|\\bbonus\\b|\\bcoupon\\b", RegexOption.IGNORE_CASE)
    private val question = Regex("\\?|하시겠|할까요|시겠어요|필요")

    /** A decision word is a "benefit mention" only inside a longer sentence with a condition and a benefit. */
    private fun isBenefitMention(text: String) =
        decisionWord.containsMatchIn(text) && condition.containsMatchIn(text) && benefit.containsMatchIn(text) &&
            !question.containsMatchIn(text) && text.length > 10

    /**
     * R1: when every text with a decision word is a benefit mention, classify without them;
     * any other decision text (a button such as "구매하기", a question, A's summary) keeps it a decision.
     */
    private fun candidateR1(texts: List<String>): PopupClassification {
        val decisionTexts = texts.filter { decisionWord.containsMatchIn(it) }
        if (decisionTexts.isEmpty() || !decisionTexts.all(::isBenefitMention)) return current(texts)
        return current(texts.map { if (isBenefitMention(it)) decisionWord.replace(it, "○") else it })
    }

    // ---- candidate rule R2 = R1 + a decision word only inside a notice's statement ----

    private val noticeWord = Regex("점검|공지|안내|알림|업데이트|변경")
    private val statementEnd = Regex("(니다|어요|에요|돼요|해요)[.!]?\\s*$")

    /** A decision word inside a full sentence that only reports something, in a popup with a notice word. */
    private fun isNoticeStatement(text: String, texts: List<String>) =
        decisionWord.containsMatchIn(text) && statementEnd.containsMatchIn(text) && !question.containsMatchIn(text) &&
            text.length > 10 && texts.any { noticeWord.containsMatchIn(it) }

    private fun candidateR2(texts: List<String>): PopupClassification {
        val mention = { t: String -> isBenefitMention(t) || isNoticeStatement(t, texts) }
        val decisionTexts = texts.filter { decisionWord.containsMatchIn(it) }
        if (decisionTexts.isEmpty() || !decisionTexts.all(mention)) return current(texts)
        return current(texts.map { if (mention(it)) decisionWord.replace(it, "○") else it })
    }

    private fun current(texts: List<String>) = kotlinx.coroutines.runBlocking { DecisionPopupClassifier().classify(analysis(texts)) }

    private data class Score(val correct: Int, val dangerous: Int, val overWarning: Int, val wrongIds: List<String>)

    private fun score(classify: (List<String>) -> PopupClassification): Score {
        var correct = 0; var dangerous = 0; var over = 0; val wrong = mutableListOf<String>()
        for (s in samples) {
            val c = classify(s.texts)
            if (s.truth.matches(c)) correct++ else wrong += "${s.id}→$c"
            if (s.truth == Truth.DECISION && (c == PopupClassification.ADVERTISEMENT || c == PopupClassification.NOTICE)) dangerous++
            if (s.truth != Truth.DECISION && c == PopupClassification.USER_DECISION) over++
        }
        return Score(correct, dangerous, over, wrong)
    }

    @Test fun setIsBalancedAndHeldOut() {
        assertEquals(35, samples.size)
        assertEquals(samples.size, samples.map { it.id }.toSet().size)
        assertEquals(18, samples.count { it.truth == Truth.DECISION })
    }

    @Test fun currentClassifierOverWarnsButNeverMissesADecision() {
        val s = score(::current)
        println("STUDY current $s")
        assertEquals("no decision shown as an ad or notice", 0, s.dangerous)
        assertEquals(CURRENT_OVER_WARNINGS, s.overWarning)
    }

    @Test fun productionNowMatchesCandidateR1OnEverySample() {
        // R1 went into hasUserDecisionSignal (2026-10-22): the classifier must agree with the study's rule.
        for (sample in samples) assertEquals(sample.id, candidateR1(sample.texts), current(sample.texts))
    }

    @Test fun candidateR1RemovesBenefitOverWarningsAndKeepsEveryDecision() {
        val s = score(::candidateR1)
        println("STUDY R1 $s")
        assertEquals("R1 must not show any decision as an ad or notice", 0, s.dangerous)
        assertTrue("R1 must not add over-warnings", s.overWarning <= CURRENT_OVER_WARNINGS)
        assertEquals(R1_OVER_WARNINGS, s.overWarning)
    }

    @Test fun candidateR2AlsoHandlesNoticeStatementsAndKeepsEveryDecision() {
        val s = score(::candidateR2)
        println("STUDY R2 $s")
        assertEquals("R2 must not show any decision as an ad or notice", 0, s.dangerous)
        assertTrue("R2 must not add over-warnings", s.overWarning <= R1_OVER_WARNINGS)
        assertEquals(R2_OVER_WARNINGS, s.overWarning)
    }

    private companion object {
        /** 17 before R1 (study, 2026-10-22); 7 with R1 in production. */
        const val CURRENT_OVER_WARNINGS = 7
        const val R1_OVER_WARNINGS = 7
        const val R2_OVER_WARNINGS = 5
    }
}
