package kr.closeforyou.accessibility.action

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionPopupClassifier
import kr.closeforyou.decision.DecisionActionPolicy
import kr.closeforyou.core.contract.DecisionContext
import kr.closeforyou.core.contract.*
import kr.closeforyou.accessibility.integration.F9FeedbackRouter
import kr.closeforyou.ui.voice.AnnouncementBatcher
import kr.closeforyou.ui.voice.SpeechHandlingListener
import kr.closeforyou.ui.voice.UiFormat
import kr.closeforyou.accessibility.integration.*
import kr.closeforyou.ui.voice.settings.AutoCloseConsent
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit synthetic-only harness, NOT evidence of connected production B/C policy.
 * Uses the same main-source reader, gate, ACTION_CLICK and outcome observer as the future service.
 * UiAutomation supplies accessibility handles; it temporarily suppresses services, without changing
 * persisted accessibility settings or granting any production auto-close permission.
 */
@RunWith(AndroidJUnit4::class)
class F9SyntheticClickInstrumentedTest {
    private val pkg = "kr.closeforyou.popuptest"
    private val rejected = mutableListOf<F9ReadStage>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun automation(): UiAutomation {
        assumeTrue(InstrumentationRegistry.getArguments().getString("f9Synthetic") == "true")
        val keepServices = InstrumentationRegistry.getArguments().getString("f9KeepServices") == "true"
        return instrumentation.getUiAutomation(if (keepServices) UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES else 0).also { a ->
            a.serviceInfo = a.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
        }
    }
    private fun UiAutomation.shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8) }
    private suspend fun launch(a: UiAutomation, scenario: String, keepPopup: Boolean = false, deferPopup: Boolean = false) {
        a.shell("am force-stop $pkg")
        check("Status: ok" in a.shell("am start -W -n $pkg/.PopupScenarioActivity --es scenario $scenario --ez f9_keep_popup $keepPopup --ez f9_defer_popup $deferPopup")) { "F9_HOST_START_FAILED" }
        a.waitForIdle(200, 5_000)
        delay(200)
    }
    private fun port(a: UiAutomation) = AndroidF9ScreenPort(instrumentation.targetContext,
        { a.rootInActiveWindow }, { a.windows }, { rejected += it }, packageAllowed = { it == pkg })

    @Test fun unconsentedAppSuppliesDepartureIdentityButNoTreeOrClickableTarget() = runBlocking {
        val a = automation(); launch(a, "P01", deferPopup = true)
        val denied = AndroidF9ScreenPort(instrumentation.targetContext, { a.rootInActiveWindow }, { a.windows },
            packageAllowed = { false })
        // Fixture preparation only, before issuing any popup snapshot/TTL. A first geometry
        // read can exceed the unchanged 100 ms F0 budget; never weaken it for the test.
        val scene = withTimeoutOrNull(3_000) {
            var found: F9Scene? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) { denied.read {} }
                if (found == null) delay(50)
            }; found
        }
        assertNotNull(scene); assertFalse(requireNotNull(scene).complete)
        assertTrue(scene.nodes.isEmpty()); assertTrue(scene.visiblePaths.isEmpty()); assertNull(scene.pageDigest)
    }

    private suspend fun preparedP01(a: UiAutomation, keepPopup: Boolean = false,
        scenario: String = "P01", beforePopup: (AndroidF9ScreenPort) -> Unit = {}): Pair<AndroidF9ScreenPort, F9Reference> {
        launch(a, scenario, keepPopup = keepPopup, deferPopup = true)
        val p = port(a); val before = page(p)
        withContext(Dispatchers.Main) { beforePopup(p) }
        check("Status: ok" in a.shell("am start -W -f 0x20000000 -n $pkg/.PopupScenarioActivity --ez f9_show_deferred_popup true")) {
            "F9_DEFERRED_POPUP_START_FAILED"
        }
        // No original popup snapshot exists until reference() reads the now-visible dialog.
        // The independent base-page proof and the eventual snapshot both keep their 5 s TTL.
        return p to reference(p, before)
    }

    private suspend fun reference(p: AndroidF9ScreenPort, page: F9PageProof? = null): F9Reference {
        val first = withTimeout(5_000) {
            var found: F9Scene? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) { p.read {} }
                if (found?.nodes?.none { it.viewId == "$pkg:id/btn_close" } == true) found = null
                if (found == null) delay(100)
            }
            found
        }
        // Let the synthetic Dialog's entry animation finish BEFORE issuing the original snapshot.
        // This is fixture preparation, never a production event exception or a renewed action TTL.
        delay(300)
        val scene = withContext(Dispatchers.Main) { p.read {} } ?: error("F9_SETTLED_POPUP_UNREADABLE")
        assertEquals("Synthetic popup changed during fixture preparation", first.windowId, scene.windowId)
        check(scene.packageName == pkg) { "F9_SYNTHETIC_PACKAGE_REQUIRED" }
        assertTrue("F9 requires an independently read page", scene.windowId != scene.pageWindowId || page != null)
        val node = scene.nodes.single { it.viewId == "$pkg:id/btn_close" }
        assertEquals("F9 target role must be native Button", "android.widget.Button", node.className)
        assertTrue("F9 target must be visible", node.path in scene.visiblePaths)
        val token = SnapshotToken(1, scene.windowId, pkg, "synthetic-p01", "synthetic-structure", "synthetic-content", 1)
        return F9Reference(PopupSnapshot(token, SystemClock.uptimeMillis(), scene.nodes.toPersistentList(),
            scene.windows.toPersistentList(), scene.screen, PopupTrigger.WINDOW_CHANGED, SnapshotCompleteness.COMPLETE),
            CloseCandidate("synthetic-close", node.path, node.viewId, node.text, node.bounds,
                EvidenceLevel.E2_LABELED_NODE, ButtonRole.CLOSE), 1, 1, page)
    }
    private suspend fun page(p: AndroidF9ScreenPort): F9PageProof {
        // Acquisition can wait for a usable page before the popup is shown. Every individual F0
        // pass retains its strict 100 ms budget; no action/reference exists yet, so no press retries.
        val scene = withTimeout(5_000) {
            var found: F9Scene? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) { p.read {} }
                if (found?.packageName != pkg || found?.nodes?.any { it.viewId == "$pkg:id/btn_close" } == true) found = null
                if (found == null) delay(50)
            }
            found
        }
        check(scene.packageName == pkg && scene.nodes.none { it.viewId == "$pkg:id/btn_close" }) { "F9_BASE_PAGE_SCOPE" }
        return F9PageProof(pkg, scene.windowId, requireNotNull(scene.pageDigest), SystemClock.uptimeMillis())
    }
    private fun state(r: F9Reference) = F9RuntimeState(r.snapshot.token, 1, 1, connected = true,
        collectionConsent = true, appAllowed = true, autoCloseConsent = true, presetAllowsAd = true,
        paused = false, userInteracting = false, cleanupReady = true)

    /** Deliberately only in androidTest: fixture-only eligibility/consent, with the REAL B policy.
     * Main now classifies P01 as COUPON. Both COUPON and ADVERTISEMENT are explicitly allowed here
     * only; production's evidence source/preset/consent remain absent and OFF.
     */
    private class SyntheticPolicy(onReport: (HandlingReport) -> Unit = {}) : F9PolicyBridge {
        val results = mutableListOf<ActionResult>()
        val reports = mutableListOf<HandlingReport>()
        private val policy = DecisionActionPolicy(allowAutoSkip = false, nowUptimeMillis = SystemClock::uptimeMillis)
        private val bridge = F9ActionPolicyBridge(policy, evidence = ::evidence, onReport = { report ->
            reports += report; onReport(report)
        })
        private suspend fun evidence(reference: F9Reference, scene: F9Scene): F9PolicyInput {
            val eligibility = AutoEligibility(reference.snapshot.token, EligibilityCheck.entries.toPersistentSet(),
                reference.snapshot.capturedAtUptimeMillis)
            val analysis = PopupAnalysis(reference.snapshot.token, texts = scene.nodes.flatMap { n ->
                listOfNotNull(n.text, n.contentDescription).map { ExtractedText(it, n.bounds, TextSource.ACCESSIBILITY_TREE, null) }
            }.toPersistentList(), closeCandidates = kotlinx.collections.immutable.persistentListOf(reference.candidate), autoEligibility = eligibility)
            val classified = analysis.copy(classification = DecisionPopupClassifier().classify(analysis))
            return F9PolicyInput(classified, DecisionContext(reference.snapshot.token, null, null, false, true,
                persistentSetOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON)))
        }
        override suspend fun authorize(reference: F9Reference, scene: F9Scene) = bridge.authorize(reference, scene)
        override fun onActionResult(result: ActionResult) = bridge.onActionResult(result)
        override fun onExecutionResult(result: ActionResult, policyConsulted: Boolean) {
            results += result
            bridge.onExecutionResult(result, policyConsulted)
        }
    }

    @Test fun p01RealActionClickRemovesPopupExactlyOnce() = runBlocking {
        val a = automation(); val (p, r) = preparedP01(a)
        val announcements = mutableListOf<AnnouncementRequest>()
        val announcer = object : PopupAnnouncer {
            override suspend fun announce(request: AnnouncementRequest): AnnouncementReceipt {
                announcements += request; return AnnouncementReceipt(AnnouncementState.UI_UPDATED)
            }
            override suspend fun repeatLast() = AnnouncementReceipt(AnnouncementState.NOT_IMPLEMENTED)
            override fun stop() = Unit
        }
        val feedbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val format = UiFormat { id, args -> instrumentation.targetContext.getString(id, *args) }
        val batcher = AnnouncementBatcher(feedbackScope, SpeechHandlingListener.batchFlush(announcer, format))
        val feedback = F9FeedbackRouter(announcer, batcher, format)
        val policy = SyntheticPolicy { report -> feedbackScope.launch { feedback.deliver(report) } }
        val closer = F9AutoCloser(p, { state(r) }, F9Clock(SystemClock::uptimeMillis), policy)
        try {
            val result = closer.execute(r)
            assertTrue("P01 actual action was blocked: ${result.block}", result.attempted)
            assertEquals(true, result.clickReturned)
            assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
            assertEquals(1, policy.results.size)
            assertFalse(closer.execute(r).attempted)
            assertEquals(1, policy.reports.size); assertNull(policy.reports.single().title)
            delay(2_100) // #51's real batch window; fake announcer, no TalkBack claim.
            assertTrue(announcements.single().text.contains("닫았어요"))
            val current = withContext(Dispatchers.Main) { p.read {} }
            assertNotNull(current)
            assertEquals(pkg, current!!.packageName)
            assertTrue(current.windows.none { it.id == r.snapshot.token.windowId })
        } finally {
            withContext(Dispatchers.Main) { closer.close(); feedback.close(); batcher.cancel() }
            feedbackScope.cancel()
        }
    }

    @Test fun p01ProductionWithoutPolicyRemainsOffEvenWithSyntheticConsent() = runBlocking {
        val a = automation(); val (p, r) = preparedP01(a)
        val closer = F9AutoCloser(p, { state(r) }, F9Clock(SystemClock::uptimeMillis))
        try {
            val result = closer.execute(r)
            assertFalse(result.attempted); assertEquals(F9Block.POLICY_UNAVAILABLE, result.block)
            val current = withContext(Dispatchers.Main) { p.read {} }
            assertNotNull(current); assertEquals(r.snapshot.token.windowId, current!!.windowId)
        } finally { closer.close() }
    }

    @Test fun p01RealClickReturnsTrueButRemainingPopupIsNotSuccess() = runBlocking {
        val a = automation(); val (p, r) = preparedP01(a, keepPopup = true); val policy = SyntheticPolicy()
        val closer = F9AutoCloser(p, { state(r) }, F9Clock(SystemClock::uptimeMillis), policy,
            allowSyntheticPolicy = true)
        try {
            val result = closer.execute(r)
            assertTrue(result.attempted); assertEquals(true, result.clickReturned)
            val observed = withContext(Dispatchers.Main) { p.read {} }
            val facts = "reader=${rejected.lastOrNull()},readable=${observed != null},sameWindow=${observed?.windowId == r.snapshot.token.windowId}," +
                "sameNodes=${observed?.nodes == r.snapshot.nodes},sameWindows=${observed?.windows == r.snapshot.windows}," +
                "nodeChanges=" + observed?.nodes?.zip(r.snapshot.nodes)?.flatMap { (a, b) -> buildList {
                    if (a.path != b.path) add("path"); if (a.viewId != b.viewId) add("viewId")
                    if (a.className != b.className) add("class"); if (a.text != b.text) add("text")
                    if (a.contentDescription != b.contentDescription) add("description")
                    if (a.clickable != b.clickable) add("clickable"); if (a.enabled != b.enabled) add("enabled")
                    if (a.bounds != b.bounds) add("bounds"); if (a.actions != b.actions) add("actions")
                } }?.distinct()
            assertEquals(facts, ClickOutcome.STILL_PRESENT, result.outcome)
            assertFalse(policy.reports.single().closedConfirmed)
            assertFalse(closer.execute(r).attempted); assertEquals(2, policy.results.size)
            assertEquals(1, policy.results.count { it.attempted })
        } finally { closer.close() }
    }

    @Test fun p08PaymentP10ConsentP11PasswordAndP18MixedAdCannotPassLiveReader() = runBlocking {
        val a = automation()
        for (scenario in listOf("P08", "P10", "P11", "P18")) {
            launch(a, scenario)
            val current = withContext(Dispatchers.Main) { port(a).read {} }
            assertNull("Unsafe synthetic scenario accepted: $scenario", current)
        }
    }

    /** No synthetic EligibilityCheck.entries: the new A adapter must earn the five conditions
     * from B wiring and the real Android reader. Only consent/execution input is fixture-owned.
     */
    @Test fun serviceSessionRealWiringAndEligibilityCloseP01AndRejectTheDuplicate() = runBlocking {
        val a = automation(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = F9CollectedSource(SystemClock::uptimeMillis)
        val reports = mutableListOf<HandlingReport>(); val event = CompletableDeferred<PopupEvent.Candidate>()
        lateinit var session: F9ServiceSession
        var closeSession: (() -> Unit)? = null
        try {
            val (_, reference) = preparedP01(a) { p ->
                session = F9ServiceSession(source, p, F9Clock(SystemClock::uptimeMillis), {
                    F9SessionAccess(true, true, true, false, false,
                        AutoCloseConsent(true, persistentSetOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON), null), true)
                }, { true }, reports::add, executionEnabled = { true })
                closeSession = { session.close(2) }
                session.observePage()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.wiring.source.events.collect { if (it is PopupEvent.Candidate) event.complete(it) }
                }
            }
            withContext(Dispatchers.Main) {
                source.publish(reference.snapshot, PopupDetection(reference.snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW)))
            }
            val candidate = event.await()
            val handled = withContext(Dispatchers.Main) { session.handle(candidate) }
            assertEquals(NextStep.EXECUTE, handled.decision?.next)
            assertTrue(requireNotNull(handled.result).closedConfirmed)
            assertEquals(1, reports.size); assertNull(reports.single().title)
            assertNull(withContext(Dispatchers.Main) { session.handle(candidate) }.decision)
            assertEquals(1, reports.size)
        } finally {
            withContext(Dispatchers.Main) { closeSession?.invoke() }
            scope.cancel()
        }
    }

    @Test fun serviceSessionRealWiringKeepsAResidentPopupUnconfirmed() = runBlocking {
        val a = automation(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = F9CollectedSource(SystemClock::uptimeMillis)
        val reports = mutableListOf<HandlingReport>(); val event = CompletableDeferred<PopupEvent.Candidate>()
        lateinit var session: F9ServiceSession
        var closeSession: (() -> Unit)? = null
        try {
            val (_, reference) = preparedP01(a, keepPopup = true) { p ->
                session = F9ServiceSession(source, p, F9Clock(SystemClock::uptimeMillis), {
                    F9SessionAccess(true, true, true, false, false,
                        AutoCloseConsent(true, persistentSetOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON), null), true)
                }, { true }, reports::add, executionEnabled = { true })
                closeSession = { session.close(2) }
                session.observePage()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.wiring.source.events.collect { if (it is PopupEvent.Candidate) event.complete(it) }
                }
            }
            withContext(Dispatchers.Main) { source.publish(reference.snapshot,
                PopupDetection(reference.snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW))) }
            val handled = withContext(Dispatchers.Main) { session.handle(event.await()) }
            assertTrue(requireNotNull(handled.result).attempted)
            assertEquals(ClickOutcome.STILL_PRESENT, handled.result!!.outcome)
            assertFalse(reports.single().closedConfirmed)
        } finally {
            withContext(Dispatchers.Main) { closeSession?.invoke() }
            scope.cancel()
        }
    }

    @Test fun serviceSessionP12StopsAtTheNextNoticeWithDecisionWording() = runBlocking {
        val a = automation(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val source = F9CollectedSource(SystemClock::uptimeMillis)
        val reports = mutableListOf<HandlingReport>(); val events = Channel<PopupEvent.Candidate>(Channel.CONFLATED)
        lateinit var session: F9ServiceSession
        var closeSession: (() -> Unit)? = null
        try {
            val (p, first) = preparedP01(a, scenario = "P12") { port ->
                session = F9ServiceSession(source, port, F9Clock(SystemClock::uptimeMillis), {
                    F9SessionAccess(true, true, true, false, false, AutoCloseConsent(true,
                        persistentSetOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON, PopupClassification.NOTICE), null), true)
                }, { true }, reports::add, executionEnabled = { true })
                closeSession = { session.close(3) }; session.observePage()
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    session.wiring.source.events.collect { if (it is PopupEvent.Candidate) events.trySend(it) }
                }
            }
            withContext(Dispatchers.Main) { source.publish(first.snapshot,
                PopupDetection(first.snapshot.token, persistentSetOf(PopupSignal.NEW_WINDOW))) }
            val handled = withContext(Dispatchers.Main) { session.handle(events.receive()) }
            assertTrue(requireNotNull(handled.result).attempted)
            assertFalse(handled.result!!.closedConfirmed) // Replacement is not an unchanged base page.
            assertEquals(1, reports.size)
            // P12's next notice literally mentions payment. Keep F0's conservative rejection;
            // never weaken it merely to progress automatically to the following consent dialog.
            assertNull("P12 decision-word notice must be refused", withContext(Dispatchers.Main) { p.read {} })
            val root = requireNotNull(a.rootInActiveWindow)
            @Suppress("DEPRECATION")
            try { assertNotEquals(first.snapshot.token.windowId, root.windowId) } finally { root.recycle() }
        } finally {
            withContext(Dispatchers.Main) { closeSession?.invoke() }
            events.close(); scope.cancel()
        }
    }
}
