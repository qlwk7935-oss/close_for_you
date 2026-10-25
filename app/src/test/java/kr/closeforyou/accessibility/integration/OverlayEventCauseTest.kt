package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as Event
import kotlinx.coroutines.CancellationException
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import org.junit.Assert.*
import org.junit.Test

class OverlayEventCauseTest {
    private class Driver {
        val token = MockPopupFixtures.notice().snapshot.token
        val binding = OwnedOverlayBinding()
        val rows = mutableListOf<List<Enum<*>>>()
        val calls = mutableListOf<String>()
        var mask = Event.WINDOWS_CHANGE_LAYER
        var type = Event.TYPE_WINDOWS_CHANGED
        var live: Set<Int>? = emptySet()
        var queryDetail = OverlayOwnedWindowDetail.UNKNOWN
        var windowClass = OverlayEventWindowClass.TARGET_APP
        var classifierFailure: Exception? = null
        var classifierCalls = 0
        var current = token
        val diagnostic = OverlayEventDiagnostic { event, reason, detail, window, change, owned, _, _, _, _, _ ->
            rows += listOf(event, reason, detail, window, change, owned)
        }
        init { binding.expect(emptySet(), token, 1); diagnostic.arm() }
        fun inspect(trace: OverlayEventDiagnostic? = diagnostic): Boolean = inspectOwnedOverlayEvent(
            OverlayWindowState(OverlayWindowStatus.SHOWING, 1),
            { calls += "TYPE"; type }, { calls += "CHANGES"; mask }, { calls += "PACKAGE"; null },
            { calls += "CURRENT"; current },
            { value, observation ->
                calls += "WINDOW"; val window = 2
                calls += "LIVE"
                if (queryDetail != OverlayOwnedWindowDetail.UNKNOWN) observation?.ownedQueryUncertain(queryDetail)
                binding.matches(window, live, value, 1, observation)
            }, { calls += "OWN_PACKAGE"; "SYNTHETIC_HOST" }, trace,
            { classifierCalls++; classifierFailure?.let { throw it }; windowClass })
    }

    @Test fun maskCategoriesCoverEveryCombinationOfKnownBits() {
        val bits = listOf(Event.WINDOWS_CHANGE_ADDED, Event.WINDOWS_CHANGE_REMOVED, Event.WINDOWS_CHANGE_TITLE,
            Event.WINDOWS_CHANGE_BOUNDS, Event.WINDOWS_CHANGE_LAYER, Event.WINDOWS_CHANGE_ACTIVE,
            Event.WINDOWS_CHANGE_FOCUSED, Event.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED,
            Event.WINDOWS_CHANGE_PARENT, Event.WINDOWS_CHANGE_CHILDREN, Event.WINDOWS_CHANGE_PIP)
        for (subset in 0 until (1 shl bits.size)) {
            val selected = bits.filterIndexed { index, _ -> subset and (1 shl index) != 0 }
            val mask = selected.fold(0, Int::or)
            val expected = when {
                Event.WINDOWS_CHANGE_BOUNDS in selected -> OverlayWindowChangeKind.BOUNDS_INCLUDED
                selected == listOf(Event.WINDOWS_CHANGE_LAYER) -> OverlayWindowChangeKind.LAYER_ONLY
                selected == listOf(Event.WINDOWS_CHANGE_ADDED) -> OverlayWindowChangeKind.ADDED_ONLY
                selected.size > 1 -> OverlayWindowChangeKind.MIXED
                else -> OverlayWindowChangeKind.UNKNOWN
            }
            assertEquals(expected, overlayWindowChangeKind(mask))
            assertEquals(OverlayWindowChangeKind.UNKNOWN, overlayWindowChangeKind(mask or (1 shl 28)))
        }
        assertEquals(OverlayWindowChangeKind.UNKNOWN, overlayWindowChangeKind(-1))
    }
    @Test fun onlyFirstRejectedEventIsWrittenAndOnlyWindowsChangedHasAMaskCategory() {
        val d = Driver(); d.live = setOf(2)
        assertTrue(d.inspect()); assertTrue(d.rows.isEmpty()); assertEquals(0, d.classifierCalls)
        d.live = emptySet(); d.mask = Event.WINDOWS_CHANGE_BOUNDS
        repeat(3) { assertFalse(d.inspect()) }
        assertEquals(1, d.rows.size); assertEquals(1, d.classifierCalls)
        assertEquals(OverlayWindowChangeKind.BOUNDS_INCLUDED, d.rows.single()[4])
        d.diagnostic.disarm(); d.diagnostic.arm(); d.type = Event.TYPE_VIEW_SCROLLED
        assertFalse(d.inspect()); assertEquals(OverlayWindowChangeKind.UNKNOWN, d.rows.last()[4])
        d.type = Event.TYPE_WINDOWS_CHANGED
        assertFalse(d.inspect()); assertEquals(2, d.rows.size) // No second log after the first refusal.
    }
    @Test fun unconfirmedDetailsDistinguishQueryNoNewMultipleAndOtherUncertainty() {
        val cases = listOf(
            Triple(null, OverlayOwnedWindowDetail.QUERY_FAILED, OverlayOwnedWindowDetail.QUERY_FAILED),
            Triple(null, OverlayOwnedWindowDetail.UNCERTAIN, OverlayOwnedWindowDetail.UNCERTAIN),
            Triple(null, OverlayOwnedWindowDetail.UNKNOWN, OverlayOwnedWindowDetail.UNCERTAIN),
            Triple(emptySet(), OverlayOwnedWindowDetail.UNKNOWN, OverlayOwnedWindowDetail.NO_NEW_WINDOW),
            Triple(setOf(2, 3), OverlayOwnedWindowDetail.UNKNOWN, OverlayOwnedWindowDetail.MULTIPLE_NEW_WINDOWS),
            Triple((1..33).toSet(), OverlayOwnedWindowDetail.UNKNOWN, OverlayOwnedWindowDetail.UNCERTAIN),
        )
        for ((live, query, expected) in cases) {
            val d = Driver(); d.live = live; d.queryDetail = query
            assertFalse(d.inspect())
            assertEquals(OverlayEventRejection.OWNED_WINDOW_UNCONFIRMED, d.rows.single()[1])
            assertEquals(expected, d.rows.single()[5])
        }
        val d = Driver(); d.binding.expect(setOf(2), d.token, 1); d.live = setOf(2)
        assertFalse(d.inspect()); assertEquals(OverlayOwnedWindowDetail.NO_NEW_WINDOW, d.rows.single()[5])
    }
    @Test fun queryDetailCannotOverrideEarlierTokenFailureOrLeakIntoAnotherEvent() {
        val d = Driver(); d.current = d.token.copy(screenVersion = d.token.screenVersion + 1)
        d.queryDetail = OverlayOwnedWindowDetail.QUERY_FAILED; d.live = null
        assertFalse(d.inspect())
        assertEquals(OverlayEventRejection.BINDING_MISMATCH, d.rows.single()[1])
        assertEquals(OverlayOwnedWindowDetail.UNKNOWN, d.rows.single()[5])
        d.diagnostic.disarm(); d.diagnostic.arm(); d.current = d.token
        d.queryDetail = OverlayOwnedWindowDetail.UNKNOWN; d.live = emptySet()
        assertFalse(d.inspect()); assertEquals(OverlayOwnedWindowDetail.NO_NEW_WINDOW, d.rows.last()[5])
        d.diagnostic.disarm(); d.diagnostic.arm(); d.type = Event.TYPE_WINDOW_STATE_CHANGED
        assertFalse(d.inspect()); assertEquals(OverlayWindowChangeKind.UNKNOWN, d.rows.last()[4])
    }
    @Test fun debugAndReleaseKeepDecisionsAndOriginalReadOrderForMasksAndOwnedSets() {
        val masks = listOf(0, Event.WINDOWS_CHANGE_LAYER, Event.WINDOWS_CHANGE_ADDED,
            Event.WINDOWS_CHANGE_BOUNDS, Event.WINDOWS_CHANGE_ADDED or Event.WINDOWS_CHANGE_TITLE,
            Event.WINDOWS_CHANGE_REMOVED, Event.WINDOWS_CHANGE_BOUNDS or Event.WINDOWS_CHANGE_REMOVED,
            1 shl 28)
        for (mask in masks) for (live in listOf(null, emptySet(), setOf(2), setOf(2, 3))) {
            val debug = Driver(); val release = Driver()
            debug.mask = mask; release.mask = mask; debug.live = live; release.live = live
            debug.queryDetail = OverlayOwnedWindowDetail.QUERY_FAILED
            release.queryDetail = OverlayOwnedWindowDetail.QUERY_FAILED
            repeat(3) {
                assertEquals(debug.inspect(), release.inspect(null)); assertEquals(debug.calls, release.calls)
            }
            val originalChangesReads = if (mask == 0) 1 else 2
            assertEquals(originalChangesReads * 3, debug.calls.count { it == "CHANGES" })
            assertEquals(0, release.classifierCalls)
        }
    }
    @Test fun aSecondChangingGetterDoesNotAddAReadOrChangeTheOriginalShortCircuitDecision() {
        for (trace in listOf(true, false)) {
            val d = Driver(); d.live = setOf(2)
            var reads = 0
            assertTrue(inspectOwnedOverlayEvent(OverlayWindowState(OverlayWindowStatus.SHOWING, 1),
                { Event.TYPE_WINDOWS_CHANGED }, { if (++reads == 1) Event.WINDOWS_CHANGE_LAYER else 0 },
                { null }, { d.token }, { _, _ -> true }, { "SYNTHETIC_HOST" },
                if (trace) d.diagnostic else null))
            assertEquals(2, reads); assertTrue(d.rows.isEmpty())
        }
    }
    @Test fun classifierFailureStillWritesMaskAndOwnedDetailAndNeverRetries() {
        for (failure in listOf(IllegalStateException("SYNTHETIC_SECRET"), CancellationException("SYNTHETIC_SECRET"))) {
            val d = Driver(); d.classifierFailure = failure
            repeat(3) { assertFalse(d.inspect()) }
            assertEquals(1, d.classifierCalls)
            assertEquals(OverlayEventWindowClass.UNKNOWN, d.rows.single()[3])
            assertEquals(OverlayWindowChangeKind.LAYER_ONLY, d.rows.single()[4])
            assertEquals(OverlayOwnedWindowDetail.NO_NEW_WINDOW, d.rows.single()[5])
        }
    }
    @Test fun reentrantReadCannotReplaceFrozenMaskOrOwnedDetail() {
        val rows = mutableListOf<List<Enum<*>>>()
        val diagnostic = OverlayEventDiagnostic { event, reason, detail, window, change, owned, _, _, _, _, _ ->
            rows += listOf(event, reason, detail, window, change, owned)
        }
        diagnostic.arm(); diagnostic.begin(OverlayEventCategory.WINDOWS_CHANGED)
        diagnostic.windowChangesObserved(Event.WINDOWS_CHANGE_LAYER)
        diagnostic.ownedWindowUnconfirmed(OverlayOwnedWindowDetail.MULTIPLE_NEW_WINDOWS)
        diagnostic.bindingRefused(true)
        diagnostic.completed(false) {
            diagnostic.begin(OverlayEventCategory.WINDOWS_CHANGED)
            diagnostic.windowChangesObserved(Event.WINDOWS_CHANGE_BOUNDS)
            diagnostic.ownedQueryUncertain(OverlayOwnedWindowDetail.QUERY_FAILED)
            diagnostic.completed(false) { error("UNEXPECTED_SECOND_READ") }
            OverlayEventWindowClass.TARGET_APP
        }
        assertEquals(OverlayWindowChangeKind.LAYER_ONLY, rows.single()[4])
        assertEquals(OverlayOwnedWindowDetail.MULTIPLE_NEW_WINDOWS, rows.single()[5])
    }
}
