package kr.closeforyou.accessibility.capture

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.TextSource
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in REAL accessibility capture. Requires a clean synthetic-only device, manually enabled
 * service and Instrumentation UiAutomation configured not to suppress that accessibility service. */
@RunWith(AndroidJUnit4::class)
class ServiceCaptureOptInTest {
    @Test fun serviceCapturesAndReadsSyntheticDialog() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("captureOcrService") == "true")
        val prefs = CorePreferences.get(instrumentation.targetContext)
        assumeTrue(!prefs.currentSettings().consentGranted && prefs.currentSettings().allowedPackages.isEmpty())
        // Required for coexistence with a separately enabled AccessibilityService.
        val automation = instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        withTimeout(10_000) { AccessibilityRuntime.status.first { it.connected } }
        val host = SyntheticHost.PACKAGE_NAME
        try {
            prefs.updateConsent(true)
            assertTrue(prefs.addAllowedPackage(host))
            prefs.updateCaptureConsent(true)
            prefs.setCaptureAllowed(host, true)
            prefs.setPaused(false)
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) {
                    val event = AccessibilityRuntime.events.first {
                        it is PopupEvent.Candidate && it.snapshot.token.packageName == host &&
                            AccessibilityRuntime.status.value.ocrTextCount > 0
                    } as PopupEvent.Candidate
                    AccessibilityRuntime.extract(event.snapshot)
                }
            }
            automation.launchSyntheticHost()
            val analysis = result.await()
            assertTrue(analysis.texts.any { it.source == TextSource.OCR && it.text.contains("점검") })
        } finally {
            prefs.updateConsent(false)
        }
    }
}
