package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.toPersistentSet
import kr.closeforyou.core.model.AutoEligibility
import kr.closeforyou.core.model.EligibilityCheck
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

class SamsungPromptLifetimeTest {
    private class Rig {
        var now = 1_000L
        val token = F9AutoCloserTest.Fixture().token
        var state = OverlayWindowState(OverlayWindowStatus.SHOWING, 2)
        val lease = ManualPromptLifetime { now }.also { it.shown(token, state) }
        val owner = Any()
        var native = OverlayNativeWindowLifetime(2, owner).also { it.attached(99); it.addReturned(true) }
        var pkg: String? = "synthetic.own"
        var event = 99
        var at = 1_000L
        var before: Set<Int>? = emptySet()
        var live: Set<Int>? = setOf(99)
        var fallbackCalls = 0
        val probe = ManualOwnershipProbe()
        fun read(type: Int = E.TYPE_WINDOWS_CHANGED, mask: Int = E.WINDOWS_CHANGE_ACTIVE, diagnostic: ManualOwnershipProbe? = probe) = inspectManualEvent(type,
            nativeControl = { inspectManualOwnedInteraction(type, state, lease, { at }, { pkg }, "synthetic.own",
                { event }, { native.liveWindow(owner, state.generation) }, before, { live }, diagnostic) },
            otherEvent = { fallbackCalls++; true },
            nativeWindowEvent = { inspectManualOwnedWindowEvent(type, { mask }, state, lease, { at }, { pkg }, "synthetic.own",
                { event }, { native.liveWindow(owner, state.generation) }, before, { live }, diagnostic) })
    }
    @Test fun ownNativeWindowStateNotificationIsAcceptedWithoutTheLegacyTitleBinding() {
        val r = Rig(); assertTrue(r.read(E.TYPE_WINDOW_STATE_CHANGED))
        assertEquals(ManualOwnership.OWNED, r.probe.result); assertEquals(0, r.fallbackCalls)
    }
    @Test fun ownWindowFocusActiveLayoutMasksAndTheirCombinationsRequireCompleteProof() {
        for (mask in listOf(E.WINDOWS_CHANGE_ACTIVE, E.WINDOWS_CHANGE_FOCUSED, E.WINDOWS_CHANGE_ADDED,
            E.WINDOWS_CHANGE_BOUNDS, E.WINDOWS_CHANGE_LAYER, E.WINDOWS_CHANGE_TITLE,
            E.WINDOWS_CHANGE_ADDED or E.WINDOWS_CHANGE_ACTIVE or E.WINDOWS_CHANGE_FOCUSED)) {
            val r = Rig(); assertTrue(r.read(mask = mask)); assertEquals(0, r.fallbackCalls)
        }
    }
    @Test fun missingPackageIsAllowedOnlyForTheExactNativeWindowsChangedEvent() {
        val r = Rig(); r.pkg = null
        assertTrue(r.read()); assertEquals(OwnershipRelation.UNAVAILABLE, r.probe.pkg)
        assertEquals(OwnershipLive.SINGLE_MATCH, r.probe.live)
        assertFalse(r.read(E.TYPE_WINDOW_STATE_CHANGED))
        assertFalse(r.read(E.TYPE_WINDOW_CONTENT_CHANGED)); assertEquals(0, r.fallbackCalls)
    }
    @Test fun targetApplicationAndOtherWindowChangesAlwaysKeepInvalidation() {
        for (type in listOf(E.TYPE_WINDOW_STATE_CHANGED, E.TYPE_WINDOWS_CHANGED)) {
            for (id in listOf(20, 98, -1)) {
                val r = Rig(); r.event = id
                assertFalse(r.read(type)); assertEquals(0, r.fallbackCalls)
            }
        }
    }
    @Test fun foreignPackageIsRejectedEvenWhenItClaimsTheCurrentNativeId() {
        val r = Rig(); r.pkg = "synthetic.other"
        assertFalse(r.read()); assertEquals(OwnershipCheck.PACKAGE, r.probe.check)
        assertFalse(r.read(E.TYPE_WINDOW_CONTENT_CHANGED))
        val code = manualEventCode(E.TYPE_WINDOW_CONTENT_CHANGED, r.probe.check, r.probe.result,
            99, 99, 20, r.pkg, "synthetic.own", "synthetic.target")
        assertEquals(ManualOwnership.NOT_OWNED, code.result)
        assertEquals(ManualEventRelation.NATIVE_ID_MATCH, code.relation)
        assertEquals(ManualEventPackage.OTHER, code.pkg)
    }
    @Test fun removalZeroUnsupportedAndMixedMasksCannotFallBackOrPreserveDisplay() {
        for (mask in listOf(0, E.WINDOWS_CHANGE_REMOVED, E.WINDOWS_CHANGE_PARENT, E.WINDOWS_CHANGE_CHILDREN,
            E.WINDOWS_CHANGE_ACTIVE or E.WINDOWS_CHANGE_REMOVED)) {
            val r = Rig(); assertFalse(r.read(mask = mask))
            assertEquals(OwnershipCheck.EVENT_MASK, r.probe.check); assertEquals(0, r.fallbackCalls)
        }
    }
    @Test fun uncertainNativeOrLiveSetAndReusedIdsAreNeverOwned() {
        val changes: List<(Rig) -> Unit> = listOf({ it.before = null }, { it.before = setOf(99) },
            { it.live = null }, { it.live = emptySet() }, { it.live = setOf(99, 100) },
            { it.native.removalRequested() }, { it.native.addReturned(false) },
            { it.state = it.state.copy(generation = 3) })
        for (change in changes) { val r = Rig().also(change); assertFalse(r.read()); assertEquals(0, r.fallbackCalls) }
    }
    @Test fun beforeShowFutureExpiredAndClockReversalStillInvalidate() {
        for (change in listOf<(Rig) -> Unit>({ it.at = 999 }, { it.at = 1_001 },
            { it.now += 30_000 }, { it.now = 999 })) {
            assertFalse(Rig().also(change).read())
        }
    }
    @Test fun ownWindowEventsAtSixSecondsRetainPresentationWithoutRenewingOriginalEvidence() {
        val r = Rig(); r.now += 6_000; r.at = r.now
        assertTrue(r.read()); assertTrue(r.read(E.TYPE_WINDOW_STATE_CHANGED))
        assertEquals(r.token, r.lease.current())
        val old = F9AutoCloserTest.Fixture().snapshot
        val eligibility = AutoEligibility(old.token, EligibilityCheck.entries.toPersistentSet(), old.capturedAtUptimeMillis)
        assertFalse(eligibility.validFor(r.token, r.now)) // Display proof never renews the old execute evidence.
    }
    @Test fun eventCreatedDuringActualAddIsAcceptedOnlyAfterShownWithTheSameNativeGeneration() {
        val r = Rig(); r.lease.nativeCreating(2); assertNull(r.lease.current())
        r.at = 1_005; r.now = 1_010
        r.lease.shown(r.token, r.state)
        assertTrue(r.read(E.TYPE_WINDOW_STATE_CHANGED))
        assertEquals(OwnershipTime.CURRENT_NATIVE_CREATION, r.probe.time)
        r.event = 20; assertFalse(r.read(E.TYPE_WINDOW_STATE_CHANGED))
    }
    @Test fun oldEventsBeforeNativeCreationAndChangedGenerationCannotUseTheAddInterval() {
        val r = Rig(); r.lease.nativeCreating(2); r.now = 1_010; r.lease.shown(r.token, r.state)
        r.at = 999; assertFalse(r.read())
        r.at = 1_005; r.state = r.state.copy(generation = 3); assertFalse(r.read())
        r.lease.nativeCreating(3); r.now = 1_020; r.lease.shown(r.token, r.state)
        assertFalse(r.read()) // A previous View's queued event never moves to the new generation.
    }
    @Test fun nativeCreationDoesNotRenewDisplayLeaseAndReversedClockCannotConfirmShow() {
        val r = Rig(); r.lease.nativeCreating(2); r.now = 1_010; r.lease.shown(r.token, r.state)
        r.now += 29_999; assertEquals(r.token, r.lease.current())
        r.now++; assertNull(r.lease.current())
        r.now = 2_000; r.lease.nativeCreating(2); r.now = 1_999
        r.lease.shown(r.token, r.state); assertNull(r.lease.current())
    }
    @Test fun diagnosticRelationsCannotCertifyEventOriginOrExposeIdentifiers() {
        val own = manualEventCode(E.TYPE_WINDOWS_CHANGED, OwnershipCheck.COMPLETE, ManualOwnership.OWNED,
            99, 99, 20, null, "synthetic.own", "synthetic.target")
        val target = manualEventCode(E.TYPE_WINDOW_STATE_CHANGED, OwnershipCheck.PACKAGE, ManualOwnership.NOT_OWNED,
            20, 99, 20, "synthetic.target", "synthetic.own", "synthetic.target")
        assertEquals(ManualEventRelation.ORIGINAL_TARGET_ID_MATCH, target.relation)
        assertEquals(ManualEventPackage.ORIGINAL_TARGET, target.pkg)
        for (c in listOf(own, target)) {
            assertFalse(c.toString().contains("synthetic")); assertFalse(c.toString().contains("99"))
        }
    }
    @Test fun lateReceiptAfterNativeCleanupCannotClearANewNativeOrScheduleDuplicateRecovery() {
        val review = ManualCleanupReview(); val permit = Any()
        var currentNative: Any? = Any(); val nextNative = Any(); var reads = 0
        completeManualCleanupReview(true, true, true) {
            currentNative = null
            if (review.take(permit, false, false)) reads++
        }
        assertNull(currentNative); currentNative = nextNative
        completeManualCleanupReview(false, true, false) { currentNative = null; reads++ }
        assertSame(nextNative, currentNative); assertFalse(review.take(permit, false, false))
        assertEquals(1, reads)
    }
    @Test fun cleanupFailureHiddenUnconfirmedPendingSelectionAndServiceCloseCannotScheduleRecovery() {
        for ((pending, ready, hidden) in listOf(Triple(true, false, true), Triple(true, true, false), Triple(false, true, true))) {
            completeManualCleanupReview(pending, ready, hidden) { error("NO_RECOVERY") }
        }
        val review = ManualCleanupReview()
        assertFalse(review.take(null, false, false)); assertFalse(review.take(Any(), true, false))
        assertFalse(review.take(Any(), false, true)); assertTrue(review.take(Any(), false, false))
    }
    @Test fun eventAndSpeechDiagnosticsAreBoundedAndThrowingSinkCannotChangeProof() {
        var events = 0; var speech = 0
        val t = ManualPromptTrace(eventSink = { _, _ -> events++; error("SYNTHETIC") },
            speechSink = { _, _ -> speech++; error("SYNTHETIC") }, sink = { _, _, _ -> })
        val r = Rig(); assertTrue(r.read())
        val code = manualEventCode(E.TYPE_WINDOWS_CHANGED, r.probe.check, r.probe.result, 99, 99, 20,
            null, "synthetic.own", "synthetic.target")
        repeat(100) { t.windowEvent(code); t.speech(ManualSpeechState.READY) }
        assertEquals(1, events); assertEquals(1, speech)
    }
    @Test fun repeatedOffersKeepTheOriginalBudgetAndReportWhyANewIdentityWasAdmitted() {
        val budget = ChoiceReviewBudget(); val token = F9AutoCloserTest.Fixture().token
        assertTrue(budget.admit(token)); assertEquals(ManualReviewAdmission.FIRST_OFFER, budget.lastAdmission)
        assertTrue(budget.admit(token)); assertEquals(ManualReviewAdmission.UNCHANGED_RECOVERY, budget.lastAdmission)
        assertFalse(budget.admit(token)); assertEquals(ManualReviewAdmission.EXHAUSTED, budget.lastAdmission)
        assertTrue(budget.admit(token.copy(structureDigest = "changed")))
        assertEquals(ManualReviewAdmission.IDENTITY_CHANGED, budget.lastAdmission)
    }
    @Test fun cancellationOfOldSpeechCannotBeReportedAsTheNextPromptsCancellation() {
        val codes = mutableListOf<Pair<Long, ManualSpeechState>>()
        val trace = ManualPromptTrace(speechSink = { attempt, code -> codes += attempt to code }, sink = { _, _, _ -> })
        trace.begin(); val oldReporter = trace.speechReporter()
        oldReporter(ManualSpeechState.REQUESTED)
        trace.begin(); trace.speech(ManualSpeechState.REQUESTED)
        oldReporter(ManualSpeechState.CANCELLED)
        assertEquals(listOf(1L to ManualSpeechState.REQUESTED, 2L to ManualSpeechState.REQUESTED,
            1L to ManualSpeechState.CANCELLED), codes)
    }
    @Test fun debugProbeAndReleaseNullProbeUseTheSameNativeWindowVerdict() {
        val changes: List<(Rig) -> Unit> = listOf({}, { it.pkg = null }, { it.pkg = "synthetic.other" },
            { it.event = 20 }, { it.event = -1 }, { it.live = null }, { it.live = setOf(99, 100) },
            { it.before = setOf(99) }, { it.at = 999 }, { it.now += 30_000 })
        for (type in listOf(E.TYPE_WINDOW_STATE_CHANGED, E.TYPE_WINDOWS_CHANGED, E.TYPE_WINDOW_CONTENT_CHANGED)) {
            for (change in changes) {
                val debug = Rig().also(change); val release = Rig().also(change)
                assertEquals(debug.read(type), release.read(type, diagnostic = null))
                assertEquals(debug.fallbackCalls, release.fallbackCalls)
            }
        }
    }

}
