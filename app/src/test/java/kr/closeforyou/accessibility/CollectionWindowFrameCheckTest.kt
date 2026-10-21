package kr.closeforyou.accessibility

import java.util.concurrent.CancellationException
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Same bounded comparator/read/deadline control used by capture. No Android fault reproduction. */
class CollectionWindowFrameCheckTest {
    private val frame = ScreenBounds(0, 0, 100, 200)
    private val target = WindowSnapshot(7, WindowType.APPLICATION, frame, true, true, 1)
    private val background = WindowSnapshot(8, WindowType.APPLICATION, frame, false, false, 0)
    private fun sample(pkg: String? = "synthetic.host", root: Int = 7, display: Int = 0,
        bounds: ScreenBounds? = frame, status: ScreenFrameStatus = ScreenFrameStatus.VERIFIED,
        rotation: Int? = 0, windows: List<WindowSnapshot> = listOf(target, background)) =
        CollectionWindowFrameSample(pkg, root, display, bounds, status, rotation, windows)
    private fun rejected(end: CollectionWindowFrameSample, retained: Int = 7) =
        assertThrows(CollectionGuardFailure::class.java) {
            CollectionWindowFrameCheck.begin(sample()).finish(retained, {}) { end }
        }

    @Test fun identicalEndpointsPermitOnlyContinuationAndReadExactlyOnce() {
        val check = CollectionWindowFrameCheck.begin(sample()); var reads = 0; var budgets = 0
        check.finish(7, { budgets++ }) { reads++; sample() }
        assertEquals(1, reads); assertEquals(3, budgets)
    }
    @Test fun windowListOrderAloneDoesNotChangeScalarScope() {
        CollectionWindowFrameCheck.begin(sample()).finish(7, {}) { sample(windows = listOf(background, target)) }
    }
    @Test fun targetIdChangeRejects() { rejected(sample(root = 8)) }
    @Test fun retainedRootIdChangeRejects() { rejected(sample(), retained = 8) }
    @Test fun packageChangeRejects() { rejected(sample(pkg = "synthetic.other")) }
    @Test fun nullPackageRejects() { rejected(sample(pkg = null)) }
    @Test fun emptyPackageRejects() { rejected(sample(pkg = "")) }
    @Test fun targetDisplayChangeRejects() { rejected(sample(display = 1)) }
    @Test fun captureIsolationStillRejectsAnySecondaryWindowAtCompletion() {
        CollectionWindowFrameCheck.requireCaptureDisplayIsolation(true) { false }
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            CollectionWindowFrameCheck.requireCaptureDisplayIsolation(true) { true }
        }
        assertEquals(PrivacyBlockReason.SYSTEM_WINDOW, failure.reason)
    }
    @Test fun noExtraSecondaryDisplayReadWhenIsolationNotRequiredAndCancellationPropagates() {
        var reads = 0
        CollectionWindowFrameCheck.requireCaptureDisplayIsolation(false) { reads++; true }
        assertEquals(0, reads)
        val original = CancellationException("PRIVATE")
        assertSame(original, assertThrows(CancellationException::class.java) {
            CollectionWindowFrameCheck.requireCaptureDisplayIsolation(true) { throw original }
        })
    }
    @Test fun frameChangeRejectsEvenWhenBothRelationshipsAreEqual() {
        val other = ScreenBounds(0, 0, 101, 201)
        rejected(sample(bounds = other, windows = listOf(target.copy(bounds = other), background)))
    }
    @Test fun rotationChangeWithIdenticalDimensionsRejects() { rejected(sample(rotation = 2)) }
    @Test fun missingRotationRejects() { rejected(sample(rotation = null)) }
    @Test fun invalidRotationRejects() { rejected(sample(rotation = 4)) }
    @Test fun unavailableFrameRejects() { rejected(sample(bounds = null, status = ScreenFrameStatus.UNAVAILABLE)) }
    @Test fun everyNonverifiedFrameStatusRejects() {
        ScreenFrameStatus.entries.filter { it != ScreenFrameStatus.VERIFIED }.forEach { rejected(sample(status = it)) }
    }
    @Test fun emptyOrOffsetFrameRejects() {
        rejected(sample(bounds = ScreenBounds(0, 0, 0, 200)))
        rejected(sample(bounds = ScreenBounds(1, 0, 100, 200)))
    }
    @Test fun targetBoundsChangeRejectsEvenWhenStillWithinFrame() {
        rejected(sample(windows = listOf(target.copy(bounds = ScreenBounds(1, 1, 99, 199)), background)))
    }
    @Test fun backgroundBoundsChangeRejects() {
        rejected(sample(windows = listOf(target, background.copy(bounds = ScreenBounds(1, 1, 99, 199)))))
    }
    @Test fun targetMetadataChangesReject() {
        for (changed in listOf(target.copy(active = false), target.copy(focused = false),
            target.copy(layer = 2), target.copy(type = WindowType.SYSTEM))) {
            rejected(sample(windows = listOf(changed, background)))
        }
    }
    @Test fun backgroundMetadataChangeRejects() {
        rejected(sample(windows = listOf(target, background.copy(active = true))))
    }
    @Test fun missingOrAdditionalWindowRejects() {
        rejected(sample(windows = listOf(target)))
        rejected(sample(windows = listOf(target, background, background.copy(id = 9))))
    }
    @Test fun missingTargetRejectsWithoutChoosingAnotherWindow() { rejected(sample(windows = listOf(background))) }
    @Test fun duplicateWindowIdsReject() { rejected(sample(windows = listOf(target, target))) }
    @Test fun emptyOrOversizedWindowListRejects() {
        rejected(sample(windows = emptyList()))
        rejected(sample(windows = (0..32).map { target.copy(id = it) }))
    }
    @Test fun activeSystemWindowRejects() {
        rejected(sample(windows = listOf(target, background.copy(type = WindowType.SYSTEM, active = true))))
    }
    @Test fun invalidInitialSampleCannotBecomeAReference() {
        for (start in listOf(sample(pkg = null), sample(display = 1), sample(status = ScreenFrameStatus.UNAVAILABLE),
            sample(rotation = null), sample(windows = emptyList()), sample(windows = listOf(target, target)))) {
            assertThrows(CollectionGuardFailure::class.java) { CollectionWindowFrameCheck.begin(start) }
        }
    }
    @Test fun initialWindowListIsCopiedWithinTheBound() {
        val mutable = mutableListOf(target, background)
        val check = CollectionWindowFrameCheck.begin(sample(windows = mutable))
        mutable.clear()
        check.finish(7, {}) { sample() }
    }
    @Test fun ordinaryEndpointReadFailureDoesNotPermitContinuation() {
        val original = SecurityException("PRIVATE"); var continued = false
        assertSame(original, assertThrows(SecurityException::class.java) {
            CollectionWindowFrameCheck.begin(sample()).finish(7, {}) { throw original }
            continued = true
        })
        assertFalse(continued)
    }
    @Test fun cancelledReadPreservesCancellationAndResourceCleanup() {
        val original = CancellationException("PRIVATE"); var released = 0
        assertSame(original, assertThrows(CancellationException::class.java) {
            CollectionResourceSafety.run({
                CollectionWindowFrameCheck.begin(sample()).finish(7, {}) { throw original }
            }, { released++; throw IllegalStateException("PRIVATE_RELEASE") }, { error("UNREACHABLE") })
        })
        assertEquals(1, released)
    }
    @Test fun deadlineBeforeReadRejectsWithoutIpc() {
        var read = false
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            CollectionWindowFrameCheck.begin(sample()).finish(7,
                { CollectionWindowFrameCheck.checkDeadline(101, 100) }) { read = true; sample() }
        }
        assertFalse(read); assertEquals(CollectionFinding.TIME_LIMIT, failure.finding)
    }
    @Test fun readThatCrossesOriginalDeadlineCannotReturnReady() {
        var now = 99L; var reads = 0; var continued = false
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            CollectionWindowFrameCheck.begin(sample()).finish(7,
                { CollectionWindowFrameCheck.checkDeadline(now, 100) }) { reads++; now = 101; sample() }
            continued = true
        }
        assertEquals(1, reads); assertFalse(continued)
        assertEquals(PrivacyBlockReason.TRUNCATED_TREE, failure.reason)
    }
    @Test fun comparatorCostIsCheckedBeforeContinuation() {
        var now = 50L
        val lateList = object : AbstractList<WindowSnapshot>() {
            override val size = 2
            override fun get(index: Int): WindowSnapshot { now = 101; return listOf(target, background)[index] }
        }
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            CollectionWindowFrameCheck.begin(sample()).finish(7,
                { CollectionWindowFrameCheck.checkDeadline(now, 100) }) { sample(windows = lateList) }
        }
        assertEquals(CollectionFinding.TIME_LIMIT, failure.finding)
    }
    @Test fun originalDeadlineBoundaryIsPreservedAndNotRestarted() {
        CollectionWindowFrameCheck.begin(sample()).finish(7,
            { CollectionWindowFrameCheck.checkDeadline(100, 100) }) { sample() }
        assertThrows(CollectionGuardFailure::class.java) { CollectionWindowFrameCheck.checkDeadline(101, 100) }
    }
    @Test fun budgetCancellationPropagatesWithoutReadOrSuccess() {
        val original = CancellationException("PRIVATE"); var reads = 0
        assertSame(original, assertThrows(CancellationException::class.java) {
            CollectionWindowFrameCheck.begin(sample()).finish(7, { throw original }) { reads++; sample() }
        })
        assertEquals(0, reads)
    }
    @Test fun validationFailureWithReleaseFailureKeepsOriginalFinding() {
        val failure = assertThrows(CollectionGuardFailure::class.java) {
            CollectionResourceSafety.run({
                CollectionWindowFrameCheck.begin(sample()).finish(7, {}) { sample(rotation = 2) }
            }, { throw SecurityException("PRIVATE") }, { error("UNREACHABLE") })
        }
        assertEquals(CollectionFinding.GEOMETRY_GUARD, failure.finding)
    }
    @Test fun changedEndIsNotRetriedOrReplacedWithLaterGoodData() {
        var calls = 0
        assertThrows(CollectionGuardFailure::class.java) {
            CollectionWindowFrameCheck.begin(sample()).finish(7, {}) {
                calls++; if (calls == 1) sample(display = 1) else sample()
            }
        }
        assertEquals(1, calls)
    }
    @Test fun unchangedOutsideWindowIsNeverCorrectedOrAcceptedByDetector() {
        val outside = ScreenBounds(0, 0, 101, 201)
        val windows = listOf(target.copy(bounds = outside), background)
        CollectionWindowFrameCheck.begin(sample(windows = windows)).finish(7, {}) { sample(windows = windows) }
        val result = PopupCandidateDetector().assess(DetectionFrame("synthetic", 7, setOf(7, 8),
            "synthetic-structure", frame, emptyList(), PopupTrigger.WINDOW_CHANGED, outside))
        assertEquals(DetectionDisposition.UNCERTAIN, result.disposition)
        assertEquals(DetectionReason.INVALID_GEOMETRY, result.reason)
        assertEquals(DetectionGeometry.WINDOW_OUTSIDE_FRAME, result.geometry?.result)
        assertEquals(outside, windows.first().bounds)
    }
    @Test fun unchangedNonpositiveWindowDoesNotBecomeCandidate() {
        val empty = ScreenBounds(1, 1, 1, 10); val windows = listOf(target.copy(bounds = empty), background)
        CollectionWindowFrameCheck.begin(sample(windows = windows)).finish(7, {}) { sample(windows = windows) }
        assertEquals(DetectionReason.INVALID_GEOMETRY, PopupCandidateDetector().assess(DetectionFrame("synthetic", 7,
            setOf(7), "synthetic-structure", frame, emptyList(), PopupTrigger.WINDOW_CHANGED, empty)).reason)
    }
    @Test fun wrappersAndFailuresHaveNoSensitiveStringRepresentation() {
        val s = sample(pkg = "SYNTHETIC_PRIVATE_ID")
        val check = CollectionWindowFrameCheck.begin(s)
        assertEquals("CollectionWindowFrameSample(redacted)", s.toString())
        assertEquals("CollectionWindowFrameCheck(redacted)", check.toString())
        val failure = rejected(sample(pkg = "SYNTHETIC_PRIVATE_ID"))
        assertNull(failure.message); assertNull(failure.cause); assertTrue(failure.suppressed.isEmpty())
    }
}
