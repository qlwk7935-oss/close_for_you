package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import android.view.accessibility.AccessibilityNodeInfo as N
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.action.F9Block
import kr.closeforyou.accessibility.integration.ManualDemoBoundsReason.*
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Actual native-ID classifier and Android-used selector/retire/read/compare/terminal boundary.
 * Synthetic native lists are not observations of the Samsung ROOT or its action handlers. */
class ManualRootDisplayCapabilitiesTest {
    private val navigation = listOf(N.ACTION_FOCUS, N.ACTION_CLEAR_FOCUS, N.ACTION_ACCESSIBILITY_FOCUS,
        N.ACTION_CLEAR_ACCESSIBILITY_FOCUS, android.R.id.accessibilityActionShowOnScreen)
    private val operative = listOf(N.ACTION_CLICK, N.ACTION_LONG_CLICK, N.ACTION_DISMISS,
        N.ACTION_SCROLL_FORWARD, N.ACTION_SCROLL_BACKWARD, N.ACTION_SET_TEXT, Int.MAX_VALUE)
    private fun rig(ids: List<Int> = navigation) = ManualDemoBoundsRejectionTest.Rig().apply {
        expand(reflow = true)
        originalSummaries = mapOf("0" to NativeActionSummary.fromIds(ids)); freshSummaries = originalSummaries
    }
    private fun mode(r: ManualDemoBoundsRejectionTest.Rig, mode: Int) {
        if (mode == 0) r.expand()
        if (mode == 1) {
            fun shift(b: ScreenBounds) = ScreenBounds(b.left + 2, b.top + 2, b.right + 2, b.bottom + 2)
            r.fresh = r.fresh.copy(nodes = r.source.nodes.map { it.copy(bounds = shift(it.bounds)) },
                windows = r.source.windows.map { if (it.id == 20) it.copy(bounds = shift(it.bounds)) else it })
        }
    }
    private fun retained(r: ManualDemoBoundsRejectionTest.Rig) {
        val source = r.source; val analysis = r.analysis
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
        assertSame(source, r.source); assertSame(analysis, r.analysis)
        assertNull(r.raw); assertNull(r.analysis!!.autoEligibility)
        assertEquals(0, r.removed); assertEquals(0, r.f.clicks)
    }
    @Test fun everyWhitelistedActionIsPresentNeverEmptyAndOnlyClassifiedForRootDisplay() {
        for (id in navigation) {
            val summary = NativeActionSummary.fromIds(listOf(id))
            assertEquals(NativeActionPresence.PRESENT, summary.presence); assertTrue(summary.displayRootSafe)
        }
        assertEquals(NativeActionPresence.NONE, NativeActionSummary.fromIds(emptyList()).presence)
        assertEquals("NativeActionSummary(redacted)", NativeActionSummary.fromIds(navigation).toString())
    }
    @Test fun clickLongClickScrollDismissCustomAndMixedActionsCannotProveDisplayRootSafety() {
        for (id in operative) {
            assertFalse(NativeActionSummary.fromIds(listOf(id)).displayRootSafe)
            assertFalse(NativeActionSummary.fromIds(navigation + id).displayRootSafe)
        }
        assertFalse(NativeActionSummary.presenceOnly(NativeActionPresence.PRESENT).displayRootSafe)
    }
    @Test fun malformedMissingAndOverLimitNativeInformationNeverBecomeNavigationOrNone() {
        for (ids in listOf(listOf(0), listOf(-1), listOf(N.ACTION_FOCUS, N.ACTION_FOCUS), (1..65).toList())) {
            val summary = NativeActionSummary.fromIds(ids)
            assertEquals(NativeActionPresence.UNKNOWN, summary.presence); assertFalse(summary.displayRootSafe)
        }
    }
    @Test fun exactNavigationSubsetIsOrderIndependentButNotPresenceOnlyEquality() {
        assertEquals(NativeActionSummary.fromIds(navigation), NativeActionSummary.fromIds(navigation.reversed()))
        assertNotEquals(NativeActionSummary.fromIds(navigation), NativeActionSummary.fromIds(navigation.dropLast(1)))
        assertNotEquals(NativeActionSummary.fromIds(navigation), NativeActionSummary.presenceOnly(NativeActionPresence.PRESENT))
    }
    @Test fun typedRowsKeepSnapshotListDescriptorBindingAndCannotRecoverAfterObservedReuse() {
        val r = rig(); val rows = r.source.nodes.map { NativeActionSummary.fromIds(navigation) }.toMutableList()
        val original = requireNotNull(NativeNodeActions.forSnapshotSummaries(r.source, rows))
        rows[0] = NativeActionSummary.UNKNOWN
        assertTrue(original.summary(r.source, r.source.nodes.size, 0).displayRootSafe)
        assertEquals(NativeActionPresence.UNKNOWN, original.summary(r.source.copy(), r.source.nodes.size, 0).presence)
        val nodes = r.fresh.nodes.toMutableList(); val first = nodes[0]
        val fresh = requireNotNull(NativeNodeActions.forNodeSummaries(nodes, nodes.map { NativeActionSummary.fromIds(navigation) }))
        nodes[0] = first.copy()
        assertEquals(NativeActionPresence.UNKNOWN, fresh.summary(nodes, nodes.size, 0).presence)
        nodes[0] = first
        assertEquals(NativeActionPresence.UNKNOWN, fresh.summary(nodes, nodes.size, 0).presence)
    }
    @Test fun stableRootNavigationPassesAllThreeDisplayPatternsWithoutRevivingExecutionEvidence() {
        for (m in 0..2) for (ids in navigation.map { listOf(it) } + listOf(navigation)) {
            val r = rig(ids); mode(r, m); retained(r)
            assertEquals(listOf(POLICY_RETAIN_ROOT_RESIZE, POLICY_RETAIN_TRANSLATION, POLICY_RETAIN_PASSIVE_REFLOW)[m], r.row!!.demoBounds)
            assertEquals(1_000L, r.source.capturedAtUptimeMillis)
        }
    }
    @Test fun rootClickableStillRejectsEvenWithOnlyVerifiedNavigationActions() {
        val r = rig(); r.source = r.source.copy(nodes = r.source.nodes.map {
            if (it.path == "0") it.copy(clickable = true) else it }.toPersistentList())
        r.attach(); r.expand(reflow = true); r.rejected(POLICY_ROOT_CLICKABLE)
    }
    @Test fun operativeOrUnspecifiedRootActionsStillRejectEveryGeometryPath() {
        for (m in 0..2) for (id in operative) {
            val r = rig(navigation + id); mode(r, m); r.rejected(if (id == Int.MAX_VALUE || id == N.ACTION_CLICK || id == N.ACTION_DISMISS)
                POLICY_NATIVE_ACTIONS_UNCONFIRMED else POLICY_ROOT_INTERACTIVE)
        }
        val unknownKind = rig(); unknownKind.originalSummaries = emptyMap(); unknownKind.freshSummaries = emptyMap()
        unknownKind.originalNativeStates = mapOf("0" to NativeActionPresence.PRESENT)
        unknownKind.freshNativeStates = unknownKind.originalNativeStates
        unknownKind.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
    }
    @Test fun unknownOrMissingRootProofIsNotRescuedByTheOtherObservedNavigationList() {
        val missing = rig(); missing.originalProofEnabled = false; missing.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
        val unknown = rig(); unknown.freshSummaries = mapOf("0" to NativeActionSummary.UNKNOWN)
        unknown.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
    }
    @Test fun navigationSubsetChangeRejectsEvenWhenBothListsArePresentAndDescriptorsAreEqual() {
        for (same in listOf(false, true)) {
            val r = rig(); r.freshSummaries = mapOf("0" to NativeActionSummary.fromIds(navigation.dropLast(1)))
            if (same) r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
            assertEquals(ManualDisplayVerdict.CHANGED, r.run())
            assertEquals(POLICY_NATIVE_ACTIONS_CHANGED, r.row!!.demoBounds); assertEquals(1, r.removed)
        }
    }
    @Test fun descendantsWithNavigationAreNotRedefinedAsPassiveByTheRootException() {
        val r = rig(); val path = r.source.nodes[1].path
        r.originalSummaries = r.originalSummaries + (path to NativeActionSummary.fromIds(navigation))
        r.freshSummaries = r.originalSummaries; r.rejected(POLICY_REFLOW_PARENT)
        val body = rig(); val bodyPath = body.source.nodes[5].path
        body.originalSummaries = body.originalSummaries + (bodyPath to NativeActionSummary.fromIds(navigation))
        body.freshSummaries = body.originalSummaries
        body.fresh = body.fresh.copy(nodes = body.fresh.nodes.map { if (it.path == bodyPath) it.copy(bounds = it.bounds.copy(bottom = 150)) else it })
        body.rejected(POLICY_INTERACTIVE_BOUNDS)
    }
    @Test fun rootNavigationCannotExcuseTargetButtonPopupTextOrCompetingWindowChange() {
        val changes: List<(ManualDemoBoundsRejectionTest.Rig) -> Unit> = listOf(
            { r -> r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.clickable) it.copy(bounds = it.bounds.copy(left = it.bounds.left + 1)) else it }) },
            { r -> r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/3")) it.copy(text = "구매") else it }) },
            { r -> r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path.endsWith("/1")) it.copy(text = "다른 팝업") else it }) },
            { r -> r.fresh = r.fresh.copy(windows = r.fresh.windows + WindowSnapshot(50, WindowType.SYSTEM, ScreenBounds(0, 0, 300, 40), false, false, 5)) },
            { r -> r.native = null })
        for (change in changes) {
            val r = rig(); change(r); assertNotEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
            assertEquals(1, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun rootClassAndProtectedContentRemainPartOfTheDisplayIdentityContract() {
        val custom = rig(); custom.source = custom.source.copy(nodes = custom.source.nodes.map {
            if (it.path == "0") it.copy(className = "custom.Root") else it }.toPersistentList())
        custom.attach(); custom.expand(reflow = true); custom.rejected(POLICY_DESCENDANT_BOUNDS)
        val protected = rig(); protected.source = protected.source.copy(nodes = protected.source.nodes.map {
            if (it.path.endsWith("/1")) it.copy(text = "결제 비밀번호") else it }.toPersistentList())
        protected.attach(); protected.expand(reflow = true); protected.rejected(POLICY_PROTECTED_CONTENT)
    }
    @Test fun actualBuildFeatureGateLeavesReleaseOnStrictComparison() {
        val r = rig(); val verdict = r.run(enabled = ManualDemoBoundsFeature.enabled)
        assertEquals(if (ManualDemoBoundsFeature.enabled) ManualDisplayVerdict.BOUNDS_RETAINED else ManualDisplayVerdict.CHANGED, verdict)
        if (!ManualDemoBoundsFeature.enabled) assertEquals(SELECT_DISABLED, r.row!!.demoBounds)
        assertEquals(0, r.f.clicks)
    }
    @Test fun contentStateAddedRemovedMixedAndUnknownWindowCannotSelectTheRootException() {
        for ((type, mask) in listOf(E.TYPE_WINDOW_CONTENT_CHANGED to E.WINDOWS_CHANGE_BOUNDS,
            E.TYPE_WINDOW_STATE_CHANGED to E.WINDOWS_CHANGE_BOUNDS, E.TYPE_WINDOWS_CHANGED to E.WINDOWS_CHANGE_ADDED,
            E.TYPE_WINDOWS_CHANGED to E.WINDOWS_CHANGE_REMOVED, E.TYPE_WINDOWS_CHANGED to (E.WINDOWS_CHANGE_ADDED or E.WINDOWS_CHANGE_BOUNDS))) {
            val r = rig(); assertEquals(ManualDisplayVerdict.CHANGED, r.run(type = type, changes = mask))
            assertEquals(SELECT_EVENT, r.row!!.demoBounds)
        }
        val r = rig(); assertEquals(ManualDisplayVerdict.CHANGED, r.run(eventId = -1)); assertEquals(SELECT_TARGET, r.row!!.demoBounds)
    }
    @Test fun navigationRetentionStillHonorsTimeoutNativeAfterAndCancellationCleanup() {
        val timeout = rig(); timeout.read = { timeout.now += 100 }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, timeout.run()); assertEquals(1, timeout.removed)
        val native = rig(); native.read = { native.native = null }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, native.run()); assertEquals(1, native.removed)
        val cancel = rig(); val primary = CancellationException("SYNTHETIC_CANCEL")
        cancel.read = { throw primary }; cancel.failCleanup = true
        try { cancel.run(); fail("CANCEL_REQUIRED") } catch (e: CancellationException) { assertSame(primary, e) }
        assertEquals(1, cancel.removed); assertEquals(0, cancel.f.clicks)
    }
    @Test fun displayCapabilityDoesNotLetManualRefreshRebaseTheOldSnapshot() = runTest {
        val r = rig(); retained(r)
        val fresh = r.source.copy(token = r.source.token.copy(screenVersion = 2, captureEpoch = 2),
            nodes = r.fresh.nodes.toPersistentList(), windows = r.fresh.windows.toPersistentList())
        val choice = UserChoice(r.source.token, ChoiceKind.CLOSE_ONCE, r.analysis!!.closeCandidates.single().id, true)
        assertNull(ManualChoiceRefresh.collect(r.source, r.analysis!!, choice, r.detection!!, { r.now }, { true }) { check -> check(); fresh })
        assertEquals(0, r.f.clicks)
    }
    @Test fun displayCapabilityCannotOverrideRealF9PreClickScreenMatch() = runTest {
        val r = rig(); retained(r); r.f.scene = r.fresh
        val result = r.f.closer(StandardTestDispatcher(testScheduler)).execute(r.f.reference.copy(
            snapshot = r.source, candidate = r.analysis!!.closeCandidates.single()))
        assertEquals(F9Block.SCREEN_UNVERIFIED, result.block); assertFalse(result.attempted); assertEquals(0, r.f.clicks)
    }
    @Test fun p2FullActionReplacementMustRejectEveryDisplayPath() {
        for (m in 0..3) {
            val r = rig(); mode(r, m)
            if (m == 3) r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
            val target = requireNotNull(r.analysis!!.closeCandidates.single().nodePath)
            r.originalSummaries = r.originalSummaries + (target to NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_LONG_CLICK)))
            r.freshSummaries = r.freshSummaries + (target to NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_SCROLL_FORWARD)))
            assertEquals(ManualDisplayVerdict.CHANGED, r.run())
            assertEquals(POLICY_NATIVE_ACTIONS_CHANGED, r.row!!.demoBounds)
            assertEquals(1, r.removed); assertNull(r.raw); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun sameCountNonTargetActionReplacementCannotHideBehindOtherTrue() {
        val r = rig(); val other = r.source.nodes[6].path
        r.originalSummaries = r.originalSummaries + (other to NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_SCROLL_BACKWARD)))
        r.freshSummaries = r.freshSummaries + (other to NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_LONG_CLICK)))
        r.rejected(POLICY_NATIVE_ACTIONS_CHANGED)
    }
    @Test fun customOrUnknownActionOnAChildNeverApprovesEvenWhenBothIdsMatch() {
        for (m in 0..3) {
            val r = rig(); mode(r, m)
            if (m == 3) r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
            val target = requireNotNull(r.analysis!!.closeCandidates.single().nodePath)
            r.originalSummaries = r.originalSummaries + (target to NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, Int.MAX_VALUE)))
            r.freshSummaries = r.originalSummaries
            val result = r.run()
            assertTrue(result == ManualDisplayVerdict.CHANGED || result == ManualDisplayVerdict.UNAVAILABLE)
            assertEquals(POLICY_NATIVE_ACTIONS_UNCONFIRMED, r.row!!.demoBounds)
            assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun presenceOnlyPresentCannotProveAnExactNativeSetInSelectedSame() {
        val r = rig(); r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
        val target = requireNotNull(r.analysis!!.closeCandidates.single().nodePath)
        r.originalSummaries = r.originalSummaries + (target to NativeActionSummary.presenceOnly(NativeActionPresence.PRESENT))
        r.freshSummaries = r.originalSummaries
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.run())
        assertEquals(POLICY_NATIVE_ACTIONS_UNCONFIRMED, r.row!!.demoBounds); assertEquals(1, r.removed)
    }
    @Test fun exactNativeSetEqualityCannotCollapseDistinctNonNavigationActions() {
        assertNotEquals(NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_LONG_CLICK)),
            NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_SCROLL_FORWARD)))
        assertNotEquals(NativeActionSummary.fromIds(listOf(N.ACTION_SCROLL_FORWARD)),
            NativeActionSummary.fromIds(listOf(N.ACTION_SCROLL_BACKWARD)))
    }

    @Test fun identicalExactBasicSetsAreOrderIndependentButStillRequireTheWholeDisplayContract() {
        for (m in 0..3) {
            val r = rig(); mode(r, m)
            if (m == 3) r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
            val target = requireNotNull(r.analysis!!.closeCandidates.single().nodePath)
            r.originalSummaries = r.originalSummaries + (target to NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_LONG_CLICK, N.ACTION_SCROLL_FORWARD)))
            r.freshSummaries = r.freshSummaries + (target to NativeActionSummary.fromIds(listOf(N.ACTION_SCROLL_FORWARD, N.ACTION_CLICK, N.ACTION_LONG_CLICK)))
            assertEquals(if (m == 3) ManualDisplayVerdict.SAME else ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
            assertNull(r.raw); assertEquals(0, r.removed); assertEquals(0, r.f.clicks)
        }
        val changed = rig(); changed.fresh = changed.fresh.copy(windowId = 99)
        assertEquals(ManualDisplayVerdict.CHANGED, changed.run()); assertEquals(0, changed.f.clicks)
    }
    @Test fun duplicateOverLimitAndProjectedActionOmissionRejectAllFourDisplayPaths() {
        val incomplete = listOf(listOf(N.ACTION_CLICK, N.ACTION_CLICK), List(65) { N.ACTION_CLICK }, emptyList(), listOf(0))
        for (ids in incomplete) for (m in 0..3) {
            val r = rig(); mode(r, m)
            if (m == 3) r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
            val target = requireNotNull(r.analysis!!.closeCandidates.single().nodePath)
            r.originalSummaries = r.originalSummaries + (target to NativeActionSummary.fromIds(ids))
            r.freshSummaries = r.originalSummaries
            val result = r.run()
            assertTrue(result == ManualDisplayVerdict.CHANGED || result == ManualDisplayVerdict.UNAVAILABLE)
            assertEquals(POLICY_NATIVE_ACTIONS_UNCONFIRMED, r.row!!.demoBounds); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun labelledBuiltInActionCannotPretendToHaveVerifiedStandardMeaning() {
        for (m in 0..3) {
            val r = rig(); mode(r, m)
            if (m == 3) r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
            val target = requireNotNull(r.analysis!!.closeCandidates.single().nodePath)
            r.originalSummaries = r.originalSummaries + (target to NativeActionSummary.fromIds(listOf(N.ACTION_CLICK), hasUnverifiedLabel = true))
            r.freshSummaries = r.originalSummaries
            val result = r.run()
            assertTrue(result == ManualDisplayVerdict.CHANGED || result == ManualDisplayVerdict.UNAVAILABLE)
            assertEquals(POLICY_NATIVE_ACTIONS_UNCONFIRMED, r.row!!.demoBounds); assertEquals(0, r.f.clicks)
        }
        assertFalse(NativeActionSummary.fromIds(navigation, hasUnverifiedLabel = true).displayRootSafe)
    }
    @Test fun exactNativeSetAndDtoProjectionCannotContradictOneAnother() {
        val r = rig(); val target = requireNotNull(r.analysis!!.closeCandidates.single().nodePath)
        r.originalSummaries = r.originalSummaries + (target to NativeActionSummary.fromIds(listOf(N.ACTION_LONG_CLICK)))
        r.freshSummaries = r.originalSummaries
        r.rejected(POLICY_NATIVE_ACTIONS_UNCONFIRMED)
    }
    @Test fun canonicalIdCopiesCannotBeChangedByCallerMutationOrLeakThroughToString() {
        val ids = mutableListOf(N.ACTION_LONG_CLICK, N.ACTION_CLICK)
        val frozen = NativeActionSummary.fromIds(ids)
        ids[0] = N.ACTION_SCROLL_FORWARD; ids.clear()
        assertTrue(frozen.hasExactKnownSet)
        assertEquals(NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_LONG_CLICK)), frozen)
        assertNotEquals(NativeActionSummary.fromIds(listOf(N.ACTION_CLICK, N.ACTION_SCROLL_FORWARD)), frozen)
        assertEquals("NativeActionSummary(redacted)", frozen.toString())
        assertFalse(NativeActionSummary.presenceOnly(NativeActionPresence.PRESENT).hasExactKnownSet)
        assertFalse(NativeActionSummary.UNKNOWN.hasExactKnownSet)
    }
    @Test fun consistentlyProjectedRootClickOrDismissIsNeverNavigationOnly() {
        for ((id, action) in listOf(N.ACTION_CLICK to NodeAction.CLICK, N.ACTION_DISMISS to NodeAction.DISMISS)) {
            val r = rig(navigation + id)
            r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path == "0") it.copy(actions = persistentSetOf(action)) else it }.toPersistentList())
            r.attach(); r.expand(reflow = true); r.rejected(POLICY_ROOT_INTERACTIVE)
        }
    }

    @Test fun rootNavigationAdditionDeletionAndReplacementRejectEverySelectedPath() {
        val changes = listOf(
            listOf(N.ACTION_FOCUS) to listOf(N.ACTION_FOCUS, android.R.id.accessibilityActionShowOnScreen),
            listOf(N.ACTION_FOCUS, android.R.id.accessibilityActionShowOnScreen) to listOf(N.ACTION_FOCUS),
            listOf(N.ACTION_FOCUS) to listOf(N.ACTION_CLEAR_FOCUS))
        for ((before, after) in changes) for (m in 0..3) {
            val r = rig(before); mode(r, m)
            if (m == 3) r.fresh = r.fresh.copy(nodes = r.source.nodes, windows = r.source.windows)
            r.freshSummaries = mapOf("0" to NativeActionSummary.fromIds(after))
            assertEquals(ManualDisplayVerdict.CHANGED, r.run())
            assertEquals(POLICY_NATIVE_ACTIONS_CHANGED, r.row!!.demoBounds)
            assertNull(r.raw); assertEquals(1, r.removed); assertEquals(0, r.f.clicks)
        }
    }

}
