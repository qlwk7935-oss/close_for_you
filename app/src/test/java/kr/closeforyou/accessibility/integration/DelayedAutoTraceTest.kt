package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.action.F9ReadStage
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DelayedAutoTraceTest {
    @Test fun actualTraceHasOneBoundedEntryPerBoundaryAndNoRawFields() {
        val seen = mutableListOf<DelayedTracePoint>()
        val t = DelayedAutoTrace { point, _, _, _, _, _, _ -> seen += point }
        repeat(100) { DelayedTracePoint.entries.forEach { t.record(it) } }
        assertEquals(DelayedTracePoint.entries, seen)
        assertEquals("DelayedAutoTrace(redacted)", t.toString())
    }
    @Test fun separateCaptureTracesCannotOverwriteOrCombineTheirReadOutcomes() {
        val a = mutableListOf<F9ReadStage?>(); val b = mutableListOf<F9ReadStage?>()
        val first = DelayedAutoTrace { _, _, read, _, _, _, _ -> a += read }
        val next = DelayedAutoTrace { _, _, read, _, _, _, _ -> b += read }
        next.record(DelayedTracePoint.PAGE_VERIFIED)
        first.record(DelayedTracePoint.PAGE_READ_REJECTED, read = F9ReadStage.TIME_LIMIT)
        assertEquals(listOf(F9ReadStage.TIME_LIMIT), a); assertEquals(listOf<F9ReadStage?>(null), b)
    }
    @Test fun failingDiagnosticSinkCannotThrowIntoTheServiceOrRetryTheBoundary() {
        var calls = 0
        val t = DelayedAutoTrace { _, _, _, _, _, _, _ -> calls++; error("PRIVATE_TEST_ERROR") }
        repeat(2) { t.record(DelayedTracePoint.CAPTURE_CANCELLED) }
        assertEquals(1, calls)
    }
    @Test fun publicationAcceptanceReflectsTheActualPrivateCandidateStream() = runTest {
        val f = F9AutoCloserTest.Fixture(); val source = F9CollectedSource { f.now }
        var candidates = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            source.events.collect { if (it is PopupEvent.Candidate) candidates++ }
        }
        val d = PopupDetection(f.token, persistentSetOf(PopupSignal.NEW_WINDOW))
        assertTrue(source.publish(f.snapshot, d)); assertEquals(1, candidates)
        source.invalidate(2)
        assertFalse(source.publish(f.snapshot, d)); assertEquals(1, candidates)
    }
    @Test fun staleIncompleteMismatchedAndProtectedPublicationsCannotBeLoggedAsAccepted() = runTest {
        for (mode in listOf("STALE", "INCOMPLETE", "TOKEN", "PROTECTED")) {
            val f = F9AutoCloserTest.Fixture(); val source = F9CollectedSource { f.now }; var candidates = 0
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                source.events.collect { if (it is PopupEvent.Candidate) candidates++ }
            }
            val d = PopupDetection(f.token, persistentSetOf(PopupSignal.NEW_WINDOW))
            val s = when (mode) {
                "STALE" -> { f.now += 5_000; f.snapshot }
                "INCOMPLETE" -> f.snapshot.copy(completeness = SnapshotCompleteness.TRUNCATED)
                "TOKEN" -> f.snapshot.copy(token = f.token.copy(screenVersion = 99))
                else -> f.snapshot.copy(nodes = persistentListOf(f.node.copy(text = "비밀번호 입력")))
            }
            assertFalse(source.publish(s, d)); assertEquals(0, candidates)
        }
    }
}
