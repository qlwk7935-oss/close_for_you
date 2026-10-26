package kr.closeforyou.accessibility.integration

import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.R
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.accessibility.CaptureDiagnostics
import kr.closeforyou.accessibility.CaptureDiagnosticSession
import kr.closeforyou.core.model.*
import kr.closeforyou.ui.voice.settings.PresetAutoCloseFeature
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The genuine A service and genuine native C window. The fixture taps only C's labelled choice;
 * F9 alone invokes the target app's button. No production automation switch is enabled. */
@RunWith(AndroidJUnit4::class)
class F9ManualServiceInstrumentedTest {
    private val app = "kr.closeforyou.popuptest"
    private fun UiAutomation.shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Suppress("DEPRECATION")
    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        try {
            if (predicate(node)) return AccessibilityNodeInfo.obtain(node)
            for (i in 0 until node.childCount) find(node.getChild(i), predicate)?.let { return it }
            return null
        } finally { node.recycle() }
    }
    @Suppress("DEPRECATION")
    private fun containsLabel(node: AccessibilityNodeInfo, label: String): Boolean {
        if (node.text?.toString() == label || node.contentDescription?.toString() == label) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { if (containsLabel(child, label)) return true } finally { child.recycle() }
        }
        return false
    }
    @Suppress("DEPRECATION")
    private fun UiAutomation.choice(label: String, enabledOnly: Boolean = true): AccessibilityNodeInfo? {
        val all = windows
        try {
            val owned = all.filter { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
            assertTrue("DUPLICATE_CHOICE_WINDOW", owned.size <= 1)
            for (w in owned) find(w.root) { it.packageName?.toString() == "kr.closeforyou" &&
                it.isClickable && (!enabledOnly || it.isEnabled) && containsLabel(it, label) }?.let { return it }
            return null
        } finally { all.forEach { it.recycle() } }
    }
    @Suppress("DEPRECATION") private fun UiAutomation.targetPresent(): Boolean {
        val node = find(rootInActiveWindow) { it.viewIdResourceName == "$app:id/btn_close" }
        return (node != null).also { node?.recycle() }
    }
    @Suppress("DEPRECATION") private fun UiAutomation.shape(label: String): String {
        val all = windows
        try { return all.joinToString { w ->
            val root = w.root
            try { "type=${w.type};root=${root != null};own=${root?.packageName?.toString() == "kr.closeforyou"};label=${root?.let { containsLabel(it, label) }}" }
            finally { root?.recycle() }
        } } finally { all.forEach { it.recycle() } }
    }

    private suspend fun realService(block: suspend (UiAutomation) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("f9Synthetic") == "true")
        val i = InstrumentationRegistry.getInstrumentation()
        val a = i.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        a.serviceInfo = a.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        val diagnostic = CaptureDiagnosticSession(app)
        CaptureDiagnostics.observer = diagnostic
        val resolver = i.targetContext.contentResolver
        val prefs = i.targetContext.getSharedPreferences("core_privacy_settings", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        a.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val owner = "kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService"
        try {
            withContext(Dispatchers.Main) {
                check(prefs.edit().putBoolean("disclosure_consent_v1", true).putBoolean("collection_paused", false)
                    .putStringSet("allowed_packages", setOf(app)).putBoolean("capture_disclosure_consent_v1", false)
                    .putStringSet("capture_allowed_packages", emptySet()).commit())
            }
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                (services?.split(':').orEmpty().filter { it.isNotEmpty() } + owner).distinct().joinToString(":")))
            check(Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1"))
            assertTrue("SERVICE_NOT_CONNECTED", withTimeoutOrNull(15_000) {
                while (!AccessibilityRuntime.status.value.connected) delay(50); true
            } == true)
            assertFalse(F9ServiceExecutionFeature.enabled); assertFalse(PresetAutoCloseFeature.live)
            a.shell("am start -W -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity")
            a.waitForIdle(200, 5_000); delay(500)
            block(a)
        } finally {
            CaptureDiagnostics.observer = null; diagnostic.close()
            a.shell("am force-stop $app")
            // End the synthetic app while the real service is still connected, allowing genuine
            // detach/removal receipts to finish. Abrupt teardown must not be called a clean reconnect.
            withTimeoutOrNull(2_000) {
                while (true) {
                    val all = a.windows
                    @Suppress("DEPRECATION")
                    val shown = try { all.any { it.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY } }
                        finally { all.forEach { it.recycle() } }
                    if (!shown) break
                    delay(50)
                }
            }
            delay(300)
            try {
                check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services))
                check(Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, enabled))
            } finally { a.dropShellPermissionIdentity() }
            withContext(Dispatchers.Main) {
                val edit = prefs.edit().clear()
                original.forEach { (key, value) -> when (value) {
                    is Boolean -> edit.putBoolean(key, value)
                    is String -> edit.putString(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
                check(edit.commit()); assertEquals(original, prefs.all)
            }
            assertEquals(services, Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
            assertEquals(enabled, Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED))
        }
    }

    @Test fun actualManualChoiceClosesP01AndReportsObservedRemoval() = runBlocking {
        realService { a ->
            a.shell("am force-stop $app")
            assertTrue(a.shell("am start -W -n $app/.PopupScenarioActivity --es scenario P01 --ez f9_defer_popup true").contains("Status: ok"))
            delay(900)
            val before = AccessibilityRuntime.status.value
            assertTrue(a.shell("am start -W -f 0x20000000 -n $app/.PopupScenarioActivity --ez f9_show_deferred_popup true").contains("Status: ok"))
            val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ui_choice_press_candidate, "닫기")
            val button = withTimeoutOrNull(4_000) {
                var found: AccessibilityNodeInfo? = null
                while (found == null) { found = a.choice(label); if (found == null) delay(30) }
                found
            }
            assertNotNull("MANUAL_CHOICE_NOT_SHOWN ${AccessibilityRuntime.status.value} ${CaptureDiagnostics.observer?.snapshot()} ${a.shape(label)}", button)
            @Suppress("DEPRECATION")
            try { assertTrue(requireNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK)) }
            finally { button?.recycle() }
            assertTrue("NO_MANUAL_RESULT ${AccessibilityRuntime.status.value}", withTimeoutOrNull(5_000) {
                while (AccessibilityRuntime.status.value.manualResultCount == before.manualResultCount) delay(50); true
            } == true)
            val after = AccessibilityRuntime.status.value
            assertEquals(ActionStatus.MECHANICAL_SUCCESS, after.lastManualStatus)
            assertEquals(ClickOutcome.CLOSED_CONFIRMED, after.lastManualOutcome)
            assertEquals(before.f9AttemptedCount + 1, after.f9AttemptedCount)
            assertFalse("POPUP_STILL_PRESENT", a.targetPresent())
            delay(300)
            assertEquals(after.f9AttemptedCount, AccessibilityRuntime.status.value.f9AttemptedCount)
        }
    }

    private suspend fun popup(a: UiAutomation, scenario: String = "P01", keep: Boolean = false) {
        a.shell("am force-stop $app")
        check(a.shell("am start -W -n $app/.PopupScenarioActivity --es scenario $scenario --ez f9_defer_popup true --ez f9_keep_popup $keep").contains("Status: ok"))
        delay(900)
        check(a.shell("am start -W -f 0x20000000 -n $app/.PopupScenarioActivity --ez f9_show_deferred_popup true").contains("Status: ok"))
    }
    private fun pressLabel() = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ui_choice_press_candidate, "닫기")
    @Suppress("DEPRECATION") private suspend fun clickChoice(a: UiAutomation) {
        val button = withTimeoutOrNull(4_000) {
            var found: AccessibilityNodeInfo? = null
            while (found == null) { found = a.choice(pressLabel()); if (found == null) delay(30) }
            found
        }
        assertNotNull("CHOICE_MISSING ${AccessibilityRuntime.status.value} ${a.shape(pressLabel())}", button)
        try { assertTrue(requireNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK)) } finally { button?.recycle() }
    }
    private suspend fun resultAfter(count: Long) {
        assertTrue("RESULT_MISSING ${AccessibilityRuntime.status.value}", withTimeoutOrNull(5_000) {
            while (AccessibilityRuntime.status.value.manualResultCount <= count) delay(40); true
        } == true)
    }
    @Test fun clickTrueWithRemainingP01NeverConfirmsAndNeverRetries() = runBlocking {
        realService { a ->
            popup(a, keep = true)
            val before = AccessibilityRuntime.status.value
            clickChoice(a); resultAfter(before.manualResultCount)
            val after = AccessibilityRuntime.status.value
            assertEquals(ActionStatus.MECHANICAL_SUCCESS, after.lastManualStatus)
            // A late interaction/focus invalidation can make the real service's observation
            // UNKNOWN. Neither state may produce success speech; the isolated observer's exact
            // STILL_PRESENT behavior has its own synthetic instrumentation and JVM coverage.
            assertTrue(after.lastManualOutcome in setOf(ClickOutcome.STILL_PRESENT, ClickOutcome.UNKNOWN))
            assertEquals(before.f9AttemptedCount + 1, after.f9AttemptedCount)
            assertTrue(a.targetPresent()); delay(600)
            assertEquals(after.f9AttemptedCount, AccessibilityRuntime.status.value.f9AttemptedCount)
        }
    }
    @Test fun p12NextPaymentNoticeIsCollectedButCannotBePressed() = runBlocking {
        realService { a ->
            popup(a, scenario = "P12")
            val before = AccessibilityRuntime.status.value
            clickChoice(a); resultAfter(before.manualResultCount)
            // Replacing an ad by a decision popup is not CLOSED_CONFIRMED.
            assertNotEquals(ClickOutcome.CLOSED_CONFIRMED, AccessibilityRuntime.status.value.lastManualOutcome)
            assertTrue("P12_NEXT_PROMPT_MISSING ${AccessibilityRuntime.status.value}", withTimeoutOrNull(4_000) {
                while (AccessibilityRuntime.status.value.manualPromptCount < before.manualPromptCount + 2) delay(40); true
            } == true)
            val keep = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ui_choice_keep_open)
            @Suppress("DEPRECATION") val button = withTimeoutOrNull(4_000) {
                var node: AccessibilityNodeInfo? = null
                while (node == null) { node = a.choice(keep); if (node == null) delay(30) }; node
            }
            assertNotNull("P12_KEEP_MISSING ${AccessibilityRuntime.status.value}", button)
            assertNull(a.choice(pressLabel()))
            assertEquals(SafetyReason.IMPORTANT_DECISION, AccessibilityRuntime.status.value.lastF9Reason)
            @Suppress("DEPRECATION") try { requireNotNull(button).performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            finally { @Suppress("DEPRECATION") button?.recycle() }
            delay(400)
            assertEquals(before.f9AttemptedCount + 1, AccessibilityRuntime.status.value.f9AttemptedCount)
        }
    }
    @Test fun originalTtlExpiresExecutionEvidenceButManualDisplayEndsAtThirtySecondsOrRealInvalidation() = runBlocking {
        realService { a ->
            popup(a)
            val before = AccessibilityRuntime.status.value
            // Wait until C is genuinely visible before measuring its display-only lifetime.
            val first = withTimeoutOrNull(4_000) {
                var node: AccessibilityNodeInfo? = null
                while (node == null) { node = a.choice(pressLabel()); if (node == null) delay(30) }; node
            }
            assertNotNull("TTL_CHOICE_MISSING ${AccessibilityRuntime.status.value}", first)
            val shownCount = AccessibilityRuntime.status.value.manualPromptCount
            @Suppress("DEPRECATION") first?.recycle()
            delay(5_200)
            assertNull(AccessibilityRuntime.currentToken())
            // A press is now an explicit REQUEST FOR A NEW F0 read, never use of expired evidence.
            val keep = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ui_choice_keep_open)
            @Suppress("DEPRECATION") val retained = a.choice(keep)
            if (retained == null) {
                // The system status bar can emit a genuine event while this real-service test
                // waits. #29 MUST close C for it; retaining UI by suppressing that event would
                // weaken the barrier. Require the actual protected external-event evidence.
                val state = AccessibilityRuntime.status.value
                assertEquals("UNEXPLAINED_DISPLAY_LOSS $state", PrivacyBlockReason.PROTECTED_APP, state.lastBlockReason)
                assertTrue(state.ownedEventRejection?.endsWith("OTHER_PACKAGE") == true)
                assertNull(state.cleanupFailure)
                assertTrue(a.targetPresent()); assertEquals(before.f9AttemptedCount, state.f9AttemptedCount)
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("f9_ttl_path", "REAL_PROTECTED_EXTERNAL_EVENT")
                })
                return@realService
            }
            @Suppress("DEPRECATION") retained?.recycle()
            @Suppress("DEPRECATION") val intent = a.choice(pressLabel())
            assertNotNull("MANUAL_RECHECK_INTENT_MISSING", intent)
            @Suppress("DEPRECATION") intent?.recycle()
            assertEquals(before.f9AttemptedCount, AccessibilityRuntime.status.value.f9AttemptedCount)
            delay(26_000)
            assertNull(a.choice(keep))
            assertEquals(shownCount, AccessibilityRuntime.status.value.manualPromptCount)
            assertEquals(before.f9AttemptedCount, AccessibilityRuntime.status.value.f9AttemptedCount)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                putString("f9_ttl_path", "THIRTY_SECOND_NATIVE_DEADLINE")
            })
        }
    }
    @Test fun explicitChoiceSixSecondsAfterShowRecollectsBeforeOneP01Click() = runBlocking {
        realService { a ->
            popup(a)
            @Suppress("DEPRECATION") val first = withTimeoutOrNull(4_000) {
                var node: AccessibilityNodeInfo? = null
                while (node == null) { node = a.choice(pressLabel()); if (node == null) delay(30) }; node
            }
            assertNotNull("MANUAL_CHOICE_NOT_SHOWN", first)
            @Suppress("DEPRECATION") first?.recycle()
            val before = AccessibilityRuntime.status.value
            delay(6_000)
            assertNull(AccessibilityRuntime.currentToken())
            clickChoice(a)
            resultAfter(before.manualResultCount)
            val result = AccessibilityRuntime.status.value
            assertEquals(before.f9AttemptedCount + 1, result.f9AttemptedCount)
            assertEquals(ActionStatus.MECHANICAL_SUCCESS, result.lastManualStatus)
        }
    }
    @Test fun consentRevocationImmediatelyDropsManualEvidenceAndWindowWithoutClick() = runBlocking {
        realService { a ->
            popup(a)
            val before = AccessibilityRuntime.status.value
            val shown = withTimeoutOrNull(4_000) {
                var node: AccessibilityNodeInfo? = null
                while (node == null) { node = a.choice(pressLabel()); if (node == null) delay(30) }; node
            }
            assertNotNull("CONSENT_CHOICE_MISSING ${AccessibilityRuntime.status.value}", shown)
            @Suppress("DEPRECATION") shown?.recycle()
            withContext(Dispatchers.Main) {
                kr.closeforyou.app.CorePreferences.get(InstrumentationRegistry.getInstrumentation().targetContext).updateConsent(false)
            }
            delay(400)
            assertNull(AccessibilityRuntime.currentToken()); assertNull(a.choice(pressLabel()))
            assertTrue(a.targetPresent())
            assertEquals(before.f9AttemptedCount, AccessibilityRuntime.status.value.f9AttemptedCount)
        }
    }
    @Test fun cleanServiceReconnectRejectsOldEvidenceAndCollectsANewManualChoice() = runBlocking {
        realService { a ->
            val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
            val owner = "kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService"
            val active = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val before = AccessibilityRuntime.status.value.f9AttemptedCount
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                active.split(':').filter { it != owner }.joinToString(":")))
            assertTrue(withTimeoutOrNull(5_000) { while (AccessibilityRuntime.status.value.connected) delay(40); true } == true)
            assertNull(AccessibilityRuntime.currentToken())
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, active))
            assertTrue(withTimeoutOrNull(5_000) { while (!AccessibilityRuntime.status.value.connected) delay(40); true } == true)
            popup(a)
            val keep = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.ui_choice_keep_open)
            val node = withTimeoutOrNull(4_000) {
                var found: AccessibilityNodeInfo? = null
                while (found == null) { found = a.choice(keep); if (found == null) delay(30) }; found
            }
            assertNotNull("RECONNECTED_CHOICE_MISSING ${AccessibilityRuntime.status.value}", node)
            assertEquals(before, AccessibilityRuntime.status.value.f9AttemptedCount)
            @Suppress("DEPRECATION") try { requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK) }
            finally { @Suppress("DEPRECATION") node?.recycle() }
            delay(400)
            assertFalse(F9ServiceExecutionFeature.enabled); assertFalse(PresetAutoCloseFeature.live)
        }
    }
}
