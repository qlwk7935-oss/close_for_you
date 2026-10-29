package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.PromptSummary
import org.junit.Assert.*
import org.junit.Test

class ManualReviewDiagnosticsTest {
    @Test fun unchangedRecoveryAndExhaustionUseExactExistingIdentityWithoutRenewingEvidence() {
        val token = MockPopupFixtures.advertisement().snapshot.token; val b = ChoiceReviewBudget()
        assertTrue(b.admit(token)); assertEquals(ManualReviewChange.INITIAL, b.lastChange)
        assertTrue(b.admit(token.copy(screenVersion = 99, captureEpoch = 100, popupInstanceId = "synthetic-other")))
        assertEquals(ManualReviewAdmission.UNCHANGED_RECOVERY, b.lastAdmission); assertEquals(ManualReviewChange.UNCHANGED, b.lastChange)
        assertFalse(b.admit(token)); assertEquals(ManualReviewAdmission.EXHAUSTED, b.lastAdmission)
    }
    @Test fun diagnosticChangeCategoryComesFromActualExistingIdentityComparison() {
        val t = MockPopupFixtures.advertisement().snapshot.token
        val cases = listOf(t.copy(packageName = "synthetic.other") to ManualReviewChange.APP_CHANGED,
            t.copy(windowId = t.windowId + 1) to ManualReviewChange.WINDOW_CHANGED,
            t.copy(structureDigest = "synthetic-structure") to ManualReviewChange.STRUCTURE_CHANGED,
            t.copy(contentDigest = "synthetic-content") to ManualReviewChange.CONTENT_CHANGED,
            t.copy(structureDigest = "synthetic-structure", contentDigest = "synthetic-content") to ManualReviewChange.STRUCTURE_AND_CONTENT_CHANGED)
        for ((fresh, expected) in cases) {
            val b = ChoiceReviewBudget(); assertTrue(b.admit(t)); assertTrue(b.admit(fresh))
            assertEquals(ManualReviewAdmission.IDENTITY_CHANGED, b.lastAdmission); assertEquals(expected, b.lastChange)
            assertTrue(b.admit(fresh)); assertFalse(b.admit(fresh))
        }
    }
    @Test fun alternatingObservedIdentitiesResetExistingBudgetButDoNotProveRealPopupReplacement() {
        val a = MockPopupFixtures.advertisement().snapshot.token; val b = a.copy(contentDigest = "synthetic-alternate")
        val budget = ChoiceReviewBudget()
        for (token in listOf(a, a, b, b, a, a)) assertTrue(budget.admit(token))
        assertEquals(ManualReviewAdmission.UNCHANGED_RECOVERY, budget.lastAdmission)
        assertFalse(budget.admit(a)) // no total-service limit is invented here
    }
    @Test fun reviewAndActualPromptSummaryAreSeparateFixedCodeObservations() {
        val rows = mutableListOf<ManualReviewCode>(); val trace = ManualPromptTrace(reviewSink = { _, c -> rows += c }, sink = { _, _, _ -> })
        trace.begin()
        trace.review(ManualReviewCode(ManualReviewPoint.ADMITTED, ManualReviewAdmission.UNCHANGED_RECOVERY, ManualReviewChange.UNCHANGED))
        val shown = ManualReviewCode(ManualReviewPoint.SHOWN, ManualReviewAdmission.UNCHANGED_RECOVERY, ManualReviewChange.UNCHANGED, PromptSummary.LIKELY_COUPON)
        trace.review(shown); trace.review(shown)
        assertNull(rows.first().summary); assertEquals(PromptSummary.LIKELY_COUPON, rows.last().summary); assertEquals(2, rows.size)
        assertFalse(rows.toString().contains("synthetic"))
    }
    @Test fun failingDiagnosticSinkDoesNotChangeAdmissionAndReportsStayBounded() {
        val token = MockPopupFixtures.advertisement().snapshot.token; val b = ChoiceReviewBudget(); var calls = 0
        val trace = ManualPromptTrace(reviewSink = { _, _ -> calls++; throw CancellationException("PRIVATE_LOG") }, sink = { _, _, _ -> })
        assertTrue(b.admit(token)); val code = ManualReviewCode(ManualReviewPoint.ADMITTED, b.lastAdmission, b.lastChange)
        repeat(50) { trace.review(code) }; assertEquals(1, calls)
        assertTrue(b.admit(token)); assertFalse(b.admit(token))
    }
    @Test fun actualCouponPromptCanContainCloseOptionWithoutClassificationChanging() {
        val fixture = MockPopupFixtures.advertisement()
        val prompt = kr.closeforyou.ui.voice.choice.ChoicePromptPlanner.plan(fixture.analysis,
            kr.closeforyou.core.model.PopupClassification.COUPON)
        val budget = ChoiceReviewBudget(); assertTrue(budget.admit(fixture.snapshot.token))
        val code = manualReviewPresentationCode(ManualReviewPoint.SHOWN, fixture.snapshot.token, prompt, budget)
        assertEquals(PromptSummary.LIKELY_COUPON, code.summary); assertEquals(true, code.pressOffered)
        assertEquals(ManualReviewChange.INITIAL, code.change)
    }
    @Test fun latePromptCannotClaimTheLatestOffersAdmissionAndNoStringsLeaveBoundary() {
        val fixture = MockPopupFixtures.advertisement()
        val prompt = kr.closeforyou.ui.voice.choice.ChoicePromptPlanner.plan(fixture.analysis,
            kr.closeforyou.core.model.PopupClassification.ADVERTISEMENT)
        val newer = fixture.snapshot.token.copy(screenVersion = 999)
        val budget = ChoiceReviewBudget(); budget.admit(newer)
        val code = manualReviewPresentationCode(ManualReviewPoint.PRESENT_REQUESTED, newer, prompt, budget)
        assertNull(code.admission); assertEquals(ManualReviewChange.UNKNOWN, code.change)
        assertEquals(PromptSummary.LIKELY_AD, code.summary)
        assertFalse(code.toString().contains(fixture.snapshot.token.packageName))
    }

}
