package kr.closeforyou.accessibility

import java.util.concurrent.CancellationException
import kr.closeforyou.accessibility.integration.P01StabilityFixture
import kr.closeforyou.accessibility.testing.*
import kr.closeforyou.core.model.PrivacyBlockReason
import kr.closeforyou.core.model.ScreenBounds
import org.junit.Assert.*
import org.junit.Test

class CaptureReadTraceTest {
    private fun trace(probe: CaptureReadTrace) = CaptureTrace(null, collectionRead = probe)

    @Test fun rootFailureBeforeSessionBindingIsVisibleWithoutTitleOrLegacyReads() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        trace.readPoint(CaptureReadPoint.ROOT)
        trace.detail(CaptureDetail.ROOT_UNAVAILABLE)
        trace.root("PRIVATE_SENTINEL",123)
        trace.syntheticWindow { error("MUST_NOT_READ") }
        trace.coordinates(ScreenBounds(0,0,1,1),ScreenBounds(0,0,1,1),ScreenBounds(0,0,1,1)) {
            error("MUST_NOT_READ")
        }
        assertEquals(CaptureDetail.ROOT_UNAVAILABLE,probe.detail)
        assertEquals(CaptureReadPoint.ROOT,probe.failurePoint)
    }
    @Test fun firstGuardSurvivesOuterGenericDetailAndReleaseFailure() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        val original=IllegalStateException("PRIVATE_SENTINEL")
        try {
            CollectionResourceSafety.run(body = { trace.readBody {
                trace.readPoint(CaptureReadPoint.SNAPSHOT_PASS)
                trace.readRejected(CaptureDetail.CHILD_UNAVAILABLE)
                throw original
            } }, cleanup = { trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { error("PRIVATE_CLEANUP") } },
                cleanupFailure = { it })
            fail()
        } catch (e: Exception) { assertSame(original,e) }
        trace.detail(CaptureDetail.TREE_PROTECTION)
        assertEquals(CaptureDetail.CHILD_UNAVAILABLE,probe.detail)
        assertEquals(CaptureReadPoint.SNAPSHOT_PASS,probe.failurePoint)
        assertEquals(CaptureReadRelease.FAILED,probe.release)
    }
    @Test fun firstF0FailureAndElevenSuccessesStillCannotCompleteComparison() {
        val probes=(0 until 12).map { CaptureReadTrace() }
        val initial=trace(probes.first())
        initial.readPoint(CaptureReadPoint.PROTECTION_PASS)
        initial.readRejected(CaptureDetail.REFRESH_FAILED)
        initial.readRelease(CaptureReadPoint.ROOT_RELEASE) { }
        initial.readReleased()
        probes.drop(1).forEach { val t=trace(it); t.readRelease(CaptureReadPoint.ROOT_RELEASE) { }; t.readReleased() }
        val unavailable=probes.count { it.detail!=CaptureDetail.NONE }
        assertEquals(1,unavailable)
        assertEquals(11,probes.count { it.detail==CaptureDetail.NONE })
        assertFalse(comparisonComplete(ComparisonIssue.BASELINE_UNAVAILABLE,true,unavailable,true,2))
        // Even erroneously completing all phases cannot erase the failed read.
        assertFalse(comparisonComplete(ComparisonIssue.NONE,true,unavailable,true,5))
        assertEquals(CaptureDetail.REFRESH_FAILED,probes.first().detail)
        assertEquals(CaptureReadRelease.COMPLETED,probes.first().release)
    }
    @Test fun blockedCollectionCanHaveSuccessfulActualRelease() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        val blocked=CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
        val result=CollectionResourceSafety.run(body={ trace.readBody {
            trace.detail(CaptureDetail.ROOT_PACKAGE_UNAVAILABLE); blocked
        } },cleanup={ trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { }; trace.readReleased() },cleanupFailure={ fail(); it })
        assertSame(blocked,result)
        assertEquals(CaptureReadRelease.COMPLETED,probe.release)
    }
    @Test fun noResourceOrUnfinishedBatchIsUnknownEvenIfAnOutcomeExists() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        trace.detail(CaptureDetail.ROOT_UNAVAILABLE)
        trace.readRelease(CaptureReadPoint.RELEASE_BATCH) { trace.readReleased() }
        assertEquals(CaptureReadRelease.UNKNOWN,probe.release)
        val unfinished=CaptureReadTrace(); val t=trace(unfinished)
        t.readRelease(CaptureReadPoint.ROOT_RELEASE) { }
        assertEquals(CaptureReadRelease.UNKNOWN,unfinished.release)
    }
    @Test fun readyFollowedByReleaseFailureIsBlockedAndRootSiteIsPreserved() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        val order=mutableListOf<Int>()
        val outcome=CollectionResourceSafety.run<CollectionResult>(body={ trace.readBody { P01StabilityFixture.sample(1) } },
            cleanup={ trace.readRelease(CaptureReadPoint.RELEASE_BATCH) {
                val batch=CollectionReleaseBatch()
                batch.attempt { trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { order+=1; error("PRIVATE") } }
                batch.attempt { trace.readRelease(CaptureReadPoint.WINDOW_RELEASE) { order+=2 } }
                batch.finish(); trace.readReleased()
            } },cleanupFailure={ CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE) })
        assertTrue(outcome is CollectionResult.Blocked)
        assertEquals(listOf(1,2),order)
        assertEquals(CaptureDetail.RELEASE_FAILED,probe.detail)
        assertEquals(CaptureReadPoint.ROOT_RELEASE,probe.failurePoint)
        assertEquals(CaptureReadRelease.FAILED,probe.release)
    }
    @Test fun childReleaseFailureCannotBeOverwrittenBySuccessfulRootWindowRelease() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        try { trace.readRelease(CaptureReadPoint.CHILD_RELEASE) { error("PRIVATE") } } catch (_: Exception) { }
        trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { }
        trace.readRelease(CaptureReadPoint.WINDOW_RELEASE) { }
        trace.readReleased()
        assertEquals(CaptureReadRelease.FAILED,probe.release)
        assertEquals(CaptureReadPoint.CHILD_RELEASE,probe.failurePoint)
    }
    @Test fun exceptionSiteIsRecordedBeforeCleanupAndDoesNotStoreExceptionOrRootValues() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        val failure=IllegalStateException("PRIVATE_SENTINEL")
        try { CollectionResourceSafety.run(body={ trace.readBody {
            trace.readPoint(CaptureReadPoint.END_WINDOW_ROOT); throw failure
        } },cleanup={ trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { error("PRIVATE_CLEANUP") } },cleanupFailure={ it }) }
        catch (e: Exception) { assertSame(failure,e) }
        assertEquals(CaptureDetail.COLLECTION_EXCEPTION,probe.detail)
        assertEquals(CaptureReadPoint.END_WINDOW_ROOT,probe.failurePoint)
        assertFalse(probe.toString().contains("PRIVATE"))
        val allowed=setOf(CaptureDetail::class.java,CaptureReadPoint::class.java,CaptureReadRelease::class.java,Boolean::class.javaPrimitiveType)
        assertTrue(CaptureReadTrace::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.all { it.type in allowed })
    }
    @Test fun bodyCancellationWinsAndEveryCleanupStillRuns() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        val cancellation=CancellationException("PRIVATE"); var cleaned=0
        try { CollectionResourceSafety.run(body={ trace.readBody { throw cancellation } },cleanup={
            val batch=CollectionReleaseBatch()
            batch.attempt { trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { cleaned++; throw CancellationException() } }
            batch.attempt { trace.readRelease(CaptureReadPoint.WINDOW_RELEASE) { cleaned++ } }
            batch.finish(); trace.readReleased()
        },cleanupFailure={ it }); fail() } catch (e: CancellationException) { assertSame(cancellation,e) }
        assertEquals(2,cleaned)
        assertEquals(CaptureDetail.COLLECTION_CANCELLED,probe.detail)
        assertEquals(CaptureReadRelease.CANCELLED,probe.release)
    }
    @Test fun cleanupCancellationAfterBlockedResultIsNotConvertedToSuccess() {
        val probe=CaptureReadTrace(); val trace=trace(probe); val cancellation=CancellationException()
        try { CollectionResourceSafety.run(body={ trace.readBody {
            trace.detail(CaptureDetail.CHILD_UNAVAILABLE)
            CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE)
        } },cleanup={ trace.readRelease(CaptureReadPoint.WINDOW_RELEASE) { throw cancellation } },cleanupFailure={ it }); fail() }
        catch (e: CancellationException) { assertSame(cancellation,e) }
        assertEquals(CaptureDetail.CHILD_UNAVAILABLE,probe.detail)
        assertEquals(CaptureReadRelease.CANCELLED,probe.release)
    }
    @Test fun ordinaryAndCancelledReleaseFailuresAreBothVisible() {
        val probe=CaptureReadTrace(); val trace=trace(probe)
        for (failure in listOf(IllegalStateException(),CancellationException())) {
            try { trace.readRelease(CaptureReadPoint.CHILD_RELEASE) { throw failure } } catch (_: Exception) { }
        }
        trace.readReleased()
        assertEquals(CaptureReadRelease.FAILED_AND_CANCELLED,probe.release)
        assertEquals(CaptureDetail.RELEASE_FAILED,probe.detail)
    }
    @Test fun captureOutcomeIsTheSameWithOrWithoutProbe() {
        for (blocked in listOf(false,true)) for (cleanupFails in listOf(false,true)) {
            fun run(probe: CaptureReadTrace?): CollectionResult {
                val trace=CaptureTrace(null,collectionRead=probe)
                return CollectionResourceSafety.run<CollectionResult>(body={ trace.readBody {
                    if (blocked) { trace.detail(CaptureDetail.PACKAGE_GATE); CollectionResult.Blocked(PrivacyBlockReason.APP_NOT_ALLOWED) }
                    else P01StabilityFixture.sample(1)
                } },cleanup={ trace.readRelease(CaptureReadPoint.ROOT_RELEASE) { if(cleanupFails) error("PRIVATE") }; trace.readReleased() },
                    cleanupFailure={ if(it is CollectionResult.Blocked) it else CollectionResult.Blocked(PrivacyBlockReason.UNREADABLE_TREE) })
            }
            val plain=run(null); val diagnosed=run(CaptureReadTrace())
            assertEquals(plain::class,diagnosed::class)
            if(plain is CollectionResult.Blocked) assertEquals(plain,diagnosed)
        }
    }
}
