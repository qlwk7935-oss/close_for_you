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
    private suspend fun launch(a: UiAutomation, scenario: String, delayMillis: Long = 0L, keepPopup: Boolean = false) {
        a.shell("am force-stop $pkg")
        check("Status: ok" in a.shell("am start -W -n $pkg/.PopupScenarioActivity --es scenario $scenario --el f9_popup_delay_ms $delayMillis --ez f9_keep_popup $keepPopup")) { "F9_HOST_START_FAILED" }
        a.waitForIdle(200, 5_000)
        delay(200)
    }
    private fun port(a: UiAutomation) = AndroidF9ScreenPort(instrumentation.targetContext,
        { a.rootInActiveWindow }, { a.windows }, { rejected += it })

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
        val scene = withTimeout(1_500) {
            var found: F9Scene? = null
            while (found == null) {
                found = withContext(Dispatchers.Main) { p.read {} }
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

    /** Deliberately only in androidTest. This is NOT the absent B auto-close implementation. */
    private class SyntheticPolicy : F9PolicyBridge {
        val results = mutableListOf<ActionResult>()
        override suspend fun authorize(reference: F9Reference, scene: F9Scene): F9Approval? {
            val analysis = PopupAnalysis(reference.snapshot.token, texts = scene.nodes.flatMap { n ->
                listOfNotNull(n.text, n.contentDescription).map { ExtractedText(it, n.bounds, TextSource.ACCESSIBILITY_TREE, null) }
            }.toPersistentList())
            if (DecisionPopupClassifier().classify(analysis) != PopupClassification.ADVERTISEMENT) return null
            return F9Approval(ActionDecision(reference.snapshot.token, ActionAuthorization.ALLOW_ONCE,
                persistentSetOf(), reference.candidate.id, source = DecisionSource.PRESET), AutoEligibility(reference.snapshot.token,
                EligibilityCheck.entries.toPersistentSet(), reference.snapshot.capturedAtUptimeMillis), F9ApprovalOrigin.SYNTHETIC_TEST)
        }
        override fun onActionResult(result: ActionResult) { results += result }
    }

    @Test fun p01RealActionClickRemovesPopupExactlyOnce() = runBlocking {
        val a = automation(); launch(a, "P01", 2_000); val p = port(a); val page = page(p); val r = reference(p, page)
        val policy = SyntheticPolicy(); val announcements = mutableListOf<String>()
        val closer = F9AutoCloser(p, { state(r) }, F9Clock(SystemClock::uptimeMillis), policy,
            { announcements += F9ResultFeedback.request(it.toActionResult()).text }, allowSyntheticPolicy = true)
        try {
            val result = closer.execute(r)
            assertTrue("P01 actual action was blocked: ${result.block}", result.attempted)
            assertEquals(true, result.clickReturned)
            assertEquals(ClickOutcome.CLOSED_CONFIRMED, result.outcome)
            assertEquals(1, policy.results.size)
            assertTrue(announcements.single().contains("닫았어요"))
            assertFalse(closer.execute(r).attempted)
            val current = withContext(Dispatchers.Main) { p.read {} }
            assertNotNull(current)
            assertEquals(pkg, current!!.packageName)
            assertTrue(current.windows.none { it.id == r.snapshot.token.windowId })
        } finally { closer.close() }
    }

    @Test fun p01ProductionWithoutPolicyRemainsOffEvenWithSyntheticConsent() = runBlocking {
        val a = automation(); launch(a, "P01", 2_000); val p = port(a); val page = page(p); val r = reference(p, page)
        val closer = F9AutoCloser(p, { state(r) }, F9Clock(SystemClock::uptimeMillis))
        try {
            val result = closer.execute(r)
            assertFalse(result.attempted); assertEquals(F9Block.POLICY_UNAVAILABLE, result.block)
            val current = withContext(Dispatchers.Main) { p.read {} }
            assertNotNull(current); assertEquals(r.snapshot.token.windowId, current!!.windowId)
        } finally { closer.close() }
    }

    @Test fun p01RealClickReturnsTrueButRemainingPopupIsNotSuccess() = runBlocking {
        val a = automation(); launch(a, "P01", 2_000, keepPopup = true)
        val p = port(a); val r = reference(p, page(p)); val policy = SyntheticPolicy()
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
            assertFalse(F9ResultFeedback.request(result.toActionResult()).text.contains("닫았어요"))
            assertFalse(closer.execute(r).attempted); assertEquals(1, policy.results.size)
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
}
