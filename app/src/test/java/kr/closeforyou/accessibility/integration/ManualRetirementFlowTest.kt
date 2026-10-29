package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateAttacher
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

/** Actual retirement classifier/barrier -> new F0 admission -> real B policy -> F9 coordinator.
 * Native lifecycle callbacks and Android screen handles are fake, not phone verification. */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualRetirementFlowTest {
    private class Rig(private val scope: TestScope) {
        val f = F9AutoCloserTest.Fixture()
        val body = f.node.copy(path = "0/0", className = "android.widget.TextView", text = "적립금 쿠폰 도착",
            clickable = false, actions = persistentSetOf())
        val close = f.node.copy(path = "0/1")
        val original = f.snapshot.copy(nodes = persistentListOf(body, close))
        val detection = PopupDetection(original.token, persistentSetOf(PopupSignal.NEW_WINDOW))
        val analysis = DecisionCandidateAttacher().attach(PopupEvent.Candidate(original, detection),
            PopupAnalysis(original.token, texts = F9CollectedSource.texts(original)))
        val choice = UserChoice(original.token, ChoiceKind.CLOSE_ONCE, analysis.closeCandidates.single().id, buttonMeaningConfirmed = true)
        val owner = Any()
        val native = OverlayNativeWindowLifetime(1, owner).also { it.attached(30); it.addReturned(true) }
        fun now(): Long = (1_000L + scope.testScheduler.currentTime).also { f.now = it }
        val lifetime = ManualPromptLifetime(::now).also { it.shown(original.token, OverlayWindowState(OverlayWindowStatus.SHOWING, 1)) }
        var selected = false
        var hidden = false
        var healthy = true
        var consent = true
        var epoch = 1L
        var presence = CleanupWindowPresence.ABSENT
        var readCount = 0
        var changedTarget = false
        val failures = mutableListOf<CleanupFailure>()
        val order = mutableListOf<String>()
        val barrier = OverlayCleanupBarrier(clock = ::now, onUncertain = { healthy = false }, failureDiagnostic = { failures += it })
        val raw = F9CollectedSource(::now)
        val session = F9ServiceSession(raw, f.port, F9Clock(::now), {
            F9SessionAccess(true, consent, true, false, false,
                kr.closeforyou.ui.voice.settings.AutoCloseConsent.NONE, true)
        }, { barrier.ready }, {}, dispatcher = StandardTestDispatcher(scope.testScheduler))
        init { raw.publish(original, detection) }
        fun select(returned: Boolean = true) {
            native.removalRequested(); barrier.retiring(null, 1, 20, native, owner)
            native.removalReturned(returned); barrier.removalReturned(returned)
            hidden = returned; selected = true
        }
        fun proof(type: Int, changes: Int = 0, ownPackage: Boolean = true, missingPackage: Boolean = false,
            eventTime: Long = now()): ManualRetirementCode = inspectManualRetirementEvent(type, changes,
            { selected && healthy }, { hidden }, {
                retirementPackageConfirmed(type, ownPackage, missingPackage) {
                    native.removalComplete(owner, 1, 30) && presence == CleanupWindowPresence.ABSENT
                }
            }, { lifetime.retirementEventWithinLease(1, eventTime) },
            { !native.identityUnavailable && native.registrationConfirmed(owner, 1) && native.retirementWindow(owner, 1) == 30 },
            { barrier.pending || (barrier.ready && native.removalComplete(owner, 1, 30) && presence == CleanupWindowPresence.ABSENT) })
        fun deliver(type: Int, changes: Int = 0, ownPackage: Boolean = true): OverlayCleanupBarrier.Receipt? {
            val compatible = proof(type, changes, ownPackage).check == ManualRetirementCheck.COMPATIBLE
            val receipt = barrier.observeWindowEvent(30, type, changes, compatible)
            // Same driver order: preserve only explicit intent, still retire all execution tokens.
            session.invalidate(++epoch, permissionOrInteraction = !compatible)
            applyManualInvalidation(ManualPromptCause.CLICK_INVALIDATED, lifetime.current() != null,
                healthy, true, compatible, { selected = false }, { error("ALREADY_HIDDEN") })
            return receipt
        }
        fun detachAndConfirm(): Boolean {
            native.detached()
            return barrier.nativeProcessed(native, hidden) { presence }
        }
        suspend fun closeSelected(): ActionResult? {
            val fresh = ManualChoiceRefresh.afterRemoval(original, analysis, choice, detection, ::now,
                { selected && healthy && consent && barrier.ready }, awaitRemoval = {
                    barrier.awaitObservationPermit(); order += "REMOVAL_CONFIRMED"
                }, awaitQuiet = {}, read = { checkpoint ->
                    checkpoint(); assertTrue(native.removalComplete(owner, 1, 30)); assertTrue(barrier.ready)
                    order += "NEW_F0"; readCount++
                    val token = original.token.copy(screenVersion = ++epoch, captureEpoch = epoch)
                    session.invalidate(epoch, false)
                    original.copy(token = token, capturedAtUptimeMillis = now(),
                        nodes = if (changedTarget) persistentListOf(body, close.copy(text = "구매")) else original.nodes)
                }) ?: return null
            f.scene = f.before.copy(nodes = fresh.snapshot.nodes, windows = fresh.snapshot.windows,
                visiblePaths = fresh.snapshot.nodes.mapTo(mutableSetOf()) { it.path })
            order += "B_F9"
            return session.executeUser(fresh.snapshot, fresh.analysis, fresh.choice, fresh.detection) {
                selected && healthy && consent && barrier.ready && epoch == fresh.snapshot.token.screenVersion
            }
        }
    }
    @Test fun clickAfterRemovalReturnWaitsForActualDetachThenNewF0AndRealBF9CloseOnce() = runTest {
        val r = Rig(this); advanceTimeBy(6_000); r.select()
        val result = async { r.closeSelected() }; runCurrent()
        assertNull(r.deliver(E.TYPE_VIEW_CLICKED)); runCurrent()
        assertTrue(r.barrier.pending); assertNull(r.barrier.permit()); assertTrue(r.failures.isEmpty())
        assertFalse(result.isCompleted); assertEquals(0, r.readCount); assertEquals(0, r.f.clicks); assertNull(r.raw.currentToken())
        assertTrue(r.detachAndConfirm()); val actual = result.await()
        assertTrue(actual!!.attempted); assertEquals(ClickOutcome.CLOSED_CONFIRMED, actual.outcome); assertEquals(1, r.f.clicks)
        assertEquals(listOf("REMOVAL_CONFIRMED", "NEW_F0", "B_F9"), r.order)
        assertEquals(1, r.readCount)
        assertFalse(r.closeSelected()!!.attempted); assertEquals(1, r.f.clicks) // actual B/F9 duplicate protection
    }
    @Test fun absentWindowListWithoutNativeDetachNeverStartsF0OrClicks() = runTest {
        val r = Rig(this); r.select(); val result = async { r.closeSelected() }; runCurrent()
        r.deliver(E.TYPE_VIEW_CLICKED)
        assertFalse(r.barrier.nativeProcessed(r.native, true) { CleanupWindowPresence.ABSENT })
        assertEquals(0, r.readCount); assertFalse(result.isCompleted)
        advanceTimeBy(5_000); runCurrent()
        assertNull(result.await()) // outer ManualChoiceRefresh deadline may win the timer ordering
        assertFalse(r.detachAndConfirm()) // late native proof cannot revive expired cleanup
        assertEquals(listOf(CleanupFailure.DEADLINE), r.failures); assertEquals(0, r.f.clicks)
    }
    @Test fun nativeDetachWithWindowStillPresentWaitsForRealAbsenceAndNeverClicksEarly() = runTest {
        val r = Rig(this); r.select(); r.presence = CleanupWindowPresence.PRESENT
        val result = async { r.closeSelected() }; runCurrent(); r.deliver(E.TYPE_VIEW_CLICKED)
        assertFalse(r.detachAndConfirm()); assertEquals(0, r.readCount); assertNull(r.barrier.permit())
        r.presence = CleanupWindowPresence.ABSENT
        val receipt = requireNotNull(r.barrier.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        r.barrier.processed(receipt, true) { r.presence }
        assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.await()!!.outcome)
    }
    @Test fun compatibleFocusContentAndWindowNotificationsAreNotRemovalReceipts() = runTest {
        val events = listOf(E.TYPE_VIEW_CLICKED to 0, E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED to 0,
            E.TYPE_VIEW_FOCUSED to 0, E.TYPE_WINDOW_CONTENT_CHANGED to 0, E.TYPE_WINDOW_STATE_CHANGED to 0,
            E.TYPE_WINDOWS_CHANGED to E.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED)
        for ((type, mask) in events) {
            val r = Rig(this); r.select()
            assertEquals(ManualRetirementCheck.COMPATIBLE, r.proof(type, mask).check)
            assertNull(r.deliver(type, mask)); assertTrue(r.barrier.pending); assertNull(r.barrier.permit())
            assertEquals(0, r.readCount); assertEquals(0, r.f.clicks); assertTrue(r.failures.isEmpty())
        }
    }
    @Test fun foreignOrMissingClickPackageCannotQuarantineAnEvent() = runTest {
        val r = Rig(this); r.select()
        assertEquals(ManualRetirementCheck.PACKAGE, r.proof(E.TYPE_VIEW_CLICKED, ownPackage = false).check)
        assertEquals(ManualRetirementCheck.PACKAGE, r.proof(E.TYPE_VIEW_CLICKED, ownPackage = false, missingPackage = true).check)
        r.deliver(E.TYPE_VIEW_CLICKED, ownPackage = false)
        assertEquals(listOf(CleanupFailure.RETIRED_ID_EVENT), r.failures); assertFalse(r.barrier.ready)
    }
    @Test fun packageLessWindowNotificationNeedsActualNativeCompletionAndAbsence() = runTest {
        val r = Rig(this); r.select()
        fun code() = r.proof(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED,
            ownPackage = false, missingPackage = true).check
        assertEquals(ManualRetirementCheck.PACKAGE, code())
        r.native.detached(); r.presence = CleanupWindowPresence.PRESENT
        assertEquals(ManualRetirementCheck.PACKAGE, code())
        r.presence = CleanupWindowPresence.ABSENT; assertEquals(ManualRetirementCheck.COMPATIBLE, code())
        assertNull(r.barrier.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED, true))
        assertTrue(r.barrier.pending); assertNull(r.barrier.permit()) // metadata alone still never confirms removal
    }
    @Test fun oldFutureExpiredAndWrongGenerationEventsAreNotCompatible() = runTest {
        val r = Rig(this); r.select()
        for (time in listOf(0L, r.now() + 1)) assertEquals(ManualRetirementCheck.TIME, r.proof(E.TYPE_VIEW_CLICKED, eventTime = time).check)
        assertFalse(r.lifetime.retirementEventWithinLease(2, r.now()))
        advanceTimeBy(30_000)
        assertEquals(ManualRetirementCheck.TIME, r.proof(E.TYPE_VIEW_CLICKED).check)
    }
    @Test fun addedMixedRemovedUnknownTouchAndScrollAreNeverTransientCompatibility() = runTest {
        for (mask in listOf(0, E.WINDOWS_CHANGE_ADDED, E.WINDOWS_CHANGE_REMOVED,
            E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_FOCUSED, E.WINDOWS_CHANGE_PARENT))
            assertFalse(nativeRetirementTransientEvent(E.TYPE_WINDOWS_CHANGED, mask))
        for (type in listOf(E.TYPE_TOUCH_INTERACTION_START, E.TYPE_TOUCH_INTERACTION_END,
            E.TYPE_TOUCH_EXPLORATION_GESTURE_END, E.TYPE_VIEW_SCROLLED, E.TYPE_VIEW_TEXT_CHANGED))
            assertFalse(nativeRetirementTransientEvent(type, 0))
    }
    @Test fun nativeFlagCannotWeakenLegacyMissingOwnerOrUnreturnedRemoval() = runTest {
        val legacy = OverlayCleanupBarrier(); legacy.retiring(30, 1, 20); legacy.removalReturned(true)
        legacy.observeWindowEvent(30, E.TYPE_VIEW_CLICKED, 0, true)
        assertNull(legacy.permit()); assertFalse(legacy.pending)
        val r = Rig(this); r.select(returned = false)
        assertNotEquals(ManualRetirementCheck.COMPATIBLE, r.proof(E.TYPE_VIEW_CLICKED).check)
        assertFalse(r.barrier.ready); assertEquals(0, r.readCount)
    }
    @Test fun repeatedCompatibleEventsCannotRenewTheOriginalCleanupDeadline() = runTest {
        val r = Rig(this); r.select()
        repeat(5) { r.deliver(E.TYPE_VIEW_CLICKED); advanceTimeBy(1_000) }
        r.deliver(E.TYPE_VIEW_CLICKED)
        assertEquals(listOf(CleanupFailure.DEADLINE), r.failures); assertFalse(r.barrier.ready)
    }
    @Test fun aQueuedTransientAfterReceiptStillNeedsThisNativeDetachNotTheOldReceipt() = runTest {
        val r = Rig(this); r.select()
        val receipt = requireNotNull(r.barrier.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        r.deliver(E.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
        r.barrier.processed(receipt, true) { CleanupWindowPresence.ABSENT }
        assertTrue(r.barrier.pending); assertNull(r.barrier.permit())
        assertTrue(r.detachAndConfirm())
    }
    @Test fun duplicateRemovalMixedMasksAndReusedIdStillFailClosed() = runTest {
        for (mode in listOf("DUPLICATE", "MIXED", "REUSED")) {
            val r = Rig(this); r.select(); r.deliver(E.TYPE_VIEW_CLICKED)
            if (mode == "MIXED") r.deliver(E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED or E.WINDOWS_CHANGE_FOCUSED)
            else {
                val receipt = requireNotNull(r.barrier.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
                if (mode == "DUPLICATE") r.barrier.observeWindowEvent(30, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED)
                else { r.native.detached(); r.barrier.processed(receipt, true) { CleanupWindowPresence.PRESENT } }
            }
            assertFalse(r.barrier.ready); assertEquals(0, r.readCount); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun presentUnknownHiddenMismatchAndQueryFailureCannotReleaseOrStartF0() = runTest {
        for (mode in listOf("PRESENT", "UNKNOWN", "HIDDEN", "THROW")) {
            val r = Rig(this); r.select(); r.deliver(E.TYPE_VIEW_CLICKED); r.native.detached()
            assertFalse(r.barrier.nativeProcessed(r.native, mode != "HIDDEN") {
                when (mode) { "PRESENT" -> CleanupWindowPresence.PRESENT; "UNKNOWN" -> CleanupWindowPresence.UNKNOWN
                    "THROW" -> error("FIXED_QUERY_FAILURE"); else -> CleanupWindowPresence.ABSENT }
            })
            assertNull(r.barrier.permit()); assertEquals(0, r.readCount); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun otherGenerationNativeCannotReleaseEvenAfterCompatibleEvent() = runTest {
        val r = Rig(this); r.select(); r.deliver(E.TYPE_VIEW_CLICKED)
        val other = OverlayNativeWindowLifetime(2, r.owner).also { it.attached(30); it.addReturned(true); it.removalRequested(); it.removalReturned(true); it.detached() }
        assertFalse(r.barrier.nativeProcessed(other, true) { error("WRONG_VIEW_MUST_NOT_QUERY") })
        assertTrue(r.barrier.pending); assertEquals(0, r.readCount)
    }
    @Test fun lateCompatibleNotificationAfterConfirmationRequiresFreshAbsenceAgain() = runTest {
        val r = Rig(this); r.select(); assertTrue(r.detachAndConfirm())
        assertEquals(ManualRetirementCheck.COMPATIBLE, r.proof(E.TYPE_VIEW_CLICKED).check)
        r.presence = CleanupWindowPresence.PRESENT
        assertEquals(ManualRetirementCheck.ABSENCE, r.proof(E.TYPE_VIEW_CLICKED).check)
        r.presence = CleanupWindowPresence.UNKNOWN
        assertEquals(ManualRetirementCheck.ABSENCE, r.proof(E.TYPE_VIEW_CLICKED).check)
    }
    @Test fun changedPopupOrDangerousNewTargetAfterRemovalDoesNotClick() = runTest {
        val r = Rig(this); r.select(); r.changedTarget = true
        val result = async { r.closeSelected() }; runCurrent(); r.deliver(E.TYPE_VIEW_CLICKED); r.detachAndConfirm()
        assertNull(result.await()); assertEquals(1, r.readCount); assertEquals(0, r.f.clicks)
    }
    @Test fun consentRevocationOrInvalidatedSelectionWhileWaitingNeverStartsF0() = runTest {
        for (revokeConsent in listOf(true, false)) {
            val r = Rig(this); r.select(); val result = async { r.closeSelected() }; runCurrent()
            r.deliver(E.TYPE_VIEW_CLICKED)
            if (revokeConsent) r.consent = false else r.selected = false
            r.detachAndConfirm()
            assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
            assertEquals(0, r.readCount); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun stoppingOneAwaiterNeverCompletesCleanupOrClicksAndRealLaterProofIsIndependent() = runTest {
        val r = Rig(this); r.select(); val waiter = launch { r.closeSelected() }; runCurrent()
        r.deliver(E.TYPE_VIEW_CLICKED); waiter.cancelAndJoin()
        assertTrue(r.barrier.pending); assertEquals(0, r.f.clicks)
        assertTrue(r.detachAndConfirm()); assertEquals(0, r.readCount)
    }
    @Test fun compatibleCodesAreBoundedFixedEnumsAndDiagnosticFailureCannotAcknowledgeRemoval() = runTest {
        var calls = 0
        val trace = ManualPromptTrace(retirementSink = { _, code ->
            assertEquals(ManualEventKind.CLICK, code.kind); calls++; error("FIXED_LOG_FAILURE")
        }, sink = { _, _, _ -> })
        val r = Rig(this); r.select()
        repeat(5) { trace.retirement(r.proof(E.TYPE_VIEW_CLICKED)) }
        assertEquals(1, calls); assertTrue(r.barrier.pending); assertNull(r.barrier.permit())
    }
    @Test fun metadataFailureIsAFixedRejectionWhileCancellationPropagatesUnchanged() {
        fun check(throws: () -> Boolean) = inspectManualRetirementEvent(E.TYPE_VIEW_CLICKED, 0,
            { true }, { true }, throws, { true }, { true }, { true })
        assertEquals(ManualRetirementCheck.READ_FAILED, check { error("PRIVATE_TEST_FAILURE") }.check)
        val cancellation = CancellationException("FIXED_CANCEL")
        assertSame(cancellation, runCatching { check { throw cancellation } }.exceptionOrNull())
    }
}
