package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as Event
import kotlinx.coroutines.CancellationException
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.overlay.OverlayWindowState
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import org.junit.Assert.*
import org.junit.Test

class OverlayEventOriginDiagnosticTest {
    private class Driver(enabled: Boolean = true, failingSink: Boolean = false) {
        var now = 100L
        val points = mutableListOf<OverlayCleanupPoint>()
        val rows = mutableListOf<List<Enum<*>>>()
        val calls = mutableListOf<String>()
        val origin = if (enabled) OverlayEventOriginDiagnostic({ now }) {
            points += it
            if (failingSink) throw CancellationException("SYNTHETIC_SECRET")
        } else null
        val trace = if (enabled) OverlayEventDiagnostic(origin) { event, reason, detail, window, change, owned, relation, time ->
            rows += listOf(event, reason, detail, window, change, owned, relation, time)
            if (failingSink) throw IllegalStateException("SYNTHETIC_SECRET")
        } else null
        val binding = OwnedOverlayBinding()
        val barrier = OverlayCleanupBarrier(clock = { now }, diagnostic = origin)
        var token = MockPopupFixtures.notice().snapshot.token.copy(windowId = 10)
        var generation = 1L
        var eventId = 20
        var live: Set<Int>? = setOf(20)
        var type = Event.TYPE_WINDOW_STATE_CHANGED
        var mask = Event.WINDOWS_CHANGE_REMOVED
        var eventTime = 120L
        var timeFails = false
        var classification = OverlayEventWindowClass.NOT_IN_LIST
        var invalidations = 0

        init { show(20) }
        fun show(id: Int, before: Set<Int>? = emptySet()) {
            origin?.beforeShow(); now += 10
            origin?.shown(generation, token.windowId, before)
            binding.expect(before, token, generation); trace?.arm()
            eventId = id; live = setOf(id)
        }
        fun inspect(): Boolean = inspectOwnedOverlayEvent(
            OverlayWindowState(OverlayWindowStatus.SHOWING, generation),
            { calls += "TYPE"; type }, { calls += "MASK"; mask }, { calls += "PACKAGE"; "SYNTHETIC_HOST" },
            { calls += "CURRENT"; token },
            { current, observation ->
                calls += "WINDOW"; val id = eventId
                calls += "LIVE"; val owned = live
                binding.matches(id, owned, current, generation, observation)
            }, { calls += "OWN_PACKAGE"; "SYNTHETIC_HOST" }, trace,
            { calls += "CLASSIFICATION"; classification },
            { calls += "TIME"; if (timeFails) error("SYNTHETIC_SECRET"); eventTime },
        ).also { if (!it) invalidations++ }
        fun bind() { assertTrue(inspect()); calls.clear() }
        fun retireAndAck() {
            barrier.retiring(binding.boundWindowFor(generation), generation, token.windowId)
            trace?.disarm(); binding.clear()
            barrier.removalReturned(true)
            val receipt = requireNotNull(barrier.observe(eventId, true, true))
            barrier.processed(receipt, true) { CleanupWindowPresence.ABSENT }
            assertTrue(barrier.ready)
        }
        fun successor(id: Int = 30, before: Set<Int>? = emptySet()) {
            generation++; token = token.copy(screenVersion = token.screenVersion + 1)
            now = 200; show(id, before)
        }
        fun relation() = rows.single()[6]
        fun time() = rows.single()[7]
    }

    @Test fun acknowledgedPreviousIdMissingFromListIsDiagnosedButStillInvalidates() {
        val d = Driver(); d.bind(); d.retireAndAck(); d.successor(); d.bind()
        d.eventId = 20; d.eventTime = 150; d.now = 250
        assertFalse(d.inspect())
        assertEquals(OverlayBindingDetail.BOUND_EVENT_WINDOW_MISMATCH, d.rows.single()[2])
        assertEquals(OverlayEventWindowClass.NOT_IN_LIST, d.rows.single()[3])
        assertEquals(OverlayEventIdRelation.PREVIOUS_C_ACKED_ID_MATCH, d.relation())
        assertEquals(OverlayEventTimeRelation.BEFORE_CURRENT_SHOW, d.time())
        assertEquals(1, d.invalidations)
        assertNull(d.barrier.observe(20, false, false)) // No historical-event exemption after ACK.
        assertEquals(listOf(OverlayCleanupPoint.C_SHOWN, OverlayCleanupPoint.C_BIND_CONFIRMED,
            OverlayCleanupPoint.C_RETIRE_INTENT, OverlayCleanupPoint.C_REMOVE_RETURNED,
            OverlayCleanupPoint.C_REMOVE_EVENT_HANDLED, OverlayCleanupPoint.C_CLEANUP_CONFIRMED,
            OverlayCleanupPoint.C_SHOWN, OverlayCleanupPoint.C_BIND_CONFIRMED), d.points)
    }

    @Test fun differentIdIsUnmatchedAndCurrentTargetIsComparedByIdentity() {
        for ((id, expected) in listOf(99 to OverlayEventIdRelation.UNMATCHED,
            10 to OverlayEventIdRelation.CURRENT_TARGET_ID_MATCH)) {
            val d = Driver(); d.bind(); d.retireAndAck(); d.successor(); d.bind()
            d.eventId = id; d.now = 250; d.eventTime = 230
            assertFalse(d.inspect()); assertEquals(expected, d.relation())
            assertEquals(OverlayEventTimeRelation.AFTER_CURRENT_SHOW, d.time())
        }
    }

    @Test fun currentCMatchDoesNotMakeItsDisappearanceSafe() {
        val d = Driver(); d.bind(); d.live = emptySet(); d.now = 150
        assertFalse(d.inspect())
        assertEquals(OverlayEventIdRelation.CURRENT_C_ID_MATCH, d.relation())
        assertEquals(OverlayBindingDetail.BOUND_WINDOW_NOT_LIVE, d.rows.single()[2])
    }

    @Test fun pendingPreviousIdentityIsSeparateFromBindingAndOtherEventFailsClosed() {
        val d = Driver(); d.bind()
        d.barrier.retiring(20, 1, 10); d.binding.clear()
        assertEquals(OverlayEventIdRelation.PREVIOUS_C_PENDING_ID_MATCH, d.origin!!.relation(20, 10, 1))
        assertNull(d.barrier.observe(20, false, false))
        assertFalse(d.barrier.ready)
        assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin.relation(20, 10, 1))
        assertFalse(d.points.contains(OverlayCleanupPoint.C_CLEANUP_CONFIRMED))
    }

    @Test fun notificationOrUnboundIdCannotCreatePredecessorHistory() {
        val d = Driver() // no event has verified/bound this window
        d.barrier.retiring(20, 1, 10)
        assertFalse(d.points.contains(OverlayCleanupPoint.C_RETIRE_INTENT))
        assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin!!.relation(20, 10, 1))
        val absent = Driver(); absent.barrier.retiring(null, 1, 10)
        assertFalse(absent.barrier.ready)
        assertEquals(OverlayEventIdRelation.UNKNOWN, absent.origin!!.relation(20, 10, 1))
    }

    @Test fun unexpectedSuccessorDuringPendingCleanupDoesNotEraseThePendingIdentity() {
        val d = Driver(); d.bind(); d.barrier.retiring(20, 1, 10)
        d.successor(); d.bind() // diagnostic-only injection; real present() forbids this ordering
        d.eventId = 20
        assertFalse(d.inspect())
        assertEquals(OverlayEventIdRelation.PREVIOUS_C_PENDING_ID_MATCH, d.relation())
        assertFalse(d.barrier.ready) // observing diagnostics never opens the actual barrier
    }

    @Test fun reusedCurrentCIdTargetCollisionOrPreviouslyLiveIdIsAmbiguous() {
        for (scenario in 0..2) {
            val d = Driver(); d.bind(); d.retireAndAck()
            if (scenario == 1) d.token = d.token.copy(windowId = 20)
            d.successor(if (scenario == 0) 20 else 30, if (scenario == 2) setOf(20) else emptySet())
            if (scenario != 2) d.bind()
            d.eventId = 99
            assertFalse(d.inspect())
            assertEquals(OverlayEventIdRelation.AMBIGUOUS_OR_REUSED, d.relation())
        }
    }

    @Test fun ackedIdFoundInExistingDiagnosticListIsAmbiguousOrUnknownRatherThanLateOrigin() {
        for (classification in OverlayEventWindowClass.entries) {
            val d = Driver(); d.bind(); d.retireAndAck(); d.successor(); d.bind()
            d.eventId = 20; d.classification = classification
            assertFalse(d.inspect())
            assertEquals(when (classification) {
                OverlayEventWindowClass.NOT_IN_LIST -> OverlayEventIdRelation.PREVIOUS_C_ACKED_ID_MATCH
                OverlayEventWindowClass.UNKNOWN -> OverlayEventIdRelation.UNKNOWN
                else -> OverlayEventIdRelation.AMBIGUOUS_OR_REUSED
            }, d.relation())
        }
    }

    @Test fun generationMismatchUnknownTargetAndInvalidEventNeverClaimIdentity() {
        val d = Driver(); d.bind()
        for (generation in listOf(0L, 2L))
            assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin!!.relation(20, 10, generation))
        assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin!!.relation(-1, 10, 1))
        assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin.relation(20, null, 1))
        assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin.relation(20, 11, 1))
    }

    @Test fun firstRejectedEventAloneReadsTimeAndWritesRelationUntilNewPresentation() {
        val d = Driver(); d.bind(); d.eventId = 99; d.now = 150
        repeat(3) { assertFalse(d.inspect()) }
        assertEquals(1, d.rows.size); assertEquals(1, d.calls.count { it == "TIME" })
        assertEquals(1, d.calls.count { it == "CLASSIFICATION" })
        d.trace!!.disarm(); assertFalse(d.inspect()); assertEquals(1, d.rows.size)
        d.trace.arm(); assertFalse(d.inspect()); assertEquals(2, d.rows.size)
    }

    @Test fun earlyMaskRejectionDoesNotReadWindowOrTokenToInventRelation() {
        val d = Driver(); d.type = Event.TYPE_WINDOWS_CHANGED
        assertFalse(d.inspect()); assertEquals(OverlayEventIdRelation.UNKNOWN, d.relation())
        assertEquals(listOf("TYPE", "MASK", "MASK", "TIME", "CLASSIFICATION"), d.calls)
    }

    @Test fun sourceTimeInsideShowIntervalFutureZeroClockFailureAndGetterFailureAreUnknown() {
        for (time in listOf(0L, 100L, 105L, 110L, 151L)) {
            val d = Driver(); d.bind(); d.eventId = 99; d.now = 150; d.eventTime = time
            assertFalse(d.inspect()); assertEquals(OverlayEventTimeRelation.UNKNOWN, d.time())
        }
        val d = Driver(); d.bind(); d.eventId = 99; d.timeFails = true
        assertFalse(d.inspect()); assertEquals(OverlayEventTimeRelation.UNKNOWN, d.time())
        val clockFailure = OverlayEventOriginDiagnostic({ error("SYNTHETIC_SECRET") }) {}
        clockFailure.beforeShow(); clockFailure.shown(1, 10, emptySet())
        assertEquals(OverlayEventTimeRelation.UNKNOWN, clockFailure.timeRelation(1))
        val backward = Driver(); backward.bind(); backward.now = 90
        assertEquals(OverlayEventTimeRelation.UNKNOWN, backward.origin!!.timeRelation(80))
    }

    @Test fun closeReconnectAndUncertainFailureDeletePreviousHistory() {
        val d = Driver(); d.bind(); d.retireAndAck(); d.barrier.close()
        assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin!!.relation(20, 10, 1))
        d.origin.beforeShow(); d.origin.shown(2, 10, emptySet()) // cannot resurrect a closed observer
        assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin.relation(20, 10, 2))
        val reconnected = Driver(); reconnected.show(30); reconnected.bind(); reconnected.eventId = 20
        assertFalse(reconnected.inspect()); assertEquals(OverlayEventIdRelation.UNMATCHED, reconnected.relation())
        for (presence in listOf(CleanupWindowPresence.PRESENT, CleanupWindowPresence.UNKNOWN)) {
            val uncertain = Driver(); uncertain.bind(); uncertain.barrier.retiring(20, 1, 10)
            uncertain.barrier.removalReturned(true)
            val receipt = requireNotNull(uncertain.barrier.observe(20, true, true))
            uncertain.barrier.processed(receipt, true) { presence }
            assertFalse(uncertain.barrier.ready)
            assertEquals(OverlayEventIdRelation.UNKNOWN, uncertain.origin!!.relation(20, 10, 1))
        }
    }

    @Test fun predecessorDoesNotBecomeAnUnboundedHistoryAcrossPresentations() {
        val d = Driver(); d.bind(); d.retireAndAck(); d.successor()
        assertEquals(OverlayEventIdRelation.PREVIOUS_C_ACKED_ID_MATCH, d.origin!!.relation(20, 10, 2))
        d.generation = 3; d.now = 300; d.show(40)
        assertEquals(OverlayEventIdRelation.UNMATCHED, d.origin.relation(20, 10, 3))
    }

    @Test fun debugNullAndThrowingSinksHaveIdenticalPolicyReadsDecisionsAndInvalidations() {
        for (failure in listOf(false, true)) for (scenario in 0..5) {
            val debug = Driver(failingSink = failure); val release = Driver(enabled = false)
            debug.bind(); release.bind()
            debug.retireAndAck(); release.retireAndAck()
            debug.successor(); release.successor(); debug.bind(); release.bind()
            for (d in listOf(debug, release)) when (scenario) {
                0 -> d.eventId = 20
                1 -> d.eventId = 10
                2 -> d.live = null
                3 -> d.live = emptySet()
                4 -> { d.type = Event.TYPE_WINDOWS_CHANGED; d.mask = Event.WINDOWS_CHANGE_REMOVED }
                5 -> d.generation++
            }
            repeat(3) {
                assertEquals(debug.inspect(), release.inspect())
                assertEquals(debug.calls.filterNot { it == "TIME" || it == "CLASSIFICATION" }, release.calls)
            }
            assertEquals(debug.invalidations, release.invalidations)
            assertEquals(debug.barrier.ready, release.barrier.ready)
            assertEquals(1, debug.rows.size); assertTrue(release.rows.isEmpty())
            assertTrue(debug.points.all { it.name.startsWith("C_") })
        }
    }

    @Test fun reentrantDiagnosticCannotReplaceFrozenIdentityOrReadTimingTwice() {
        val d = Driver(); d.bind(); d.eventId = 99
        d.trace!!.begin(OverlayEventCategory.WINDOW_STATE_CHANGED)
        d.trace.decisionContext(20, 10, 1)
        d.trace.bindingRejected(OverlayBindingRejection.BINDING_MISMATCH, OverlayBindingDetail.BOUND_WINDOW_NOT_LIVE)
        d.trace.bindingRefused(true)
        var times = 0
        d.trace.completed(false, {
            times++; d.trace.begin(OverlayEventCategory.OTHER)
            d.trace.decisionContext(99, 10, 1)
            d.trace.completed(false, { error("UNEXPECTED_TIME") }) { error("UNEXPECTED_QUERY") }
            50L
        }) { OverlayEventWindowClass.NOT_IN_LIST }
        assertEquals(1, times)
        assertEquals(OverlayEventIdRelation.CURRENT_C_ID_MATCH, d.relation())
        assertEquals(OverlayEventCategory.WINDOW_STATE_CHANGED, d.rows.single()[0])
    }

    @Test fun duplicateOwnedIdsUseExistingReadsAndReleaseAllResourcesWithoutChangingPolicySet() {
        val d = Driver(); val reads = mutableListOf<String>(); var releases = 0
        val result = readOwnedOverlayIds<Int, Int>({ listOf(1, 2) }, { true }, { it }, { _, _ -> true },
            { true }, { reads += "ID"; 20 }, { releases++ }, { releases++ }, { fail("UNEXPECTED_FAILURE") }, d.trace)
        assertEquals(setOf(20), result); assertEquals(2, reads.size); assertEquals(4, releases)
        d.bind(); d.eventId = 99; assertFalse(d.inspect())
        assertEquals(OverlayEventIdRelation.AMBIGUOUS_OR_REUSED, d.relation())
        assertEquals("OverlayEventOriginDiagnostic(redacted)", d.origin.toString())
        assertFalse(d.rows.toString().contains("SYNTHETIC_SECRET"))
    }

    @Test fun ownedQueryFailurePurgesHistoryAndCannotAuthorizeAnyEvent() {
        val d = Driver(); d.bind(); d.retireAndAck(); d.successor(); d.bind()
        var releases = 0
        assertNull(readOwnedOverlayIds<Int, Int>({ listOf(1, 2) }, { true }, { it }, { _, _ -> true },
            { true }, { throw CancellationException("SYNTHETIC_SECRET") }, { releases++ }, { releases++ }, {}, d.trace))
        assertEquals(3, releases) // first root plus both acquired windows
        assertEquals(OverlayEventIdRelation.UNKNOWN, d.origin!!.relation(20, 10, 2))
        d.eventId = 20; assertFalse(d.inspect()); assertEquals(OverlayEventIdRelation.UNKNOWN, d.relation())
    }

    @Test fun releaseFactoryHasNoObserverOrDiagnosticSinkWhenRunAgainstReleaseClasses() {
        if (System.getProperty("issue29.release") == "true") {
            assertNull(ChoiceFlowLog.overlayOrigins())
            assertNull(ChoiceFlowLog.overlayEvents())
            assertNull(ChoiceFlowLog.create())
        }
    }
}
