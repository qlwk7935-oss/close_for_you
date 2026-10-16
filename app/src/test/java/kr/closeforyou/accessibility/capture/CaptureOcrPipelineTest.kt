package kr.closeforyou.accessibility.capture

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureOcrPipelineTest {
    private val box = ScreenBounds(100, 500, 900, 560)
    private fun imagePopup(): PopupSnapshot {
        val source = MockPopupFixtures.notice().snapshot
        return source.copy(nodes = source.nodes.toPersistentList().add(NodeSnapshot(
            "0/image", null, "android.widget.ImageView", null, null, false, true,
            ScreenBounds(100, 500, 950, 1300),
        )))
    }
    private class Frame(override val bounds: ScreenBounds) : CaptureFrame {
        var closed = false
        override fun close() { closed = true }
    }
    private class Shots : ScreenshotPort {
        var issue: EvidenceIssue? = null
        var calls = 0
        var frame: Frame? = null
        var afterCapture: () -> Unit = {}
        var failure: EvidenceIssue? = null
        override fun denial(token: SnapshotToken) = issue
        override fun availability(token: SnapshotToken) = if (issue == null) CaptureAvailability.AVAILABLE else CaptureAvailability.PRIVACY_BLOCKED
        override suspend fun capture(snapshot: PopupSnapshot, region: CaptureRegion): CaptureFrame {
            calls++
            failure?.let { throw EvidenceFailure(it) }
            return Frame(region.bounds).also { frame = it; afterCapture() }
        }
    }

    @Test fun koreanNoticeDatesTimesAndAmountsRemainUnknownEvidence() = runTest {
        for (text in listOf("점검 공지", "2026년 10월 18일 02시부터 04시까지 점검", "행사 가격 10,000원")) {
            val shots = Shots()
            var checks = 0
            val pipeline = CaptureOcrPipeline(shots, OcrPort { listOf(OcrLine(text, box)) }, { true }, { checks++; null })
            val result = pipeline.analyze(imagePopup()) as EvidenceOutcome.Ready
            assertEquals(EvidenceIssue.NONE, result.issue)
            val ocr = result.analysis.texts.single { it.source == TextSource.OCR }
            assertEquals(text, ocr.text)
            assertEquals(box, ocr.bounds)
            assertNull(ocr.confidence)
            assertEquals(PopupClassification.UNKNOWN, result.analysis.classification)
            assertTrue(result.analysis.closeCandidates.isEmpty())
            assertEquals(3, checks)
            assertTrue(shots.frame!!.closed)
        }
    }

    @Test fun agreeingTreeAndOcrKeepBothSourcesAndWithinSourceDuplicatesCollapse() = runTest {
        val snapshot = imagePopup()
        val original = snapshot.nodes.first()
        val shots = Shots()
        val pipeline = CaptureOcrPipeline(shots,
            OcrPort { listOf(OcrLine(original.text!!, original.bounds), OcrLine(original.text, original.bounds)) }, { true }, { null })
        val result = pipeline.analyze(snapshot) as EvidenceOutcome.Ready
        val same = result.analysis.texts.filter { it.text == original.text && it.bounds == original.bounds }
        assertEquals(setOf(TextSource.OCR, TextSource.ACCESSIBILITY_TREE), same.map { it.source }.toSet())
        assertEquals(2, same.size)
    }

    @Test fun readingOrderUsesScreenTopThenLeft() = runTest {
        val lines = listOf(OcrLine("둘째 줄", ScreenBounds(100, 800, 900, 850)),
            OcrLine("첫째 오른쪽", ScreenBounds(500, 500, 900, 550)),
            OcrLine("첫째 왼쪽", ScreenBounds(100, 500, 450, 550)))
        val result = CaptureOcrPipeline(Shots(), OcrPort { lines }, { true }, { null }).analyze(imagePopup()) as EvidenceOutcome.Ready
        assertEquals(listOf("첫째 왼쪽", "첫째 오른쪽", "둘째 줄"), result.analysis.texts.filter { it.source == TextSource.OCR }.map { it.text })
    }

    @Test fun noConsentDisabledServiceAndMissingCapabilityNeverCapture() = runTest {
        for (issue in listOf(EvidenceIssue.NOT_REQUESTED, EvidenceIssue.SERVICE_DISABLED, EvidenceIssue.CAPABILITY_MISSING)) {
            val shots = Shots().also { it.issue = issue }
            val result = CaptureOcrPipeline(shots, OcrPort { error("OCR must not start") }, { true }, { null })
                .analyze(imagePopup()) as EvidenceOutcome.Ready
            assertEquals(0, shots.calls)
            assertTrue(result.analysis.texts.all { it.source == TextSource.ACCESSIBILITY_TREE })
            assertEquals(issue, result.issue)
        }
    }

    @Test fun f0aPasswordStopsBeforeScreenshot() = runTest {
        val shots = Shots()
        val result = CaptureOcrPipeline(shots, OcrPort { error("No OCR") }, { true }, { PrivacyBlockReason.PASSWORD })
            .analyze(imagePopup()) as EvidenceOutcome.Blocked
        assertEquals(PrivacyBlockReason.PASSWORD, result.reason)
        assertEquals(0, shots.calls)
    }

    @Test fun f0bSensitiveImagesExposeNoAnalysisAndReleasePixels() = runTest {
        for (text in listOf("비밀번호 입력", "인증번호 654321", "계좌 123-456-789012", "카드 1234 5678 9012 3456",
            "결제 화면", "금융 정보 안내", "은행 잔액 안내", "로그인 안내", "사용자 sample@example.test", "연락처 010-0000-0000")) {
            val shots = Shots()
            val result = CaptureOcrPipeline(shots, OcrPort { listOf(OcrLine(text, box)) }, { true }, { null })
                .analyze(imagePopup())
            assertTrue(result is EvidenceOutcome.Blocked)
            assertTrue(shots.frame!!.closed)
            assertFalse(result.toString().contains(text))
        }
    }

    @Test fun textlessImageAndUncertainGeometryNeverExposeOcr() = runTest {
        for (lines in listOf(emptyList(), listOf(OcrLine("점검 공지", ScreenBounds(0, 0, 50, 50))),
            listOf(OcrLine("점검 공지", box, 0.1f)))) {
            val shots = Shots()
            val result = CaptureOcrPipeline(shots, OcrPort { lines }, { true }, { null }).analyze(imagePopup()) as EvidenceOutcome.Ready
            assertEquals(EvidenceIssue.PRIVACY_UNCERTAIN, result.issue)
            assertTrue(result.analysis.texts.none { it.source == TextSource.OCR })
            assertTrue(shots.frame!!.closed)
        }
    }

    @Test fun treeSufficientOrUncertainRegionAvoidUnnecessaryCapture() = runTest {
        val shots = Shots()
        val pipeline = CaptureOcrPipeline(shots, OcrPort { error("No OCR") }, { true }, { null })
        assertEquals(EvidenceIssue.TREE_SUFFICIENT, (pipeline.analyze(MockPopupFixtures.notice().snapshot) as EvidenceOutcome.Ready).issue)
        val full = imagePopup().copy(windows = persistentListOf(
            imagePopup().windows.first().copy(bounds = imagePopup().screenBounds)))
        assertEquals(EvidenceIssue.REGION_UNCERTAIN, (pipeline.analyze(full) as EvidenceOutcome.Ready).issue)
        assertEquals(0, shots.calls)
    }

    @Test fun secureCaptureAndOcrFailuresKeepSafeTreeOnly() = runTest {
        for (issue in listOf(EvidenceIssue.SECURE_WINDOW, EvidenceIssue.CAPTURE_FAILED, EvidenceIssue.CAPTURE_TIMEOUT)) {
            val shots = Shots().also { it.failure = issue }
            val result = CaptureOcrPipeline(shots, OcrPort { error("No OCR") }, { true }, { null }).analyze(imagePopup()) as EvidenceOutcome.Ready
            assertEquals(issue, result.issue)
            assertFalse(result.analysis.texts.isEmpty())
            assertTrue(result.analysis.texts.none { it.source == TextSource.OCR })
        }
        val shots = Shots()
        val failed = CaptureOcrPipeline(shots, OcrPort { throw EvidenceFailure(EvidenceIssue.OCR_FAILED) }, { true }, { null })
            .analyze(imagePopup()) as EvidenceOutcome.Ready
        assertEquals(EvidenceIssue.OCR_FAILED, failed.issue)
        assertTrue(shots.frame!!.closed)
    }

    @Test fun ocrTimeoutDiscardsOcrAndClosesFrame() = runTest {
        val shots = Shots()
        val result = CaptureOcrPipeline(shots, OcrPort { awaitCancellation() }, { true }, { null }, 50)
            .analyze(imagePopup()) as EvidenceOutcome.Ready
        assertEquals(EvidenceIssue.OCR_TIMEOUT, result.issue)
        assertTrue(result.analysis.texts.none { it.source == TextSource.OCR })
        assertTrue(shots.frame!!.closed)
    }

    @Test fun screenChangeAfterCaptureCancelsBeforeOcr() = runTest {
        var current = true
        val shots = Shots().also { it.afterCapture = { current = false } }
        val job = async { CaptureOcrPipeline(shots, OcrPort { error("No OCR") }, { current }, { null }).analyze(imagePopup()) }
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(shots.frame!!.closed)
    }

    @Test fun screenChangeDuringOcrDropsLateResult() = runTest {
        var current = true
        val shots = Shots()
        val released = CompletableDeferred<Unit>()
        val job = async {
            CaptureOcrPipeline(shots, OcrPort { released.await(); listOf(OcrLine("점검 공지", box)) }, { current }, { null })
                .analyze(imagePopup())
        }
        runCurrent()
        current = false
        released.complete(Unit)
        runCurrent()
        assertTrue(job.isCancelled)
        assertTrue(shots.frame!!.closed)
    }

    @Test fun userTouchOrCancellationDuringOcrReleasesFrame() = runTest {
        val shots = Shots()
        val job = async { CaptureOcrPipeline(shots, OcrPort { awaitCancellation() }, { true }, { null }).analyze(imagePopup()) }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(shots.frame!!.closed)
        assertTrue(job.isCancelled)
    }

    @Test fun sensitiveScreenAppearingAfterOcrBlocksTreeAndOcrTogether() = runTest {
        val shots = Shots()
        var checks = 0
        val result = CaptureOcrPipeline(shots, OcrPort { listOf(OcrLine("점검 공지", box)) }, { true }, {
            checks++; if (checks == 3) PrivacyBlockReason.EDITABLE_FIELD else null
        }).analyze(imagePopup())
        assertTrue(result is EvidenceOutcome.Blocked)
        assertTrue(shots.frame!!.closed)
    }

    @Test fun protectionChangingImmediatelyAfterScreenshotPreventsOcr() = runTest {
        val shots = Shots()
        var checks = 0
        val result = CaptureOcrPipeline(shots, OcrPort { error("No OCR") }, { true }, {
            checks++; if (checks == 2) PrivacyBlockReason.PASSWORD else null
        }).analyze(imagePopup()) as EvidenceOutcome.Blocked
        assertEquals(PrivacyBlockReason.PASSWORD, result.reason)
        assertTrue(shots.frame!!.closed)
    }

    @Test fun timeoutCannotReturnTreeWhenProtectionBecomesUncertain() = runTest {
        val shots = Shots()
        var checks = 0
        val result = CaptureOcrPipeline(shots, OcrPort { awaitCancellation() }, { true }, {
            checks++; if (checks == 3) PrivacyBlockReason.UNREADABLE_TREE else null
        }, 50).analyze(imagePopup())
        assertTrue(result is EvidenceOutcome.Blocked)
        assertTrue(shots.frame!!.closed)
    }
}
