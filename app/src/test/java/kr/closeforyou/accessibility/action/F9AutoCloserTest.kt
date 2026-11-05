package kr.closeforyou.accessibility.action

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.core.model.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class F9AutoCloserTest {
    internal class Fixture {
        var now = 1_000L
        val token = SnapshotToken(1, 20, "synthetic.app", "popup", "structure", "content", 1)
        val bounds = ScreenBounds(20, 20, 200, 200)
        val node = NodeSnapshot("0/0", "close", "android.widget.Button", "닫기", null,
            true, true, ScreenBounds(30, 130, 100, 180), persistentSetOf(NodeAction.CLICK))
        val windows = persistentListOf(
            WindowSnapshot(10, WindowType.APPLICATION, ScreenBounds(0, 0, 300, 500), false, false, 1),
            WindowSnapshot(20, WindowType.APPLICATION, bounds, true, true, 2))
        val snapshot = PopupSnapshot(token, now, persistentListOf(node), windows,
            ScreenBounds(0, 0, 300, 500), PopupTrigger.WINDOW_CHANGED, SnapshotCompleteness.COMPLETE)
        val candidate = CloseCandidate("c", node.path, node.viewId, node.text, node.bounds,
            EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE)
        val reference = F9Reference(snapshot, candidate, 1, 1)
        var state = F9RuntimeState(token, 1, 1, connected = true, collectionConsent = true,
            appAllowed = true, autoCloseConsent = true, presetAllowsAd = true, paused = false,
            userInteracting = false, cleanupReady = true)
        val before = F9Scene(token.packageName, 20, 10, snapshot.screenBounds, windows,
            snapshot.nodes, setOf(node.path), true, pageDigest = "unchanged-base-page")
        var scene: F9Scene? = before
        var after: F9Scene? = before.copy(windowId = 10, windows = persistentListOf(windows[0].copy(active = true, focused = true)),
            nodes = persistentListOf(), visiblePaths = emptySet())
        var clicks = 0
        var returned = true
        var beforePress: () -> Unit = {}
        var afterPress: () -> Unit = {}
        var approvalTransform: (F9Approval) -> F9Approval = { it }
        var authorize: suspend () -> Unit = {}
        val reported = mutableListOf<ActionResult>()
        val announced = mutableListOf<F9Result>()
        val port = object : F9ScreenPort {
            override fun read(checkpoint: () -> Unit): F9Scene? { checkpoint(); return if (clicks == 0) scene else after }
            override fun press(reference: F9Reference, accept: (F9Scene) -> Boolean): F9Press {
                beforePress()
                val current = scene ?: return F9Press(false, null)
                if (!accept(current)) return F9Press(false, null)
                clicks++
                afterPress()
                return F9Press(true, returned)
            }
        }
        val bridge = object : F9PolicyBridge {
            override suspend fun authorize(reference: F9Reference, scene: F9Scene): F9Approval {
                authorize()
                return approvalTransform(F9Approval(
                    ActionDecision(token, ActionAuthorization.ALLOW_ONCE, persistentSetOf(), candidate.id, source = DecisionSource.PRESET),
                    AutoEligibility(token, EligibilityCheck.entries.toPersistentSet(), snapshot.capturedAtUptimeMillis),
                    F9ApprovalOrigin.SYNTHETIC_TEST))
            }
            override fun onActionResult(result: ActionResult) { reported += result }
        }
        fun closer(dispatcher: CoroutineDispatcher, policy: F9PolicyBridge = bridge) = F9AutoCloser(
            port, { state }, F9Clock { now }, policy, { announced += it }, dispatcher,
            observationDelays = listOf(1L, 1L), allowSyntheticPolicy = true)
    }

    @Test fun closesOnlyAfterObservedRemovalAndReportsOneActualAttempt() = runTest {
        val f = Fixture(); val closer = f.closer(StandardTestDispatcher(testScheduler))
        val result = closer.execute(f.reference)
        assertTrue(result.attempted); assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
        assertEquals(1, f.clicks); assertEquals(listOf(result.toActionResult()), f.reported); assertEquals(listOf(result), f.announced)
        assertFalse(closer.execute(f.reference).attempted); assertEquals(1, f.clicks)
    }

    @Test fun clickReturnDoesNotEstablishClosure() = runTest {
        for (returned in listOf(true, false)) {
            val f = Fixture(); f.returned = returned; f.after = f.before
            val result = f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference)
            assertTrue(result.attempted); assertEquals(ClickOutcome.STILL_PRESENT, result.outcome)
            assertFalse(result.closedConfirmed)
        }
    }

    @Test fun missingPolicyNeverReadsOrClicksAndNeverReportsAnAttempt() = runTest {
        val f = Fixture()
        val result = f.closer(StandardTestDispatcher(testScheduler), F9PolicyBridge.UNAVAILABLE).execute(f.reference)
        assertFalse(result.attempted); assertEquals(F9Block.POLICY_UNAVAILABLE, result.block)
        assertEquals(0, f.clicks); assertTrue(f.reported.isEmpty())
    }

    @Test fun originalCaptureTtlHasStrictFiveSecondBoundary() = runTest {
        for ((age, clicks) in listOf(4_999L to 1, 5_000L to 0, 5_001L to 0, -1L to 0)) {
            val f = Fixture(); f.now += age
            assertEquals(clicks == 1, f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference).attempted)
            assertEquals(clicks, f.clicks)
        }
    }

    @Test fun changedRuntimeAndRevokedSettingsBlockAtLastPossibleMoment() = runTest {
        val changes: List<(F9RuntimeState) -> F9RuntimeState> = listOf(
            { it.copy(token = it.token?.copy(screenVersion = 2)) },
            { it.copy(token = it.token?.copy(captureEpoch = 2)) },
            { it.copy(token = it.token?.copy(windowId = 22)) },
            { it.copy(serviceGeneration = 2) }, { it.copy(policyRevision = 2) },
            { it.copy(connected = false) }, { it.copy(collectionConsent = false) },
            { it.copy(appAllowed = false) }, { it.copy(autoCloseConsent = false) },
            { it.copy(presetAllowsAd = false) }, { it.copy(paused = true) },
            { it.copy(userInteracting = true) }, { it.copy(cleanupReady = false) })
        for (change in changes) {
            val f = Fixture(); f.beforePress = { f.state = change(f.state) }
            val result = f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference)
            assertFalse(result.attempted); assertEquals(0, f.clicks); assertEquals(listOf(result.toActionResult()), f.reported)
        }
    }

    @Test fun nodeLabelGeometryCompletenessAndAmbiguityAreRechecked() = runTest {
        val changes: List<(Fixture) -> F9Scene> = listOf(
            { it.before.copy(nodes = persistentListOf(it.node.copy(text = "동의"))) },
            { it.before.copy(nodes = persistentListOf(it.node.copy(viewId = "other"))) },
            { it.before.copy(nodes = persistentListOf(it.node.copy(bounds = it.bounds))) },
            { it.before.copy(nodes = persistentListOf(it.node.copy(enabled = false))) },
            { it.before.copy(visiblePaths = emptySet()) }, { it.before.copy(complete = false) },
            { it.before.copy(nodes = persistentListOf(it.node, it.node.copy(path = "0/1"))) })
        for (change in changes) {
            val f = Fixture(); f.scene = change(f)
            assertFalse(f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference).attempted)
            assertEquals(0, f.clicks)
        }
    }

    @Test fun allFiveChecksAndOriginalCaptureTimeAreMandatory() = runTest {
        for (missing in EligibilityCheck.entries) {
            val f = Fixture(); f.approvalTransform = { it.copy(eligibility = it.eligibility.copy(checks = (it.eligibility.checks - missing).toPersistentSet())) }
            assertFalse(f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference).attempted)
        }
        val f = Fixture(); f.approvalTransform = { it.copy(eligibility = it.eligibility.copy(capturedAtUptimeMillis = f.now + 1)) }
        assertFalse(f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference).attempted)
    }

    @Test fun simultaneousRequestsAndFreshTokensForSamePopupCannotDoubleClick() = runTest {
        val f = Fixture(); f.authorize = { delay(10) }; val closer = f.closer(StandardTestDispatcher(testScheduler))
        val results = awaitAll(async { closer.execute(f.reference) }, async { closer.execute(f.reference) })
        assertEquals(1, results.count { it.attempted }); assertEquals(1, f.clicks)
        assertEquals(2, f.reported.size); assertEquals(1, f.reported.count { it.attempted })
        val other = f.reference.copy(snapshot = f.snapshot.copy(token = f.token.copy(popupInstanceId = "new")))
        f.state = f.state.copy(token = other.snapshot.token)
        assertFalse(closer.execute(other).attempted); assertEquals(1, f.clicks)
    }

    @Test fun cancellationBeforePressAndServiceStopPreventTheClick() = runTest {
        val f = Fixture(); val closer = f.closer(StandardTestDispatcher(testScheduler))
        f.authorize = { awaitCancellation() }
        val job = launch { closer.execute(f.reference) }; runCurrent(); job.cancelAndJoin()
        assertEquals(0, f.clicks); assertEquals(1, f.reported.size); assertFalse(f.reported.single().attempted)
        closer.close(); assertFalse(closer.execute(f.reference).attempted)
    }

    @Test fun cancellationAfterPressStillReportsAnUnknownActualAttempt() = runTest {
        val f = Fixture(); val closer = f.closer(StandardTestDispatcher(testScheduler))
        val job = launch { closer.execute(f.reference) }; runCurrent(); job.cancelAndJoin()
        assertEquals(1, f.clicks); assertEquals(1, f.reported.size)
        assertTrue(f.reported.single().attempted); assertEquals(ClickOutcome.UNKNOWN, f.reported.single().outcome)
    }

    @Test fun leftAppLocksOutThatAppUntilServiceEndsAndReplacementWindowsAreUnknown() = runTest {
        val f = Fixture(); f.after = f.before.copy(packageName = "other.app")
        val closer = f.closer(StandardTestDispatcher(testScheduler))
        assertEquals(ClickOutcome.LEFT_APP, closer.execute(f.reference).outcome)
        val next = f.reference.copy(snapshot = f.snapshot.copy(token = f.token.copy(structureDigest = "different")))
        f.state = f.state.copy(token = next.snapshot.token)
        assertEquals(F9Block.LEFT_APP_BEFORE, closer.execute(next).block); assertEquals(1, f.clicks)
        val g = Fixture(); g.after = g.before.copy(windowId = 99)
        assertEquals(ClickOutcome.UNKNOWN, g.closer(StandardTestDispatcher(testScheduler)).execute(g.reference).outcome)
    }

    @Test fun sensitiveMixedDecisionAndScamTextAreNeverPressed() = runTest {
        for (text in listOf("광고 결제", "광고 개인정보 동의", "광고 sign up하기", "allow알림", "당첨 즉시 설치", "virus detected", "송금", "비밀번호")) {
            assertTrue(text, F9ContentSafety.blocked(text))
            val f = Fixture()
            val nodes = persistentListOf(f.node, f.node.copy(path = "0/1", viewId = null,
                className = "android.widget.TextView", text = text, clickable = false, actions = persistentSetOf()))
            f.scene = f.before.copy(nodes = nodes)
            val r = f.reference.copy(snapshot = f.snapshot.copy(nodes = nodes))
            assertFalse(f.closer(StandardTestDispatcher(testScheduler)).execute(r).attempted)
            assertEquals(0, f.clicks)
        }
        assertTrue(F9ContentSafety.protectedPackage("com.kbstar.kbbank"))
        assertTrue(F9ContentSafety.protectedPackage("com.example.passwordmanager"))
    }

    @Test fun staleExpiryDuringPolicyAndCancellationAtCommitAreBlocked() = runTest {
        val f = Fixture(); f.authorize = { f.now += 5_000 }
        assertFalse(f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference).attempted)
        assertEquals(0, f.clicks)
        val g = Fixture(); val closer = g.closer(StandardTestDispatcher(testScheduler))
        lateinit var job: Job
        g.beforePress = { job.cancel() }
        job = launch(start = CoroutineStart.LAZY) { closer.execute(g.reference) }
        job.start(); job.join()
        assertEquals(0, g.clicks); assertEquals(1, g.reported.size); assertFalse(g.reported.single().attempted)
    }

    @Test fun absentOrExpiredPrePopupPageCannotStandInForCurrentBackground() = runTest {
        val f = Fixture()
        val windows = persistentListOf(f.windows[1])
        f.scene = f.before.copy(windows = windows, pageWindowId = 20, pageDigest = "popup-digest")
        val r = f.reference.copy(snapshot = f.snapshot.copy(windows = windows))
        assertFalse(f.closer(StandardTestDispatcher(testScheduler)).execute(r).attempted)
        val g = Fixture(); g.scene = g.before.copy(windows = windows, pageWindowId = 20, pageDigest = "popup-digest")
        g.now += 5_000
        val expired = r.copy(snapshot = r.snapshot.copy(capturedAtUptimeMillis = g.now),
            pageBeforePopup = F9PageProof(g.token.packageName, 10, "page", 1_000))
        assertFalse(g.closer(StandardTestDispatcher(testScheduler)).execute(expired).attempted)
    }

    @Test fun syntheticReceiptIsNotAcceptedByDefaultProductionExecutor() = runTest {
        val f = Fixture()
        val closer = F9AutoCloser(f.port, { f.state }, F9Clock { f.now }, f.bridge,
            dispatcher = StandardTestDispatcher(testScheduler))
        assertFalse(closer.execute(f.reference).attempted); assertEquals(0, f.clicks)
    }

    @Test fun v2AutomaticApprovalRequiresPresetSourceAndNeverAcceptsLegacyOrUserChoice() = runTest {
        for (source in listOf(null, DecisionSource.USER_CHOICE, DecisionSource.REMEMBERED_RULE)) {
            val f = Fixture(); f.approvalTransform = { it.copy(decision = it.decision.copy(source = source)) }
            assertFalse(f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference).attempted)
            assertEquals(0, f.clicks); assertEquals(1, f.reported.size); assertFalse(f.reported.single().attempted)
        }
    }

    @Test fun contractV2PresetReceiptUsesSharedEligibilityAndReportsSharedOutcome() = runTest {
        val f = Fixture(); f.approvalTransform = { it.copy(origin = F9ApprovalOrigin.CONTRACT_V2) }
        val closer = F9AutoCloser(f.port, { f.state }, F9Clock { f.now }, f.bridge,
            dispatcher = StandardTestDispatcher(testScheduler), observationDelays = listOf(1L))
        val result = closer.execute(f.reference)
        assertTrue(result.attempted); assertEquals(1, f.clicks)
        val shared = f.reported.single()
        assertEquals(result.token, shared.token)
        assertEquals(ClickOutcome.CLOSED_CONFIRMED, shared.outcome)
        assertTrue(shared.attempted); assertFalse(shared.semanticSafetyConfirmedByUser)
    }

    @Test fun observerFailureOrNewPageIsNeverMisreportedAsClosed() = runTest {
        val f = Fixture(); f.after = null
        assertEquals(ClickOutcome.UNKNOWN, f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference).outcome)
        val g = Fixture(); g.after = g.after!!.copy(pageDigest = "different-page")
        assertEquals(ClickOutcome.LEFT_APP, g.closer(StandardTestDispatcher(testScheduler)).execute(g.reference).outcome)
    }

    @Test fun blockedStaleAndDuplicateRequestsEachNotifyTheBridgeOnceWithoutPretendingToAttempt() = runTest {
        for (prepare in listOf<(Fixture) -> Unit>({ it.now += 5_000 }, { it.state = it.state.copy(autoCloseConsent = false) },
            { it.scene = null }, { it.beforePress = { it.state = it.state.copy(cleanupReady = false) } })) {
            val f = Fixture(); prepare(f)
            val result = f.closer(StandardTestDispatcher(testScheduler)).execute(f.reference)
            assertFalse(result.attempted); assertEquals(0, f.clicks)
            assertEquals(listOf(result.toActionResult()), f.reported)
            assertNull(f.reported.single().outcome)
        }
        val f = Fixture(); val closer = f.closer(StandardTestDispatcher(testScheduler))
        val first = closer.execute(f.reference); val duplicate = closer.execute(f.reference)
        assertEquals(listOf(first.toActionResult(), duplicate.toActionResult()), f.reported)
        assertEquals(1, f.clicks)
    }

    @Test fun cancellationBeforePressNotifiesBOnceAndPropagatesCancellation() = runTest {
        val f = Fixture(); f.authorize = { awaitCancellation() }
        val closer = f.closer(StandardTestDispatcher(testScheduler))
        val job = launch { closer.execute(f.reference) }; runCurrent(); job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(0, f.clicks)
        assertEquals(1, f.reported.size); assertFalse(f.reported.single().attempted)
        assertEquals(ActionStatus.NOT_EXECUTED, f.reported.single().status); assertNull(f.reported.single().outcome)
    }

    @Test fun resultReceiverFailureCannotRetryClickOrDeliverTheSameResultTwice() = runTest {
        val f = Fixture(); var deliveries = 0
        val bridge = object : F9PolicyBridge by f.bridge {
            override fun onActionResult(result: ActionResult) { deliveries++; throw IllegalStateException("synthetic receiver failure") }
            override fun onExecutionResult(result: ActionResult, policyConsulted: Boolean) = onActionResult(result)
        }
        val result = f.closer(StandardTestDispatcher(testScheduler), bridge).execute(f.reference)
        assertTrue(result.closedConfirmed); assertEquals(1, deliveries); assertEquals(1, f.clicks)
    }

    @Test fun cancellationBeforeMainDispatchStillDeliversOneNoPressResult() = runTest {
        val f = Fixture(); val closer = f.closer(StandardTestDispatcher(testScheduler))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { closer.execute(f.reference) }
        job.cancelAndJoin()
        assertEquals(0, f.clicks); assertEquals(1, f.reported.size)
        assertFalse(f.reported.single().attempted); assertNull(f.reported.single().outcome)
        assertEquals(ActionStatus.NOT_EXECUTED, f.reported.single().status)
    }
}
