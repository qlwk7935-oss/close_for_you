package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import org.junit.Assert.*
import org.junit.Test

class OverlayEventDiagnosticTest {
    private class Driver {
        val token = MockPopupFixtures.notice().snapshot.token
        val rows = mutableListOf<Pair<OverlayEventCategory, OverlayEventRejection>>()
        val calls = mutableListOf<String>()
        val diagnostic = OverlayEventDiagnostic { event, reason -> rows += event to reason }
        val binding = OwnedOverlayBinding()
        var state = OverlayWindowState(OverlayWindowStatus.SHOWING, 1L)
        var type = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        var changes = AccessibilityEvent.WINDOWS_CHANGE_ADDED
        var pkg: String? = "SYNTHETIC_HOST"
        var current: SnapshotToken? = token
        var live: Set<Int>? = setOf(2)
        var window = 2
        var currentFailure: Exception? = null
        var liveFailure: Exception? = null

        init { binding.expect(emptySet(), token, 1L); diagnostic.arm() }
        fun read(observation: OverlayEventDiagnostic? = diagnostic): Boolean = inspectOwnedOverlayEvent(
            state,
            { calls += "TYPE"; type },
            { calls += "CHANGES"; changes },
            { calls += "PACKAGE"; pkg },
            { calls += "CURRENT"; currentFailure?.let { throw it }; current },
            { value, trace ->
                binding.matches(readWindow(), readLive(), value, state.generation, trace)
            },
            { calls += "OWN_PACKAGE"; "SYNTHETIC_HOST" }, observation,
        )
        private fun readWindow(): Int { calls += "WINDOW"; return window }
        private fun readLive(): Set<Int>? { calls += "LIVE"; liveFailure?.let { throw it }; return live }
        fun reason(expected: OverlayEventRejection) { assertEquals(expected, rows.single().second) }
    }

    @Test fun contentUsesTheOriginalGetterOrderAndAcceptedEventsProduceNoLog() {
        val d = Driver(); assertTrue(d.read())
        assertEquals(listOf("TYPE", "PACKAGE", "CURRENT", "WINDOW", "LIVE", "OWN_PACKAGE"), d.calls)
        assertTrue(d.rows.isEmpty())
    }
    @Test fun unsupportedWindowMaskNeverReadsOwnershipOrPackageOrCurrent() {
        val d = Driver(); d.type = AccessibilityEvent.TYPE_WINDOWS_CHANGED
        d.changes = AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED
        assertFalse(d.read()); d.reason(OverlayEventRejection.MASK_UNSUPPORTED)
        assertEquals(OverlayEventCategory.WINDOWS_CHANGED, d.rows.single().first)
        assertEquals(listOf("TYPE", "CHANGES", "CHANGES"), d.calls)
    }
    @Test fun zeroWindowMaskKeepsTheOriginalSingleChangesRead() {
        val d = Driver(); d.type = AccessibilityEvent.TYPE_WINDOWS_CHANGED; d.changes = 0
        assertFalse(d.read()); d.reason(OverlayEventRejection.MASK_UNSUPPORTED)
        assertEquals(listOf("TYPE", "CHANGES"), d.calls)
    }
    @Test fun everyExistingHarmlessWindowBitAndTheirCombinationRemainAllowed() {
        val bits = listOf(AccessibilityEvent.WINDOWS_CHANGE_ADDED, AccessibilityEvent.WINDOWS_CHANGE_BOUNDS,
            AccessibilityEvent.WINDOWS_CHANGE_LAYER, AccessibilityEvent.WINDOWS_CHANGE_TITLE)
        for (mask in bits + bits.reduce(Int::or)) {
            val d = Driver(); d.type = AccessibilityEvent.TYPE_WINDOWS_CHANGED; d.changes = mask
            assertTrue(d.read()); assertTrue(d.rows.isEmpty())
            assertEquals(2, d.calls.count { it == "CHANGES" })
        }
    }
    @Test fun activeFocusedRemovedAndUnknownBitsRemainRejectedWithoutOwnershipReads() {
        for (mask in listOf(AccessibilityEvent.WINDOWS_CHANGE_ACTIVE, AccessibilityEvent.WINDOWS_CHANGE_FOCUSED,
            AccessibilityEvent.WINDOWS_CHANGE_REMOVED, AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED,
            1 shl 28)) {
            val d = Driver(); d.type = AccessibilityEvent.TYPE_WINDOWS_CHANGED; d.changes = mask
            assertFalse(d.read()); d.reason(OverlayEventRejection.MASK_UNSUPPORTED)
            assertFalse(d.calls.contains("LIVE"))
        }
    }
    @Test fun actualFocusEventTypesUseFocusCategoryAndExistingOwnershipChecks() {
        for (type in listOf(AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)) {
            val d = Driver(); d.type = type; d.pkg = null
            assertFalse(d.read()); d.reason(OverlayEventRejection.PACKAGE_UNCONFIRMED)
            assertEquals(OverlayEventCategory.FOCUS, d.rows.single().first)
            assertEquals(listOf("TYPE", "PACKAGE", "CURRENT", "WINDOW", "LIVE", "OWN_PACKAGE"), d.calls)
        }
    }
    @Test fun ownedFocusWithMatchingPackageIsStillIgnored() {
        val d = Driver(); d.type = AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED
        assertTrue(d.read()); assertTrue(d.rows.isEmpty())
    }
    @Test fun interactionAndTextEventsAreNotPromotedToOwnedEvents() {
        for (type in listOf(AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) {
            val d = Driver(); d.type = type
            assertFalse(d.read()); d.reason(OverlayEventRejection.OWNERSHIP_NOT_CHECKED)
            assertEquals(OverlayEventCategory.OTHER, d.rows.single().first)
            assertEquals(listOf("TYPE"), d.calls)
        }
    }
    @Test fun windowStateHasItsOwnFixedCategory() {
        val d = Driver(); d.type = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED; d.pkg = null
        assertFalse(d.read()); d.reason(OverlayEventRejection.PACKAGE_UNCONFIRMED)
        assertEquals(OverlayEventCategory.WINDOW_STATE_CHANGED, d.rows.single().first)
    }
    @Test fun absentBeforeSetIsTheFirstBindingRefusalEvenWhenCurrentIsUnavailable() {
        val d = Driver(); d.binding.clear(); d.current = null
        assertFalse(d.read()); d.reason(OverlayEventRejection.BEFORE_SET_UNAVAILABLE)
        assertTrue(d.calls.contains("LIVE")) // The original arguments were evaluated before matches().
    }
    @Test fun nullCurrentAtTheActualTokenComparisonReportsCurrentUnavailable() {
        val d = Driver(); d.current = null
        assertFalse(d.read()); d.reason(OverlayEventRejection.CURRENT_UNAVAILABLE)
    }
    @Test fun anotherTokenIsBindingMismatchAndDoesNotPretendToBeTtl() {
        val d = Driver(); d.current = d.token.copy(screenVersion = d.token.screenVersion + 1)
        assertFalse(d.read()); d.reason(OverlayEventRejection.BINDING_MISMATCH)
    }
    @Test fun anotherGenerationIsBindingMismatch() {
        val d = Driver(); d.state = d.state.copy(generation = 2)
        assertFalse(d.read()); d.reason(OverlayEventRejection.BINDING_MISMATCH)
    }
    @Test fun unavailableEmptyAmbiguousOrExcessiveLiveSetIsNotConfirmed() {
        for (live in listOf(null, emptySet(), setOf(2, 3), (1..33).toSet())) {
            val d = Driver(); d.live = live
            assertFalse(d.read()); d.reason(OverlayEventRejection.OWNED_WINDOW_UNCONFIRMED)
        }
    }
    @Test fun eventWindowDifferentFromTheOnlyNewOwnedWindowIsBindingMismatch() {
        val d = Driver(); d.window = 9
        assertFalse(d.read()); d.reason(OverlayEventRejection.BINDING_MISMATCH)
    }
    @Test fun aKnownWindowThatDisappearedRemainsRejected() {
        val d = Driver(); assertTrue(d.read()); d.live = emptySet()
        assertFalse(d.read()); d.reason(OverlayEventRejection.BINDING_MISMATCH)
    }
    @Test fun missingPackageExceptionRemainsLimitedToOwnedWindowLayout() {
        val d = Driver(); d.type = AccessibilityEvent.TYPE_WINDOWS_CHANGED; d.pkg = null
        assertTrue(d.read()); assertTrue(d.rows.isEmpty())
        d.type = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        assertFalse(d.read()); d.reason(OverlayEventRejection.PACKAGE_UNCONFIRMED)
    }
    @Test fun mismatchedPackageIsRejectedAfterSuccessfulBinding() {
        val d = Driver(); d.pkg = "SYNTHETIC_OTHER"
        assertFalse(d.read()); d.reason(OverlayEventRejection.PACKAGE_UNCONFIRMED)
    }
    @Test fun firstFailureIsReportedOnceUntilAnotherActualPresentationArmsTheDiagnostic() {
        val d = Driver(); d.type = AccessibilityEvent.TYPE_WINDOWS_CHANGED; d.changes = 0
        repeat(20) { assertFalse(d.read()) }
        assertEquals(1, d.rows.size)
        d.type = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED; d.pkg = null
        assertFalse(d.read()); d.reason(OverlayEventRejection.MASK_UNSUPPORTED)
        d.diagnostic.disarm(); d.diagnostic.arm()
        assertFalse(d.read()); assertEquals(2, d.rows.size)
        assertEquals(OverlayEventRejection.PACKAGE_UNCONFIRMED, d.rows.last().second)
    }
    @Test fun hiddenAndRemoveFailedWindowsDoNotReadEventGettersOrEmitV4() {
        for (status in listOf(OverlayWindowStatus.HIDDEN, OverlayWindowStatus.REMOVE_FAILED)) {
            val d = Driver(); d.state = d.state.copy(status = status)
            assertFalse(d.read()); assertTrue(d.calls.isEmpty()); assertTrue(d.rows.isEmpty())
        }
    }
    @Test fun disarmedDiagnosticCannotEmitEvenIfThePredicateIsCalled() {
        val d = Driver(); d.diagnostic.disarm(); d.pkg = null
        assertFalse(d.read()); assertTrue(d.rows.isEmpty())
    }
    @Test fun failedDiagnosticSinkCannotChangeDecisionOrTriggerRetries() {
        var writes = 0
        val diagnostic = OverlayEventDiagnostic { _, _ -> writes++; throw IllegalStateException("SYNTHETIC_SECRET") }
        diagnostic.arm()
        val d = Driver(); d.pkg = null
        repeat(3) { assertFalse(d.read(diagnostic)) }
        assertEquals(1, writes)
    }
    @Test fun sinkCancellationDoesNotCancelWorkOrCreateAFalseAcceptedDecision() {
        val diagnostic = OverlayEventDiagnostic { _, _ -> throw CancellationException("SYNTHETIC_SECRET") }
        diagnostic.arm()
        val d = Driver(); d.pkg = null
        assertFalse(d.read(diagnostic))
        d.pkg = "SYNTHETIC_HOST"; assertTrue(d.read(diagnostic))
    }
    @Test fun actualReadCancellationIsNotCaughtByDiagnosticsAndFinallyStillRuns() {
        val d = Driver(); val original = CancellationException("SYNTHETIC_SECRET"); d.currentFailure = original
        var cleaned = false
        try { try { d.read(); fail() } finally { cleaned = true } }
        catch (failure: CancellationException) { assertSame(original, failure) }
        assertTrue(cleaned); assertTrue(d.rows.isEmpty()); assertFalse(d.calls.contains("LIVE"))
    }
    @Test fun actualReadExceptionRemainsTheOriginalExceptionEvenWithCleanup() {
        val d = Driver(); val original = IllegalStateException("SYNTHETIC_SECRET"); d.liveFailure = original
        var cleaned = false
        try { try { d.read(); fail() } finally { cleaned = true } }
        catch (failure: IllegalStateException) { assertSame(original, failure) }
        assertTrue(cleaned); assertTrue(d.rows.isEmpty()); assertFalse(d.calls.contains("OWN_PACKAGE"))
    }
    @Test fun opaqueBindingRefusalDoesNotInventAReason() {
        val rows = mutableListOf<OverlayEventRejection>()
        val diagnostic = OverlayEventDiagnostic { _, reason -> rows += reason }; diagnostic.arm()
        val state = OverlayWindowState(OverlayWindowStatus.SHOWING, 1L)
        assertFalse(inspectOwnedOverlayEvent(state, { AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED },
            { error("UNEXPECTED_CHANGES_READ") }, { "SYNTHETIC_HOST" }, { null }, { _, _ -> false },
            { "SYNTHETIC_HOST" }, diagnostic))
        assertEquals(listOf(OverlayEventRejection.UNKNOWN), rows)
    }
    @Test fun debugAndReleaseNullDiagnosticKeepIdenticalDecisionsAndReadCounts() {
        for (type in listOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED, AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            for (mask in listOf(0, AccessibilityEvent.WINDOWS_CHANGE_ADDED, AccessibilityEvent.WINDOWS_CHANGE_ACTIVE)) {
                val a = Driver(); val b = Driver(); a.type = type; b.type = type; a.changes = mask; b.changes = mask
                assertEquals(a.read(), b.read(null)); assertEquals(a.calls, b.calls)
            }
        }
    }
    @Test fun policyMatchesItsOriginalBooleanContractForEveryInputCombination() {
        for (kind in OverlayEventKind.entries) for (bits in 0 until 32) {
            val bound = bits and 1 != 0; val live = bits and 2 != 0; val pkg = bits and 4 != 0
            val missing = bits and 8 != 0; val current = bits and 16 != 0
            val original = bound && live && current && (pkg || (missing && kind == OverlayEventKind.OWN_WINDOW_LAYOUT)) &&
                kind in setOf(OverlayEventKind.CONTENT, OverlayEventKind.WINDOW_STATE, OverlayEventKind.OWN_WINDOW_LAYOUT, OverlayEventKind.FOCUS)
            assertEquals(original, OwnedOverlayEventPolicy.mayIgnore(kind, bound, live, pkg, missing, current))
        }
    }
    @Test fun toStringDoesNotExposeEventOrPayloadOrSink() {
        assertEquals("OverlayEventDiagnostic(redacted)", Driver().diagnostic.toString())
    }
}
