package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.ui.voice.overlay.*
import org.junit.Assert.*
import org.junit.Test

/** Exact service proof/lookup helpers, using C's real immutable native-lifetime model.
 * Android getter lambdas are fake; this does not reproduce LG Binder/cache behavior. */
class ManualOwnershipDiagnosticTest {
    private class Rig {
        var now = 1_000L
        val calls = mutableListOf<String>()
        val owner = Any()
        var native: OverlayNativeWindowLifetime? = OverlayNativeWindowLifetime(2, owner).also {
            it.attached(99); it.addReturned(true)
        }
        val lease = ManualPromptLifetime { calls += "CLOCK"; now }
        var state = OverlayWindowState(OverlayWindowStatus.SHOWING, 2)
        var at = 1_000L
        var pkg: String? = "synthetic.own"
        var event = 99
        var before: Set<Int>? = emptySet()
        var live: Set<Int>? = setOf(99)
        val probe = ManualOwnershipProbe()
        init { lease.shown(F9AutoCloserTest.Fixture().token, state); calls.clear() }
        fun read(diagnostic: ManualOwnershipProbe? = probe): Boolean = inspectManualOwnedInteraction(
            E.TYPE_WINDOW_CONTENT_CHANGED, state, lease,
            { calls += "TIME"; at }, { calls += "PACKAGE"; pkg }, "synthetic.own",
            { calls += "WINDOW"; event },
            { calls += "NATIVE"; readManualNativeWindow(native, owner, state.generation, diagnostic) },
            before, { calls += "LIVE"; live }, diagnostic)
    }
    @Test fun completeProofIsOwnedAndReportsOnlyComparedEnums() {
        val r = Rig(); assertTrue(r.read())
        val c = r.probe.code()
        assertEquals(ManualOwnership.OWNED, c.result); assertEquals(OwnershipCheck.COMPLETE, c.check)
        assertEquals(OwnershipRelation.MATCH, c.window); assertEquals(OwnershipRelation.MATCH, c.pkg)
        assertEquals(OwnershipRelation.MATCH, c.generation); assertEquals(OwnershipRelation.MATCH, c.nativeGeneration)
        assertEquals(OwnershipTime.WITHIN_SHOW, c.time); assertEquals(OwnershipLease.ACTIVE, c.lease)
        assertEquals(OwnershipBefore.NATIVE_ABSENT, c.before); assertEquals(OwnershipLive.SINGLE_MATCH, c.live)
        assertEquals(OwnershipNative.REGISTERED_OBJECT, c.native); assertEquals(OwnershipRelation.MATCH, c.lookup)
        assertFalse(c.toString().contains("synthetic")); assertFalse(c.toString().contains("99"))
    }
    @Test fun otherAndUnknownPackagesRemainRejectedBeforeNativeOrWindowGetters() {
        for (pkg in listOf("synthetic.external", null)) {
            val r = Rig(); r.pkg = pkg; assertFalse(r.read())
            assertEquals(OwnershipCheck.PACKAGE, r.probe.check)
            assertEquals(if (pkg == null) ManualOwnership.UNKNOWN else ManualOwnership.NOT_OWNED, r.probe.result)
            assertFalse("NATIVE" in r.calls); assertFalse("WINDOW" in r.calls); assertFalse("LIVE" in r.calls)
            assertEquals(OwnershipRelation.NOT_CHECKED, r.probe.window)
        }
    }
    @Test fun missingBeforeSetIsUnknownAndCannotPerformANativeLookup() {
        val r = Rig(); r.before = null
        assertFalse(r.read()); assertEquals(OwnershipCheck.BEFORE_SET, r.probe.check)
        assertEquals(OwnershipBefore.UNAVAILABLE, r.probe.before); assertFalse("NATIVE" in r.calls)
    }
    @Test fun absentNativeAndRegisteredButUnattachedNativeAreDistinguishedWithoutInventingAnId() {
        val absent = Rig(); absent.native = null
        assertFalse(absent.read()); assertEquals(OwnershipNative.ABSENT, absent.probe.native)
        val pending = Rig(); pending.native = OverlayNativeWindowLifetime(2, pending.owner)
        assertFalse(pending.read()); assertEquals(OwnershipNative.REGISTERED_OBJECT, pending.probe.native)
        for (r in listOf(absent, pending)) {
            assertEquals(ManualOwnership.UNKNOWN, r.probe.result)
            assertEquals(OwnershipCheck.NATIVE_LOOKUP, r.probe.check)
            assertEquals(OwnershipRelation.UNAVAILABLE, r.probe.lookup); assertFalse("WINDOW" in r.calls)
        }
    }
    @Test fun nativeGenerationMismatchAndLeaseGenerationMismatchHaveSeparateBoundaries() {
        val native = Rig(); native.native = OverlayNativeWindowLifetime(1, native.owner).also {
            it.attached(99); it.addReturned(true)
        }
        assertFalse(native.read()); assertEquals(OwnershipRelation.MISMATCH, native.probe.nativeGeneration)
        assertEquals(OwnershipCheck.NATIVE_LOOKUP, native.probe.check); assertEquals(ManualOwnership.NOT_OWNED, native.probe.result)
        val lease = Rig(); lease.state = lease.state.copy(generation = 3)
        assertFalse(lease.read()); assertEquals(OwnershipRelation.MISMATCH, lease.probe.generation)
        assertEquals(OwnershipCheck.GENERATION, lease.probe.check); assertFalse("NATIVE" in lease.calls)
    }
    @Test fun wrongWindowIsNotOwnedButUnavailableEventWindowRemainsUnknown() {
        for (id in listOf(20, -1)) {
            val r = Rig(); r.event = id; assertFalse(r.read())
            assertEquals(OwnershipCheck.EVENT_WINDOW, r.probe.check)
            assertEquals(if (id < 0) ManualOwnership.UNKNOWN else ManualOwnership.NOT_OWNED, r.probe.result)
            assertEquals(if (id < 0) OwnershipRelation.UNAVAILABLE else OwnershipRelation.MISMATCH, r.probe.window)
            assertFalse("LIVE" in r.calls)
        }
    }
    @Test fun reusedNativeIdInBeforeSetCannotBeAcceptedAndDoesNotReadTheEventWindow() {
        val r = Rig(); r.before = setOf(99)
        assertFalse(r.read()); assertEquals(OwnershipBefore.NATIVE_ALREADY_PRESENT, r.probe.before)
        assertEquals(ManualOwnership.NOT_OWNED, r.probe.result); assertFalse("WINDOW" in r.calls)
    }
    @Test fun liveSetUnavailableEmptyMultipleAndOtherAreDistinctAndNeverOwned() {
        for ((set, expected) in listOf(null to OwnershipLive.UNAVAILABLE, emptySet<Int>() to OwnershipLive.EMPTY,
            setOf(98, 99) to OwnershipLive.MULTIPLE, setOf(98) to OwnershipLive.SINGLE_OTHER)) {
            val r = Rig(); r.live = set
            assertFalse(r.read()); assertEquals(expected, r.probe.live)
            assertEquals(OwnershipCheck.LIVE_SET, r.probe.check)
            assertEquals(if (expected == OwnershipLive.SINGLE_OTHER) ManualOwnership.NOT_OWNED else ManualOwnership.UNKNOWN, r.probe.result)
        }
    }
    @Test fun beforeShowFutureAndExpiredDisplayRemainRejectedWithOriginalClockReadOrder() {
        val before = Rig(); before.at = 999
        assertFalse(before.read()); assertEquals(OwnershipTime.BEFORE_SHOW, before.probe.time)
        assertEquals(1, before.calls.count { it == "CLOCK" })
        val future = Rig(); future.at = 1_001
        assertFalse(future.read()); assertEquals(OwnershipTime.FUTURE, future.probe.time)
        assertEquals(2, future.calls.count { it == "CLOCK" })
        val expired = Rig(); expired.now += 30_000
        assertFalse(expired.read()); assertEquals(OwnershipLease.EXPIRED, expired.probe.lease)
        assertEquals(OwnershipCheck.DISPLAY_LEASE, expired.probe.check)
        val reversed = Rig(); reversed.now = 999
        assertFalse(reversed.read()); assertEquals(OwnershipLease.CLOCK_REVERSED, reversed.probe.lease)
    }
    @Test fun diagnosticEnabledAndDisabledHaveIdenticalVerdictsGetterCountsAndClockCounts() {
        val changes: List<(Rig) -> Unit> = listOf({}, { it.pkg = null }, { it.event = 20 },
            { it.native = null }, { it.before = null }, { it.live = null }, { it.at = 999 }, { it.now += 30_000 })
        for (change in changes) {
            val enabled = Rig().also(change); val disabled = Rig().also(change)
            assertEquals(disabled.read(null), enabled.read()); assertEquals(disabled.calls, enabled.calls)
        }
    }
    @Test fun ownershipLogsAreBoundedAndThrowingSinkCannotAlterTheProof() {
        var count = 0
        val trace = ManualPromptTrace(ownershipSink = { _, _ -> count++; error("SYNTHETIC") }, sink = { _, _, _ -> })
        val r = Rig(); assertTrue(r.read())
        repeat(100) { trace.ownership(r.probe.code()) }; assertEquals(1, count)
        trace.begin(); trace.ownership(r.probe.code()); assertEquals(2, count)
        assertEquals("ManualOwnershipProbe(redacted)", r.probe.toString())
    }
    @Test fun nativeFailureDoesNotFallBackToPackageOrBindingBasedContentExemption() {
        val r = Rig(); r.native = null
        assertFalse(inspectManualEvent(E.TYPE_WINDOW_CONTENT_CHANGED, { r.read() }, { error("NO_FALLBACK") }))
        r.now += 6_000
        assertFalse(r.read()); assertEquals(OwnershipLease.ACTIVE, r.probe.lease)
    }
}
