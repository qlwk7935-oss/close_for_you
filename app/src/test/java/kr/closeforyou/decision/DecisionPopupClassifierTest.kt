package kr.closeforyou.decision

import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class DecisionPopupClassifierTest {
    private val classifier = DecisionPopupClassifier()
    private val bounds = ScreenBounds(80, 400, 1000, 1450)

    // A's runtime marks every analysis like this and does not produce close candidates yet.
    private val realLimitations = persistentSetOf(AnalysisLimit.INCOMPLETE_EVIDENCE, AnalysisLimit.IMAGE_MODEL_NOT_IMPLEMENTED)

    @Test fun fixturesMatchTheirExpectedDisplayClassification() = runBlocking {
        for (fixture in MockPopupFixtures.all()) {
            assertEquals(fixture.scenarioId, fixture.expectedDisplayClassification, classifier.classify(fixture.analysis))
        }
    }

    @Test fun realRuntimeLimitationsStillShowAdvertisementsAndNotices() = runBlocking {
        val ad = MockPopupFixtures.advertisement().analysis
        val notice = MockPopupFixtures.notice().analysis
        assertEquals(PopupClassification.ADVERTISEMENT, classifier.classify(ad.copy(limitations = realLimitations, closeCandidates = persistentListOf())))
        assertEquals(PopupClassification.NOTICE, classifier.classify(notice.copy(limitations = realLimitations, closeCandidates = persistentListOf())))
    }

    @Test fun fixedDecisionSummariesFromAAreUserDecisions() = runBlocking {
        val summaries = listOf("결제 선택 필요", "구매 선택 필요", "구독 선택 필요", "동의 선택 필요", "권한 허용 선택 필요", "삭제 선택 필요")
        for (summary in summaries) {
            for (source in TextSource.entries) {
                assertEquals(summary, PopupClassification.USER_DECISION, classify(summary to source))
            }
        }
    }

    @Test fun ocrOnlyAdvertisementWordingIsNeitherAnAdvertisementNorANotice() = runBlocking {
        assertEquals(PopupClassification.UNKNOWN, classify("광고 · 가상 체험 상품 안내" to TextSource.OCR))
        assertEquals(PopupClassification.UNKNOWN, classify("AD" to TextSource.OCR))
        assertEquals(PopupClassification.NOTICE, classify("가상 서비스 점검 안내" to TextSource.OCR))
    }

    @Test fun videoSurfaceAloneIsNotAnAdvertisement() = runBlocking {
        val video = analysis("가상 영상 창" to TextSource.ACCESSIBILITY_TREE).copy(videoSurfaceObserved = true)
        assertEquals(PopupClassification.UNKNOWN, classifier.classify(video))
    }

    @Test fun decisionWordingWinsOverAdvertisementAndNoticeWording() = runBlocking {
        assertEquals(PopupClassification.USER_DECISION, classify("광고 · 가상 상품 구매 안내" to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.USER_DECISION, classify("가상 서비스 로그인 안내" to TextSource.ACCESSIBILITY_TREE))
        // Design F7 decision signals.
        assertEquals(PopupClassification.USER_DECISION, classify("가상 계정 연결 안내" to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.USER_DECISION, classify("가상 개인정보 수집 안내" to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.USER_DECISION, classify("Link your account" to TextSource.ACCESSIBILITY_TREE))
    }

    @Test fun onlyWordingThatDoubtsItselfIsUncertain() = runBlocking {
        assertEquals(PopupClassification.ADVERTISEMENT, classify("광고 · 가상 이벤트 참여 여부 선택" to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.UNKNOWN, classify("광고 여부 불명" to TextSource.ACCESSIBILITY_TREE))
    }

    @Test fun installAdvertisementStaysAnAdvertisement() = runBlocking {
        assertEquals(PopupClassification.ADVERTISEMENT, classify("광고 · 가상 앱 설치하고 쿠폰 받기" to TextSource.ACCESSIBILITY_TREE))
    }

    @Test fun decisionButtonLabelOrRoleMakesAUserDecision() = runBlocking {
        val ad = MockPopupFixtures.advertisement().analysis
        val candidate = ad.closeCandidates.single()
        val trialButton = ad.copy(closeCandidates = persistentListOf(candidate.copy(label = "무료 체험 시작")))
        val decisionRole = ad.copy(closeCandidates = persistentListOf(candidate.copy(role = ButtonRole.USER_DECISION)))
        assertEquals(PopupClassification.USER_DECISION, classifier.classify(trialButton))
        assertEquals(PopupClassification.USER_DECISION, classifier.classify(decisionRole))
    }

    @Test fun englishWordsUseWordBoundaries() = runBlocking {
        assertEquals(PopupClassification.ADVERTISEMENT, classify("AD" to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.ADVERTISEMENT, classify("Sponsored" to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.UNKNOWN, classify("Download complete" to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.UNKNOWN, classify("Please read this" to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.USER_DECISION, classify("I agree" to TextSource.ACCESSIBILITY_TREE))
    }

    @Test fun blankOrMissingTextIsUnknown() = runBlocking {
        assertEquals(PopupClassification.UNKNOWN, classify("   " to TextSource.ACCESSIBILITY_TREE))
        assertEquals(PopupClassification.UNKNOWN, classifier.classify(analysis()))
    }

    /** Review on #30 (A): a real request beside a benefit condition must stay a decision. */
    @Test fun benefitConditionNextToARealRequestStaysADecision() = runBlocking {
        val tree = TextSource.ACCESSIBILITY_TREE
        for (text in listOf(
            "가입하면 포인트를 드립니다. 개인정보 제공에 동의하세요",   // A's counterexample: two sentences
            "가입하면 포인트를 드리니 지금 동의하세요",               // one sentence
            "결제하면 추가 할인, 지금 결제하세요",                    // the same word as a direct request
            "구독하면 첫 달 무료. 구독하세요",
            "동의하면 쿠폰 지급",                                     // consent is never a benefit condition
            "개인정보 제공 시 1,000포인트 적립",
            "권한을 허용하면 할인 쿠폰 증정",
            "Sign up now and get 10% off. Please agree to the terms",
            "If you subscribe you get a free month and we delete your old plan",
            "가입하면 포인트 지급, 결제 정보 등록 필요",                // a request word
        )) {
            assertEquals(text, PopupClassification.USER_DECISION, classify("광고" to tree, text to tree))
        }
        // The narrowed R1 still lets a pure benefit condition through.
        for (text in listOf("가입하면 포인트를 드립니다. 시험용 광고입니다.", "앱 첫 결제 시 30% 할인 쿠폰", "회원가입하고 웰컴 혜택 받으세요")) {
            assertEquals(text, PopupClassification.ADVERTISEMENT, classify("광고" to tree, text to tree))
        }
    }

    private suspend fun classify(vararg texts: Pair<String, TextSource>) = classifier.classify(analysis(*texts))

    private fun analysis(vararg texts: Pair<String, TextSource>) = PopupAnalysis(
        token = MockPopupFixtures.notice().snapshot.token,
        texts = texts.map { (text, source) -> ExtractedText(text, bounds, source, null) }.toPersistentList(),
        limitations = realLimitations,
    )
}
