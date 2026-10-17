package kr.closeforyou.servicetest

import android.app.UiAutomation
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** External runner: am instrument targets ONLY the synthetic host, never the service process.
 * All app state/analysis remains in the real application's classloader behind a debug-only IPC. */
@RunWith(AndroidJUnit4::class)
class ServiceCaptureOptInTest {
    @Test fun serviceCapturesAndReadsSyntheticDialog() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureOcrService") == "true")
        assertEquals("WRONG_INSTRUMENTATION_TARGET", HOST, instrumentation.targetContext.packageName)
        assertFalse("TEST_RUNNING_ON_MAIN", Looper.myLooper() == Looper.getMainLooper())
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            ?: throw AssertionError("UIAUTOMATION_UNAVAILABLE")
        // Public SDK has no flags getter; request preservation, then verify actual app callbacks.
        val resolver = instrumentation.targetContext.contentResolver
        suspend fun call(method: String, session: String? = null, optIn: Boolean = false): Bundle = withContext(Dispatchers.IO) {
            try {
                resolver.call(PROBE, method, session, Bundle().apply { putBoolean("explicitOptIn", optIn) })
                    ?: reply("PROBE_UNAVAILABLE")
            } catch (_: SecurityException) { reply("PROBE_PERMISSION_DENIED") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { reply("PROBE_UNAVAILABLE") }
        }
        var last = reply("PROBE_UNAVAILABLE")
        val ready = withTimeoutOrNull(10_000) {
            while (true) {
                last = call("STATUS")
                if (last.getString("code") == "READY") break
                if (last.getString("code") == "PROBE_PERMISSION_DENIED") break
                delay(100)
            }
            last.getString("code") == "READY"
        } == true
        if (!ready) {
            val framework = withContext(Dispatchers.IO) {
                // Do not attach raw dumpsys to any test result/log/file. Only the parsed enum escapes.
                FrameworkAccessibilitySummary.parse(shell(automation, "dumpsys accessibility"))
            }
            val code = safeCode(last.getString("code"))
            val main = last.getBoolean("mainResponsive")
            val phase = safeCode(last.getString("phase"))
            val creates = last.getInt("creates").coerceIn(0, 999)
            val callbacks = last.getInt("connections").coerceIn(0, 999)
            throw AssertionError("SERVICE_CONNECTION_TIMEOUT:$code;PHASE=$phase;MAIN=$main;CREATES=$creates;CALLBACKS=$callbacks;FRAMEWORK=${framework.name}")
        }
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SERVICE_CONNECTION_READY\n") })
        if (arguments.getString("connectionOnly") == "true") return@runBlocking
        var session: String? = null
        try {
            val begun = call("BEGIN", optIn = true)
            assertEquals("SYNTHETIC_SESSION_NOT_STARTED", "STARTED", safeCode(begun.getString("code")))
            session = requireNotNull(begun.getString("session"))
            // Recreate only the synthetic task, never force-stop either application.
            withContext(Dispatchers.IO) {
                val launched = shell(automation, "am start -W -f 0x10008000 -n $HOST/kr.closeforyou.capturetest.SyntheticPopupActivity")
                assertTrue("SYNTHETIC_HOST_LAUNCH_FAILED", "Status: ok" in launched)
            }
            var result = reply("WAITING")
            val completed = withTimeoutOrNull(20_000) {
                while (true) {
                    result = call("RESULT", session)
                    if (result.getString("code") != "WAITING") break
                    delay(100)
                }
                true
            } == true
            val diagnostic = diagnostic(result)
            if (!completed) throw AssertionError("CAPTURE_RESULT_TIMEOUT:$diagnostic")
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "CAPTURE_RESULT:$diagnostic\n") })
            assertEquals("SYNTHETIC_OCR_NOT_CONFIRMED", "OCR_MATCH", safeCode(result.getString("code")))
            assertTrue("OCR_EVIDENCE_EMPTY", result.getInt("ocrCount") > 0)
        } finally {
            withContext(NonCancellable) { session?.let { call("END", it) } }
        }
    }

    private fun diagnostic(result: Bundle): String = listOf(
        "ATTEMPT=${result.getInt("attempt").coerceIn(0, 999)}",
        "STAGE=${safeCode(result.getString("stage"))}",
        "REACHED=${result.getStringArrayList("reached").orEmpty().take(20).joinToString(",") { safeCode(it) }}",
        "BLOCK=${safeCode(result.getString("block"))}",
        "EVIDENCE=${safeCode(result.getString("evidence"))}",
        "DETAIL=${safeCode(result.getString("detail"))}",
        "GEOMETRY=${safeCode(result.getString("geometry"))}",
        "API=${safeCode(result.getString("api"))}",
        "TARGET=${safeCode(result.getString("targetType"))}",
        "RELATED=${safeCode(result.getString("relatedType"))}",
        "EVENT_WINDOW=${safeCode(result.getString("eventWindow"))}",
        "IGNORED=${result.getInt("ignoredEvents").coerceIn(0, 999)}",
    ).joinToString(";")

    private fun reply(code: String) = Bundle().apply { putString("code", code) }
    private fun safeCode(value: String?): String = value?.takeIf { it.matches(Regex("[A-Z_]{1,64}")) } ?: "UNKNOWN"
    private fun shell(automation: UiAutomation, command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes().toString(Charsets.UTF_8) }
    private companion object {
        const val HOST = "kr.closeforyou.capturetest"
        val PROBE: Uri = Uri.parse("content://kr.closeforyou.syntheticcapturetest")
    }
}
