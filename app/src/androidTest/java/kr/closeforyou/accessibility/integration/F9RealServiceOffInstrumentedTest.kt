package kr.closeforyou.accessibility.integration

import android.app.UiAutomation
import android.content.Context
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.core.model.NextStep
import kr.closeforyou.ui.voice.settings.PresetAutoCloseFeature
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** REAL service, analysis permission for the synthetic app only, automation OFF. Exact restoration. */
@RunWith(AndroidJUnit4::class)
class F9RealServiceOffInstrumentedTest {
    private fun UiAutomation.shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(command))
        .use { it.readBytes().toString(Charsets.UTF_8).trim() }

    @Test fun actualCollectionReachesBInTheRealServiceAndNeverClicksWhileOff() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("f9Synthetic") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val a = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        a.adoptShellPermissionIdentity("android.permission.WRITE_SECURE_SETTINGS")
        val resolver = instrumentation.targetContext.contentResolver
        val services = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val enabled = Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED)
        val app = "kr.closeforyou.popuptest"
        val owner = "kr.closeforyou/kr.closeforyou.accessibility.CloseForYouAccessibilityService"
        val prefs = instrumentation.targetContext.getSharedPreferences("core_privacy_settings", Context.MODE_PRIVATE)
        val original = prefs.all.toMap()
        try {
            withContext(Dispatchers.Main) {
                check(prefs.edit().putBoolean("disclosure_consent_v1", true).putBoolean("collection_paused", false)
                    .putStringSet("allowed_packages", setOf(app)).putBoolean("capture_disclosure_consent_v1", false)
                    .putStringSet("capture_allowed_packages", emptySet()).commit())
            }
            val list = services?.takeIf { it.isNotEmpty() }?.split(':').orEmpty()
            check(Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (list + owner).distinct().joinToString(":")))
            check(Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1"))
            assertTrue("F9_SERVICE_NOT_CONNECTED", withTimeoutOrNull(15_000) {
                while (!AccessibilityRuntime.status.value.connected) delay(100); true
            } == true)
            assertFalse(F9ServiceExecutionFeature.enabled); assertFalse(PresetAutoCloseFeature.live)
            a.shell("am force-stop $app")
            a.shell("am start -W -n $app/.PopupScenarioActivity --es scenario P01 --ez f9_defer_popup true")
            delay(800)
            val before = AccessibilityRuntime.status.value.f9DecisionCount
            val attemptedBefore = AccessibilityRuntime.status.value.f9AttemptedCount
            a.shell("am start -W -f 0x20000000 -n $app/.PopupScenarioActivity --ez f9_show_deferred_popup true")
            assertTrue("F9_SERVICE_NO_B_DECISION connected=${AccessibilityRuntime.status.value.connected} " +
                "block=${AccessibilityRuntime.status.value.lastBlockReason} " +
                "collected=${AccessibilityRuntime.status.value.candidateCount}", withTimeoutOrNull(15_000) {
                while (AccessibilityRuntime.status.value.f9DecisionCount <= before) delay(100); true
            } == true)
            assertNotEquals(NextStep.EXECUTE, AccessibilityRuntime.status.value.lastF9Next)
            assertEquals(attemptedBefore, AccessibilityRuntime.status.value.f9AttemptedCount)
            fun hasClose(node: android.view.accessibility.AccessibilityNodeInfo?): Boolean {
                if (node == null) return false
                @Suppress("DEPRECATION")
                try {
                    if (node.viewIdResourceName == "$app:id/btn_close") return true
                    for (i in 0 until node.childCount) if (hasClose(node.getChild(i))) return true
                    return false
                } finally { node.recycle() }
            }
            assertTrue(hasClose(a.rootInActiveWindow))
        } finally {
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
                check(edit.commit())
                assertEquals(original, prefs.all)
            }
            assertEquals(services, Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
            assertEquals(enabled, Settings.Secure.getString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED))
        }
    }
}
