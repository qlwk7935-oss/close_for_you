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
        val details = mutableListOf<OverlayBindingDetail>()
        val classes = mutableListOf<OverlayEventWindowClass>()
        var classificationReads = 0
        var classification = OverlayEventWindowClass.UNKNOWN
        var classificationFailure: Exception? = null
        val calls = mutableListOf<String>()
        val diagnostic = OverlayEventDiagnostic { event, reason, detail, windowClass -> rows += event to reason; details += detail; classes += windowClass }
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
            { classificationReads++; classificationFailure?.let { throw it }; classification },
        )
        private fun readWindow(): Int { calls += "WINDOW"; return window }
        private fun readLive(): Set<Int>? { calls += "LIVE"; liveFailure?.let { throw it }; return live }
        fun reason(expected: OverlayEventRejection) { assertEquals(expected, rows.single().second) }
        fun detail(expected: OverlayBindingDetail) {
            reason(OverlayEventRejection.BINDING_MISMATCH); assertEquals(expected, details.single())
        }
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
        d.detail(OverlayBindingDetail.NONNULL_TOKEN_MISMATCH)
    }
    @Test fun anotherGenerationIsBindingMismatch() {
        val d = Driver(); d.state = d.state.copy(generation = 2)
        assertFalse(d.read()); d.reason(OverlayEventRejection.BINDING_MISMATCH)
        d.detail(OverlayBindingDetail.CONTROL_STATE_MISMATCH)
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
        d.detail(OverlayBindingDetail.NEW_EVENT_WINDOW_MISMATCH)
    }
    @Test fun aKnownWindowThatDisappearedRemainsRejected() {
        val d = Driver(); assertTrue(d.read()); d.live = emptySet()
        assertFalse(d.read()); d.reason(OverlayEventRejection.BINDING_MISMATCH)
        d.detail(OverlayBindingDetail.BOUND_WINDOW_NOT_LIVE)
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
        val diagnostic = OverlayEventDiagnostic { _, _, _, _ -> writes++; throw IllegalStateException("SYNTHETIC_SECRET") }
        diagnostic.arm()
        val d = Driver(); d.pkg = null
        repeat(3) { assertFalse(d.read(diagnostic)) }
        assertEquals(1, writes)
    }
    @Test fun sinkCancellationDoesNotCancelWorkOrCreateAFalseAcceptedDecision() {
        val diagnostic = OverlayEventDiagnostic { _, _, _, _ -> throw CancellationException("SYNTHETIC_SECRET") }
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
        val diagnostic = OverlayEventDiagnostic { _, reason, detail, _ ->
            rows += reason; assertEquals(OverlayBindingDetail.UNKNOWN, detail)
        }; diagnostic.arm()
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
    @Test fun v5BoundEventWindowMismatchDoesNotReadMembershipOrRebind() {
        val d = Driver(); assertTrue(d.read()); d.calls.clear()
        var membershipReads = 0; var iterations = 0
        d.live = object : AbstractSet<Int>() {
            override val size = 1
            override fun iterator(): Iterator<Int> { iterations++; return setOf(2).iterator() }
            override fun contains(element: Int): Boolean { membershipReads++; return element == 2 }
        }
        d.window = 9
        assertFalse(d.read()); d.detail(OverlayBindingDetail.BOUND_EVENT_WINDOW_MISMATCH)
        assertEquals(0, membershipReads); assertEquals(0, iterations)
        assertEquals(listOf("TYPE", "PACKAGE", "CURRENT", "WINDOW", "LIVE", "OWN_PACKAGE"), d.calls)
        d.window = 2; assertTrue(d.read()); assertEquals(1, membershipReads)
        assertEquals(1, d.rows.size)
    }
    @Test fun v5BoundWindowNotLiveReadsMembershipExactlyOnce() {
        val d = Driver(); assertTrue(d.read()); d.calls.clear()
        var membershipReads = 0
        d.live = object : AbstractSet<Int>() {
            override val size = 1
            override fun iterator(): Iterator<Int> = error("UNEXPECTED_ITERATION")
            override fun contains(element: Int): Boolean { membershipReads++; return false }
        }
        assertFalse(d.read()); d.detail(OverlayBindingDetail.BOUND_WINDOW_NOT_LIVE)
        assertEquals(1, membershipReads)
        assertEquals(listOf("TYPE", "PACKAGE", "CURRENT", "WINDOW", "LIVE", "OWN_PACKAGE"), d.calls)
    }
    @Test fun v5ControlStateFailurePrecedesMissingCurrent() {
        val d = Driver(); d.state = d.state.copy(generation = 2); d.current = null
        assertFalse(d.read()); d.detail(OverlayBindingDetail.CONTROL_STATE_MISMATCH)
    }
    @Test fun v5NonBindingReasonsAlwaysHaveUnknownDetail() {
        val cases: List<(Driver) -> Unit> = listOf(
            { it.binding.clear() }, { it.live = null }, { it.pkg = null }, { it.current = null },
            { it.type = AccessibilityEvent.TYPE_WINDOWS_CHANGED; it.changes = 0 },
            { it.type = AccessibilityEvent.TYPE_VIEW_SCROLLED },
        )
        for (configure in cases) {
            val d = Driver(); configure(d); assertFalse(d.read())
            assertNotEquals(OverlayEventRejection.BINDING_MISMATCH, d.rows.single().second)
            assertEquals(OverlayBindingDetail.UNKNOWN, d.details.single())
        }
    }
    @Test fun v5FirstDetailIsNotOverwrittenAndAnotherPresentationCannotInheritIt() {
        val d = Driver(); d.state = d.state.copy(generation = 2)
        assertFalse(d.read()); d.detail(OverlayBindingDetail.CONTROL_STATE_MISMATCH)
        d.state = d.state.copy(generation = 1); d.current = d.token.copy(screenVersion = d.token.screenVersion + 1)
        assertFalse(d.read()); d.detail(OverlayBindingDetail.CONTROL_STATE_MISMATCH)
        d.diagnostic.disarm(); d.diagnostic.arm()
        assertFalse(d.read()); assertEquals(2, d.rows.size)
        assertEquals(OverlayBindingDetail.NONNULL_TOKEN_MISMATCH, d.details.last())
        d.diagnostic.disarm(); d.diagnostic.arm(); d.current = null
        assertFalse(d.read()); assertEquals(OverlayEventRejection.CURRENT_UNAVAILABLE, d.rows.last().second)
        assertEquals(OverlayBindingDetail.UNKNOWN, d.details.last())
    }
    @Test fun v5DebugAndNullPortKeepIdenticalReadsAndDecisionsForEveryDetail() {
        val cases: List<(Driver) -> Unit> = listOf(
            { it.state = it.state.copy(generation = 2) },
            { it.current = it.token.copy(screenVersion = it.token.screenVersion + 1) },
            { assertTrue(it.read(null)); it.window = 9 },
            { assertTrue(it.read(null)); it.live = emptySet() },
            { it.window = 9 },
        )
        for (configure in cases) {
            val a = Driver(); val b = Driver(); configure(a); configure(b); a.calls.clear(); b.calls.clear()
            assertFalse(a.read()); assertFalse(b.read(null)); assertEquals(a.calls, b.calls)
            assertNotEquals(OverlayBindingDetail.UNKNOWN, a.details.single())
        }
    }
    @Test fun v5UnspecifiedBindingFailureRemainsUnknown() {
        val details = mutableListOf<OverlayBindingDetail>()
        val diagnostic = OverlayEventDiagnostic { _, reason, detail, _ ->
            assertEquals(OverlayEventRejection.BINDING_MISMATCH, reason); details += detail
        }; diagnostic.arm()
        assertFalse(inspectOwnedOverlayEvent(OverlayWindowState(OverlayWindowStatus.SHOWING, 1),
            { AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }, { error("UNEXPECTED_CHANGES_READ") },
            { "SYNTHETIC_HOST" }, { null },
            { _, observation -> observation?.bindingRejected(OverlayBindingRejection.BINDING_MISMATCH); false },
            { "SYNTHETIC_HOST" }, diagnostic))
        assertEquals(listOf(OverlayBindingDetail.UNKNOWN), details)
    }
    @Test fun windowClassificationRunsOnlyForTheFirstRejectedEventPerPresentation() {
        val d = Driver()
        assertTrue(d.read()); assertEquals(0, d.classificationReads)
        d.pkg = null; d.classification = OverlayEventWindowClass.SYSTEM
        repeat(3) { assertFalse(d.read()) }
        assertEquals(1, d.classificationReads)
        assertEquals(listOf(OverlayEventWindowClass.SYSTEM), d.classes)
        d.diagnostic.disarm(); assertFalse(d.read()); assertEquals(1, d.classificationReads)
        d.diagnostic.arm(); d.classification = OverlayEventWindowClass.TARGET_APP
        assertFalse(d.read()); assertEquals(2, d.classificationReads)
        assertEquals(OverlayEventWindowClass.TARGET_APP, d.classes.last())
    }
    @Test fun windowClassificationAlsoRunsAfterEarlyMaskAndInteractionRejections() {
        for (type in listOf(AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_VIEW_SCROLLED)) {
            val d = Driver(); d.type = type; d.changes = 0
            d.classification = OverlayEventWindowClass.NOT_IN_LIST
            assertFalse(d.read()); assertEquals(1, d.classificationReads)
            assertEquals(listOf(OverlayEventWindowClass.NOT_IN_LIST), d.classes)
            assertFalse(d.calls.contains("CURRENT")); assertFalse(d.calls.contains("LIVE"))
        }
    }
    @Test fun everyWindowClassAndDiagnosticFailureKeepsDebugAndReleaseDecisionsAndPolicyReadsIdentical() {
        val configurations: List<(Driver) -> Unit> = listOf(
            {}, { it.window = 9 }, { it.pkg = null }, { it.live = null }, { it.current = null },
            { it.state = it.state.copy(generation = 2) },
            { it.type = AccessibilityEvent.TYPE_WINDOWS_CHANGED; it.changes = 0 },
            { it.type = AccessibilityEvent.TYPE_VIEW_SCROLLED },
            { it.diagnostic.disarm() },
            { it.state = it.state.copy(status = OverlayWindowStatus.HIDDEN) },
            { it.state = it.state.copy(status = OverlayWindowStatus.REMOVE_FAILED) },
        )
        for (classification in OverlayEventWindowClass.entries) for (failure in listOf(null,
            IllegalStateException("SYNTHETIC_SECRET"), CancellationException("SYNTHETIC_SECRET"))) {
            for (configure in configurations) {
                val debug = Driver(); val release = Driver(); configure(debug); configure(release)
                debug.classification = classification; debug.classificationFailure = failure
                repeat(3) {
                    assertEquals(debug.read(), release.read(null))
                    // Includes original binding reads; diagnostic reads are counted separately.
                    assertEquals(debug.calls, release.calls)
                }
                assertEquals(0, release.classificationReads)
                assertTrue(debug.classificationReads <= 1)
                if (debug.rows.isNotEmpty()) assertEquals(
                    if (failure == null) classification else OverlayEventWindowClass.UNKNOWN, debug.classes.single())
            }
        }
    }
    @Test fun classificationFailureStillWritesTheOriginalReasonAndDetailOnlyOnce() {
        val d = Driver(); assertTrue(d.read()); d.window = 9
        d.classificationFailure = CancellationException("SYNTHETIC_SECRET")
        repeat(3) { assertFalse(d.read()) }
        d.detail(OverlayBindingDetail.BOUND_EVENT_WINDOW_MISMATCH)
        assertEquals(listOf(OverlayEventWindowClass.UNKNOWN), d.classes)
        assertEquals(1, d.classificationReads)
        d.window = 2; assertTrue(d.read()) // No diagnostic failure quarantine in the real binding.
    }
    @Test fun reentrantClassificationCannotOverwriteTheFirstEventCodesOrReadAnotherWindow() {
        val rows = mutableListOf<List<Enum<*>>>()
        val diagnostic = OverlayEventDiagnostic { event, reason, detail, windowClass ->
            rows += listOf(event, reason, detail, windowClass)
        }
        diagnostic.arm(); diagnostic.begin(OverlayEventCategory.WINDOW_STATE_CHANGED)
        diagnostic.bindingRejected(OverlayBindingRejection.BINDING_MISMATCH,
            OverlayBindingDetail.BOUND_EVENT_WINDOW_MISMATCH)
        diagnostic.bindingRefused(true)
        diagnostic.completed(false) {
            diagnostic.begin(OverlayEventCategory.WINDOWS_CHANGED)
            diagnostic.rejected(OverlayEventRejection.MASK_UNSUPPORTED)
            diagnostic.completed(false) { error("UNEXPECTED_SECOND_CLASSIFICATION") }
            OverlayEventWindowClass.SYSTEM
        }
        assertEquals(listOf(listOf(OverlayEventCategory.WINDOW_STATE_CHANGED, OverlayEventRejection.BINDING_MISMATCH,
            OverlayBindingDetail.BOUND_EVENT_WINDOW_MISMATCH, OverlayEventWindowClass.SYSTEM)), rows)
    }
}
