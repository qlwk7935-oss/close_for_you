package kr.closeforyou.accessibility.capture

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

internal object SyntheticHost {
    const val PACKAGE_NAME = "kr.closeforyou.capturetest"
    const val ACTIVITY_NAME = "kr.closeforyou.capturetest.SyntheticPopupActivity"
}

/** Launches the installed host in ITS OWN process, never ActivityScenario in the test classloader.
 * Opt-in on a synthetic-only device. A missing/crashed host must fail, not be silently skipped. */
@RunWith(AndroidJUnit4::class)
class SyntheticHostLaunchTest {
    @Test fun instrumentationCanLoadTargetKotlinRuntime() {
        // Instrumentation uses the target application's runtime, unlike a standalone test APK.
        val runtime = Class.forName("kotlin.jvm.internal.Intrinsics", true, javaClass.classLoader)
        assertEquals("kotlin.jvm.internal.Intrinsics", runtime.name)
    }

    @Suppress("DEPRECATION") // Release only the framework nodes owned by this test.
    @Test fun standaloneHostDisplaysItsDialog() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("syntheticHost") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automation.launchSyntheticHost()
        withTimeout(10_000) {
            while (true) {
                val root = automation.rootInActiveWindow
                val displayed = try {
                    if (root?.packageName?.toString() != SyntheticHost.PACKAGE_NAME) false else {
                        val buttons = root.findAccessibilityNodeInfosByText("닫기")
                        try { buttons.any { it.text?.toString() == "닫기" && it.isClickable } }
                        finally { buttons.forEach { it.recycle() } }
                    }
                } finally { root?.recycle() }
                if (displayed) break
                delay(100)
            }
        }
        assertTrue(automation.rootInActiveWindow?.let { root ->
            try { root.packageName?.toString() == SyntheticHost.PACKAGE_NAME }
            finally { root.recycle() }
        } == true)
    }
}

/** Shell launch gives the host its ordinary, separate application classloader on newer Android too. */
internal fun UiAutomation.launchSyntheticHost() {
    ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand("am force-stop ${SyntheticHost.PACKAGE_NAME}"))
        .use { it.readBytes() }
    val output = ParcelFileDescriptor.AutoCloseInputStream(executeShellCommand(
        "am start -W -n ${SyntheticHost.PACKAGE_NAME}/${SyntheticHost.ACTIVITY_NAME}",
    )).use { it.readBytes().toString(Charsets.UTF_8) }
    assertTrue("Synthetic host shell launch failed", "Status: ok" in output)
}
