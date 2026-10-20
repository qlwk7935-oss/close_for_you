package kr.closeforyou.data.diagnostics

import kr.closeforyou.core.contract.DiagnosticCode
import kr.closeforyou.core.model.SafetyReason
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CountingSafeDiagnosticsTest {

    @Test
    fun countsCodesAndReasons() {
        val diagnostics = CountingSafeDiagnostics()
        diagnostics.record(DiagnosticCode.PRIVACY_BLOCKED, persistentSetOf(SafetyReason.PRIVACY_BLOCKED))
        diagnostics.record(DiagnosticCode.PRIVACY_BLOCKED, persistentSetOf(SafetyReason.PRIVACY_BLOCKED))
        diagnostics.record(
            DiagnosticCode.ACTION_NOT_IMPLEMENTED,
            persistentSetOf(SafetyReason.NOT_IMPLEMENTED, SafetyReason.FIRST_SEEN),
        )
        diagnostics.record(DiagnosticCode.CANDIDATE_FOUND, persistentSetOf())

        val snapshot = diagnostics.snapshot()
        assertEquals(2L, snapshot.count(DiagnosticCode.PRIVACY_BLOCKED))
        assertEquals(1L, snapshot.count(DiagnosticCode.ACTION_NOT_IMPLEMENTED))
        assertEquals(1L, snapshot.count(DiagnosticCode.CANDIDATE_FOUND))
        assertEquals(0L, snapshot.count(DiagnosticCode.SNAPSHOT_INVALIDATED))
        assertEquals(2L, snapshot.count(DiagnosticCode.PRIVACY_BLOCKED, SafetyReason.PRIVACY_BLOCKED))
        assertEquals(1L, snapshot.count(DiagnosticCode.ACTION_NOT_IMPLEMENTED, SafetyReason.FIRST_SEEN))
        assertEquals(0L, snapshot.count(DiagnosticCode.CANDIDATE_FOUND, SafetyReason.FIRST_SEEN))
        assertEquals(4L, snapshot.total)
    }

    @Test
    fun everyCodeAppearsInTheSnapshot() {
        val snapshot = CountingSafeDiagnostics().snapshot()
        assertEquals(DiagnosticCode.entries.toSet(), snapshot.codes.keys)
        assertEquals(0L, snapshot.total)
    }

    @Test
    fun resetClearsCounts() {
        val diagnostics = CountingSafeDiagnostics()
        diagnostics.record(DiagnosticCode.CANDIDATE_FOUND, persistentSetOf(SafetyReason.FIRST_SEEN))
        diagnostics.reset()
        assertEquals(0L, diagnostics.snapshot().total)
        assertEquals(0, diagnostics.snapshot().reasons.size)
    }

    @Test
    fun concurrentRecordsAreNotLost() {
        val diagnostics = CountingSafeDiagnostics()
        val pool = Executors.newFixedThreadPool(8)
        repeat(1_000) {
            pool.execute {
                diagnostics.record(DiagnosticCode.CANDIDATE_FOUND, persistentSetOf(SafetyReason.FIRST_SEEN))
            }
        }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(1_000L, diagnostics.snapshot().count(DiagnosticCode.CANDIDATE_FOUND))
        assertEquals(1_000L, diagnostics.snapshot().count(DiagnosticCode.CANDIDATE_FOUND, SafetyReason.FIRST_SEEN))
    }
}
