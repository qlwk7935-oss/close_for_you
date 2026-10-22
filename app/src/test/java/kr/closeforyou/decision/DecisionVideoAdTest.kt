package kr.closeforyou.decision

import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionVideoAdTest {
    private val video = MockPopupFixtures.videoWithoutAdEvidence().analysis
    private val bounds = video.texts.first().bounds

    private fun text(value: String, source: TextSource = TextSource.ACCESSIBILITY_TREE) = ExtractedText(value, bounds, source, null)

    private fun analysis(vararg texts: ExtractedText, candidates: List<CloseCandidate> = emptyList(), videoSurface: Boolean = true) =
        video.copy(texts = texts.toList().toPersistentList(), closeCandidates = candidates.toPersistentList(), videoSurfaceObserved = videoSurface)

    private fun skip(id: String, label: String?, evidence: EvidenceLevel = EvidenceLevel.E2_LABELED_NODE, role: ButtonRole = ButtonRole.SKIP) =
        CloseCandidate(id, "0/$id", null, label, bounds, evidence, role)

    @Test fun aVideoAloneIsNotAVideoAd() {
        val report = DecisionVideoAd.inspect(video)
        assertTrue(report.videoObserved)
        assertFalse(report.advertisementEvidence)
        assertFalse(report.isVideoAd)
        assertNull(report.countdownSeconds)
    }

    @Test fun advertisementEvidenceNeedsTreeText() {
        assertTrue(DecisionVideoAd.inspect(analysis(text("광고 · 가상 상품"))).isVideoAd)
        assertFalse("OCR alone is not evidence", DecisionVideoAd.inspect(analysis(text("광고 · 가상 상품", TextSource.OCR))).advertisementEvidence)
        assertFalse("ad wording without a video is not a video ad", DecisionVideoAd.inspect(analysis(text("광고"), videoSurface = false)).isVideoAd)
        // Same rule as the classifier: self-doubting wording or a decision signal wins over "광고".
        assertFalse(DecisionVideoAd.inspect(analysis(text("광고 여부 미확인"))).advertisementEvidence)
        assertFalse(DecisionVideoAd.inspect(analysis(text("광고 · 구독 결제"))).advertisementEvidence)
    }

    @Test fun countdownsAreReadOnlyWhenWrittenOnScreen() {
        val cases = mapOf(
            "5초 후 건너뛰기" to 5, "광고는 10초 뒤에 건너뛸 수 있어요" to 10, "건너뛰기까지 3초" to 3,
            "Skip ad in 5" to 5, "You can skip this ad in 7s" to 7, "Skip in 12 seconds" to 12, "５초 후 건너뛰기" to 5,
        )
        for ((value, seconds) in cases) assertEquals(value, seconds, DecisionVideoAd.countdownIn(value))
        for (value in listOf("광고 건너뛰기", "Skip Ad", "건너뛰기", "1:30", "2026년 10월 21일", "in 5 seconds", "0초 후", "999초 후 건너뛰기", "5초")) {
            assertNull(value, DecisionVideoAd.countdownIn(value))
        }
    }

    @Test fun timesUnrelatedToSkippingAreNotSkipCountdowns() {
        // Review on #20 (A): only a wait for skipping may be announced as one.
        for (value in listOf(
            "5초 후 결제", "가입까지 5초", "5초 후", "3초 뒤 자동으로 다음 영상", "다음 영상까지 5초",
            "5초 후 결제 건너뛰기", "구독하면 3초 뒤 건너뛸 수 있어요", "Pay in 5 seconds", "Skip in 5 and start free trial",
            "Next video in 5s", "5초 후 앱 설치",
        )) {
            assertNull(value, DecisionVideoAd.countdownIn(value))
        }
        // A screen that shows only an unrelated time reports no skip wait at all.
        val report = DecisionVideoAd.inspect(analysis(text("광고"), text("5초 후 결제"), text("다음 영상까지 3초")))
        assertNull(report.countdownSeconds)
        assertNull(report.countdownSource)
        // ...and an unrelated time next to a real skip countdown does not make them disagree.
        assertEquals(5, DecisionVideoAd.inspect(analysis(text("광고"), text("다음 영상까지 3초"), text("5초 후 건너뛰기"))).countdownSeconds)
    }

    @Test fun conflictingCountdownsAreNotAnnounced() {
        val one = DecisionVideoAd.inspect(analysis(text("광고"), text("5초 후 건너뛰기"), text("5초 후 건너뛰기", TextSource.OCR)))
        assertEquals(5, one.countdownSeconds)
        assertEquals("the tree reading is preferred", TextSource.ACCESSIBILITY_TREE, one.countdownSource)
        val ocrOnly = DecisionVideoAd.inspect(analysis(text("광고"), text("3초 후 건너뛰기", TextSource.OCR)))
        assertEquals(TextSource.OCR, ocrOnly.countdownSource)
        val conflict = DecisionVideoAd.inspect(analysis(text("광고"), text("5초 후 건너뛰기"), text("3초 후 건너뛰기", TextSource.OCR)))
        assertNull(conflict.countdownSeconds)
        assertNull(conflict.countdownSource)
    }

    @Test fun onlyRealSkipButtonsAreListed() {
        val report = DecisionVideoAd.inspect(analysis(text("광고"), candidates = listOf(
            skip("a", "광고 건너뛰기"),
            skip("b", "5초 후 건너뛰기"),
            skip("c", null, EvidenceLevel.E1_DISMISS),
            skip("d", "Skip Ad", EvidenceLevel.E3_USER_BOUND_NODE),
            skip("e", "닫기", role = ButtonRole.CLOSE),
            skip("f", "닫기"),
            skip("g", "Skip Ad", EvidenceLevel.E1_DISMISS),
        )))
        assertEquals(listOf("a", "g"), report.skipCandidateIds)
    }

    @Test fun theReportCarriesNoScreenText() {
        val report = DecisionVideoAd.inspect(analysis(text("광고 · 비밀 상품명"), text("5초 후 건너뛰기")))
        assertFalse(report.toString().contains("비밀"))
        assertFalse(report.toString().contains("건너뛰기"))
    }

    @Test fun classifierStillDoesNotCallAVideoAnAdvertisementWithoutEvidence() = kotlinx.coroutines.runBlocking {
        assertEquals(PopupClassification.UNKNOWN, DecisionPopupClassifier().classify(video))
        assertEquals(PopupClassification.ADVERTISEMENT, DecisionPopupClassifier().classify(analysis(text("광고 · 가상 상품"))))
        assertEquals(persistentListOf<String>(), DecisionVideoAd.inspect(video).skipCandidateIds)
    }
}
