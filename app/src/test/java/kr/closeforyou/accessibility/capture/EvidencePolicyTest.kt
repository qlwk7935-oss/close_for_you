package kr.closeforyou.accessibility.capture

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.*
import kr.closeforyou.testing.mock.MockPopupFixtures
import org.junit.Assert.*
import org.junit.Test

class EvidencePolicyTest {
    @Test fun separatePopupWindowIsBoundedButNormalFullscreenIsNotTrusted() {
        val snapshot = MockPopupFixtures.notice().snapshot
        assertEquals(snapshot.windows.first().bounds, PopupRegionResolver.resolve(snapshot)!!.bounds)
        assertNull(PopupRegionResolver.resolve(snapshot.copy(windows = persistentListOf(snapshot.windows.first().copy(bounds = snapshot.screenBounds)))))
    }

    @Test fun explicitDialogIsAcceptedAndAnOccludingWindowIsRejected() {
        val snapshot = MockPopupFixtures.notice().snapshot
        val full = snapshot.copy(windows = persistentListOf(snapshot.windows.first().copy(bounds = snapshot.screenBounds)),
            nodes = persistentListOf(snapshot.nodes.first().copy(className = "android.app.Dialog")))
        assertNotNull(PopupRegionResolver.resolve(full))
        val overlay = WindowSnapshot(20, WindowType.ACCESSIBILITY_OVERLAY, snapshot.windows.first().bounds, false, false, 5)
        assertNull(PopupRegionResolver.resolve(full.copy(windows = persistentListOf(full.windows.first(), overlay))))
    }

    @Test fun ambiguousDialogsAndTruncatedTreesAreRejected() {
        val s = MockPopupFixtures.notice().snapshot
        val nodes = persistentListOf(s.nodes.first().copy(className = "android.app.Dialog"),
            s.nodes.first().copy(path = "other", className = "android.app.Dialog", bounds = ScreenBounds(100, 500, 800, 900)))
        assertNull(PopupRegionResolver.resolve(s.copy(windows = persistentListOf(s.windows.first().copy(bounds = s.screenBounds)), nodes = nodes)))
        assertNull(PopupRegionResolver.resolve(s.copy(completeness = SnapshotCompleteness.TRUNCATED)))
    }

    @Test fun captureBudgetSerializesLateCallbacksAndEnforcesIntervalAndRate() {
        var now = 0L
        val budget = CaptureBudget { now }
        val lease = budget.acquire()
        assertEquals(EvidenceIssue.BUSY, budget.denial())
        now = 10_000
        assertEquals(EvidenceIssue.BUSY, budget.denial())
        lease.close(); lease.close()
        assertNull(budget.denial())
        repeat(5) { budget.acquire().close(); now += 2_000 }
        assertEquals(EvidenceIssue.RATE_LIMITED, budget.denial())
        now = 70_000
        assertNull(budget.denial())
        budget.rateLimited()
        assertEquals(EvidenceIssue.RATE_LIMITED, budget.denial())
        now += 6_000
        assertNull(budget.denial())
    }

    @Test fun captureBudgetHasAFiniteProcessSessionAllowance() {
        var now = 0L
        val budget = CaptureBudget { now }
        repeat(30) { budget.acquire().close(); now += 60_000 }
        assertEquals(EvidenceIssue.BUDGET_EXHAUSTED, budget.denial())
    }

    @Test fun privacyReviewTreatsNumbersAndUnreviewableContentConservatively() {
        val box = ScreenBounds(0, 0, 600, 200)
        fun review(text: String) = OcrPrivacyReview.review(listOf(OcrLine(text, box)), box)
        assertEquals(PrivacyReview.TEXT_CHECK_PASSED, review("점검 2026년 10월 18일 02시 30분"))
        assertEquals(PrivacyReview.TEXT_CHECK_PASSED, review("행사 가격 10,000원"))
        assertEquals(PrivacyReview.UNCERTAIN, review("확인 654321"))
        assertEquals(PrivacyReview.SENSITIVE, review("고객 sample@example.test"))
        assertEquals(PrivacyReview.SENSITIVE, review("가상 카드 1234-5678-9012-3456"))
        assertEquals(PrivacyReview.UNCERTAIN, review("お知らせ"))
        assertFalse(OcrLine("합성 비밀번호", box).toString().contains("비밀번호"))
    }
}
