package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.coroutines.CancellationException
import kr.closeforyou.ui.voice.overlay.OverlayNativeWindowLifetime
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production query, display short circuit and service pre-handler barrier order.
 * Android getter/native callbacks are fake; no Samsung native/window-cache behavior is proved. */
class ManualLiveSetCleanupDiagnosticsTest {
    private class Reader {
        var windows = listOf(30)
        var failRead = false
        var failRoot = false
        var failRelease = false
        var rootMissing = false
        var rootOwned = true
        var titleOwned = true
        var overlay = true
        var unhealthy = false
        val calls = mutableListOf<String>()
        val released = mutableListOf<Int>()
        fun read(probe: ManualOwnedReadProbe? = null) = readOwnedOverlayIds(
            readWindows = { calls += "WINDOWS"; if (failRead) error("PRIVATE_READ"); windows },
            isOverlayOnDefaultDisplay = { calls += "TYPE"; overlay },
            readRoot = { calls += "ROOT"; if (failRoot) error("PRIVATE_ROOT"); if (rootMissing) null else it + 100 },
            isOwnedRoot = { _, _ -> calls += "OWNER"; rootOwned },
            hasOwnedTitle = { calls += "TITLE"; titleOwned }, windowId = { calls += "ID"; it },
            releaseRoot = { released += it; if (failRelease) error("PRIVATE_RELEASE") },
            releaseWindow = { released += it; if (failRelease) error("PRIVATE_RELEASE") },
            onUnhealthy = { unhealthy = true }, diagnostic = null, probe = probe)
        fun display(): ManualDisplayProbe {
            val display = ManualDisplayProbe().also { it.point = ManualDisplayPoint.NATIVE_BEFORE }
            manualDisplayOwnedId({ 30 }, { false }, {
                val read = ManualOwnedReadProbe()
                read(read).also { display.ownedRead = read.code() }
            }, display)
            return display
        }
    }
    @Test fun queryFailureIsDistinctFromZeroOwnedCandidatesAndRemainsUnavailable() {
        val r = Reader().also { it.failRead = true }; val p = r.display()
        assertEquals(ManualDisplayDetail.LIVE_SET_UNCONFIRMED, p.detail)
        assertEquals(ManualOwnedCount.UNAVAILABLE, p.failureOwned.count)
        assertEquals(ManualOwnedQuery.READ_FAILED, p.failureOwned.query); assertTrue(r.unhealthy)
        val empty = Reader().also { it.overlay = false }.display()
        assertEquals(ManualOwnedCount.NONE, empty.failureOwned.count)
        assertEquals(ManualOwnedQuery.COMPLETE, empty.failureOwned.query)
        assertEquals(OverlayOwnedScanDetail.NO_ELIGIBLE_OVERLAY, empty.failureOwned.scan)
    }
    @Test fun emptyFrameworkListAndOversizedListAreUnavailableNotZeroOwnedWindows() {
        for ((list, query) in listOf(emptyList<Int>() to ManualOwnedQuery.EMPTY_WINDOW_LIST,
            (1..33).toList() to ManualOwnedQuery.WINDOW_LIMIT)) {
            val r = Reader().also { it.windows = list }; val p = r.display()
            assertEquals(ManualOwnedCount.UNAVAILABLE, p.failureOwned.count)
            assertEquals(query, p.failureOwned.query); assertFalse(r.unhealthy)
            assertEquals(list, r.released)
        }
    }
    @Test fun zeroCandidatesRecordsMissingRootWithoutInventingNativeAbsence() {
        val p = Reader().also { it.rootMissing = true }.display()
        assertEquals(ManualOwnedCount.NONE, p.failureOwned.count)
        assertEquals(OverlayOwnedScanDetail.ROOT_UNAVAILABLE, p.failureOwned.scan)
        assertEquals(OwnershipRelation.UNAVAILABLE, p.failureOwned.native)
    }
    @Test fun rootOwnershipAndTitleRejectionAreSeparateExistingScanChecks() {
        val owner = Reader().also { it.rootOwned = false }; val p = owner.display()
        assertEquals(OverlayOwnedScanDetail.ROOT_RELATION_UNCONFIRMED, p.failureOwned.scan)
        assertFalse(owner.calls.contains("TITLE"))
        val title = Reader().also { it.titleOwned = false }.display()
        assertEquals(OverlayOwnedScanDetail.TITLE_UNCONFIRMED, title.failureOwned.scan)
        assertEquals(ManualOwnedCount.NONE, title.failureOwned.count)
    }
    @Test fun multipleCandidatesNeverBecomeConfirmedNativeOwnership() {
        val p = Reader().also { it.windows = listOf(30, 31) }.display()
        assertEquals(ManualOwnedCount.MULTIPLE, p.failureOwned.count)
        assertEquals(OwnershipRelation.UNAVAILABLE, p.failureOwned.native)
        assertEquals(ManualDisplayDetail.LIVE_SET_UNCONFIRMED, p.detail)
    }
    @Test fun singleOtherCandidateReportsMismatchAndRefuses() {
        val p = Reader().also { it.windows = listOf(31) }.display()
        assertEquals(ManualOwnedCount.ONE, p.failureOwned.count)
        assertEquals(OwnershipRelation.MISMATCH, p.failureOwned.native)
        assertEquals(ManualDisplayDetail.LIVE_SET_UNCONFIRMED, p.detail)
    }
    @Test fun matchingCandidateKeepsOriginalSuccessWithoutNewGetters() {
        val r = Reader(); val p = r.display()
        assertEquals(ManualDisplayDetail.NONE, p.detail)
        assertEquals(ManualOwnedCount.ONE, p.ownedRead.count)
        assertEquals(OwnershipRelation.MATCH, p.ownedRead.native)
        assertEquals(listOf("WINDOWS", "TYPE", "ROOT", "OWNER", "TITLE", "ID"), r.calls)
        assertEquals(listOf(130, 30), r.released)
    }
    @Test fun duplicateSameOrdinalWindowIdStillUsesExistingSetSemantics() {
        val r = Reader().also { it.windows = listOf(30, 30) }; val p = r.display()
        assertEquals(ManualOwnedCount.ONE, p.ownedRead.count) // describes filtered unique IDs, not raw list size
        assertEquals(ManualDisplayDetail.NONE, p.detail)
    }
    @Test fun releaseFailureDiscardsPreviouslyFoundCandidateAndAttemptsEveryRelease() {
        val r = Reader().also { it.windows = listOf(30, 31); it.failRelease = true }; val p = r.display()
        assertEquals(ManualOwnedQuery.RELEASE_FAILED, p.failureOwned.query)
        assertEquals(ManualOwnedCount.UNAVAILABLE, p.failureOwned.count)
        assertTrue(r.unhealthy); assertEquals(listOf(130, 131, 30, 31), r.released)
    }
    @Test fun readAndReleaseFailuresAreBothRecordedWithoutExceptionText() {
        val r = Reader().also { it.windows = listOf(30, 31); it.failRoot = true; it.failRelease = true }; val p = r.display()
        assertEquals(ManualOwnedQuery.READ_AND_RELEASE_FAILED, p.failureOwned.query)
        assertEquals(listOf(30, 31), r.released); assertTrue(r.unhealthy)
        assertFalse(p.failureOwned.toString().contains("PRIVATE")); assertFalse(p.toString().contains("PRIVATE"))
    }
    @Test fun probePresenceDoesNotChangeQueryResultGetterOrderOrReleaseOrder() {
        for (mode in 0..4) {
            fun rig() = Reader().also { when (mode) { 1 -> it.failRelease = true; 2 -> it.rootMissing = true
                3 -> it.titleOwned = false; 4 -> it.failRead = true } }
            val a = rig(); val b = rig()
            assertEquals(a.read(), b.read(ManualOwnedReadProbe()))
            assertEquals(a.calls, b.calls); assertEquals(a.released, b.released); assertEquals(a.unhealthy, b.unhealthy)
        }
    }
    @Test fun firstFailureRetainsItsOwnedQueryEvenIfLaterStateChanges() {
        val p = Reader().also { it.windows = listOf(31) }.display()
        val first = p.failureOwned
        p.ownedRead = ManualOwnedReadCode(ManualOwnedCount.ONE, ManualOwnedQuery.COMPLETE,
            OverlayOwnedScanDetail.OWNED_CANDIDATE, OwnershipRelation.MATCH)
        p.point = ManualDisplayPoint.NATIVE_AFTER; p.reject(ManualDisplayDetail.EXCEPTION)
        assertEquals(first, p.failureOwned); assertEquals(ManualDisplayPoint.NATIVE_BEFORE, p.failurePoint)
    }
    private class Cleanup {
        var time = 1_000L
        val owner = Any()
        val native = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true) }
        val order = mutableListOf<String>()
        val events = mutableListOf<ManualCleanupEventCode>()
        val failures = mutableListOf<CleanupFailure>()
        val barrier = OverlayCleanupBarrier(clock = { time }, eventDiagnostic = {
            order += "OBSERVE"; events += it
        }, failureAttemptDiagnostic = { failure, _ -> order += "FAIL"; failures += failure })
        init {
            native.removalRequested(); barrier.retiring(null, 1, 20, native, owner)
            native.removalReturned(true); barrier.removalReturned(true); native.detached()
            assertFalse(barrier.nativeProcessed(native, true) { CleanupWindowPresence.PRESENT })
        }
        fun deliver(type: Int, mask: Int = 0): OverlayCleanupBarrier.Receipt? {
            val code = inspectNativeCleanupRetirement(type, mask, { true }, { true }, { true })
            order += "CLASSIFY"
            return barrier.observeWindowEvent(30, type, mask, code.check == ManualRetirementCheck.CLEANUP_WAIT).also {
                order += "HANDLER" // actual service always handles WAIT_ONLY normally; not a choice permit
            }
        }
    }
    @Test fun actualStateWaitThenMixedRemovalSequenceIdentifiesActualFailingEvent() {
        val r = Cleanup()
        assertNull(r.deliver(E.TYPE_WINDOW_STATE_CHANGED)); assertTrue(r.barrier.pending)
        assertTrue(r.events.single().transient)
        assertEquals(CleanupNativeProgress.WAITING_WINDOW_ABSENCE, r.events.single().native)
        val mask = E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_BOUNDS
        assertNull(r.deliver(E.TYPE_WINDOWS_CHANGED, mask))
        val c = r.events.last()
        assertEquals(ManualWindowMask.MIXED_REMOVAL, c.mask); assertTrue(c.removed); assertTrue(c.bounds)
        assertFalse(c.added); assertFalse(c.transient); assertEquals(OwnershipRelation.MATCH, c.relation)
        assertEquals(ManualCleanupEventResult.REJECTED, c.result)
        assertEquals(listOf("CLASSIFY", "OBSERVE", "HANDLER", "CLASSIFY", "FAIL", "OBSERVE", "HANDLER"), r.order)
        assertEquals(listOf(CleanupFailure.RETIRED_ID_EVENT), r.failures); assertFalse(r.barrier.ready)
    }
    @Test fun eventTypeRejectionNeverClaimsPackageOrNativeCheckWasPerformed() {
        val code = inspectNativeCleanupRetirement(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ADDED,
            { error("NO_DISPLAY") }, { error("NO_PACKAGE") }, { error("NO_NATIVE") })
        assertEquals(ManualRetirementCheck.EVENT_TYPE, code.check); assertEquals(ManualWindowMask.ADDED, code.mask)
        assertEquals(OwnershipRelation.NOT_CHECKED, code.display)
        assertEquals(OwnershipRelation.NOT_CHECKED, code.pkg); assertEquals(OwnershipRelation.NOT_CHECKED, code.native)
    }
    @Test fun retirementShortCircuitRecordsOnlyActualExistingChecks() {
        val order = mutableListOf<String>()
        val code = inspectNativeCleanupRetirement(E.TYPE_WINDOW_STATE_CHANGED, 0,
            { order += "DISPLAY"; true }, { order += "PACKAGE"; false }, { error("NO_NATIVE") })
        assertEquals(listOf("DISPLAY", "PACKAGE"), order); assertEquals(ManualRetirementCheck.PACKAGE, code.check)
        assertEquals(OwnershipRelation.MATCH, code.display); assertEquals(OwnershipRelation.MISMATCH, code.pkg)
        assertEquals(OwnershipRelation.NOT_CHECKED, code.native)
    }
    @Test fun pureRemovalStillRequiresActualNativeDetachAndFullAbsence() {
        val r = Cleanup(); r.deliver(E.TYPE_WINDOW_STATE_CHANGED)
        val receipt = requireNotNull(r.deliver(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        assertFalse(r.barrier.ready)
        r.barrier.processed(receipt, true) { CleanupWindowPresence.ABSENT }
        assertTrue(r.barrier.ready); assertTrue(r.failures.isEmpty())
        assertEquals(ManualWindowMask.REMOVED, r.events.last().mask)
    }
    @Test fun addedUnknownAndWrongIdEventsNeverBecomeCleanupSuccess() {
        for (mask in listOf(0, E.WINDOWS_CHANGE_ADDED, 1 shl 29)) {
            val r = Cleanup(); assertNull(r.deliver(E.TYPE_WINDOWS_CHANGED, mask))
            assertFalse(r.barrier.ready); assertEquals(CleanupFailure.RETIRED_ID_EVENT, r.failures.single())
        }
        val r = Cleanup()
        assertNull(r.barrier.observeWindowEvent(99, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        assertEquals(OwnershipRelation.MISMATCH, r.events.single().relation)
        assertTrue(r.barrier.pending); assertFalse(r.barrier.ready)
    }
    @Test fun diagnosticSinkFailureCannotPreventOriginalBarrierFailureOrReleaseIt() {
        val owner = Any(); val native = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true) }
        val failures = mutableListOf<CleanupFailure>()
        val b = OverlayCleanupBarrier(eventDiagnostic = { throw CancellationException("PRIVATE_LOG") },
            failureAttemptDiagnostic = { f, _ -> failures += f })
        native.removalRequested(); b.retiring(null, 1, 20, native, owner); b.removalReturned(true)
        assertNull(b.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ADDED))
        assertFalse(b.ready); assertEquals(CleanupFailure.RETIRED_ID_EVENT, failures.single())
    }
    @Test fun firstFailureAndOriginalDeadlineRemainStickyAfterFurtherEvents() {
        val r = Cleanup(); r.time += 5_000
        r.deliver(E.TYPE_WINDOW_STATE_CHANGED)
        assertEquals(CleanupFailure.DEADLINE, r.failures.first()); assertFalse(r.barrier.ready)
        r.deliver(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED)
        assertFalse(r.barrier.nativeProcessed(r.native, true) { CleanupWindowPresence.ABSENT })
        assertEquals(listOf(CleanupFailure.DEADLINE), r.failures)
    }
    @Test fun cancellationStillRunsRemovalAndPreservesOriginalCancellation() {
        val p = ManualDisplayProbe(); val original = CancellationException("PRIVATE_CANCEL"); var removed = false
        assertSame(original, runCatching {
            reviewManualDisplay({}, { checkedManualDisplay(p) { throw original } },
                { removed = true; error("PRIVATE_SECONDARY") })
        }.exceptionOrNull())
        assertTrue(removed); assertEquals(ManualDisplayDetail.CANCELLED, p.detail)
    }
    @Test fun firstRejectedEventHasReservedReportAfterRoutineCapAndIsNeverRetried() {
        val codes = mutableListOf<ManualCleanupEventCode>()
        val trace = ManualPromptTrace(cleanupEventSink = { _, c -> codes += c; error("PRIVATE_LOG") }, sink = { _, _, _ -> })
        val r = Cleanup(); r.deliver(E.TYPE_WINDOW_STATE_CHANGED); val code = r.events.single()
        for (mask in ManualWindowMask.entries) for (returned in listOf(true, false))
            trace.cleanupEvent(code.copy(mask = mask, returned = returned))
        assertEquals(16, codes.size)
        repeat(5) { trace.cleanupEvent(code.copy(result = ManualCleanupEventResult.REJECTED)) }
        assertEquals(17, codes.size); assertEquals(ManualCleanupEventResult.REJECTED, codes.last().result)
    }
    @Test fun cleanupAndDisplayDiagnosticsStayBoundedAndCannotGrantClickPermission() {
        var calls = 0
        val trace = ManualPromptTrace(cleanupEventSink = { _, _ -> calls++; error("PRIVATE_LOG") }, sink = { _, _, _ -> })
        val r = Cleanup(); r.deliver(E.TYPE_WINDOW_STATE_CHANGED)
        repeat(50) { trace.cleanupEvent(r.events.single()) }; assertEquals(1, calls)
        trace.begin(); trace.cleanupEvent(r.events.single()); assertEquals(2, calls)
        assertFalse(r.barrier.ready) // diagnostics never change admission; not a click port
    }
}
