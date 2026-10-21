package kr.closeforyou.accessibility

import java.util.concurrent.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kr.closeforyou.core.model.PrivacyBlockReason
import org.junit.Assert.*
import org.junit.Test

/** Tests the actual capture/child cleanup boundary using callbacks, not Android recycle faults. */
class CollectionResourceSafetyTest {
    private enum class Result { READY, BLOCKED }
    private fun run(body: () -> Result = { Result.READY }, cleanup: () -> Unit = {}) =
        CollectionResourceSafety.run(body, cleanup) { Result.BLOCKED }

    @Test fun successfulCollectionReturnsSameResultAndReleasesOnce() {
        val order = mutableListOf<String>()
        assertEquals(Result.READY, run({ order += "collect"; Result.READY }, { order += "release" }))
        assertEquals(listOf("collect", "release"), order)
    }
    @Test fun earlyBlockedReturnStillReleases() {
        var released = false
        assertEquals(Result.BLOCKED, run({ Result.BLOCKED }, { released = true }))
        assertTrue(released)
    }
    @Test fun ordinaryCleanupFailureCannotReturnReady() {
        assertEquals(Result.BLOCKED, run(cleanup = { throw IllegalStateException("PRIVATE") }))
    }
    @Test fun blockedReasonAndDiagnosticIdentitySurviveCleanupFailure() {
        val original = CollectionResult.Blocked(PrivacyBlockReason.PASSWORD)
        val result = CollectionResourceSafety.run({ original }, { throw SecurityException("PRIVATE") }) { it }
        assertSame(original, result)
    }
    @Test fun originalFailureSurvivesOrdinaryCleanupFailureWithoutSuppressedValues() {
        val original = IllegalArgumentException("PRIVATE_INSPECTION")
        val thrown = assertThrows(IllegalArgumentException::class.java) {
            run({ throw original }, { throw IllegalStateException("PRIVATE_RELEASE") })
        }
        assertSame(original, thrown); assertTrue(thrown.suppressed.isEmpty())
    }
    @Test fun guardFailureSurvivesChildReleaseFailure() {
        val original = CollectionGuardFailure(PrivacyBlockReason.SENSITIVE_CONTENT,
            CollectionField.TEXT, CollectionFinding.SENSITIVE_TEXT)
        val progress = CollectionProgress()
        val thrown = assertThrows(CollectionGuardFailure::class.java) {
            CollectionResourceSafety.visitChild(progress, {
                progress.at(CollectionOperation.NODE_TEXT_CHECK, CollectionField.TEXT) { throw original }
            }, { throw SecurityException("PRIVATE") })
        }
        assertSame(original, thrown)
        assertEquals(CollectionOperation.NODE_TEXT_CHECK, progress.failure?.operation)
        assertEquals(CollectionField.TEXT, progress.failure?.field)
    }
    @Test fun childReleaseFailureAloneUsesFixedFailureAndOriginalBoundary() {
        val progress = CollectionProgress()
        val thrown = assertThrows(CollectionCleanupFailure::class.java) {
            CollectionResourceSafety.visitChild(progress, {}, { throw SecurityException("PRIVATE") })
        }
        assertEquals("COLLECTION_CLEANUP_FAILED", thrown.message)
        assertNull(thrown.cause); assertTrue(thrown.suppressed.isEmpty())
        assertEquals(CollectionOperation.CHILD_RELEASE, progress.failure?.operation)
        assertEquals(CollectionExceptionKind.SECURITY, progress.failure?.exception)
    }
    @Test fun malformedAndBudgetGuardsRemainOriginalFailuresAfterCleanup() {
        for (finding in listOf(CollectionFinding.MALFORMED_NODE_BOUNDS, CollectionFinding.TIME_LIMIT,
            CollectionFinding.NODE_COUNT_LIMIT, CollectionFinding.MISSING_CHILD)) {
            val original = CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE, finding = finding)
            assertSame(original, assertThrows(CollectionGuardFailure::class.java) {
                run({ throw original }, { throw IllegalStateException("PRIVATE_RELEASE") })
            })
        }
    }
    @Test fun uninstrumentedGuardDoesNotAcquireMisleadingChildReleaseDiagnostic() {
        val progress = CollectionProgress()
        val original = CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE,
            finding = CollectionFinding.CHILD_COUNT_LIMIT)
        assertSame(original, assertThrows(CollectionGuardFailure::class.java) {
            CollectionResourceSafety.visitChild(progress, { throw original }, { throw SecurityException("PRIVATE") })
        })
        assertNull(progress.failure)
    }
    @Test fun actualChildBoundaryPreservesCancellationAndReleasesOnce() {
        val progress = CollectionProgress(); val original = CancellationException("PRIVATE")
        var releases = 0
        assertSame(original, assertThrows(CancellationException::class.java) {
            CollectionResourceSafety.visitChild(progress, {
                progress.at(CollectionOperation.CHILD_READ) { throw original }
            }, { releases++; throw SecurityException("PRIVATE") })
        })
        assertEquals(1, releases); assertEquals(CollectionOperation.CHILD_READ, progress.failure?.operation)
    }
    @Test fun originalCancellationPropagatesAndCleanupRuns() {
        val original = CancellationException("PRIVATE")
        var released = false
        assertSame(original, assertThrows(CancellationException::class.java) {
            run({ throw original }, { released = true })
        })
        assertTrue(released)
    }
    @Test fun originalCancellationWinsOverOrdinaryReleaseFailure() {
        val original = CancellationException("PRIVATE")
        assertSame(original, assertThrows(CancellationException::class.java) {
            run({ throw original }, { throw SecurityException("PRIVATE_RELEASE") })
        })
        assertTrue(original.suppressed.isEmpty())
    }
    @Test fun originalCancellationWinsOverDifferentReleaseCancellation() {
        val original = CancellationException("PRIVATE")
        assertSame(original, assertThrows(CancellationException::class.java) {
            run({ throw original }, { throw CancellationException("OTHER_PRIVATE") })
        })
    }
    @Test fun cleanupCancellationDoesNotBecomeBlockedOrReady() {
        for (value in Result.entries) {
            val original = CancellationException("PRIVATE")
            assertSame(original, assertThrows(CancellationException::class.java) {
                run({ value }, { throw original })
            })
        }
    }
    @Test fun cleanupCancellationAlsoPropagatesWhenBodyHasOrdinaryFailure() {
        val cancelled = CancellationException("PRIVATE")
        assertSame(cancelled, assertThrows(CancellationException::class.java) {
            run({ throw IllegalStateException("PRIVATE") }, { throw cancelled })
        })
    }
    @Test fun nestedChildCancellationCannotBeMaskedByAnyAncestorCleanupException() {
        val original = CancellationException("PRIVATE")
        val releases = mutableListOf<String>()
        assertSame(original, assertThrows(CancellationException::class.java) {
            run({
                run({ throw original }, { releases += "child"; throw IllegalStateException("PRIVATE") })
            }, { releases += "root"; throw IllegalStateException("PRIVATE") })
        })
        assertEquals(listOf("child", "root"), releases)
    }
    @Test fun allRootsAndWindowsAreAttemptedDespiteMultipleReleaseExceptions() {
        val seen = mutableListOf<String>(); val batch = CollectionReleaseBatch()
        listOf("target", "background", "window1", "window2").forEach { name ->
            batch.attempt { seen += name; throw IllegalArgumentException("PRIVATE") }
        }
        assertThrows(CollectionCleanupFailure::class.java) { batch.finish() }
        assertEquals(listOf("target", "background", "window1", "window2"), seen)
    }
    @Test fun batchRetainsFirstCancellationAndStillAttemptsOtherReleases() {
        val original = CancellationException("PRIVATE"); val seen = mutableListOf<Int>()
        val batch = CollectionReleaseBatch()
        batch.attempt { seen += 0; throw original }
        batch.attempt { seen += 1; throw SecurityException("PRIVATE") }
        batch.attempt { seen += 2; throw CancellationException("OTHER_PRIVATE") }
        batch.attempt { seen += 3 }
        assertSame(original, assertThrows(CancellationException::class.java) { batch.finish() })
        assertEquals(listOf(0, 1, 2, 3), seen)
    }
    @Test fun cleanBatchHasNoFailure() {
        val batch = CollectionReleaseBatch(); var count = 0
        repeat(3) { batch.attempt { count++ } }; batch.finish(); assertEquals(3, count)
    }
    @Test fun failedBatchCannotBeRepairedByLaterSuccessfulReleases() {
        val batch = CollectionReleaseBatch()
        batch.attempt { throw IllegalStateException("PRIVATE") }
        batch.attempt {}
        assertThrows(CollectionCleanupFailure::class.java) { batch.finish() }
    }
    @Test fun collectionBoundaryBlocksReadyAfterBatchFailure() {
        var count = 0
        assertEquals(Result.BLOCKED, run(cleanup = {
            val batch = CollectionReleaseBatch()
            batch.attempt { count++; throw IllegalStateException("PRIVATE") }
            batch.attempt { count++ }; batch.finish()
        }))
        assertEquals(2, count)
    }
    @Test fun originalFailureWinsOverBatchFailureAndAllResourcesAreAttempted() {
        val original = IllegalArgumentException("PRIVATE"); var count = 0
        assertSame(original, assertThrows(IllegalArgumentException::class.java) {
            run({ throw original }, {
                val batch = CollectionReleaseBatch()
                repeat(4) { batch.attempt { count++; throw SecurityException("PRIVATE") } }; batch.finish()
            })
        })
        assertEquals(4, count)
    }
    @Test fun localReferencesCanBeClearedEvenWhenBatchFinishThrows() {
        val resources = mutableListOf(Any(), Any())
        run(cleanup = {
            val batch = CollectionReleaseBatch()
            try { resources.forEach { batch.attempt { throw IllegalStateException("PRIVATE") } }; batch.finish() }
            finally { resources.clear() }
        })
        assertTrue(resources.isEmpty())
    }
    @Test fun coroutineCallerSeesCancellationWithoutPublishingResult() = runTest {
        var published = false; var released = false
        val child = launch {
            run({ throw CancellationException("PRIVATE") }, { released = true })
            published = true
        }
        child.join()
        assertTrue(child.isCancelled); assertTrue(released); assertFalse(published)
    }
    @Test fun bodyFatalErrorIsNotConvertedToSafeResult() {
        val original = AssertionError("PRIVATE"); var released = false
        assertSame(original, assertThrows(AssertionError::class.java) {
            run({ throw original }, { released = true; throw IllegalStateException("PRIVATE") })
        })
        assertTrue(released)
    }
    @Test fun cleanupFatalErrorIsNotConvertedToBlocked() {
        val original = AssertionError("PRIVATE")
        assertSame(original, assertThrows(AssertionError::class.java) { run(cleanup = { throw original }) })
    }
    @Test fun representationsContainNoFrameworkFailureOrResourceValue() {
        val batch = CollectionReleaseBatch()
        batch.attempt { throw IllegalStateException("PRIVATE_COORDINATES_TEXT_TOKEN") }
        assertEquals("CollectionReleaseBatch(redacted)", batch.toString())
        val fixed = assertThrows(CollectionCleanupFailure::class.java) { batch.finish() }
        assertFalse(fixed.toString().contains("PRIVATE")); assertNull(fixed.cause)
        assertTrue(fixed.suppressed.isEmpty())
    }
    @Test fun handledCleanupFailuresWriteNothingToStandardOutputOrError() {
        val beforeOut = System.out; val beforeErr = System.err
        val bytes = java.io.ByteArrayOutputStream(); val stream = java.io.PrintStream(bytes)
        try {
            System.setOut(stream); System.setErr(stream)
            run(cleanup = { throw SecurityException("PRIVATE_COORDINATES_TEXT_TOKEN") })
            val batch = CollectionReleaseBatch()
            batch.attempt { throw IllegalStateException("PRIVATE") }
            assertThrows(CollectionCleanupFailure::class.java) { batch.finish() }
            assertEquals(0, bytes.size())
        } finally { System.setOut(beforeOut); System.setErr(beforeErr); stream.close() }
    }
}
