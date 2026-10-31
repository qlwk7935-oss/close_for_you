package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent as E
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.contract.*
import kr.closeforyou.accessibility.NativeActionSummary
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.*
import kr.closeforyou.ui.voice.*
import kr.closeforyou.ui.voice.choice.*
import kr.closeforyou.ui.voice.integration.*
import kr.closeforyou.ui.voice.overlay.*
import kr.closeforyou.ui.voice.settings.AnnouncementDetail
import org.junit.Assert.*
import org.junit.Test

/** Real C Flow/planner/keeper + A guard/lease/barrier/refresh/session + real B policy.
 * Only native windows, collector DTOs, clock and TTS are fake ports. Not a Samsung test. */
@OptIn(ExperimentalCoroutinesApi::class)
class P01SamsungSequenceRegressionTest {
    private class Rig(val scope: TestScope) {
        val f = F9AutoCloserTest.Fixture()
        fun now() = 1_000L + scope.testScheduler.currentTime
        val body = f.node.copy(path = "0/1", className = "android.widget.TextView", viewId = "synthetic-body",
            text = "광고 신상 안내", clickable = false, actions = persistentSetOf())
        val original = f.snapshot.copy(nodes = persistentListOf(f.node, body))
        var offered = original
        var rawToken: SnapshotToken? = original.token
        var valid = true; var selected = false; var quiet = CompletableDeferred<Unit>()
        var access = F9SessionAccess(true, true, true, false, false, announcementAvailable = true)
        val registration = ManualDisplayRegistration()
        val lifetime = ManualPromptLifetime(::now)
        val health = ChoicePresentationHealth()
        val actions = mutableListOf<ManualActionCode>(); val reads = mutableListOf<F9ReadCode>()
        val failures = mutableListOf<CleanupFailure>(); val results = mutableListOf<ActionResult>()
        val speech = mutableListOf<ManualSpeechState>(); val order = mutableListOf<String>()
        val barrier = OverlayCleanupBarrier(clock = ::now, onUncertain = health::fail,
            failureAttemptDiagnostic = { code, _ -> failures += code })
        val action = ManualActionTrace(actions::add)
        var freshReads = 0; var decisionCalls = 0; var shows = 0
        var blockPurpose: F9ReadPurpose? = null
        var collection: (PopupSnapshot) -> PopupSnapshot? = { it }
        var removeFails = false
        var native: OverlayNativeWindowLifetime? = null
        val owner = Any(); var nativeId = 0; var detached = false
        var prompt: ChoicePrompt? = null; var answer: ((PromptAnswer) -> Unit)? = null
        var onDecision: () -> Unit = {}
        private val engine = object : SpeechEngine {
            override val readiness = MutableStateFlow(SpeechReadiness.READY)
            var finish = CompletableDeferred<Unit>()
            override suspend fun speak(text: String): Boolean { order += "TTS_START"; finish.await(); order += "TTS_RETURN"; return true }
            override fun stop() { order += "TTS_STOP" }
            override fun shutdown() = stop()
        }
        val tts = TtsPopupAnnouncer(engine, "SYNTHETIC")
        val announcer = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
                if (request.kind == AnnouncementKind.ACTION_RESULT) return AnnouncementReceipt(AnnouncementState.TTS_COMPLETED)
                return announceManualPrompt(engine.readiness, { valid && lifetime.current() == request.token }, speech::add) { tts.announce(request) }
            }
            override suspend fun repeatLast() = tts.repeatLast()
            override fun stop() = tts.stop()
        }
        val listener = PromptAnnouncementListener(announcer, scope.backgroundScope,
            { PromptAnnounceMode.PANE_TITLE_AND_TTS }, 700L, { AnnouncementDetail.SHORT }, { _, _ -> "SYNTHETIC" })
        private data class Native(val prompt: ChoicePrompt, val n: OverlayNativeWindowLifetime)
        private val keeper = OverlayWindowKeeper(object : OverlayWindowOps<Native> {
            override fun add(window: Native) { order += "ADD"; window.n.attached(nativeId); window.n.addReturned(true) }
            override fun announceShown(window: Native) { listener.onShown(window.prompt) { false } }
            override fun announceDismissed(window: Native) {
                order += "REMOVE_REQUEST"; window.n.removalRequested()
                barrier.retiring(null, window.n.generation, offered.token.windowId, window.n, owner)
                listener.onDismissed()
            }
            override fun stop(window: Native) = Unit
            override fun remove(window: Native, immediate: Boolean): RemoveOutcome {
                window.n.removalReturned(!removeFails)
                return if (removeFails) RemoveOutcome.FAILED else RemoveOutcome.REMOVED
            }
            override fun blank(window: Native) = Unit
            override fun destroy(window: Native) = Unit
            override fun enterRevalidating(window: Native) = error("REVALIDATING_OFF")
        })
        private val delegate = object : ChoicePromptPresenter {
            override val windowState get() = OverlayWindowState(keeper.status, keeper.windowGeneration)
            override val isShowing get() = keeper.status == OverlayWindowStatus.SHOWING
            override fun present(value: ChoicePrompt, receiver: (PromptAnswer) -> Unit): PromptShowResult {
                val result = keeper.show { generation ->
                    registration.created(generation); lifetime.nativeCreating(generation)
                    nativeId = 30 + generation.toInt(); detached = false
                    val n = OverlayNativeWindowLifetime(generation, owner); native = n
                    Native(value, n)
                }
                if (result == PromptShowResult.SHOWN) {
                    shows++; prompt = value; answer = receiver; lifetime.shown(value.token, windowState); order += "SHOWN"
                }
                return result
            }
            override fun dismiss() {
                keeper.dismiss()
                if (barrier.pending) barrier.removalReturned(keeper.status == OverlayWindowStatus.HIDDEN)
            }
        }
        val guarded = GuardedChoicePresenter(delegate, { lifetime.current() ?: rawToken }, { access.userInteracting }, health) { barrier.fail() }
        private val presenter = object : ChoicePromptPresenter by guarded {
            override fun present(prompt: ChoicePrompt, receiver: (PromptAnswer) -> Unit) = guarded.present(prompt) {
                if (it is PromptAnswer.Picked && it.choice.kind == ChoiceKind.CLOSE_ONCE) { selected = true; order += "CHOICE" }
                receiver(it)
            }
        }
        private fun blockedRead(purpose: F9ReadPurpose): Boolean {
            if (blockPurpose != purpose) return false
            val r = F9ReadObservation(purpose, reads::add)
            r.stage = F9ReadStage.OCCLUSION
            val blocked = f9OccludingWindow({ false }, { false }, { WindowType.SYSTEM }, { 5 to 2 },
                { ScreenBounds(0, 0, 300, 40) }, original.windows[1].bounds, original.screenBounds, purpose != F9ReadPurpose.PRESS,
                { error("BOUNDED_PASSIVE_NOT_QUERIED") }, r::occluding)
            check(blocked); r.reject(F9ReadDetail.OCCLUDING_WINDOW); r.report()
            return true
        }
        private val port = object : F9ScreenPort {
            override fun read(checkpoint: () -> Unit) = f.port.read(checkpoint)
            override fun readFor(purpose: F9ReadPurpose, checkpoint: () -> Unit): F9Scene? {
                checkpoint(); order += purpose.name
                return if (blockedRead(purpose)) null else f.port.read(checkpoint)
            }
            override fun press(reference: F9Reference, accept: (F9Scene) -> Boolean): F9Press {
                order += "PRESS"
                return if (blockedRead(F9ReadPurpose.PRESS)) F9Press(false, null) else f.port.press(reference, accept)
            }
        }
        private val raw = F9CollectedSource(::now)
        private val policy = DecisionActionPolicy(allowAutoSkip = false, nowUptimeMillis = ::now)
        val session = F9ServiceSession(raw, port, F9Clock(::now), { access }, { barrier.ready }, {},
            policyFactory = { object : ActionPolicy by policy {
                override suspend fun decide(analysis: PopupAnalysis, context: DecisionContext) = policy.decide(analysis, context).also {
                    decisionCalls++; order += "B_" + it.next.name; onDecision()
                }
            } }, dispatcher = StandardTestDispatcher(scope.testScheduler))
        val stream = MutableSharedFlow<PopupEvent>(extraBufferCapacity = 1)
        private val source = object : SnapshotSource {
            override val events = stream
            override fun currentToken() = lifetime.current() ?: rawToken
        }
        private val wiring = DecisionCandidateWiring(source, object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot) = PopupAnalysis(snapshot.token, texts = F9CollectedSource.texts(snapshot))
        })
        private val gate = ChoicePresentationGate(::now, { source.currentToken() == it }, barrier::permits,
            { true }, { baseline, checkpoint -> checkpoint(); EvidenceSnapshotComparison.firstMismatch(baseline, offered) == EvidenceMismatch.NONE }, {})
        private val budget = ChoiceReviewBudget(boundGeometryRecovery = ManualPresentationStabilityFeature.enabled)
        private val flow = PopupChoiceFlow(wiring.source, object : EvidenceExtractor {
            override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
                val analysis = wiring.extractor.extract(snapshot); check(gate.awaitReady(snapshot.token)); return analysis
            }
        }, DecisionPopupClassifier(), presenter, announcer, AnnouncementBatcher(scope.backgroundScope, onFlush = {}),
            ChoiceHandler { choice, analysis ->
                order += "HANDLER"
                val refreshed = ManualChoiceRefresh.afterRemoval(offered, analysis, choice,
                    PopupDetection(offered.token, persistentSetOf(PopupSignal.NEW_WINDOW)), ::now,
                    { valid && !health.failed }, { barrier.awaitObservationPermit(); order += "ABSENT" },
                    { quiet.await(); order += "QUIET" }, action) { checkpoint ->
                        checkpoint(); freshReads++; order += "F0"
                        val fresh = offered.copy(token = offered.token.copy(screenVersion = offered.token.screenVersion + 1,
                            captureEpoch = offered.token.screenVersion + 1, popupInstanceId = "synthetic-fresh"), capturedAtUptimeMillis = now())
                        collection(fresh)?.also { f.scene = f.before.copy(nodes = it.nodes, windows = it.windows) }
                    }
                val result = if (refreshed == null) ActionResult(choice.token, ActionStatus.NOT_EXECUTED, SafetyReason.UNVERIFIED_TARGET)
                    else session.executeUser(refreshed.snapshot, refreshed.analysis, refreshed.choice, refreshed.detection, action) {
                        valid && !health.failed && barrier.ready
                    }
                results += result; result
            }, UiText { "SYNTHETIC" }, { "SYNTHETIC" }, rememberChoicesEnabled = false, revalidatingEnabled = false)
        private val job = scope.backgroundScope.launch(UnconfinedTestDispatcher(scope.testScheduler)) { flow.run() }
        suspend fun show(snapshot: PopupSnapshot = original): Boolean {
            if (!budget.admit(snapshot.token)) return false
            offered = snapshot; rawToken = snapshot.token; gate.prepare(snapshot, requireNotNull(barrier.permit()))
            stream.emit(PopupEvent.Candidate(snapshot, PopupDetection(snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW))))
            scope.runCurrent(); return guarded.isShowing
        }
        fun completeSpeech() { engine.finish.complete(Unit) }
        fun choose(): Pair<PromptAnswer, (PromptAnswer) -> Unit> {
            val p = requireNotNull(prompt); val cb = requireNotNull(answer)
            val choice = ChoicePromptPlanner.toUserChoice(p, p.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
            val picked = PromptAnswer.Picked(choice, ChoiceOrigin.TAP, keeper.currentGeneration)
            keeper.deliver(picked, true, cb)
            if (barrier.pending) barrier.removalReturned(keeper.status == OverlayWindowStatus.HIDDEN)
            return picked to cb
        }
        fun removal(presence: CleanupWindowPresence = CleanupWindowPresence.ABSENT, detach: Boolean = true): Boolean {
            val n = requireNotNull(native)
            if (detach && !detached) { detached = true; n.detached() }
            return barrier.nativeProcessed(n, keeper.status == OverlayWindowStatus.HIDDEN) { presence }
        }
        fun expireOriginal() { rawToken = null; gate.clear() }
        fun ownedIds(includeNative: Boolean, ownedRoot: Boolean = true, probe: ManualOwnedReadProbe? = null): Set<Int>? = readOwnedOverlayIds(
            { offered.windows + if (includeNative) listOf(WindowSnapshot(nativeId, WindowType.ACCESSIBILITY_OVERLAY,
                offered.screenBounds, false, false, 4)) else emptyList() },
            { it.type == WindowType.ACCESSIBILITY_OVERLAY }, { it.id }, { id, window -> ownedRoot && id == window.id },
            { true }, { it.id }, {}, {}, health::fail, null, probe)
        fun review(live: Set<Int>? = setOf(nativeId), changed: Boolean = false, ownedRoot: Boolean = true,
            demoScene: F9Scene? = null, demoCandidates: List<CloseCandidate>? = null): ManualDisplayVerdict {
            val probe = ManualDisplayProbe().also { it.point = ManualDisplayPoint.NATIVE_BEFORE }
            return reviewManualDisplay(retire = { expireOriginal() }, verify = {
                verifyRegisteredManualDisplay(probe, { check(valid) }, {
                    registration.read(keeper.windowGeneration,
                        { readManualNativeWindow(native, owner, keeper.windowGeneration, null) }, { false }, {
                            val p = ManualOwnedReadProbe()
                            try { if (live == null) null else ownedIds(includeNative = !ownedRoot || live.contains(nativeId), ownedRoot = ownedRoot, probe = p) }
                            finally { probe.ownedRead = p.code() }
                        }, probe)
                }) { identity ->
                    if (blockedRead(F9ReadPurpose.DISPLAY_REVIEW)) return@verifyRegisteredManualDisplay ManualDisplayVerdict.UNAVAILABLE
                    val overlay = WindowSnapshot(identity.window, WindowType.ACCESSIBILITY_OVERLAY, original.screenBounds, true, true, 4)
                    val windows = if (identity.listing == ManualDisplayListing.NOT_ENUMERATED) offered.windows else
                        offered.windows.map { if (it.id == offered.token.windowId) it.copy(active = false, focused = false) else it } + overlay
                    val scene = demoScene ?: f.before.copy(nodes = if (changed) listOf(body.copy(text = "다른 안내")) else offered.nodes, windows = windows)
                    // These fake native reads explicitly report the fixture's full action lists.
                    // Production requires the collector/reader metadata; DTO inference is forbidden.
                    fun states(nodes: List<NodeSnapshot>) = nodes.map { n -> NativeActionSummary.fromIds(n.actions.map { when (it) {
                        NodeAction.CLICK -> android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK
                        NodeAction.DISMISS -> android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS
                    } }) }
                    val exactNative = demoCandidates != null || ManualPresentationStabilityFeature.enabled
                    val displayRead = if (!exactNative) scene else scene.copy(
                        nativeActions = NativeNodeActions.forNodeSummaries(scene.nodes, states(scene.nodes)))
                    ManualDisplayContinuity.assess(offered, displayRead, identity.window, probe, identity.listing, demoCandidates,
                        if (!exactNative) null else NativeNodeActions.forSnapshotSummaries(offered, states(offered.nodes)),
                        strictActions = ManualPresentationStabilityFeature.enabled)
                }
            }, remove = {
                valid = false; guarded.revoke(); lifetime.clear()
                stream.tryEmit(PopupEvent.Invalidated(offered.token.screenVersion + 1)); delegate.dismiss(); announcer.stop()
            })
        }
    }
    private fun actualTargetScene(r: Rig, snapshot: PopupSnapshot, nodes: List<NodeSnapshot> = snapshot.nodes) =
        r.f.before.copy(packageName = snapshot.token.packageName, windowId = snapshot.token.windowId,
            screen = snapshot.screenBounds, nodes = nodes, visiblePaths = nodes.map { it.path }.toSet(),
            windows = snapshot.windows + WindowSnapshot(r.nativeId, WindowType.ACCESSIBILITY_OVERLAY,
                snapshot.screenBounds, false, false, 4))

    @Test fun reviewStableFirstHundredMillisDoesNotIgnoreTargetMovementAt130To140Millis() = runTest {
        for (eventAt in listOf(130L, 135L, 140L)) {
            val r = Rig(this); var reads = 0
            val start = testScheduler.currentTime
            val stable = requireNotNull(PrePresentationStability.collect(P01StabilityFixture.sample(0, r.now()), r::now,
                { r.valid && r.barrier.ready }, { yield(); true }, { check ->
                    check(); val captured = r.now(); advanceTimeBy(20)
                    P01StabilityFixture.sample(++reads, captured)
                }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO))
            assertEquals(40L, testScheduler.currentTime - start)
            assertTrue(r.show(stable.snapshot))
            advanceTimeBy(eventAt - (testScheduler.currentTime - start)); runCurrent()
            val changed = P01StabilityFixture.sample(9, r.now(), expanded = true).snapshot
            val stops = r.order.count { it == "TTS_STOP" }
            assertEquals(ManualDisplayVerdict.CHANGED, r.review(demoScene = actualTargetScene(r, changed)))
            runCurrent(); assertFalse(r.guarded.isShowing); assertNull(r.rawToken)
            assertTrue(r.order.count { it == "TTS_STOP" } > stops)
            assertEquals(0, r.f.clicks); assertEquals(0, r.decisionCalls)
        }
    }
    @Test fun reviewButtonsAndTextMoving135MillisAfterCShowStillRemovePromptAndNeverClick() = runTest {
        val r = Rig(this); var reads = 0
        val stable = requireNotNull(PrePresentationStability.collect(P01StabilityFixture.sample(0), r::now,
            { true }, { yield(); true }, { P01StabilityFixture.sample(++reads, r.now()) }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO))
        assertTrue(r.show(stable.snapshot)); val shownAt = testScheduler.currentTime
        advanceTimeBy(135); runCurrent(); assertEquals(135L, testScheduler.currentTime - shownAt)
        val changed = P01StabilityFixture.sample(9, r.now(), expanded = true).snapshot
        assertEquals(ManualDisplayVerdict.CHANGED, r.review(demoScene = actualTargetScene(r, changed)))
        runCurrent(); assertFalse(r.guarded.isShowing); assertNull(r.rawToken); assertEquals(0, r.f.clicks)
    }
    @Test fun reviewLateDeliveryOfAnOlderGeometryEventStillReadsTheCurrentTarget() = runTest {
        val r = Rig(this); var reads = 0
        val stable = requireNotNull(PrePresentationStability.collect(P01StabilityFixture.sample(0), r::now,
            { true }, { yield(); true }, { P01StabilityFixture.sample(++reads, r.now()) }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO))
        assertTrue(r.show(stable.snapshot)); advanceTimeBy(135); runCurrent()
        val changedAt135 = P01StabilityFixture.sample(9, r.now(), expanded = true).snapshot
        advanceTimeBy(200); runCurrent() // Event delivery is deliberately late; no event-time exemption.
        assertEquals(ManualDisplayVerdict.CHANGED, r.review(demoScene = actualTargetScene(r, changedAt135)))
        runCurrent(); assertFalse(r.guarded.isShowing); assertEquals(0, r.f.clicks)
    }

    @Test fun serviceVariantContractControlsTheSharedStabilityToRealBAndCPath() = runTest {
        val r = Rig(this); var reads = 0
        val accepted = PrePresentationStability.collect(P01StabilityFixture.sample(0), r::now,
            { r.valid && r.barrier.ready }, { yield(); true },
            { check -> check(); P01StabilityFixture.sample(++reads, r.now()) },
            contract = ManualPresentationStabilityFeature.contract)
        if (ManualPresentationStabilityFeature.enabled) {
            assertEquals(PresentationAssurance.OBSERVATION_ONLY_DEMO, ManualPresentationStabilityFeature.contract)
            assertNotNull(accepted); assertEquals(2, reads)
            assertTrue(r.show(requireNotNull(accepted).snapshot)); runCurrent()
            assertEquals(1, r.shows)
        } else {
            assertEquals(PresentationAssurance.STRICT_GENERATION_REQUIRED, ManualPresentationStabilityFeature.contract)
            assertNull(accepted); assertEquals(0, reads); assertEquals(0, r.shows)
        }
        assertEquals(0, r.f.clicks); assertEquals(0, r.decisionCalls)
    }

    @Test fun observedDisplayCannotResurrectExpiredExecutionEvidenceOrBypassFreshF0Failure() = runTest {
        val r = Rig(this); var reads = 0
        val accepted = requireNotNull(PrePresentationStability.collect(P01StabilityFixture.sample(0), r::now,
            { true }, { yield(); true }, { P01StabilityFixture.sample(++reads, r.now()) },
            contract = PresentationAssurance.OBSERVATION_ONLY_DEMO))
        val originalToken = accepted.snapshot.token; val originalTime = accepted.snapshot.capturedAtUptimeMillis
        assertTrue(r.show(accepted.snapshot)); advanceTimeBy(700); runCurrent()
        r.completeSpeech(); runCurrent(); advanceTimeBy(5_400); r.expireOriginal()
        assertNull(r.rawToken); assertTrue(r.guarded.isShowing)
        assertEquals(originalToken, accepted.snapshot.token); assertEquals(originalTime, accepted.snapshot.capturedAtUptimeMillis)
        r.collection = { null }
        r.choose(); runCurrent(); assertTrue(r.removal()); r.quiet.complete(Unit)
        runCurrent(); advanceTimeBy(1_500); runCurrent()
        assertEquals(1, r.freshReads); assertEquals(0, r.decisionCalls); assertEquals(0, r.f.clicks)
        assertTrue(r.results.none { it.attempted })
    }

    @Test fun boundedFreshStabilityUsesRealBAndCFlowOnceAndDoesNotAutoClick() = runTest {
        val r = Rig(this)
        val seed = P01StabilityFixture.sample(0)
        var reads = 0
        val accepted = PrePresentationStability.collect(seed, r::now, { r.valid && r.barrier.ready },
            { yield(); true }, { check -> check(); P01StabilityFixture.sample(++reads, r.now(), expanded = true) }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO)
        assertNotNull(accepted); assertEquals(2, reads); assertEquals(0, r.shows)
        assertTrue(r.show(requireNotNull(accepted).snapshot)); runCurrent()
        assertEquals(1, r.shows); assertEquals(accepted.snapshot.token, r.prompt?.token)
        assertEquals(accepted.snapshot.nodes.last().bounds,
            DecisionCandidateAttacher().attach(PopupEvent.Candidate(accepted.snapshot,
                PopupDetection(accepted.snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW))),
                PopupAnalysis(accepted.snapshot.token, texts = F9CollectedSource.texts(accepted.snapshot))).closeCandidates.single().bounds)
        advanceTimeBy(700); runCurrent(); r.completeSpeech(); runCurrent()
        assertTrue(ManualSpeechState.COMPLETED in r.speech)
        assertTrue(r.guarded.isShowing); assertEquals(0, r.f.clicks); assertEquals(0, r.decisionCalls)
    }
    @Test fun unstableFreshGeometryNeverReachesRealCOrTtsAndCleanupPendingBlocksAdmission() = runTest {
        val r = Rig(this); var reads = 0
        val accepted = PrePresentationStability.collect(P01StabilityFixture.sample(0), r::now, { r.valid && r.barrier.ready },
            { yield(); true }, { P01StabilityFixture.sample(++reads, r.now(), expanded = reads % 2 == 0) }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO)
        assertNull(accepted); assertEquals(3, reads); assertEquals(0, r.shows)
        assertFalse("TTS_START" in r.order); assertEquals(0, r.f.clicks)
        r.barrier.fail(); reads = 0
        assertNull(PrePresentationStability.collect(P01StabilityFixture.sample(0), r::now, { r.barrier.ready },
            { true }, { reads++; P01StabilityFixture.sample(reads) }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO))
        assertEquals(0, reads); assertEquals(0, r.shows)
    }
    @Test fun laterTargetMovementAfterFreshStabilityStillRemovesRealCAndStopsItsSpeech() = runTest {
        val r = Rig(this); var reads = 0
        val accepted = requireNotNull(PrePresentationStability.collect(P01StabilityFixture.sample(0), r::now,
            { true }, { yield(); true }, { P01StabilityFixture.sample(++reads, r.now()) }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO))
        assertTrue(r.show(accepted.snapshot)); advanceTimeBy(700); runCurrent()
        assertTrue("TTS_START" in r.order)
        val s = accepted.snapshot
        val moved = s.nodes.map { if(it.path == s.nodes.last().path) it.copy(bounds =
            it.bounds.copy(left = it.bounds.left + 1, right = it.bounds.right + 1)) else it }
        val scene = r.f.before.copy(packageName = s.token.packageName, windowId = s.token.windowId,
            screen = s.screenBounds, nodes = moved, windows = s.windows + WindowSnapshot(r.nativeId, WindowType.ACCESSIBILITY_OVERLAY, s.screenBounds, false, false, 4))
        assertEquals(ManualDisplayVerdict.CHANGED, r.review(demoScene = scene))
        runCurrent(); assertFalse(r.guarded.isShowing); assertTrue("TTS_STOP" in r.order)
        assertEquals(0, r.f.clicks)
    }

    @Test fun fullP01FlowCompletesSpeechWaitsForRealCleanupAndQuietThenRevalidatesAndClicksOnce() = runTest {
        val r = Rig(this); assertTrue(r.show()); advanceTimeBy(700); runCurrent(); r.completeSpeech(); runCurrent()
        assertTrue(ManualSpeechState.COMPLETED in r.speech)
        advanceTimeBy(5_400); r.expireOriginal(); assertTrue(r.guarded.isShowing)
        val (picked, callback) = r.choose(); runCurrent(); assertEquals(0, r.freshReads); assertEquals(0, r.f.clicks)
        assertFalse(r.removal(detach = false)); assertFalse(r.barrier.ready)
        assertFalse(r.removal(CleanupWindowPresence.PRESENT)); assertEquals(0, r.freshReads)
        assertTrue(r.removal()); runCurrent(); assertEquals(0, r.freshReads)
        r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
        assertEquals(1, r.freshReads); assertEquals(1, r.decisionCalls); assertEquals(1, r.f.clicks)
        assertEquals(ClickOutcome.CLOSED_CONFIRMED, r.results.single().outcome)
        callback(picked); runCurrent(); assertEquals(1, r.f.clicks)
        assertTrue(r.order.indexOf("ABSENT") < r.order.indexOf("F0")); assertTrue(r.order.indexOf("F0") < r.order.indexOf("B_EXECUTE"))
    }
    @Test fun demoRootResizeKeepsActualCAndSpeechPastSixSecondsButDoesNotPermitChangedFreshF0() = runTest {
        val r = Rig(this)
        val root = r.f.node.copy(path = "0", viewId = "synthetic-root", text = null, className = "android.widget.FrameLayout",
            bounds = r.f.bounds, clickable = false, actions = persistentSetOf())
        val source = r.original.copy(nodes = persistentListOf(root, r.f.node, r.body))
        assertTrue(r.show(source)); advanceTimeBy(700); runCurrent()
        val expanded = ScreenBounds(18, 18, 202, 202)
        val scene = r.f.before.copy(nodes = source.nodes.map { if (it.path == "0") it.copy(bounds = expanded) else it },
            windows = source.windows.map { if (it.id == 20) it.copy(bounds = expanded) else it })
        val stops = r.order.count { it == "TTS_STOP" }
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.review(emptySet(), demoScene = scene, demoCandidates = listOf(r.f.candidate)))
        runCurrent(); assertTrue(r.guarded.isShowing); assertEquals(stops, r.order.count { it == "TTS_STOP" })
        advanceTimeBy(6_000); runCurrent(); r.completeSpeech(); runCurrent()
        assertTrue(r.guarded.isShowing); assertTrue(ManualSpeechState.COMPLETED in r.speech)
        assertEquals(1, r.shows); assertEquals(source.token, r.lifetime.current()); assertNull(r.rawToken)
        r.collection = { it.copy(nodes = scene.nodes.toPersistentList(), windows = scene.windows.toPersistentList()) }
        r.choose(); assertTrue(r.removal()); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
        assertEquals(1, r.freshReads); assertEquals(0, r.f.clicks); assertFalse(r.results.single().attempted)
        assertEquals(source.capturedAtUptimeMillis, r.offered.capturedAtUptimeMillis)
    }
    @Test fun demoRetentionCannotBypassFinalF9WhenTargetMovesAfterApprovedRefresh() = runTest {
        val r = Rig(this)
        val root = r.f.node.copy(path = "0", viewId = "synthetic-root", text = null, className = "android.widget.FrameLayout",
            bounds = r.f.bounds, clickable = false, actions = persistentSetOf())
        val source = r.original.copy(nodes = persistentListOf(root, r.f.node, r.body)); assertTrue(r.show(source))
        val b = ScreenBounds(18, 18, 202, 202)
        val scene = r.f.before.copy(nodes = source.nodes.map { if (it.path == "0") it.copy(bounds = b) else it },
            windows = source.windows.map { if (it.id == 20) it.copy(bounds = b) else it })
        assertEquals(ManualDisplayVerdict.BOUNDS_RETAINED, r.review(emptySet(), demoScene = scene, demoCandidates = listOf(r.f.candidate)))
        // Actual fresh F0 after C removal returns the exact original tree; no timestamp copying
        // by retention. Then the final press re-read changes independently and must not click.
        r.f.beforePress = { r.f.scene = scene }
        r.choose(); assertTrue(r.removal()); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
        assertEquals(1, r.freshReads); assertEquals(1, r.decisionCalls); assertEquals(0, r.f.clicks)
        assertFalse(r.results.single().attempted)
    }
    @Test fun latestSamsungSystemOcclusionBlocksManualEligibilityEvenAfterBApproves() = runTest {
        val r = Rig(this); r.blockPurpose = F9ReadPurpose.MANUAL_ELIGIBILITY; r.show(); r.choose(); r.removal(); r.quiet.complete(Unit)
        runCurrent(); advanceTimeBy(1_500); runCurrent()
        assertTrue("B_EXECUTE" in r.order); assertEquals(0, r.f.clicks); assertFalse(r.results.single().attempted)
        val read = r.reads.single(); assertEquals(F9ReadPurpose.MANUAL_ELIGIBILITY, read.purpose)
        assertEquals(F9ReadDetail.OCCLUDING_WINDOW, read.detail); assertEquals(WindowType.SYSTEM, read.occlusion!!.type)
        assertEquals(F9PassiveBarResult.NOT_CHECKED_BOUNDED_TARGET, read.occlusion.passive)
    }
    @Test fun shownThenSystemOcclusionCancelsCAnnouncementButCleanupCanFinishNormally() = runTest {
        val r = Rig(this); r.show(); advanceTimeBy(700); runCurrent(); r.blockPurpose = F9ReadPurpose.DISPLAY_REVIEW
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.review()); runCurrent()
        assertFalse(r.guarded.isShowing); assertTrue(ManualSpeechState.CANCELLED in r.speech)
        assertTrue(r.removal()); assertTrue(r.barrier.ready); assertFalse(r.health.failed); assertEquals(0, r.f.clicks)
    }
    @Test fun initialUnlistedNativeCanRetainVerifiedTargetButLaterLossCannotResurrectItsAnswer() = runTest {
        val r = Rig(this); r.show(); val oldAnswer = r.answer
        assertNotNull(r.native?.liveWindow(r.owner, r.native!!.generation))
        val noRemote = r.ownedIds(includeNative = false); assertEquals(emptySet<Int>(), noRemote)
        val oldPrompt = requireNotNull(r.prompt); val oldGeneration = requireNotNull(r.native).generation
        assertEquals(ManualDisplayVerdict.SAME, r.review(noRemote)); runCurrent()
        assertTrue(r.guarded.isShowing); assertEquals(0, r.f.clicks)
        // Only the initial unlisted phase has a strict target-only display path. Once listed,
        // a later loss cannot silently reopen registration for this generation.
        assertEquals(ManualDisplayVerdict.SAME, r.review(setOf(r.nativeId)))
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.review(noRemote)); runCurrent()
        assertFalse(r.guarded.isShowing); assertEquals(0, r.freshReads); assertEquals(0, r.f.clicks)
        // The later remote sample cannot retroactively authorize this removed generation.
        assertEquals(setOf(r.nativeId), r.ownedIds(includeNative = true))
        val oldChoice = ChoicePromptPlanner.toUserChoice(oldPrompt, oldPrompt.options.first { it.kind == PromptOptionKind.PRESS_CANDIDATE })
        requireNotNull(oldAnswer)(PromptAnswer.Picked(oldChoice, ChoiceOrigin.TAP, oldGeneration)); runCurrent()
        assertFalse(r.selected); assertTrue(r.removal()); assertEquals(0, r.f.clicks)
    }
    @Test fun initialMissingRemoteRegistrationKeepsActualCFlowAndSpeechButNeverSkipsFreshF0OrClickGates() = runTest {
        val r = Rig(this); assertTrue(r.show())
        assertEquals(ManualDisplayVerdict.SAME, r.review(emptySet())); runCurrent()
        assertTrue(r.guarded.isShowing); assertEquals(0, r.freshReads); assertEquals(0, r.f.clicks)
        advanceTimeBy(700); runCurrent(); r.completeSpeech(); runCurrent()
        assertTrue(ManualSpeechState.COMPLETED in r.speech)
        advanceTimeBy(5_400); assertTrue(r.guarded.isShowing)
        r.choose(); runCurrent(); assertEquals(0, r.f.clicks); assertEquals(0, r.freshReads)
        assertFalse(r.removal(detach = false)); assertTrue(r.removal())
        r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
        assertEquals(1, r.freshReads); assertEquals(1, r.decisionCalls); assertEquals(1, r.f.clicks)
        assertEquals(ClickOutcome.CLOSED_CONFIRMED, r.results.single().outcome)
    }
    @Test fun actualTargetChangeWhileRemoteNativeIsStillUnlistedCancelsRealCFlowAndSpeech() = runTest {
        val r = Rig(this); r.show(); advanceTimeBy(700); runCurrent()
        assertEquals(ManualDisplayVerdict.CHANGED, r.review(emptySet(), changed = true)); runCurrent()
        r.completeSpeech(); runCurrent(); assertFalse(r.guarded.isShowing)
        assertFalse(ManualSpeechState.COMPLETED in r.speech); assertEquals(0, r.f.clicks)
        assertTrue(r.removal())
    }
    @Test fun changedWholePopupRemovesAndNoSpeechOrActionCanBeCompletedByLateCallback() = runTest {
        val r = Rig(this); r.show(); advanceTimeBy(700); runCurrent()
        assertEquals(ManualDisplayVerdict.CHANGED, r.review(changed = true)); runCurrent()
        r.completeSpeech(); runCurrent(); assertFalse(ManualSpeechState.COMPLETED in r.speech)
        assertEquals(0, r.f.clicks); assertTrue(r.removal())
    }
    @Test fun lateNativeIdAndGenerationMustBeExactBeforeReadingRemoteList() {
        val owner = Any(); val n = OverlayNativeWindowLifetime(1, owner).also { it.attached(31); it.addReturned(true) }
        val p = ManualDisplayProbe()
        assertNull(manualDisplayOwnedId({ readManualNativeWindow(n, owner, 2, null) }, { error("NO_BEFORE") }, { error("NO_REMOTE_READ") }, p))
        assertEquals(ManualDisplayDetail.NATIVE_ID_UNAVAILABLE, p.detail)
    }
    @Test fun failedFreshCollectionOrChangedTargetStopsBeforeBAndF9() = runTest {
        for (mode in 0..2) {
            val r = Rig(this); r.collection = { when (mode) {
                0 -> null; 1 -> it.copy(nodes = persistentListOf(r.f.node.copy(text = "구매"), r.body))
                else -> it.copy(token = it.token.copy(structureDigest = "different-popup"))
            } }
            r.show(); r.choose(); r.removal(); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
            assertEquals(0, r.decisionCalls); assertEquals(0, r.f.clicks); assertFalse(r.results.single().attempted)
        }
    }
    @Test fun nativeAbsenceUncertaintyAndActualRemoveFailureCannotReachFreshCollector() = runTest {
        for (failure in listOf(false, true)) {
            val r = Rig(this); r.removeFails = failure; r.show(); r.choose()
            if (!failure) r.removal(CleanupWindowPresence.UNKNOWN)
            r.quiet.complete(Unit); runCurrent()
            assertTrue(r.health.failed); assertEquals(0, r.freshReads); assertEquals(0, r.f.clicks)
        }
    }
    @Test fun preClickOrPressOcclusionAndFinalTargetReplacementNeverClick() = runTest {
        for (purpose in listOf(F9ReadPurpose.PRE_CLICK, F9ReadPurpose.PRESS, null)) {
            val r = Rig(this); r.blockPurpose = purpose
            if (purpose == null) r.f.beforePress = { r.f.scene = r.f.scene!!.copy(nodes = listOf(r.f.node.copy(text = "구매"))) }
            r.show(); r.choose(); r.removal(); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
            assertEquals(0, r.f.clicks); assertFalse(r.results.single().attempted)
        }
    }
    @Test fun clickDeliveryWithoutPopupDisappearanceOrReadablePostClickIsNotSuccess() = runTest {
        for (unavailable in listOf(false, true)) {
            val r = Rig(this)
            if (unavailable) r.blockPurpose = F9ReadPurpose.POST_CLICK else r.f.after = r.f.before.copy(nodes = r.original.nodes)
            r.show(); r.choose(); r.removal(); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
            assertEquals(1, r.f.clicks); val result = r.results.single(); assertTrue(result.attempted)
            assertNotEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
        }
    }
    @Test fun consentRevokedAfterBApprovalStillPreventsActualClick() = runTest {
        val r = Rig(this); r.onDecision = { r.access = r.access.copy(collectionConsent = false) }
        r.show(); r.choose(); r.removal(); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
        assertTrue("B_EXECUTE" in r.order); assertEquals(0, r.f.clicks); assertFalse(r.results.single().attempted)
    }
    @Test fun reorderedRetirementEventsWaitForDetachAndAbsenceWithoutDroppingSafety() = runTest {
        val r = Rig(this); r.show(); r.choose(); runCurrent()
        for (type in listOf(E.TYPE_WINDOW_STATE_CHANGED, E.TYPE_WINDOWS_CHANGED)) {
            val mask = if (type == E.TYPE_WINDOWS_CHANGED) E.WINDOWS_CHANGE_BOUNDS else 0
            val code = inspectNativeCleanupRetirement(type, mask, { true }, { true }, { true })
            assertEquals(ManualRetirementCheck.CLEANUP_WAIT, code.check)
            r.barrier.observeWindowEvent(r.nativeId, type, mask, true)
        }
        val receipt = requireNotNull(r.barrier.observeWindowEvent(r.nativeId, E.TYPE_WINDOWS_CHANGED, E.WINDOWS_CHANGE_REMOVED))
        r.barrier.processed(receipt, true) { CleanupWindowPresence.ABSENT }
        assertFalse(r.barrier.ready); assertEquals(0, r.freshReads)
        assertTrue(r.removal()); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
        assertEquals(1, r.f.clicks); assertTrue(r.failures.isEmpty())
    }
    @Test fun couponAndNoticeUseActualBClassificationButStillRequireFreshSingleExecution() = runTest {
        for (body in listOf("쿠폰 할인 안내", "업데이트 공지 안내")) {
            val r = Rig(this)
            assertTrue(r.show(r.original.copy(nodes = persistentListOf(r.f.node, r.body.copy(text = body)))))
            r.choose(); r.removal(); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
            assertEquals(1, r.freshReads); assertEquals(1, r.f.clicks)
            assertEquals(ClickOutcome.CLOSED_CONFIRMED, r.results.single().outcome)
        }
    }
    @Test fun dangerousContextCannotAcquireAClickEvenIfAVisibleCloseChoiceIsOffered() = runTest {
        for (body in listOf("결제 구매 안내", "권한 동의 안내", "구독 삭제 안내", "바이러스 감염 즉시 설치")) {
            val r = Rig(this); r.show(r.original.copy(nodes = persistentListOf(r.f.node, r.body.copy(text = body))))
            if (r.prompt!!.options.any { it.kind == PromptOptionKind.PRESS_CANDIDATE }) {
                r.choose(); r.removal(); r.quiet.complete(Unit); runCurrent(); advanceTimeBy(1_500); runCurrent()
                assertTrue(r.results.none { it.attempted })
            }
            assertEquals(0, r.f.clicks)
        }
    }
    @Test fun metadataOwnershipRejectionNeverBecomesRemotePresenceSuccess() = runTest {
        val r = Rig(this); r.show()
        assertEquals(emptySet<Int>(), r.ownedIds(includeNative = true, ownedRoot = false))
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, r.review(r.ownedIds(true, ownedRoot = false), ownedRoot = false))
        runCurrent(); assertEquals(0, r.f.clicks); assertFalse(r.guarded.isShowing)
    }

}
