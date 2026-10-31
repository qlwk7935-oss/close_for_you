package kr.closeforyou.accessibility.integration

import java.util.concurrent.CancellationException
import kr.closeforyou.accessibility.*
import org.junit.Assert.*
import org.junit.Test

class PresentationNodeRefreshTest {
    @Test fun checkpointThenWindowThenRefreshThenCheckpointAndEndpointInOrder() {
        val order = mutableListOf<String>(); var clocks = 0
        assertTrue(refreshPresentationNodeRead(1_000, { order += "CLOCK"; 1_000L + clocks++ },
            { order += "CURRENT" }, { order += "WINDOW"; 2 }, { order += "REFRESH"; true }))
        assertEquals(listOf("CURRENT", "WINDOW", "CLOCK", "REFRESH", "CURRENT", "CLOCK", "WINDOW"), order)
    }
    @Test fun staleCancelledObsoleteOrIdentityChangedNeverAllowsSnapshotUse() {
        var refreshes = 0
        assertFalse(refreshPresentationNodeRead(1_000, { 1_000 }, {}, { 2 }, { refreshes++; false }))
        assertEquals(1, refreshes)
        var ids = 0
        assertFalse(refreshPresentationNodeRead(1_000, { 1_000 }, {}, { if(ids++ == 0) 2 else 3 }, { true }))
        val cancelled = CancellationException("SYNTHETIC")
        try { refreshPresentationNodeRead(1_000, { 1_000 }, { throw cancelled }, { 2 }, { fail(); true }); fail() }
        catch (e: CancellationException) { assertSame(cancelled, e) }
    }
    @Test fun beforeAndAfterDeadlineBackwardClockAndNegativeWindowAreRejected() {
        assertFalse(refreshPresentationNodeRead(1_000, { 1_100 }, {}, { 2 }, { fail(); true }))
        assertFalse(refreshPresentationNodeRead(1_000, { 999 }, {}, { 2 }, { fail(); true }))
        assertFalse(refreshPresentationNodeRead(1_000, { 1_000 }, {}, { -1 }, { fail(); true }))
        var time = 1_000L
        assertFalse(refreshPresentationNodeRead(1_000, { time }, {}, { 2 }, { time = 1_100; true }))
        time = 1_000
        assertFalse(refreshPresentationNodeRead(1_000, { time }, {}, { 2 }, { time = 999; true }))
    }
    @Test fun detailedRefreshCodesUseExistingReadsAndPreserveShortCircuitOrder() {
        data class Case(val first: Long, val last: Long, val id: Int, val endId: Int,
            val refreshed: Boolean, val detail: CaptureDetail)
        val cases=listOf(
            Case(1_000,1_001,-1,-1,true,CaptureDetail.INVALID_WINDOW),
            Case(999,1_000,2,2,true,CaptureDetail.INVALID_CLOCK),
            Case(1_100,1_100,2,2,true,CaptureDetail.TIME_LIMIT),
            Case(1_000,1_001,2,2,false,CaptureDetail.REFRESH_FAILED),
            Case(1_000,1_100,2,3,true,CaptureDetail.REFRESH_WINDOW_CHANGED),
            Case(1_000,999,2,2,true,CaptureDetail.INVALID_CLOCK),
            Case(1_000,1_100,2,2,true,CaptureDetail.TIME_LIMIT),
            Case(1_000,1_099,2,2,true,CaptureDetail.NONE))
        for (case in cases) {
            val orders=mutableListOf<List<String>>()
            val outcomes=mutableListOf<Boolean>()
            var detail=CaptureDetail.NONE
            for (diagnose in listOf(false,true)) {
                val order=mutableListOf<String>(); var times=0; var ids=0
                outcomes += refreshPresentationNodeRead(1_000,
                    { order += "CLOCK"; if(times++==0) case.first else case.last },
                    { order += "CURRENT" },
                    { order += "WINDOW"; if(ids++==0) case.id else case.endId },
                    { order += "REFRESH"; case.refreshed },
                    { if(diagnose) detail=it })
                orders+=order
            }
            assertEquals(case.detail,detail)
            assertEquals(outcomes[0],outcomes[1])
            assertEquals(case.detail==CaptureDetail.NONE,outcomes[1])
            assertEquals(orders[0],orders[1])
        }
    }
    @Test fun observerFailureCannotTurnRejectedRefreshIntoApprovalOrReplaceCancellation() {
        assertFalse(refreshPresentationNodeRead(1_000,{1_000},{},{2},{false}) { error("SYNTHETIC") })
        assertFalse(refreshPresentationNodeRead(1_000,{1_000},{},{2},{false}) { throw CancellationException() })
        val cancelled=CancellationException("SYNTHETIC")
        try { refreshPresentationNodeRead(1_000,{1_000},{throw cancelled},{2},{true}) { fail() }; fail() }
        catch(e: CancellationException) { assertSame(cancelled,e) }
    }
    @Test fun collectionCancellationAndPrimaryFailureSurviveIndependentAllResourceReleaseAttempts() {
        for (primary in listOf(CancellationException("SYNTHETIC"), IllegalStateException("SYNTHETIC"))) {
            val order = mutableListOf<Int>()
            try {
                CollectionResourceSafety.run(body = { throw primary }, cleanup = {
                    val batch = CollectionReleaseBatch()
                    batch.attempt { order += 1; throw IllegalStateException() }
                    batch.attempt { order += 2 }; batch.attempt { order += 3 }; batch.finish()
                }, cleanupFailure = { fail(); it })
                fail()
            } catch (e: Exception) { assertSame(primary, e) }
            assertEquals(listOf(1, 2, 3), order)
        }
    }
    @Test fun releaseFailureCannotApproveReadyAndNeverReplacesFirstBlockedReason() {
        val ready = P01StabilityFixture.sample(1)
        val blocked = CollectionResult.Blocked(kr.closeforyou.core.model.PrivacyBlockReason.SENSITIVE_CONTENT)
        for (outcome in listOf<CollectionResult>(ready, blocked)) {
            val checked = CollectionResourceSafety.run(body = { outcome }, cleanup = { throw IllegalStateException() },
                cleanupFailure = { if(it is CollectionResult.Blocked) it else CollectionResult.Blocked(kr.closeforyou.core.model.PrivacyBlockReason.UNREADABLE_TREE) })
            assertTrue(checked is CollectionResult.Blocked)
            if(outcome === blocked) assertSame(blocked, checked)
        }
    }
}
