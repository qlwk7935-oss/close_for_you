package kr.closeforyou.accessibility

import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class CaptureReadTraceReleaseTest {
    @Test fun releaseObserverHasNoStateAndNeverReportsFixedDiagnosticResults() {
        val probe=CaptureReadTrace(); val trace=CaptureTrace(null,collectionRead=probe)
        trace.readPoint(CaptureReadPoint.SNAPSHOT_PASS)
        trace.readRejected(CaptureDetail.CHILD_UNAVAILABLE)
        trace.detail(CaptureDetail.ROOT_UNAVAILABLE)
        trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { }
        trace.readReleased()
        assertEquals(CaptureDetail.NONE,probe.detail)
        assertEquals(CaptureReadPoint.UNKNOWN,probe.failurePoint)
        assertEquals(CaptureReadRelease.UNKNOWN,probe.release)
        assertTrue(CaptureReadTrace::class.java.declaredFields.none { !java.lang.reflect.Modifier.isStatic(it.modifiers) })
    }
    @Test fun releaseStubInvokesCallbacksOnceAndPropagatesTheSameCancellation() {
        val probe=CaptureReadTrace(); var calls=0
        assertEquals(1,probe.body { ++calls })
        val cancelled=CancellationException("SYNTHETIC")
        try { probe.release(CaptureReadPoint.ROOT_RELEASE) { calls++; throw cancelled }; fail() }
        catch (e: CancellationException) { assertSame(cancelled,e) }
        assertEquals(2,calls)
    }
}
