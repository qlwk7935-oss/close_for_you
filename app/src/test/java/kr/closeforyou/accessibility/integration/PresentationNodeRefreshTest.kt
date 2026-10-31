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
