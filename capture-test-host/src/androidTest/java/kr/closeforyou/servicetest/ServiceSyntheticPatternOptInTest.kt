package kr.closeforyou.servicetest

import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.capturetest.SyntheticPopupActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Same real service/window screenshot callback as OCR, but synthetic-only inspection before
 * the unchanged geometry refusal. No output transform is exported or installed as a crop rule. */
@RunWith(AndroidJUnit4::class)
class ServiceSyntheticPatternOptInTest {
    @Test fun verifiesOwnedPatternTranslationAndComparesFreshDecorElevation() = runBlocking {
        assumeTrue("WINDOW_CAPTURE_API_REQUIRED", android.os.Build.VERSION.SDK_INT >= 34)
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureOcrService") == "true" && arguments.getString("syntheticPattern") == "true")
        // Reuse the real connection check, including UiAutomation preservation and diagnostics.
        assertFalse("PATTERN_PROBE_REQUIRES_CAPTURE", arguments.getString("connectionOnly") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val normal = observe(false)
        delay(3_000) // preserve the existing capture budget; never restart service to reset it
        val flat = observe(true)
        val effect = when {
            normal.getString("patternDecorBuffer") == "INSET_ALL" && flat.getString("patternDecorBuffer") == "EXACT" &&
                normal.getBoolean("elevated") && !flat.getBoolean("elevated") -> "CONSISTENT_WITH_ELEVATION_INSETS"
            normal.getString("patternDecorBuffer") == flat.getString("patternDecorBuffer") -> "NO_RELATION_CHANGE"
            else -> "INCONCLUSIVE"
        }
        instrumentation.sendStatus(0, Bundle().apply {
            putString("stream", "SYNTHETIC_PATTERN_COMPARISON;ELEVATION_EFFECT=$effect;PRODUCTION_POLICY=UNCHANGED\n")
        })
    }

    private suspend fun observe(zeroElevation: Boolean): Bundle {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val uri = Uri.parse("content://kr.closeforyou.syntheticcapturetest")
        suspend fun call(method: String, key: String? = null, extras: Bundle? = null) = withContext(Dispatchers.IO) {
            try { resolver.call(uri, method, key, extras) ?: Bundle() }
            catch (_: SecurityException) { throw AssertionError("PATTERN_PROBE_PERMISSION_DENIED") }
        }
        var activity: SyntheticPopupActivity? = null
        var session: String? = null
        try {
            val begin = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true); putBoolean("patternProbe", true) })
            assertEquals("PATTERN_SESSION_NOT_STARTED", "STARTED", DiagnosticCode.safe(begin.getString("code")))
            session = requireNotNull(begin.getString("session"))
            val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, SyntheticPopupActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra("syntheticPattern", true).putExtra("patternSession", session)
                .putExtra("zeroElevation", zeroElevation)) as SyntheticPopupActivity
            activity = shown
            var result = Bundle()
            val observed = withTimeoutOrNull(20_000) {
                while (true) {
                    result = call("RESULT", session)
                    if (result.getBoolean("patternReleased")) break
                    delay(100)
                }
                true
            } == true
            var arm = "NOT_ARMED"
            var elevated: Boolean? = null
            instrumentation.runOnMainSync { arm = shown.patternArmCode; elevated = shown.syntheticDialogHasElevatedDecor() }
            // Codes only, including failure paths. Never print owned geometry, Bitmap or raw exceptions.
            val code = DiagnosticCode.safe(result.getString("patternCode"))
            val decor = DiagnosticCode.safe(result.getString("patternDecorBuffer"))
            val outer = DiagnosticCode.safe(result.getString("patternOuter"))
            val mode = if (zeroElevation) "ZERO_ELEVATION" else "DEFAULT_ELEVATION"
            val detail = "MODE=$mode;ARM=${DiagnosticCode.safe(arm)};PATTERN=$code;DECOR_BUFFER=$decor;OUTER=$outer;" +
                "ELEVATED=${when (elevated) { true -> "YES"; false -> "NO"; null -> "UNKNOWN" }};BUFFER_RELEASED=${result.getBoolean("patternReleased")};" +
                "API=${DiagnosticCode.safe(result.getString("api"))};FRAME=${DiagnosticCode.safe(result.getString("frameStatus"))}"
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SYNTHETIC_PATTERN_OBSERVED;$detail\n") })
            assertTrue("PATTERN_OBSERVATION_TIMEOUT;$detail", observed)
            assertEquals("PATTERN_NOT_ARMED", "PATTERN_ARMED", DiagnosticCode.safe(arm))
            assertEquals("PATTERN_MAPPING_NOT_VERIFIED;$detail", "TRANSLATION_VERIFIED", code)
            assertEquals("PATTERN_API_NOT_WINDOW", "WINDOW", DiagnosticCode.safe(result.getString("api")))
            assertEquals("PATTERN_FRAME_NOT_VERIFIED", "VERIFIED", DiagnosticCode.safe(result.getString("frameStatus")))
            if (zeroElevation) assertEquals("ZERO_ELEVATION_NOT_APPLIED", false, elevated)
            return result.apply { putBoolean("elevated", elevated == true) }
        } finally {
            withContext(NonCancellable) {
                session?.let { call("END", it) }
                activity?.let { shown -> instrumentation.runOnMainSync { shown.finish() } }
            }
        }
    }
}
