package kr.closeforyou.accessibility.capture

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.core.model.*
import kr.closeforyou.accessibility.CaptureDetail
import kr.closeforyou.accessibility.RegionGeometry
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

    @Test fun regionDiagnosticsDistinguishInactiveMissingAndNonApplicationWithoutRelaxation() {
        val s = MockPopupFixtures.notice().snapshot
        val w = s.windows.first()
        fun reason(window: WindowSnapshot) = PopupRegionResolver.inspect(s.copy(windows = persistentListOf(window))).detail
        assertEquals(CaptureDetail.REGION_TARGET_INACTIVE, reason(w.copy(active = false)))
        assertEquals(CaptureDetail.REGION_TARGET_MISSING, reason(w.copy(id = w.id + 1)))
        assertEquals(CaptureDetail.REGION_TARGET_NOT_APPLICATION, reason(w.copy(type = WindowType.SYSTEM)))
        assertEquals(CaptureDetail.REGION_WINDOW_BOUNDED, reason(w))
    }

    @Test fun regionDiagnosticsSeparateLargeDialogAmbiguityAndOccludingLayerType() {
        val s = MockPopupFixtures.notice().snapshot
        val w = s.windows.first()
        val full = s.copy(windows = persistentListOf(w.copy(bounds = s.screenBounds)),
            nodes = persistentListOf(s.nodes.first().copy(className = "android.widget.FrameLayout")))
        assertEquals(CaptureDetail.REGION_NO_BOUNDED_DIALOG, PopupRegionResolver.inspect(full).detail)
        val dialogs = full.copy(nodes = persistentListOf(
            s.nodes.first().copy(className = "android.app.Dialog"),
            s.nodes.first().copy(path = "other", className = "android.app.Dialog", bounds = ScreenBounds(100, 500, 800, 900))))
        assertEquals(CaptureDetail.REGION_MULTIPLE_DIALOGS, PopupRegionResolver.inspect(dialogs).detail)
        for (type in listOf(WindowType.SYSTEM, WindowType.INPUT_METHOD, WindowType.APPLICATION, WindowType.ACCESSIBILITY_OVERLAY)) {
            val overlay = WindowSnapshot(20, type, w.bounds, false, false, w.layer + 1)
            val overlaid = s.copy(windows = persistentListOf(w, overlay))
            val result = PopupRegionResolver.inspect(overlaid)
            assertNull(result.region)
            assertEquals(CaptureDetail.REGION_OCCLUDED, result.detail)
            assertEquals(type, result.relatedType)
            // Only the pre-existing API 34+ exclusion of inactive/unfocused accessibility overlays remains.
            assertEquals(type == WindowType.ACCESSIBILITY_OVERLAY, PopupRegionResolver.resolve(overlaid, true) != null)
            assertNull(PopupRegionResolver.resolve(overlaid.copy(windows = persistentListOf(w, overlay.copy(active = true))), true))
        }
    }

    @Test fun geometryReportsRelativeValidityWithoutPublishingCoordinates() {
        val screen = ScreenBounds(0, 0, 1000, 2000)
        assertEquals(RegionGeometry.BOUNDED, PopupRegionResolver.geometry(ScreenBounds(100, 500, 900, 1000), screen))
        assertEquals(RegionGeometry.LARGE_OR_FULL, PopupRegionResolver.geometry(screen, screen))
        assertEquals(RegionGeometry.OUTSIDE, PopupRegionResolver.geometry(ScreenBounds(-1, 0, 900, 1000), screen))
        assertEquals(RegionGeometry.EMPTY, PopupRegionResolver.geometry(ScreenBounds(0, 0, 0, 0), screen))
    }

    @Test fun originalBoundedWindowIsUsableWithoutAnyDialogClassNode() {
        val s = MockPopupFixtures.notice().snapshot
        val nodes = persistentListOf(s.nodes.first().copy(className = "android.widget.FrameLayout"))
        val badFrame = s.copy(nodes = nodes, screenBounds = ScreenBounds(0, 0, 0, 0))
        assertEquals(RegionGeometry.OUTSIDE, PopupRegionResolver.geometry(s.windows.first().bounds, badFrame.screenBounds))
        assertEquals(CaptureDetail.REGION_WINDOW_OUTSIDE, PopupRegionResolver.inspect(badFrame).detail)
        val corrected = badFrame.copy(screenBounds = s.screenBounds)
        val result = PopupRegionResolver.inspect(corrected)
        assertEquals(CaptureDetail.REGION_WINDOW_BOUNDED, result.detail)
        assertEquals(s.windows.first().bounds, result.region!!.bounds) // No clipping or synthetic coordinates.
        assertEquals(s.windows.first(), result.region.window)
    }

    @Test fun outOfFrameWindowNeverFallsBackToAnInnerDialogEvenByOnePixel() {
        val s = MockPopupFixtures.notice().snapshot
        val w = s.windows.first()
        val screen = s.screenBounds
        for (box in listOf(ScreenBounds(-1, 0, screen.right, screen.bottom),
                ScreenBounds(0, -1, screen.right, screen.bottom),
                ScreenBounds(0, 0, screen.right + 1, screen.bottom),
                ScreenBounds(0, 0, screen.right, screen.bottom + 1))) {
            val snapshot = s.copy(windows = persistentListOf(w.copy(bounds = box)),
                nodes = persistentListOf(s.nodes.first().copy(className = "android.app.Dialog")))
            val result = PopupRegionResolver.inspect(snapshot, isolatedWindowCapture = true)
            assertNull(result.region)
            assertEquals(CaptureDetail.REGION_WINDOW_OUTSIDE, result.detail)
            assertEquals(box, snapshot.windows.first().bounds)
        }
    }

    @Test fun fullScreenActivityAndInvalidDialogNodesStillFailClosed() {
        val s = MockPopupFixtures.notice().snapshot
        val full = s.copy(windows = persistentListOf(s.windows.first().copy(bounds = s.screenBounds)))
        val noDialog = full.copy(nodes = persistentListOf(s.nodes.first().copy(className = "android.widget.FrameLayout")))
        assertEquals(CaptureDetail.REGION_NO_BOUNDED_DIALOG, PopupRegionResolver.inspect(noDialog).detail)
        val outside = full.copy(nodes = persistentListOf(s.nodes.first().copy(className = "android.app.Dialog",
            bounds = ScreenBounds(-1, 0, 300, 600))))
        assertEquals(CaptureDetail.REGION_DIALOG_OUTSIDE, PopupRegionResolver.inspect(outside).detail)
        val large = full.copy(nodes = persistentListOf(s.nodes.first().copy(className = "android.app.Dialog", bounds = s.screenBounds)))
        assertEquals(CaptureDetail.REGION_DIALOG_NOT_BOUNDED, PopupRegionResolver.inspect(large).detail)
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

    @Test fun rollingThirtyCaptureLimitRejectsThenRecoversWithoutRestart() {
        var now = 0L
        val budget = CaptureBudget { now }
        repeat(30) { budget.acquire().close(); now += 10_000 }
        assertEquals(EvidenceIssue.BUDGET_EXHAUSTED, budget.denial())
        assertEquals(EvidenceIssue.BUDGET_EXHAUSTED,
            assertThrows(EvidenceFailure::class.java) { budget.acquire() }.issue)
        now = 599_999
        assertEquals(EvidenceIssue.BUDGET_EXHAUSTED, budget.denial())
        now = 600_000
        assertNull(budget.denial())
        budget.acquire().close()
        assertEquals(EvidenceIssue.BUDGET_EXHAUSTED, budget.denial())
        now = 610_000
        assertNull(budget.denial())
    }

    @Test fun sustainedUseCanExceedThirtyLifetimeCaptures() {
        var now = 0L
        val budget = CaptureBudget { now }
        repeat(100) {
            assertNull(budget.denial())
            budget.acquire().close()
            now += 20_000
        }
    }

    @Test fun recoveryNeverReleasesAnOutstandingNativeLease() {
        var now = 0L
        val budget = CaptureBudget { now }
        val lease = budget.acquire()
        now = 1_200_000
        assertEquals(EvidenceIssue.BUSY, budget.denial())
        assertThrows(EvidenceFailure::class.java) { budget.acquire() }
        lease.close()
        assertNull(budget.denial())
    }

    @Test fun rollingWindowRecoveryStillHonorsMinimumIntervalAndSystemBackoff() {
        var now = 0L
        val budget = CaptureBudget { now }
        repeat(30) { budget.acquire().close(); now += 10_000 }
        now = 650_000 // six old slots have expired
        budget.acquire().close()
        now += 1_999
        assertEquals(EvidenceIssue.RATE_LIMITED, budget.denial())
        now++
        assertNull(budget.denial())
        budget.rateLimited()
        now += 5_999
        assertEquals(EvidenceIssue.RATE_LIMITED, budget.denial())
        now++
        assertNull(budget.denial())
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
