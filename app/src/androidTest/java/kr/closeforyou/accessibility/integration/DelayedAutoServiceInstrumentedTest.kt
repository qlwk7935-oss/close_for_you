package kr.closeforyou.accessibility.integration

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.accessibility.*
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.ui.voice.settings.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in real service test. Requires prior human accessibility/consent/allowlist/preset UI setup.
 * No secure-settings adoption, consent writes, synthetic service results or target-button taps.
 * UiAutomation clicks ONLY our Debug enable/confirmation UI. F9 alone presses the delayed target.
 */
@RunWith(AndroidJUnit4::class)
class DelayedAutoServiceInstrumentedTest {
    private val fixture = "kr.closeforyou.popuptest"
    private fun UiAutomation.shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private fun UiAutomation.start(command: String) { assertTrue("SYNTHETIC_START_FAILED", shell(command).contains("Status: ok")) }
    private suspend fun waitFor(code: String, millis: Long = 6_000, predicate: () -> Boolean) {
        assertTrue(code, withTimeoutOrNull(millis) { while (!predicate()) delay(25); true } == true)
    }

    // Read only the A Debug activation UI; verify package/window before any content/child getters.
    @Suppress("DEPRECATION")
    private fun click(node: AccessibilityNodeInfo, window: Int, id: String, budget: IntArray, depth: Int = 0): Boolean {
        check(++budget[0] <= 400 && depth <= 32) { "DEBUG_UI_BUDGET" }
        check(node.packageName?.toString() == "kr.closeforyou" && node.windowId == window) { "DEBUG_UI_NOT_OWNED" }
        if (node.viewIdResourceName == id) {
            check(node.isClickable && node.isEnabled) { "DEBUG_UI_DISABLED" }
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val accepted = CollectionResourceSafety.run(body = { click(child, window, id, budget, depth + 1) },
                cleanup = { child.recycle() }, cleanupFailure = { throw CollectionCleanupFailure() })
            if (accepted) return true
        }
        return false
    }
    @Suppress("DEPRECATION") private fun UiAutomation.clickActivation(id: String): Boolean {
        val root = rootInActiveWindow ?: return false
        return CollectionResourceSafety.run(body = { click(root, root.windowId, id, intArrayOf(0)) },
            cleanup = { root.recycle() }, cleanupFailure = { throw CollectionCleanupFailure() })
    }

    private suspend fun trial(background: String, show: String) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("delayedAutoSynthetic") == "true")
        val i = InstrumentationRegistry.getInstrumentation()
        val a = i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val oldFlags = a.serviceInfo.flags
        var failedBeforeCleanup = false
        try {
            val settings = CorePreferences.get(i.targetContext).currentSettings()
            val ui = UiVoicePreferences.get(i.targetContext).current()
            assertTrue("HUMAN_SERVICE_SETUP_REQUIRED", AccessibilityRuntime.status.value.connected)
            assertTrue("HUMAN_COLLECTION_CONSENT_REQUIRED", settings.consentGranted && !settings.paused)
            assertTrue("HUMAN_ALLOWLIST_REQUIRED", fixture in settings.allowedPackages && fixture !in ui.helpAppsExcluded)
            assertTrue("HUMAN_AUTO_CLOSE_CONSENT_REQUIRED", ui.autoCloseConsentNow(live = true).granted)
            a.serviceInfo = a.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
            a.shell("am force-stop $fixture")
            a.start("am start -W -n kr.closeforyou/.accessibility.testing.DelayedAutoCloseActivity")
            waitFor("ACTIVATION_NOT_READY") { a.clickActivation("kr.closeforyou:id/a_enable_delayed_auto") }
            waitFor("CONFIRMATION_NOT_READY") { a.clickActivation("android:id/button1") }
            waitFor("ACTIVATION_FAILED") { DebugDelayedAutoClose.ticket() != null }
            val prompts = AccessibilityRuntime.status.value.manualPromptCount
            val pages = DebugDelayedAutoClose.observation.value.pages
            a.start(background)
            // Observe an independently collected real background; do not invent/renew page proof.
            waitFor("BACKGROUND_F0_NOT_VERIFIED") { DebugDelayedAutoClose.observation.value.pages > pages }
            a.start(show)
            delay(1_000)
            assertEquals("PRESS_BEFORE_DELAY", 0, DebugDelayedAutoClose.observation.value.attempts)
            waitFor("REAL_B_WAIT_NOT_OBSERVED") { DelayedAutoPhase.WAITING_FOR_TARGET in DebugDelayedAutoClose.observation.value.seen }
            waitFor("F9_TERMINAL_RESULT_NOT_OBSERVED") { DelayedAutoPhase.F9_RESULT in DebugDelayedAutoClose.observation.value.seen }
            val observed = DebugDelayedAutoClose.observation.value
            assertTrue("NO_FRESH_CONTINUATION", DelayedAutoPhase.REOBSERVED in observed.seen)
            assertEquals("CLOSE_NOT_CONFIRMED", ClickOutcome.CLOSED_CONFIRMED, observed.outcome)
            assertEquals("WRONG_PRESS_COUNT", 1, observed.attempts)
            assertEquals("MANUAL_PROMPT_DURING_AUTOMATIC_WAIT", prompts, AccessibilityRuntime.status.value.manualPromptCount)
            delay(500)
            assertEquals("DUPLICATE_PRESS", 1, DebugDelayedAutoClose.observation.value.attempts)
            assertNull("TRIAL_DID_NOT_END", DebugDelayedAutoClose.ticket())
        } catch (cancelled: CancellationException) {
            failedBeforeCleanup = true; throw cancelled
        } catch (assertion: AssertionError) {
            failedBeforeCleanup = true; throw assertion // Only the fixed/scalar assertions above.
        } catch (_: Exception) {
            failedBeforeCleanup = true; throw AssertionError("DELAYED_AUTO_FRAMEWORK_FAILURE")
        } catch (fatal: Error) {
            failedBeforeCleanup = true; throw fatal
        }
        finally {
            // Independent cleanups; an old failure/cancellation wins, cleanup-only failure fails.
            var failed = false
            try { withContext(NonCancellable + Dispatchers.Main) { DebugDelayedAutoClose.userOff() } }
            catch (_: Exception) { failed = true }
            try { a.shell("am force-stop $fixture") } catch (_: Exception) { failed = true }
            try {
                a.serviceInfo = a.serviceInfo.apply { flags = oldFlags }
                check(a.serviceInfo.flags == oldFlags) { "FLAGS_NOT_RESTORED" }
            } catch (_: Exception) { failed = true }
            if (!failedBeforeCleanup && failed) throw CollectionCleanupFailure()
        }
    }
    @Test fun p23RealDelayedCouponIsPressedOnceAndClosureIsObserved() = runBlocking {
        trial("am start -W -n $fixture/.PopupScenarioActivity --es scenario P23 --ez f9_defer_popup true",
            "am start -W -f 0x20000000 -n $fixture/.PopupScenarioActivity --ez f9_show_deferred_popup true")
    }
    @Test fun independentSafeDelayedAdIsPressedOnceAndClosureIsObserved() = runBlocking {
        trial("am start -W -n $fixture/.ADelayedAdActivity",
            "am start -W -f 0x20000000 -n $fixture/.ADelayedAdActivity --ez show_delayed true")
    }
}
