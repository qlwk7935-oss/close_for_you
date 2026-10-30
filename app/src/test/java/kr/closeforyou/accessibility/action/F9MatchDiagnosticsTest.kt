package kr.closeforyou.accessibility.action

import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.integration.*
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

/** Actual executor/matches/terminal trace; only Android reads/actions and clock are fake ports. */
@OptIn(ExperimentalCoroutinesApi::class)
class F9MatchDiagnosticsTest {
    private class Rig {
        val f = F9AutoCloserTest.Fixture()
        var reference = f.reference
        val rows = mutableListOf<ManualActionCode>()
        val trace = ManualActionTrace(rows::add)
        var reads = 0; var presses = 0; var policies = 0; var clocks = 0
        var time: () -> Long = { f.now }
        var read: (() -> F9Scene?)? = null
        val port = object : F9ScreenPort {
            override fun read(checkpoint: () -> Unit): F9Scene? {
                reads++; checkpoint(); return read?.invoke() ?: f.port.read(checkpoint)
            }
            override fun press(reference: F9Reference, accept: (F9Scene) -> Boolean): F9Press {
                presses++; return f.port.press(reference, accept)
            }
        }
        val bridge = object : F9PolicyBridge {
            override suspend fun authorize(reference: F9Reference, scene: F9Scene): F9Approval {
                policies++
                return F9Approval(ActionDecision(reference.snapshot.token, ActionAuthorization.ALLOW_ONCE,
                    persistentSetOf(), reference.candidate.id,
                    source = if (reference.userChoice == null) DecisionSource.PRESET else DecisionSource.USER_CHOICE),
                    AutoEligibility(reference.snapshot.token, EligibilityCheck.entries.toPersistentSet(), reference.snapshot.capturedAtUptimeMillis),
                    F9ApprovalOrigin.SYNTHETIC_TEST)
            }
            override fun onActionResult(result: ActionResult) { f.reported += result }
        }
        fun closer(scope: TestScope) = F9AutoCloser(port, { f.state }, F9Clock { clocks++; time() }, bridge,
            dispatcher = StandardTestDispatcher(scope.testScheduler), observationDelays = listOf(1L), allowSyntheticPolicy = true)
        fun nodes(values: List<NodeSnapshot>) {
            reference = reference.copy(snapshot = reference.snapshot.copy(nodes = values.toPersistentList()))
            f.scene = f.scene!!.copy(nodes = values)
        }
        fun windows(values: List<WindowSnapshot>) {
            reference = reference.copy(snapshot = reference.snapshot.copy(windows = values.toPersistentList()))
            f.scene = f.scene!!.copy(windows = values)
        }
        fun hiddenPage() {
            windows(listOf(f.windows[1])); f.scene = f.scene!!.copy(pageWindowId = f.token.windowId)
            reference = reference.copy(pageBeforePopup = F9PageProof(f.token.packageName, 10, f.before.pageDigest!!, 1_000))
        }
        fun manual() {
            reference = reference.copy(userChoice = UserChoice(reference.snapshot.token, ChoiceKind.CLOSE_ONCE, reference.candidate.id, true))
        }
    }
    private data class Case(val failure: F9MatchFailure, val change: (Rig) -> Unit)
    private fun cases(): List<Case> = listOf(
        Case(F9MatchFailure.SCENE_INCOMPLETE) { it.f.scene = it.f.before.copy(complete = false) },
        Case(F9MatchFailure.SNAPSHOT_INCOMPLETE) { it.reference = it.reference.copy(snapshot = it.f.snapshot.copy(completeness = SnapshotCompleteness.TRUNCATED)) },
        Case(F9MatchFailure.PACKAGE_MISMATCH) { it.f.scene = it.f.before.copy(packageName = "synthetic.other") },
        Case(F9MatchFailure.PROTECTED_PACKAGE) {
            val token = it.f.token.copy(packageName = "synthetic.bank")
            it.reference = it.reference.copy(snapshot = it.f.snapshot.copy(token = token)); it.f.state = it.f.state.copy(token = token)
            it.f.scene = it.f.before.copy(packageName = token.packageName)
        },
        Case(F9MatchFailure.WINDOW_ID_MISMATCH) { it.f.scene = it.f.before.copy(windowId = 21) },
        Case(F9MatchFailure.PAGE_DIGEST_UNAVAILABLE) { it.f.scene = it.f.before.copy(pageDigest = null) },
        Case(F9MatchFailure.SCREEN_BOUNDS_MISMATCH) { it.f.scene = it.f.before.copy(screen = ScreenBounds(0, 0, 301, 500)) },
        Case(F9MatchFailure.NODES_MISMATCH) { it.f.scene = it.f.before.copy(nodes = listOf(it.f.node.copy(enabled = false))) },
        Case(F9MatchFailure.WINDOWS_MISMATCH) { it.f.scene = it.f.before.copy(windows = it.f.windows.reversed()) },
        Case(F9MatchFailure.NODE_COUNT_INVALID) { it.nodes(emptyList()) },
        Case(F9MatchFailure.PROTECTED_TEXT) { it.nodes(listOf(it.f.node.copy(contentDescription = "구매"))) },
        Case(F9MatchFailure.TARGET_WINDOW_UNAVAILABLE) { it.windows(emptyList()) },
        Case(F9MatchFailure.TARGET_WINDOW_TYPE) { it.windows(listOf(it.f.windows[0], it.f.windows[1].copy(type = WindowType.SYSTEM))) },
        Case(F9MatchFailure.TARGET_WINDOW_INACTIVE) { it.windows(listOf(it.f.windows[0], it.f.windows[1].copy(active = false))) },
        Case(F9MatchFailure.TARGET_WINDOW_UNFOCUSED) { it.windows(listOf(it.f.windows[0], it.f.windows[1].copy(focused = false))) },
        Case(F9MatchFailure.TARGET_WINDOW_OUTSIDE_FRAME) { it.windows(listOf(it.f.windows[0], it.f.windows[1].copy(bounds = ScreenBounds(-1, 20, 200, 200)))) },
        Case(F9MatchFailure.PAGE_PROOF_UNAVAILABLE) { it.hiddenPage(); it.reference = it.reference.copy(pageBeforePopup = null) },
        Case(F9MatchFailure.PAGE_PACKAGE_MISMATCH) { it.hiddenPage(); it.reference = it.reference.copy(pageBeforePopup = it.reference.pageBeforePopup!!.copy(packageName = "synthetic.other")) },
        Case(F9MatchFailure.PAGE_WINDOW_EQUALS_POPUP) { it.hiddenPage(); it.reference = it.reference.copy(pageBeforePopup = it.reference.pageBeforePopup!!.copy(windowId = it.f.token.windowId)) },
        Case(F9MatchFailure.PAGE_DIGEST_BLANK) { it.hiddenPage(); it.reference = it.reference.copy(pageBeforePopup = it.reference.pageBeforePopup!!.copy(digest = " ")) },
        Case(F9MatchFailure.PAGE_TIMESTAMP_NEGATIVE) { it.hiddenPage(); it.reference = it.reference.copy(pageBeforePopup = it.reference.pageBeforePopup!!.copy(capturedAtUptimeMillis = -1)) },
        Case(F9MatchFailure.PAGE_TIMESTAMP_AFTER_CAPTURE) { it.hiddenPage(); it.reference = it.reference.copy(pageBeforePopup = it.reference.pageBeforePopup!!.copy(capturedAtUptimeMillis = 1_001)) },
        Case(F9MatchFailure.PAGE_TIMESTAMP_IN_FUTURE) { r -> r.hiddenPage(); r.time = { if (r.clocks == 1) 1_000 else 999 } },
        Case(F9MatchFailure.PAGE_PROOF_EXPIRED) { it.hiddenPage(); it.f.now = 7_000; it.reference = it.reference.copy(snapshot = it.reference.snapshot.copy(capturedAtUptimeMillis = it.f.now)) },
        Case(F9MatchFailure.FULLSCREEN_WITH_HIDDEN_PAGE) { it.hiddenPage(); it.windows(listOf(it.f.windows[1].copy(bounds = it.f.snapshot.screenBounds))) },
        Case(F9MatchFailure.TARGET_NODE_UNAVAILABLE) { it.reference = it.reference.copy(candidate = it.f.candidate.copy(nodePath = "missing")) },
        Case(F9MatchFailure.CLOSE_NODE_COUNT) { it.nodes(listOf(it.f.node, it.f.node.copy(path = "0/1"))) },
        Case(F9MatchFailure.CLOSE_NODE_MISMATCH) {
            it.nodes(listOf(it.f.node, it.f.node.copy(path = "0/1", text = "본문", clickable = false)))
            it.reference = it.reference.copy(candidate = it.f.candidate.copy(nodePath = "0/1"))
        },
        Case(F9MatchFailure.CANDIDATE_EVIDENCE) { it.reference = it.reference.copy(candidate = it.f.candidate.copy(evidence = EvidenceLevel.E4_VISUAL_ONLY)) },
        Case(F9MatchFailure.CANDIDATE_ROLE) { it.reference = it.reference.copy(candidate = it.f.candidate.copy(role = ButtonRole.UNKNOWN)) },
        Case(F9MatchFailure.CANDIDATE_LABEL) { it.reference = it.reference.copy(candidate = it.f.candidate.copy(label = "확인")) },
        Case(F9MatchFailure.CANDIDATE_VIEW_ID) { it.reference = it.reference.copy(candidate = it.f.candidate.copy(viewId = "other")) },
        Case(F9MatchFailure.CANDIDATE_BOUNDS) { it.reference = it.reference.copy(candidate = it.f.candidate.copy(bounds = it.f.bounds)) },
        Case(F9MatchFailure.NODE_DISABLED) { it.nodes(listOf(it.f.node.copy(enabled = false))) },
        Case(F9MatchFailure.WIDGET_UNSUPPORTED) { it.nodes(listOf(it.f.node.copy(className = "android.view.View"))) },
        Case(F9MatchFailure.NODE_NOT_VISIBLE) { it.f.scene = it.f.before.copy(visiblePaths = emptySet()) },
        Case(F9MatchFailure.CLICK_ACTION_UNAVAILABLE) { it.nodes(listOf(it.f.node.copy(actions = persistentSetOf()))) },
        Case(F9MatchFailure.NODE_OUTSIDE_WINDOW) {
            val node = it.f.node.copy(bounds = ScreenBounds(0, 0, 10, 10)); it.nodes(listOf(node))
            it.reference = it.reference.copy(candidate = it.f.candidate.copy(bounds = node.bounds))
        },
        Case(F9MatchFailure.LABEL_NOT_PRESENT) { it.reference = it.reference.copy(candidate = it.f.candidate.copy(label = "창 닫기")) },
        Case(F9MatchFailure.LABELS_NOT_ALL_CLOSE) { it.nodes(listOf(it.f.node.copy(contentDescription = "도움말"))) }
    )
    @Test fun fortyReachableFirstFailuresComeFromActualExecutorAndCannotAuthorizeOrPress() = runTest {
        assertEquals(40, cases().size)
        for (case in cases()) {
            val r = Rig(); case.change(r)
            val result = r.closer(this).execute(r.reference, trace = r.trace)
            assertEquals(case.failure.name, F9Block.SCREEN_UNVERIFIED, result.block)
            assertFalse(case.failure.name, result.attempted)
            assertEquals(case.failure.name, listOf(case.failure), r.rows.mapNotNull { it.screenMatch })
            assertEquals(1, r.reads); assertEquals(0, r.presses); assertEquals(0, r.policies); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun disabledDiagnosticsKeepTheSameFortyRejectionsWithoutAdditionalReadsOrClockCalls() = runTest {
        for (case in cases()) {
            val debug = Rig(); case.change(debug)
            val silent = Rig(); case.change(silent)
            val a = debug.closer(this).execute(debug.reference, trace = debug.trace)
            val b = silent.closer(this).execute(silent.reference)
            assertEquals(case.failure.name, a.block, b.block); assertEquals(a.attempted, b.attempted)
            assertEquals(debug.reads, silent.reads); assertEquals(debug.clocks, silent.clocks)
            assertTrue(silent.rows.isEmpty()); assertEquals(0, silent.f.clicks)
        }
    }
    @Test fun firstFailureWinsOverLaterNodeWindowAndExpiredPageConditions() = runTest {
        val r = Rig(); r.hiddenPage(); r.f.scene = r.f.scene!!.copy(nodes = emptyList(), windows = emptyList())
        r.f.now = 7_000; r.reference = r.reference.copy(snapshot = r.reference.snapshot.copy(capturedAtUptimeMillis = r.f.now))
        r.closer(this).execute(r.reference, trace = r.trace)
        assertEquals(listOf(F9MatchFailure.NODES_MISMATCH), r.rows.mapNotNull { it.screenMatch })
        assertEquals(1, r.clocks) // hidden-page clock must NOT be read after an earlier mismatch
    }
    @Test fun sixSecondManualSelectionDoesNotRenewTheIndependentPrePopupPageProof() = runTest {
        val r = Rig(); r.hiddenPage(); r.manual(); r.f.now = 7_000
        r.reference = r.reference.copy(snapshot = r.reference.snapshot.copy(capturedAtUptimeMillis = r.f.now))
        val result = r.closer(this).execute(r.reference, trace = r.trace)
        assertEquals(F9Block.SCREEN_UNVERIFIED, result.block); assertEquals(0, r.f.clicks)
        assertEquals(listOf(F9MatchFailure.PAGE_PROOF_EXPIRED), r.rows.mapNotNull { it.screenMatch })
        assertEquals(1_000L, r.reference.pageBeforePopup!!.capturedAtUptimeMillis)
    }
    @Test fun pageProofHasStrictOriginal4999And5000BoundaryWithFreshPopupEvidence() = runTest {
        for ((age, attempted) in listOf(4_999L to true, 5_000L to false, 5_001L to false)) {
            val r = Rig(); r.hiddenPage(); r.manual(); r.f.now = 1_000 + age
            r.reference = r.reference.copy(snapshot = r.reference.snapshot.copy(capturedAtUptimeMillis = r.f.now))
            val result = r.closer(this).execute(r.reference, trace = r.trace)
            assertEquals(attempted, result.attempted); assertEquals(if (attempted) 1 else 0, r.f.clicks)
            assertEquals(if (attempted) emptyList() else listOf(F9MatchFailure.PAGE_PROOF_EXPIRED), r.rows.mapNotNull { it.screenMatch })
        }
    }
    @Test fun currentSeparateBackgroundDoesNotReadOrRequireAnOldPageProof() = runTest {
        for (diagnostics in listOf(true, false)) {
            val r = Rig(); r.manual(); r.f.now = 7_000
            r.reference = r.reference.copy(snapshot = r.reference.snapshot.copy(capturedAtUptimeMillis = r.f.now),
                pageBeforePopup = F9PageProof(r.f.token.packageName, 10, "old", 1_000))
            val result = r.closer(this).execute(r.reference, trace = r.trace.takeIf { diagnostics })
            assertTrue(result.attempted); assertTrue(result.closedConfirmed); assertEquals(1, r.f.clicks)
            assertTrue(r.rows.none { it.screenMatch != null })
        }
    }
    @Test fun exactWindowListRetainsOrderingFocusLayerAndOverlayMembershipChecks() = runTest {
        val transforms: List<(Rig) -> List<WindowSnapshot>> = listOf(
            { it.f.windows.reversed() }, { it.f.windows.map { w -> w.copy(layer = w.layer + 1) } },
            { it.f.windows.map { w -> w.copy(focused = !w.focused) } },
            { it.f.windows + WindowSnapshot(30, WindowType.ACCESSIBILITY_OVERLAY, it.f.bounds, false, false, 3) })
        for (change in transforms) {
            val r = Rig(); r.f.scene = r.f.before.copy(windows = change(r))
            assertFalse(r.closer(this).execute(r.reference, trace = r.trace).attempted)
            assertEquals(listOf(F9MatchFailure.WINDOWS_MISMATCH), r.rows.mapNotNull { it.screenMatch })
            assertEquals(0, r.f.clicks)
        }
    }
    @Test fun duplicateTargetWindowsAndNodesNeverPassUniqueLookup() = runTest {
        val r = Rig(); r.windows(listOf(r.f.windows[1], r.f.windows[1]))
        r.closer(this).execute(r.reference, trace = r.trace)
        assertEquals(listOf(F9MatchFailure.TARGET_WINDOW_UNAVAILABLE), r.rows.mapNotNull { it.screenMatch })
        val s = Rig(); s.nodes(listOf(s.f.node, s.f.node))
        s.closer(this).execute(s.reference, trace = s.trace)
        assertEquals(listOf(F9MatchFailure.TARGET_NODE_UNAVAILABLE), s.rows.mapNotNull { it.screenMatch })
    }
    @Test fun earlierUniqueClickableCloseGuardDominatesNonclickableAndEmptyLabelConditions() = runTest {
        for (node in listOf(Rig().f.node.copy(clickable = false), Rig().f.node.copy(text = null, contentDescription = null))) {
            val r = Rig(); r.nodes(listOf(node))
            assertFalse(r.closer(this).execute(r.reference, trace = r.trace).attempted)
            assertEquals(listOf(F9MatchFailure.CLOSE_NODE_COUNT), r.rows.mapNotNull { it.screenMatch })
        }
    }
    @Test fun finalPressMatchFailureIsRecordedOnlyAfterAcceptanceReturnsAndNeverClicks() = runTest {
        val r = Rig(); r.hiddenPage()
        // New popup remains fresh while original page expires in the final callback.
        r.reference = r.reference.copy(snapshot = r.reference.snapshot.copy(capturedAtUptimeMillis = 2_000))
        r.f.now = 2_000
        val sizeAtPress = intArrayOf(-1)
        r.f.beforePress = { sizeAtPress[0] = r.rows.size; r.f.now = 6_000 }
        val result = r.closer(this).execute(r.reference, trace = r.trace)
        assertFalse(result.attempted); assertEquals(0, r.f.clicks)
        assertEquals(listOf(F9MatchFailure.PAGE_PROOF_EXPIRED), r.rows.mapNotNull { it.screenMatch })
        assertTrue(r.rows.size > sizeAtPress[0])
    }
    @Test fun successfulFinalAcceptanceHasNoDiagnosticIoBeforeTheSingleAction() = runTest {
        val r = Rig(); var count = -1
        r.f.beforePress = { count = r.rows.size }
        r.f.afterPress = { assertEquals(count, r.rows.size) }
        val closer = r.closer(this)
        assertTrue(closer.execute(r.reference, trace = r.trace).closedConfirmed)
        assertFalse(closer.execute(r.reference, trace = r.trace).attempted)
        assertEquals(1, r.f.clicks); assertTrue(r.rows.none { it.screenMatch != null })
    }
    @Test fun throwingOrCancellingDiagnosticSinkCannotTurnMismatchIntoSuccess() = runTest {
        for (cancel in listOf(true, false)) {
            val r = Rig(); r.hiddenPage(); r.reference = r.reference.copy(pageBeforePopup = null)
            val trace = ManualActionTrace { if (cancel) throw CancellationException("SYNTHETIC") else error("SYNTHETIC") }
            val result = r.closer(this).execute(r.reference, trace = trace)
            assertEquals(F9Block.SCREEN_UNVERIFIED, result.block); assertFalse(result.attempted); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun cancelledReadPropagatesWithoutInventingAMatchFailureOrAttempt() = runTest {
        val r = Rig(); val cancel = CancellationException("SYNTHETIC")
        r.read = { throw cancel }
        val failed = runCatching { r.closer(this).execute(r.reference, trace = r.trace) }.exceptionOrNull()
        assertTrue(failed is CancellationException)
        assertTrue(failed === cancel || failed?.cause === cancel); assertEquals(0, r.f.clicks)
        assertTrue(r.rows.none { it.screenMatch != null })
        assertTrue(r.rows.any { it.block == F9Block.CANCELLED })
    }
    @Test fun clockFailureInsideMatchIsReadFailedRatherThanAFalseMismatchDiagnosis() = runTest {
        val r = Rig(); r.hiddenPage(); r.time = { if (r.clocks == 1) 1_000 else error("SYNTHETIC") }
        val result = r.closer(this).execute(r.reference, trace = r.trace)
        assertEquals(F9Block.READ_FAILED, result.block); assertEquals(0, r.f.clicks)
        assertTrue(r.rows.none { it.screenMatch != null })
    }
    @Test fun matchFailureIsInvocationLocalAndCannotLeakIntoTheNextRejectedExecution() = runTest {
        val r = Rig(); r.hiddenPage(); r.reference = r.reference.copy(pageBeforePopup = null)
        val closer = r.closer(this)
        closer.execute(r.reference, trace = r.trace)
        val later = mutableListOf<ManualActionCode>()
        assertEquals(F9Block.ALREADY_RESERVED, closer.execute(r.reference, trace = ManualActionTrace(later::add)).block)
        assertTrue(later.none { it.screenMatch != null }); assertEquals(0, r.f.clicks)
    }
    @Test fun unchangedLazyPageClockBoundaryAddsNoDiagnosticTimeRead() = runTest {
        val ordinary = Rig()
        assertTrue(ordinary.closer(this).execute(ordinary.reference, trace = ordinary.trace).attempted)
        assertEquals(4, ordinary.clocks)
        val hidden = Rig(); hidden.hiddenPage()
        assertTrue(hidden.closer(this).execute(hidden.reference, trace = hidden.trace).attempted)
        assertEquals(6, hidden.clocks) // original hidden-page clock once in each matches call
        val rejected = Rig(); rejected.hiddenPage()
        rejected.reference = rejected.reference.copy(pageBeforePopup = rejected.reference.pageBeforePopup!!.copy(packageName = "synthetic.other"))
        rejected.closer(this).execute(rejected.reference, trace = rejected.trace)
        assertEquals(2, rejected.clocks) // original clock read precedes page's first predicate
    }
    @Test fun diagnosticRecordContainsOnlyEnumsAndCannotRetainInputIdentityOrText() {
        val rows = mutableListOf<ManualActionCode>()
        ManualActionTrace(rows::add).record(ManualActionStep.F9_VALIDATION, ManualActionState.REJECTED,
            block = F9Block.SCREEN_UNVERIFIED, screenMatch = F9MatchFailure.PAGE_PROOF_EXPIRED)
        assertEquals(F9MatchFailure.PAGE_PROOF_EXPIRED, rows.single().screenMatch)
        assertTrue(ManualActionCode::class.java.getDeclaredField("screenMatch").type.isEnum)
        assertTrue(ManualActionCode::class.java.declaredFields.filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) }.all {
            it.type.isEnum || it.type == F9EligibilityCode::class.java
        })
    }
}
