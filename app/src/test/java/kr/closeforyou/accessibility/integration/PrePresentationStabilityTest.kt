package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityNodeInfo as N
import kotlinx.collections.immutable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.closeforyou.accessibility.*
import kr.closeforyou.core.model.*
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.decision.DecisionCandidateAttacher
import org.junit.Assert.*
import org.junit.Test

/** P01-shaped descriptor hierarchy, not measured Samsung coordinates or native Android nodes.
 * Both TextViews carry actual platform navigation/selection/text capability IDs. All readers
 * below return new DTO objects; the Android producer additionally refreshes the real nodes.
 */
internal object P01StabilityFixture {
    fun sample(sequence: Int, at: Long = 1_000L, expanded: Boolean = false): CollectionResult.Ready {
        val box = if (expanded) ScreenBounds(60, 90, 340, 350) else ScreenBounds(80, 100, 320, 330)
        val inset = if (expanded) 15 else 20
        fun node(path: String, cls: String, text: String?, y: Int, click: Boolean = false) = NodeSnapshot(
            path, "synthetic-$path", "android.widget.$cls", text, null, click, true,
            ScreenBounds(box.left + inset, box.top + y, box.right - inset, box.top + y + 25),
            if (click) persistentSetOf(NodeAction.CLICK) else persistentSetOf())
        val root = NodeSnapshot("0", "synthetic-root", "android.widget.FrameLayout", null, null,
            false, true, box, persistentSetOf())
        val layout = root.copy(path = "0/0", className = "android.widget.LinearLayout", viewId = "synthetic-layout")
        val nodes = persistentListOf(root, layout, node("0/0/0", "TextView", "광고 안내", 20),
            node("0/0/1", "TextView", "새 소식", 55), node("0/0/2", "Button", "자세히 보기", 100, true),
            node("0/0/3", "Button", "닫기", 145, true))
        val screen = ScreenBounds(0, 0, 400, 600)
        val windows = persistentListOf(WindowSnapshot(1, WindowType.APPLICATION, screen, false, false, 0),
            WindowSnapshot(2, WindowType.APPLICATION, box, true, true, 1))
        val token = SnapshotToken(1, 2, "kr.closeforyou.popuptest", "synthetic-$sequence", "geometry-$expanded", "content", 1)
        val snap = PopupSnapshot(token, at, nodes, windows, screen, PopupTrigger.WINDOW_CHANGED, SnapshotCompleteness.COMPLETE)
        val nav = listOf(N.ACTION_ACCESSIBILITY_FOCUS, N.ACTION_SELECT, N.ACTION_CLEAR_SELECTION)
        val text = nav + listOf(N.ACTION_NEXT_AT_MOVEMENT_GRANULARITY, N.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY, N.ACTION_SET_SELECTION)
        return result(snap, listOf(nav, nav, text, text, listOf(N.ACTION_CLICK), listOf(N.ACTION_CLICK)))
    }
    fun result(s: PopupSnapshot, ids: List<List<Int>> = s.nodes.map { if (it.clickable) listOf(N.ACTION_CLICK) else emptyList() }): CollectionResult.Ready =
        CollectionResult.Ready(s, DetectionFrame("app", s.token.windowId, setOf(1,2), s.token.structureDigest, s.screenBounds,
            s.nodes.map { DetectionOutline(it.path, it.bounds, if (it.path == "0") 1 else 0, false) }, s.trigger,
            s.windows.single { it.id == s.token.windowId }.bounds),
            NativeNodeActions.forSnapshotSummaries(s, ids.map { NativeActionSummary.fromIds(it) }))
}

@OptIn(ExperimentalCoroutinesApi::class)
class PrePresentationStabilityTest {
    private class Rig {
        var time = 1_000L; var valid = true; var count = 0; var turns = 0
        var failTurn = false; var readFailure: Exception? = null; var duringRead: (() -> Unit)? = null
        val seed = P01StabilityFixture.sample(0)
        var producer: (Int) -> CollectionResult = { P01StabilityFixture.sample(it, time) }
        var beforeTurn: (Int) -> Unit = {}
        val codes = mutableListOf<PresentationStabilityEnd>()
        suspend fun run(contract: PresentationAssurance = PresentationAssurance.OBSERVATION_ONLY_DEMO) = PrePresentationStability.collect(seed, { time }, { valid }, {
            yield(); beforeTurn(++turns); !failTurn
        }, { check -> check(); count++; duringRead?.invoke(); readFailure?.let { throw it }; check(); producer(count) }, codes::add, contract = contract)
    }
    /** Final review contract: descriptor equality and a fresh DTO UUID are not a native
     * generation witness. Strict mode must reject without such proof;
     * it is NOT evidence that a Samsung native replacement actually occurred.
     */
    @Test fun reviewMissingNativeGenerationProofMustNotGrantStableAdmission() = runTest {
        val r = Rig()
        val accepted = r.run(PresentationAssurance.STRICT_GENERATION_REQUIRED)
        assertEquals(0, r.count)
        assertEquals(listOf(PresentationStabilityEnd.GENERATION_UNPROVEN), r.codes)
        assertTrue("NATIVE_GENERATION_UNPROVEN_MUST_REJECT", accepted == null)
    }
    @Test fun omittedContractCannotAccidentallyOptIntoObservation() = runTest {
        val r = Rig()
        val accepted = PrePresentationStability.collect(r.seed, { r.time }, { true },
            { error("STRICT_MUST_NOT_POST") }, { error("STRICT_MUST_NOT_READ") }, r.codes::add)
        assertNull(accepted)
        assertEquals(listOf(PresentationStabilityEnd.GENERATION_UNPROVEN), r.codes)
    }
    @Test fun explicitObservationAcceptsOnlyTheLastIndependentReadWithoutGenerationClaim() = runTest {
        val r = Rig(); val reads = mutableListOf<CollectionResult.Ready>()
        r.producer = { P01StabilityFixture.sample(it, r.time).also(reads::add) }
        val accepted = r.run(PresentationAssurance.OBSERVATION_ONLY_DEMO)
        assertSame(reads.last(), accepted); assertEquals(2, reads.size)
        assertEquals(listOf(PresentationStabilityEnd.STABLE_FRESH), r.codes)
        assertNotEquals(r.seed.snapshot.token, accepted!!.snapshot.token)
    }
    @Test fun observedRemovalAndReadditionCannotRestoreTheCapturedEpoch() = runTest {
        val freshness = ScreenFreshness(); val epoch = freshness.version
        var reads = 0; var turns = 0
        val accepted = PrePresentationStability.collect(P01StabilityFixture.sample(0), { 1_000L },
            { freshness.isCurrent(epoch) }, {
                if (++turns == 2) { freshness.invalidate(); freshness.invalidate() }
                true
            }, { check -> check(); P01StabilityFixture.sample(++reads) },
            contract = PresentationAssurance.OBSERVATION_ONLY_DEMO)
        assertNull(accepted); assertEquals(1, reads)
        assertFalse(freshness.isCurrent(epoch))
    }
    /** Characterizes the blind interval, not a verified Samsung/native-generation observation.
     * The event is not delivered yet, so service epoch remains current; the read port only
     * returns A. A hidden A -> B -> A transition cannot be certified absent from two A DTOs.
     */
    @Test fun reviewUnobservedRoundTripWithLateEventIsNotRepresentedByTheReadContract() = runTest {
        val r = Rig(); var worldGeometry = false; var nativeGeneration = 1
        r.duringRead = {
            worldGeometry = true
            nativeGeneration++
            worldGeometry = false
        }
        r.producer = { P01StabilityFixture.sample(it, r.time, expanded = worldGeometry) }
        val accepted = r.run()
        assertNotNull(accepted) // observable contract accepts; no claim about hidden native generation
        assertEquals(3, nativeGeneration); assertEquals(2, r.count)
        assertEquals(listOf(PresentationStabilityEnd.STABLE_FRESH), r.codes)
    }

    @Test fun reviewObservedBoundsRoundTripDiscardsSeedAndIntermediateSample() = runTest {
        val r = Rig(); val reads = mutableListOf<CollectionResult.Ready>()
        r.beforeTurn = { r.time = 1_000L + it * 20 }
        r.producer = { P01StabilityFixture.sample(it, r.time, expanded = it == 1).also(reads::add) }
        val last = requireNotNull(r.run())
        assertEquals(3, r.count); assertSame(reads.last(), last)
        assertNotEquals(r.seed.snapshot.token, last.snapshot.token)
        assertNotEquals(reads.first().snapshot.nodes.last().bounds, last.snapshot.nodes.last().bounds)
        assertEquals(r.seed.snapshot.nodes, last.snapshot.nodes) // equal values do not resurrect seed token
        assertEquals(listOf(PresentationStabilityEnd.STABLE_RECOLLECTED), r.codes)
    }

    @Test fun stableReadsReturnTheActualLastNewObjectAndOnlyThenRunRealF1AndB() = runTest {
        val r = Rig(); val objects = mutableListOf<CollectionResult.Ready>()
        r.producer = { P01StabilityFixture.sample(it, r.time).also(objects::add) }
        val stable = requireNotNull(r.run()); assertSame(objects.last(), stable); assertEquals(2, r.count)
        assertNotSame(r.seed.snapshot, stable.snapshot); assertNotEquals(r.seed.snapshot.token, stable.snapshot.token)
        val signals = PopupCandidateDetector().detect(stable.frame); assertEquals(setOf(PopupSignal.NEW_WINDOW), signals)
        val analysis = DecisionCandidateAttacher().attach(PopupEvent.Candidate(stable.snapshot,
            PopupDetection(stable.snapshot.token, signals.toPersistentSet())), PopupAnalysis(stable.snapshot.token, texts = F9CollectedSource.texts(stable.snapshot)))
        assertEquals(1, analysis.closeCandidates.count { it.role == ButtonRole.CLOSE })
        assertEquals(stable.snapshot.nodes.last().bounds, analysis.closeCandidates.single { it.role == ButtonRole.CLOSE }.bounds)
        assertEquals(listOf(PresentationStabilityEnd.STABLE_FRESH), r.codes)
    }
    @Test fun showThenSetLayoutShapedRootTextAndTwoButtonMovesDiscardSeedAndUseNewStableCapture() = runTest {
        val r = Rig(); r.producer = { P01StabilityFixture.sample(it, r.time, expanded = true) }
        val stable = requireNotNull(r.run()); assertEquals(2, r.count)
        assertEquals(listOf(PresentationStabilityEnd.STABLE_RECOLLECTED), r.codes)
        assertNotEquals(r.seed.snapshot.nodes.last().bounds, stable.snapshot.nodes.last().bounds)
        assertEquals("synthetic-2", stable.snapshot.token.popupInstanceId)
        assertEquals(r.seed.snapshot.capturedAtUptimeMillis, 1_000L) // seed never rewritten
    }
    @Test fun changeBetweenFreshReadsRequiresAnotherRealReadNotADispatcherTurn() = runTest {
        val r = Rig(); r.producer = { P01StabilityFixture.sample(it, r.time, expanded = it >= 2) }
        assertNotNull(r.run()); assertEquals(3, r.count); assertEquals(3, r.turns)
    }
    @Test fun continuouslyMovingGeometryIsBoundedAndCannotProduceACandidate() = runTest {
        val r = Rig(); r.producer = { P01StabilityFixture.sample(it, r.time, expanded = it % 2 == 0) }
        assertNull(r.run()); assertEquals(3, r.count); assertEquals(listOf(PresentationStabilityEnd.UNSTABLE), r.codes)
    }
    @Test fun everyGeometryVariantRemainsRejectedWhileMovingIncludingPreviouslyRetainedDemoCases() = runTest {
        for(kind in listOf("ROOT_ONLY", "TRANSLATION", "PASSIVE_REFLOW", "CLOSE", "OTHER_BUTTON", "TEXT_AND_BUTTONS")) {
            val r = Rig()
            r.producer = { n ->
                val fresh = P01StabilityFixture.sample(n, r.time)
                fun shift(b: ScreenBounds) = b.copy(left = b.left + n, right = b.right + n)
                val root = fresh.snapshot.nodes.first().bounds
                val expanded = root.copy(left = root.left - n, right = root.right + n)
                val nodes = fresh.snapshot.nodes.mapIndexed { i, node -> when {
                    kind == "TRANSLATION" -> node.copy(bounds = shift(node.bounds))
                    kind == "ROOT_ONLY" && i == 0 -> node.copy(bounds = expanded)
                    kind == "PASSIVE_REFLOW" && i == 2 -> node.copy(bounds = shift(node.bounds))
                    kind == "CLOSE" && i == 5 -> node.copy(bounds = shift(node.bounds))
                    kind == "OTHER_BUTTON" && i == 4 -> node.copy(bounds = shift(node.bounds))
                    kind == "TEXT_AND_BUTTONS" && i >= 2 -> node.copy(bounds = shift(node.bounds))
                    else -> node
                } }.toPersistentList()
                val snapshot = fresh.snapshot.copy(nodes = nodes, windows = fresh.snapshot.windows.map { w ->
                    if(w.id != 2) w else when(kind) {
                        "TRANSLATION" -> w.copy(bounds = shift(w.bounds))
                        "ROOT_ONLY" -> w.copy(bounds = expanded)
                        else -> w
                    }
                }.toPersistentList())
                val meta = NativeNodeActions.forSnapshotSummaries(snapshot, nodes.indices.map {
                    fresh.nativeActions!!.observedSummary(fresh.snapshot, nodes.size, it)
                })
                fresh.copy(snapshot = snapshot, nativeActions = meta, frame = fresh.frame.copy(
                    windowBounds = snapshot.windows.single { it.id == 2 }.bounds,
                    outlines = fresh.frame.outlines.mapIndexed { i, outline -> outline.copy(bounds = nodes[i].bounds) }))
            }
            assertNull(kind, r.run()); assertEquals(kind, 3, r.count)
        }
    }

    @Test fun contentOrStructureReplacementWithSameWindowIdFailsWithoutRebasingCandidate() = runTest {
        for (change in listOf("TEXT", "PATH", "CLASS", "ID", "CLICKABLE", "ENABLED", "COUNT", "ACTION")) {
            val r = Rig(); r.producer = { n ->
                val f = P01StabilityFixture.sample(n, r.time); val ns = f.snapshot.nodes.toMutableList()
                ns[3] = when (change) {
                    "TEXT" -> ns[3].copy(text = "다른 내용")
                    "PATH" -> ns[3].copy(path = "0/0/9")
                    "CLASS" -> ns[3].copy(className = "android.widget.ImageView")
                    "ID" -> ns[3].copy(viewId = "other")
                    "CLICKABLE" -> ns[3].copy(clickable = true)
                    "ENABLED" -> ns[3].copy(enabled = false)
                    "ACTION" -> ns[3].copy(actions = persistentSetOf(NodeAction.DISMISS))
                    else -> ns[3]
                }
                if (change == "COUNT") ns.removeAt(ns.lastIndex)
                val snapshot = f.snapshot.copy(nodes = ns.toPersistentList())
                f.copy(snapshot = snapshot, nativeActions = NativeNodeActions.forSnapshotSummaries(snapshot,
                    ns.indices.map { f.nativeActions!!.observedSummary(f.snapshot, f.snapshot.nodes.size, it) }))
            }
            assertNull(change, r.run()); assertEquals(change, 1, r.count)
        }
    }
    @Test fun changedExactNativeActionsUnknownCustomDuplicateOrWrongObjectFailClosed() = runTest {
        for (kind in listOf("CHANGED", "UNKNOWN", "CUSTOM", "DUPLICATE", "WRONG_OWNER", "MISSING")) {
            val r = Rig(); r.producer = { n ->
                val f = P01StabilityFixture.sample(n, r.time)
                val rows = f.snapshot.nodes.mapIndexed { i, node ->
                    when {
                        i == 2 && kind == "UNKNOWN" -> NativeActionSummary.UNKNOWN
                        i == 2 && kind == "CUSTOM" -> NativeActionSummary.fromIds(listOf(0x7f000001))
                        i == 2 && kind == "DUPLICATE" -> NativeActionSummary.fromIds(listOf(N.ACTION_SELECT, N.ACTION_SELECT))
                        node.clickable -> NativeActionSummary.fromIds(listOf(N.ACTION_CLICK))
                        else -> NativeActionSummary.fromIds(emptyList())
                    }
                }
                f.copy(nativeActions = if (kind == "MISSING") null else NativeNodeActions.forSnapshotSummaries(
                    if (kind == "WRONG_OWNER") f.snapshot.copy() else f.snapshot, rows))
            }
            assertNull(kind, r.run())
        }
    }
    @Test fun wrongPackageWindowEpochFrameOrWindowLayerFailsClosed() = runTest {
        for (field in listOf("PACKAGE", "WINDOW", "EPOCH", "FRAME", "LAYER", "FOCUS", "OVERLAY", "OCCLUSION", "INPUT")) {
            val r = Rig(); r.producer = { n ->
                val s = P01StabilityFixture.sample(n, r.time).snapshot
                val f = when (field) {
                    "PACKAGE" -> s.copy(token = s.token.copy(packageName = "kr.synthetic.other"))
                    "WINDOW" -> s.copy(token = s.token.copy(windowId = 9))
                    "EPOCH" -> s.copy(token = s.token.copy(screenVersion = 2, captureEpoch = 2))
                    "FRAME" -> s.copy(screenBounds = ScreenBounds(0, 0, 500, 700))
                    "LAYER" -> s.copy(windows = s.windows.map { it.copy(layer = it.layer + 1) }.toPersistentList())
                    "FOCUS" -> s.copy(windows = s.windows.map { it.copy(focused = false) }.toPersistentList())
                    else -> s.copy(windows = (s.windows + WindowSnapshot(8, when(field) {
                        "OVERLAY" -> WindowType.ACCESSIBILITY_OVERLAY; "INPUT" -> WindowType.INPUT_METHOD; else -> WindowType.SYSTEM
                    }, s.screenBounds, false, false, 5)).toPersistentList())
                }
                P01StabilityFixture.result(f)
            }
            assertNull(field, r.run())
        }
    }
    @Test fun invalidGeometryParentGraphAndIncompleteTreesNeverStabilize() = runTest {
        for (kind in listOf("EMPTY", "REVERSED", "OUTSIDE", "PARENT", "DUPLICATE", "INCOMPLETE")) {
            val r = Rig(); r.producer = { n ->
                val s = P01StabilityFixture.sample(n, r.time).snapshot; val nodes = s.nodes.toMutableList()
                when(kind) {
                    "EMPTY" -> nodes.clear()
                    "REVERSED" -> nodes[2] = nodes[2].copy(bounds = ScreenBounds(200, 150, 150, 120))
                    "OUTSIDE" -> nodes[2] = nodes[2].copy(bounds = s.screenBounds)
                    "PARENT" -> nodes[2] = nodes[2].copy(path = "0/9/0")
                    "DUPLICATE" -> nodes[2] = nodes[2].copy(path = "0/0")
                }
                P01StabilityFixture.result(s.copy(nodes = nodes.toPersistentList(), completeness =
                    if(kind == "INCOMPLETE") SnapshotCompleteness.TRUNCATED else SnapshotCompleteness.COMPLETE))
            }
            assertNull(kind, r.run())
        }
    }
    @Test fun seedOrReadObjectReusedTokenReusedAndInvalidCaptureTimeAreNotIndependentEvidence() = runTest {
        for (kind in listOf("SEED", "SAME_OBJECT", "SAME_TOKEN", "OLD_TIME", "FUTURE_TIME")) {
            val r = Rig(); var cached: CollectionResult.Ready? = null
            r.producer = { n ->
                when(kind) {
                    "SEED" -> r.seed
                    "SAME_OBJECT" -> cached ?: P01StabilityFixture.sample(n).also { cached = it }
                    "SAME_TOKEN" -> P01StabilityFixture.sample(1)
                    "OLD_TIME" -> P01StabilityFixture.sample(n, 999)
                    else -> P01StabilityFixture.sample(n, 1_001)
                }
            }
            assertNull(kind, r.run())
        }
    }
    @Test fun eventInteractionConsentDisconnectGenerationOrCleanupRevocationBetweenReadsCannotPublish() = runTest {
        for (boundary in 1..3) {
            val r = Rig(); r.beforeTurn = { if(it == boundary) r.valid = false }
            if(boundary <= 2) assertNull(r.run()) else assertNotNull(r.run()) // no third turn on stable pair
            assertTrue(r.count <= 2)
        }
    }
    @Test fun eachActualReadOwnsItsTraceAndDiscardedSeedCannotOverwriteFinalF1Progress() = runTest {
        val r = Rig()
        val diagnostics = CaptureDiagnosticSession(r.seed.snapshot.token.packageName)
        var trace = CaptureTrace(diagnostics, 2, 1)
        trace.root(r.seed.snapshot.token.packageName, 2)
        val discarded = trace
        val stable = PrePresentationStability.collect(r.seed, { r.time }, { true }, { yield(); true }, {
            trace = CaptureTrace(diagnostics, 2, 1)
            val sample = P01StabilityFixture.sample(++r.count)
            trace.root(sample.snapshot.token.packageName, 2)
            sample
        }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO)
        assertNotNull(stable)
        trace.stage(CaptureStage.POPUP_DETECTED)
        discarded.block(PrivacyBlockReason.UNREADABLE_TREE)
        val final = requireNotNull(diagnostics.snapshot())
        assertEquals(3, final.attempt) // seed + two real fresh reads; not three copies of one attempt
        assertTrue(CaptureStage.POPUP_DETECTED in final.reached)
        assertNull(final.block)
    }

    @Test fun terminalReportIsOnceEvenWhenSinkThrowsAndContainsOnlyEnum() = runTest {
        val r = Rig(); var reports = 0
        val result = PrePresentationStability.collect(r.seed, { r.time }, { true }, { true }, {
            r.count++; P01StabilityFixture.sample(r.count)
        }, { reports++; throw IllegalStateException("SYNTHETIC_SECRET") }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO)
        assertNotNull(result); assertEquals(1, reports)
        assertTrue(PresentationStabilityEnd.entries.all { !it.name.contains("synthetic", true) })
    }
    @Test fun blockedReadExceptionAndFailedDispatcherNeverRetryOrShow() = runTest {
        val blocked = Rig(); blocked.producer = { CollectionResult.Blocked(PrivacyBlockReason.SENSITIVE_CONTENT) }
        assertNull(blocked.run()); assertEquals(1, blocked.count)
        val error = Rig(); error.readFailure = IllegalStateException("SYNTHETIC_SECRET")
        assertNull(error.run()); assertEquals(listOf(PresentationStabilityEnd.FAILED), error.codes)
        val post = Rig(); post.failTurn = true; assertNull(post.run()); assertEquals(0, post.count)
    }
    @Test fun originalTtlAndWholeHundredMillisecondBudgetAreNeverExtended() = runTest {
        val r = Rig(); r.beforeTurn = { r.time += 50 }
        assertNull(r.run()); assertEquals(listOf(PresentationStabilityEnd.DEADLINE), r.codes); assertEquals(1, r.count)
        val ttl = Rig(); ttl.time = 6_000; assertNull(ttl.run()); assertEquals(0, ttl.count)
        val backwards = Rig(); backwards.time = 999; assertNull(backwards.run())
    }
    @Test fun cancellationBetweenRefreshAndCompletionPropagatesAndReportsExactlyOnce() = runTest {
        val r = Rig(); r.readFailure = CancellationException("SYNTHETIC_SECRET")
        try { r.run(); fail() } catch (_: CancellationException) { }
        assertEquals(listOf(PresentationStabilityEnd.CANCELLED), r.codes); assertEquals(1, r.count)
    }
    @Test fun latePostedCallbackAfterCancellationCannotResumeTheOperation() = runTest {
        val r = Rig(); val queue = ArrayDeque<Runnable>()
        val job = async { PrePresentationStability.collect(r.seed, { 1_000 }, { true }, {
            awaitChoicePresentationTurn({ queue += it; true }, { queue.remove(it) })
        }, { r.count++; P01StabilityFixture.sample(r.count) }, contract = PresentationAssurance.OBSERVATION_ONLY_DEMO) }
        runCurrent(); val late = queue.first(); job.cancelAndJoin(); assertTrue(queue.isEmpty())
        late.run(); assertEquals(0, r.count)
    }
    @Test fun detectorFrameMustBelongToTheAcceptedSnapshotRatherThanEarlierGeometry() = runTest {
        for(field in listOf("WINDOW", "FRAME", "STRUCTURE", "BOUNDS", "OUTLINES", "APPLICATIONS")) {
            val r = Rig(); r.producer = { n ->
                val f = P01StabilityFixture.sample(n)
                f.copy(frame = when(field) {
                    "WINDOW" -> f.frame.copy(activeWindowId = 9)
                    "FRAME" -> f.frame.copy(screenBounds = f.frame.screenBounds.copy(right = 401))
                    "STRUCTURE" -> f.frame.copy(structureDigest = "earlier")
                    "BOUNDS" -> f.frame.copy(windowBounds = f.frame.screenBounds)
                    "OUTLINES" -> f.frame.copy(outlines = emptyList())
                    else -> f.frame.copy(applicationWindowIds = emptySet())
                })
            }
            assertNull(field, r.run()); assertEquals(1, r.count)
        }
    }
    @Test fun strictDisplayedReadRequiresExactNativeSetsEvenWhenDtoNodesAndGeometryAreEqual() {
        val a = P01StabilityFixture.sample(0)
        val s = a.snapshot
        val scene = kr.closeforyou.accessibility.action.F9Scene(s.token.packageName, s.token.windowId, 1,
            s.screenBounds, s.windows, s.nodes, s.nodes.map { it.path }.toSet(), true,
            nativeActions = NativeNodeActions.forNodeSummaries(s.nodes, s.nodes.indices.map {
                a.nativeActions!!.observedSummary(s, s.nodes.size, it)
            }))
        assertEquals(ManualDisplayVerdict.SAME, ManualDisplayContinuity.assess(s, scene, 30,
            listing = ManualDisplayListing.NOT_ENUMERATED, originalActions = a.nativeActions, strictActions = true))
        assertEquals(ManualDisplayVerdict.UNAVAILABLE, ManualDisplayContinuity.assess(s, scene.copy(nativeActions = null), 30,
            listing = ManualDisplayListing.NOT_ENUMERATED, originalActions = a.nativeActions, strictActions = true))
        val rows = s.nodes.indices.map { i -> if(i == 2) NativeActionSummary.fromIds(listOf(N.ACTION_SELECT))
            else a.nativeActions!!.observedSummary(s, s.nodes.size, i) }
        assertEquals(ManualDisplayVerdict.CHANGED, ManualDisplayContinuity.assess(s, scene.copy(
            nativeActions = NativeNodeActions.forNodeSummaries(s.nodes, rows)), 30,
            listing = ManualDisplayListing.NOT_ENUMERATED, originalActions = a.nativeActions, strictActions = true))
    }

    @Test fun strictFinalGateCannotReuseStabilityWhenGeometryOrNativeActionsChange() {
        val a = P01StabilityFixture.sample(0); val equal = P01StabilityFixture.sample(1)
        assertTrue(PrePresentationStability.sameExact(a.snapshot, a.nativeActions, equal.snapshot, equal.nativeActions))
        assertFalse(PrePresentationStability.sameExact(a.snapshot, a.nativeActions, a.snapshot, a.nativeActions))
        val reusedToken = P01StabilityFixture.result(equal.snapshot.copy(token = a.snapshot.token))
        assertFalse(PrePresentationStability.sameExact(a.snapshot, a.nativeActions, reusedToken.snapshot, reusedToken.nativeActions))
        val moved = P01StabilityFixture.sample(2, expanded = true)
        assertFalse(PrePresentationStability.sameExact(a.snapshot, a.nativeActions, moved.snapshot, moved.nativeActions))
        val changedAction = P01StabilityFixture.result(equal.snapshot)
        assertFalse(PrePresentationStability.sameExact(a.snapshot, a.nativeActions, changedAction.snapshot, changedAction.nativeActions))
    }
}
