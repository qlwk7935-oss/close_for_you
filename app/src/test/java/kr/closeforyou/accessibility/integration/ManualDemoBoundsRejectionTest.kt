package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.action.F9AutoCloserTest
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.accessibility.NativeActionPresence
import kr.closeforyou.accessibility.NativeNodeActions
import kr.closeforyou.accessibility.integration.ManualDemoBoundsReason.*
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCandidateAttacher
import org.junit.Assert.*
import org.junit.Test

/** Multiple Android-shaped containers, two buttons, actual B attachment, then the real
 * selector/probe/registered read/comparison/terminal report. Still not a native Samsung tree. */
class ManualDemoBoundsRejectionTest {
    internal class Rig(val diagnostics: Boolean = true) {
        val f = F9AutoCloserTest.Fixture()
        private fun box(path: String, kind: String, b: ScreenBounds, text: String? = null, clickable: Boolean = false) =
            f.node.copy(path = path, viewId = "synthetic:$path", className = kind, bounds = b, text = text,
                contentDescription = null, clickable = clickable, actions = if (clickable) persistentSetOf(NodeAction.CLICK) else persistentSetOf())
        val bounds = ScreenBounds(20, 20, 240, 360)
        var source = f.snapshot.copy(windows = f.windows.map { if (it.id == 20) it.copy(bounds = bounds) else it }.toPersistentList(), nodes = persistentListOf(
            box("0", "android.widget.FrameLayout", bounds),
            box("0/0", "android.widget.LinearLayout", bounds),
            box("0/0/0", "android.widget.FrameLayout", ScreenBounds(30, 30, 230, 350)),
            box("0/0/0/0", "android.widget.LinearLayout", ScreenBounds(30, 30, 230, 350)),
            box("0/0/0/0/0", "android.widget.TextView", ScreenBounds(40, 40, 220, 70), "광고 안내"),
            box("0/0/0/0/1", "android.widget.TextView", ScreenBounds(40, 80, 220, 120), "가을 할인 쿠폰"),
            box("0/0/0/0/2", "android.widget.Button", ScreenBounds(40, 200, 220, 240), "자세히 보기", true),
            box("0/0/0/0/3", "android.widget.Button", ScreenBounds(40, 260, 220, 310), "닫기", true)))
        var fresh = f.before.copy(nodes = source.nodes, windows = source.windows, visiblePaths = source.nodes.map { it.path }.toSet())
        var detection: PopupDetection? = PopupDetection(source.token, persistentSetOf(PopupSignal.NEW_WINDOW))
        var analysis: PopupAnalysis? = null
        val probe = ManualDisplayProbe(diagnostics)
        var raw: SnapshotToken? = source.token; var removed = 0; var reads = 0; var now = 1_000L
        var native: ManualDisplayIdentity? = ManualDisplayIdentity(30, ManualDisplayListing.NOT_ENUMERATED)
        var read: (() -> Unit)? = null
        var failCleanup = false; var failSink = false
        // Explicit fake native reads; the DTO's projected actions are NOT production proof.
        var originalNativeStates = emptyMap<String, NativeActionPresence>()
        var freshNativeStates = emptyMap<String, NativeActionPresence>()
        var originalProofEnabled = true; var freshProofEnabled = true
        fun originalProof() = if (originalProofEnabled) NativeNodeActions.forSnapshot(source, source.nodes.map {
            originalNativeStates[it.path] ?: if (it.actions.isEmpty()) NativeActionPresence.NONE else NativeActionPresence.PRESENT }) else null
        fun withFreshProof() = fresh.copy(nativeActions = if (freshProofEnabled) NativeNodeActions.forNodes(fresh.nodes, fresh.nodes.map {
            freshNativeStates[it.path] ?: if (it.actions.isEmpty()) NativeActionPresence.NONE else NativeActionPresence.PRESENT }) else null)
        var row: ManualDisplayReviewCode? = null
        init { attach(); expand() }
        fun attach() {
            detection = detection?.copy(token = source.token)
            analysis = DecisionCandidateAttacher().attach(PopupEvent.Candidate(source, requireNotNull(detection)), PopupAnalysis(source.token))
        }
        fun expand(reflow: Boolean = false) {
            val b = ScreenBounds(18, 18, 242, 362)
            fresh = fresh.copy(nodes = source.nodes.map { n -> when {
                n.path == "0" -> n.copy(bounds = b)
                reflow && n.className in setOf("android.widget.FrameLayout", "android.widget.LinearLayout") ->
                    n.copy(bounds = n.bounds.copy(left = n.bounds.left - 2, right = n.bounds.right + 2))
                else -> n
            } }, windows = source.windows.map { if (it.id == 20) it.copy(bounds = b) else it })
        }
        fun run(enabled: Boolean = true, type: Int = E.TYPE_WINDOWS_CHANGED, changes: Int = E.WINDOWS_CHANGE_BOUNDS,
            eventId: Int = 20): ManualDisplayVerdict {
            val nativeSource = originalProof()
            val candidates = manualDemoBoundsCandidates(enabled, type, changes, eventId, source, analysis, detection, probe::recordDemoBounds)
            return reviewManualDisplay({ raw = null }, {
                val started = now
                verifyRegisteredManualDisplay(probe, { manualDisplayCheckpoint({ true }, { now }, started, probe) }, { native }) { id ->
                    reads++; read?.invoke()
                    ManualDisplayContinuity.assess(source, withFreshProof(), id.window, probe, id.listing, candidates, nativeSource)
                }
            }, { removed++; if (failCleanup) throw IllegalStateException("SYNTHETIC_CLEANUP") }, { v ->
                row = ManualDisplayReviewCode(ManualEventKind.WINDOW_SET, ManualWindowMask.BOUNDS, v, probe.failurePoint,
                    probe.detail, probe.failureOwned, probe.failureListing, probe.failureNodes, probe.failureBounds, probe.demoBounds)
                if (failSink) throw CancellationException("SYNTHETIC_REPORT")
            })
        }
        fun rejected(reason: ManualDemoBoundsReason) {
            assertEquals(ManualDisplayVerdict.CHANGED, run()); assertEquals(reason, row!!.demoBounds)
            assertEquals(1, reads); assertEquals(1, removed); assertEquals(0, f.clicks); assertNull(raw)
            assertEquals(ManualDisplayDetail.NODES_CHANGED, row!!.detail)
        }
    }
    @Test fun actualBAttachmentOfNestedTwoButtonTreeSelectsOneCloseAndCanRetainRootOnlyResize() {
        val r = Rig(); assertEquals(1, r.analysis!!.closeCandidates.size)
        assertEquals("0/0/0/0/3", r.analysis!!.closeCandidates.single().nodePath)
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run()); assertEquals(POLICY_RETAIN_ROOT_RESIZE, r.row!!.demoBounds)
        assertEquals(0, r.removed); assertEquals(1, r.reads); assertNull(r.raw); assertEquals(0, r.f.clicks)
    }
    @Test fun nestedPassiveContainerReflowNowRequiresTheNarrowParentCheckedPolicy() {
        val r = Rig(); r.expand(reflow = true)
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
        assertEquals(POLICY_RETAIN_PASSIVE_REFLOW, r.row!!.demoBounds)
        assertEquals(0, r.removed); assertEquals(0, r.f.clicks); assertNull(r.raw)
    }
    @Test fun bodyReflowWithUnchangedWordsAndFixedButtonUsesExplicitPassiveReflowCode() {
        val r = Rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == "0/0/0/0/1")
            it.copy(bounds = it.bounds.copy(bottom = 150)) else it })
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
        assertEquals(POLICY_RETAIN_PASSIVE_REFLOW, r.row!!.demoBounds)
    }
    @Test fun selectorTokenScopeAndCandidateFailuresAreReportedBeforeAnyPolicyAssessment() {
        val changes: List<Pair<(Rig) -> Unit, ManualDemoBoundsReason>> = listOf(
            ({ r: Rig -> r.analysis = null }) to SELECT_ANALYSIS_MISSING,
            ({ r: Rig -> r.analysis = r.analysis!!.copy(token = r.source.token.copy(screenVersion = 9)) }) to SELECT_ANALYSIS_TOKEN,
            ({ r: Rig -> r.detection = null }) to SELECT_DETECTION_MISSING,
            ({ r: Rig -> r.detection = r.detection!!.copy(token = r.source.token.copy(screenVersion = 9)) }) to SELECT_DETECTION_TOKEN,
            ({ r: Rig -> r.detection = r.detection!!.copy(signals = persistentSetOf(PopupSignal.NEW_WINDOW, PopupSignal.SAME_WINDOW_OVERLAY)) }) to SELECT_SCOPE,
            ({ r: Rig -> r.analysis = r.analysis!!.copy(closeCandidates = persistentListOf()) }) to SELECT_NO_CANDIDATE,
            ({ r: Rig -> r.analysis = r.analysis!!.copy(closeCandidates = (r.analysis!!.closeCandidates + r.analysis!!.closeCandidates).toPersistentList()) }) to SELECT_MULTIPLE_CANDIDATES)
        for ((change, expected) in changes) { val r = Rig(); change(r); r.rejected(expected) }
    }
    @Test fun disabledWrongEventAndWrongTargetHaveDeterministicPriorityAndNoAuthority() {
        val disabled = Rig(); assertEquals(ManualDisplayVerdict.CHANGED, disabled.run(enabled = false))
        assertEquals(SELECT_DISABLED, disabled.row!!.demoBounds)
        val event = Rig(); event.run(type = E.TYPE_WINDOW_CONTENT_CHANGED); assertEquals(SELECT_EVENT, event.row!!.demoBounds)
        val target = Rig(); target.run(eventId = -1); assertEquals(SELECT_TARGET, target.row!!.demoBounds)
        val allWrong = Rig(); allWrong.analysis = null; allWrong.run(enabled = false, eventId = -1)
        assertEquals(SELECT_DISABLED, allWrong.row!!.demoBounds)
    }
    @Test fun originalEmptyOrOutsideWindowNodeIsNotAssumedVisibleOrOmitted() {
        for (bounds in listOf(ScreenBounds(40, 80, 40, 120), ScreenBounds(0, 0, 10, 10))) {
            val r = Rig(); r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path == "0/0/0/0/1") it.copy(bounds = bounds) else it }.toPersistentList())
            r.attach(); r.rejected(POLICY_ORIGINAL_NODE_GEOMETRY)
        }
    }
    @Test fun currentNodeOutsideTargetAndCandidateNotVisibleAreDifferentFailures() {
        val outside = Rig(); outside.fresh = outside.fresh.copy(nodes = outside.fresh.nodes.map {
            if (it.path == "0/0/0/0/1") it.copy(bounds = ScreenBounds(0, 0, 10, 10)) else it })
        outside.rejected(POLICY_CURRENT_NODE_GEOMETRY)
        val invisible = Rig(); invisible.fresh = invisible.fresh.copy(visiblePaths = emptySet())
        invisible.rejected(POLICY_CANDIDATE_NOT_VISIBLE)
    }
    @Test fun bRecognizesNormalizedLabelAndE1ButExistingDemoRestrictionsRemainUnchanged() {
        val whitespace = Rig(); whitespace.source = whitespace.source.copy(nodes = whitespace.source.nodes.map {
            if (it.path == "0/0/0/0/3") it.copy(text = " 닫기 ") else it }.toPersistentList())
        whitespace.attach(); whitespace.expand(); assertEquals(ButtonRole.CLOSE, whitespace.analysis!!.closeCandidates.single().role)
        whitespace.rejected(POLICY_CANDIDATE_LABEL)
        val dismiss = Rig(); dismiss.source = dismiss.source.copy(nodes = dismiss.source.nodes.map {
            if (it.path == "0/0/0/0/3") it.copy(actions = persistentSetOf(NodeAction.CLICK, NodeAction.DISMISS)) else it }.toPersistentList())
        dismiss.attach(); dismiss.expand(); assertEquals(EvidenceLevel.E1_DISMISS, dismiss.analysis!!.closeCandidates.single().evidence)
        dismiss.rejected(POLICY_CANDIDATE_ROLE_EVIDENCE)
    }
    @Test fun popupAndButtonReplacementAreStillRejectedBeforeGeometryPatternAssessment() {
        for (path in listOf("0/0/0/0/1", "0/0/0/0/3")) {
            val r = Rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == path) it.copy(text = "다른 안내") else it })
            r.rejected(POLICY_NODE_FIELDS)
        }
        val root = Rig(); root.source = root.source.copy(nodes = root.source.nodes.map { if (it.path == "0") it.copy(clickable = true) else it }.toPersistentList())
        root.attach(); root.expand(); root.rejected(POLICY_ROOT_INTERACTIVE)
    }
    @Test fun acceptedPolicyCannotTurnLaterTimeoutOrNativeFailureIntoRetainedResult() {
        val timeout = Rig(); timeout.read = { timeout.now += 100 }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, timeout.run())
        assertEquals(POLICY_RETAIN_ROOT_RESIZE, timeout.row!!.demoBounds)
        assertEquals(ManualDisplayDetail.TIME_LIMIT, timeout.row!!.detail); assertEquals(1, timeout.removed)
        val native = Rig(); native.read = { native.native = null }
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, native.run())
        assertEquals(POLICY_RETAIN_ROOT_RESIZE, native.row!!.demoBounds); assertEquals(1, native.removed)
    }
    @Test fun firstPolicyCodeSurvivesCleanupAndReportingFailuresAndExistingCancellationWins() {
        val r = Rig(); r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map {
            if (it.path.endsWith("/3")) it.copy(bounds = it.bounds.copy(left = it.bounds.left + 1)) else it })
        r.failSink = true; r.failCleanup = true
        try { r.run(); fail("CLEANUP_REQUIRED") } catch (_: kr.closeforyou.accessibility.CollectionCleanupFailure) { }
        assertEquals(POLICY_INTERACTIVE_BOUNDS, r.row!!.demoBounds)
        r.probe.recordDemoBounds(SELECT_DISABLED); r.probe.reject(ManualDisplayDetail.NATIVE_ID_CHANGED)
        assertEquals(POLICY_INTERACTIVE_BOUNDS, r.probe.demoBounds); assertEquals(1, r.removed)
        val c = Rig(); val primary = CancellationException("SYNTHETIC_PRIMARY")
        c.read = { throw primary }; c.failCleanup = true
        try { c.run(); fail("CANCEL_REQUIRED") } catch (e: CancellationException) { assertSame(primary, e) }
        assertEquals(SELECTED, c.row!!.demoBounds); assertEquals(1, c.removed); assertEquals(0, c.f.clicks)
    }
    @Test fun diagnosticsOffAndThrowingCallbacksCannotChangeAnyPolicyDecision() {
        for (diagnostics in listOf(false, true)) {
            val r = Rig(diagnostics); assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
            assertEquals(if (diagnostics) POLICY_RETAIN_ROOT_RESIZE else NOT_CHECKED, r.row!!.demoBounds)
        }
        val r = Rig()
        val list = manualDemoBoundsCandidates(true, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_BOUNDS, 20,
            r.source, r.analysis, r.detection) { throw CancellationException("SYNTHETIC_DIAGNOSTIC") }
        assertEquals(r.analysis!!.closeCandidates, list)
        assertTrue(ManualDemoBoundsPolicy.retains(r.source, r.withFreshProof(), 30, ManualDisplayListing.NOT_ENUMERATED,
            requireNotNull(list), r.originalProof()) { throw CancellationException("SYNTHETIC_DIAGNOSTIC") })
        assertEquals("ManualDisplayProbe(redacted)", r.probe.toString())
    }
    @Test fun remainingDefensivePolicyBranchesReportTheirActualFirstFailureOnly() {
        // These are direct calls to the SAME policy; outer Android checks can reject some
        // malformed inputs earlier. They are not claims that those inputs reached Samsung.
        fun rejected(reason: ManualDemoBoundsReason, change: (Rig) -> Unit) {
            val r = Rig(); change(r); val codes = mutableListOf<ManualDemoBoundsReason>()
            assertFalse(reason.name, ManualDemoBoundsPolicy.retains(r.source, r.withFreshProof(), 30,
                ManualDisplayListing.NOT_ENUMERATED, r.analysis!!.closeCandidates, r.originalProof(), codes::add))
            assertEquals(listOf(reason), codes)
        }
        rejected(POLICY_INCOMPLETE) { it.fresh = it.fresh.copy(complete = false) }
        rejected(POLICY_BINDING) { it.fresh = it.fresh.copy(packageName = "synthetic.other") }
        rejected(POLICY_NODE_COUNT) { it.fresh = it.fresh.copy(nodes = it.fresh.nodes.dropLast(1)) }
        rejected(POLICY_WINDOW_SCOPE) { it.source = it.source.copy(windows = persistentListOf(it.source.windows.last(), it.source.windows.last())) }
        rejected(POLICY_CANDIDATE_COUNT) { it.analysis = it.analysis!!.copy(closeCandidates = persistentListOf()) }
        rejected(POLICY_TARGET_UNCONFIRMED) { r -> r.fresh = r.fresh.copy(windows = r.fresh.windows.map { if (it.id == 20) it.copy(active = false) else it }) }
        rejected(POLICY_ORIGINAL_TARGET) { it.source = it.source.copy(windows = persistentListOf(it.source.windows.first())) }
        rejected(POLICY_WINDOW_UNCHANGED) { it.fresh = it.fresh.copy(windows = it.source.windows) }
        rejected(POLICY_NATIVE_COLLISION) { it.source = it.source.copy(windows = (it.source.windows +
            WindowSnapshot(30, WindowType.ACCESSIBILITY_OVERLAY, it.source.screenBounds, false, false, 4)).toPersistentList()) }
        rejected(POLICY_WINDOW_SET) { it.fresh = it.fresh.copy(windows = it.fresh.windows.reversed()) }
        rejected(POLICY_OTHER_WINDOW) { r -> r.fresh = r.fresh.copy(windows = r.fresh.windows.map { if (it.id == 10) it.copy(layer = 0) else it }) }
        rejected(POLICY_TARGET_METADATA) { r -> r.fresh = r.fresh.copy(windows = r.fresh.windows.map { if (it.id == 20) it.copy(layer = 3) else it }) }
        rejected(POLICY_WINDOW_GEOMETRY) { r -> r.source = r.source.copy(windows = r.source.windows.map {
            if (it.id == 20) it.copy(bounds = ScreenBounds(20, 20, 20, 360)) else it }.toPersistentList()) }
        rejected(POLICY_ROOT_PATH) { r -> r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path == "0") it.copy(path = "synthetic-other") else it }.toPersistentList()) }
        rejected(POLICY_ROOT_WINDOW) { r -> r.fresh = r.fresh.copy(nodes = r.fresh.nodes.map { if (it.path == "0") it.copy(bounds = r.bounds) else it }) }
        rejected(POLICY_CANDIDATE_NODE) { r -> r.analysis = r.analysis!!.copy(closeCandidates = persistentListOf(r.analysis!!.closeCandidates.single().copy(nodePath = "synthetic-missing"))) }
        rejected(POLICY_CANDIDATE_BINDING) { r -> r.analysis = r.analysis!!.copy(closeCandidates = persistentListOf(r.analysis!!.closeCandidates.single().copy(viewId = "synthetic-other"))) }
        rejected(POLICY_CANDIDATE_ACTION) { r ->
            r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path.endsWith("/3")) it.copy(enabled = false) else it }.toPersistentList()); r.expand()
        }
        rejected(POLICY_AMBIGUOUS_LABELS) { r ->
            r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path.endsWith("/3")) it.copy(contentDescription = "다른 동작") else it }.toPersistentList()); r.expand()
        }
        rejected(POLICY_MULTIPLE_ACTION_LABELS) { r ->
            r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path.endsWith("/2")) it.copy(text = "닫기") else it }.toPersistentList()); r.expand()
        }
        rejected(POLICY_PROTECTED_CONTENT) { r ->
            r.source = r.source.copy(nodes = r.source.nodes.map { if (it.path.endsWith("/1")) it.copy(text = "비밀번호 입력") else it }.toPersistentList()); r.expand()
        }
        rejected(POLICY_NONCOHERENT_TRANSLATION) { r ->
            fun shift(b: ScreenBounds) = ScreenBounds(b.left + 2, b.top + 2, b.right + 2, b.bottom + 2)
            r.fresh = r.fresh.copy(nodes = r.source.nodes.map { if (it.path.endsWith("/3")) it else it.copy(bounds = shift(it.bounds)) },
                windows = r.source.windows.map { if (it.id == 20) it.copy(bounds = shift(it.bounds)) else it })
        }
    }
    @Test fun translationAcceptanceCodeStillRequiresSuccessfulFinalDisplayBoundary() {
        val r = Rig()
        fun shift(b: ScreenBounds) = ScreenBounds(b.left + 3, b.top + 5, b.right + 3, b.bottom + 5)
        r.fresh = r.fresh.copy(nodes = r.source.nodes.map { it.copy(bounds = shift(it.bounds)) },
            windows = r.source.windows.map { if (it.id == 20) it.copy(bounds = shift(it.bounds)) else it })
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.run())
        assertEquals(POLICY_RETAIN_TRANSLATION, r.row!!.demoBounds)
        assertEquals(0, r.removed); assertEquals(0, r.f.clicks); assertNull(r.raw)
    }
}
