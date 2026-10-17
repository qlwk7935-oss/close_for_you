package kr.closeforyou.servicetest

import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.capturetest.SyntheticPopupActivity
import kr.closeforyou.synthetictest.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observe both fresh Dialogs before the final assertion. Failed content remains a failed mode;
 * UNKNOWN/UNKNOWN or either failure can never become evidence of an elevation relationship. */
@RunWith(AndroidJUnit4::class)
class ServiceSyntheticPatternOptInTest {
    @Test fun verifiesOwnedPatternTranslationAndComparesFreshDecorElevation() = runBlocking {
        assumeTrue("WINDOW_CAPTURE_API_REQUIRED", android.os.Build.VERSION.SDK_INT >= 34)
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("captureOcrService") == "true" && arguments.getString("syntheticPattern") == "true")
        assertFalse("PATTERN_PROBE_REQUIRES_CAPTURE", arguments.getString("connectionOnly") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val comparison = PatternModeRunner.run(::observe) { delay(3_000) }
        val summary = "DEFAULT_RESULT=${comparison.normal.result.name};ZERO_RESULT=${comparison.flat.result.name};" +
            "ELEVATION_EFFECT=${comparison.effect.name};PRODUCTION_POLICY=UNCHANGED"
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SYNTHETIC_PATTERN_COMPARISON;$summary\n") })
        assertTrue("PATTERN_MAPPING_NOT_VERIFIED;$summary", comparison.passed)
    }

    private suspend fun observe(mode: PatternMode): PatternModeOutcome {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val uri = Uri.parse("content://kr.closeforyou.syntheticcapturetest")
        suspend fun call(method: String, key: String? = null, extras: Bundle? = null): Bundle = withContext(Dispatchers.IO) {
            try { resolver.call(uri, method, key, extras) ?: throw ProbeFailure(PatternModeResult.PROBE_UNAVAILABLE) }
            catch (_: SecurityException) { throw ProbeFailure(PatternModeResult.PROBE_PERMISSION_DENIED) }
        }
        var activity: SyntheticPopupActivity? = null
        var session: String? = null
        var result = Bundle()
        var arm = "NOT_ARMED"
        var elevated = ObservedElevation.UNKNOWN
        var rendering: Set<PatternRenderHint> = emptySet()
        var modeResult = PatternModeResult.OBSERVATION_FAILED
        var cleanupComplete = true
        try {
            val begin = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true); putBoolean("patternProbe", true) })
            if (begin.getString("code") != "STARTED") throw ProbeFailure(PatternModeResult.SESSION_NOT_STARTED)
            session = begin.getString("session") ?: throw ProbeFailure(PatternModeResult.SESSION_NOT_STARTED)
            val shown = try {
                instrumentation.startActivitySync(Intent(instrumentation.targetContext, SyntheticPopupActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra("syntheticPattern", true).putExtra("patternSession", session)
                    .putExtra("zeroElevation", mode == PatternMode.ZERO_ELEVATION)) as SyntheticPopupActivity
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw ProbeFailure(PatternModeResult.HOST_LAUNCH_FAILED) }
            activity = shown
            val observed = withTimeoutOrNull(20_000) {
                while (true) {
                    result = call("RESULT", session)
                    if (result.getString("code") == "INVALID_SESSION") throw ProbeFailure(PatternModeResult.SESSION_EXPIRED)
                    if (result.getBoolean("patternReleased")) break
                    delay(100)
                }
                true
            } == true
            instrumentation.runOnMainSync {
                arm = shown.patternArmCode
                rendering = shown.patternRendering
                elevated = when (shown.syntheticDialogHasElevatedDecor()) {
                    true -> ObservedElevation.YES; false -> ObservedElevation.NO; null -> ObservedElevation.UNKNOWN
                }
            }
            modeResult = when {
                !observed -> PatternModeResult.OBSERVATION_TIMEOUT
                arm != "PATTERN_ARMED" -> PatternModeResult.NOT_ARMED
                result.getString("patternCode") != "TRANSLATION_VERIFIED" -> PatternModeResult.MAPPING_NOT_VERIFIED
                result.getString("patternLandmarks") != "ALIGNED" || result.getString("patternContentScan") != "COMPLETE" ||
                    result.getStringArrayList("patternMismatches").orEmpty().isNotEmpty() ||
                    parse(result.getString("patternDecorBuffer"), DecorBufferRelation.UNKNOWN) == DecorBufferRelation.UNKNOWN -> PatternModeResult.INCONSISTENT_RESULT
                result.getString("api") != "WINDOW" -> PatternModeResult.API_NOT_WINDOW
                result.getString("frameStatus") != "VERIFIED" -> PatternModeResult.FRAME_NOT_VERIFIED
                elevated == ObservedElevation.UNKNOWN -> PatternModeResult.ELEVATION_UNAVAILABLE
                mode == PatternMode.ZERO_ELEVATION && elevated != ObservedElevation.NO -> PatternModeResult.ZERO_ELEVATION_NOT_APPLIED
                else -> PatternModeResult.PASSED
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ProbeFailure) { modeResult = failure.code }
        catch (_: Exception) { modeResult = PatternModeResult.OBSERVATION_FAILED }
        catch (_: AssertionError) { modeResult = PatternModeResult.OBSERVATION_FAILED }
        finally {
            // One failed END must not prevent owned Activity cleanup or the other mode's attempt.
            withContext(NonCancellable) {
                session?.let {
                    try { if (call("END", it).getString("code") != "ENDED") cleanupComplete = false }
                    catch (_: Exception) { cleanupComplete = false }
                    catch (_: AssertionError) { cleanupComplete = false }
                }
                activity?.let { shown ->
                    try { instrumentation.runOnMainSync { shown.finish() } }
                    catch (_: Exception) { cleanupComplete = false }
                    catch (_: AssertionError) { cleanupComplete = false }
                }
            }
        }
        if (!cleanupComplete && modeResult == PatternModeResult.PASSED) modeResult = PatternModeResult.CLEANUP_FAILED
        val outcome = PatternModeOutcome(mode, modeResult, parse(result.getString("patternCode"), PatternCode.NOT_OBSERVED),
            parse(result.getString("patternDecorBuffer"), DecorBufferRelation.UNKNOWN), elevated)
        val mismatch = result.getStringArrayList("patternMismatches").orEmpty().take(12)
            .mapNotNull { value -> ContentMismatch.entries.firstOrNull { it.name == value } }.distinct().sortedBy { it.ordinal }
            .joinToString(",") { it.name }.ifEmpty { "NONE" }
        val detail = listOf("MODE=${mode.name}", "MODE_RESULT=${outcome.result.name}", "ARM=${safe(arm)}",
            "PATTERN=${outcome.pattern.name}", "LANDMARKS=${safe(result.getString("patternLandmarks"))}",
            "CONTENT_SCAN=${safe(result.getString("patternContentScan"))}", "MISMATCH=$mismatch",
            "DECOR_BUFFER=${outcome.decor.name}", "OUTER=${safe(result.getString("patternOuter"))}", "ELEVATED=${elevated.name}",
            "RENDER=${rendering.sortedBy { it.ordinal }.joinToString(",") { it.name }.ifEmpty { "UNKNOWN" }}",
            "SOURCE_COLOR=${safe(result.getString("patternSourceColorSpace"))}", "COPY_COLOR=${safe(result.getString("patternCopyColorSpace"))}",
            "BUFFER_RELEASED=${result.getBoolean("patternReleased")}", "CLEANUP=${if (cleanupComplete) "COMPLETE" else "FAILED"}",
            "API=${safe(result.getString("api"))}", "FRAME=${safe(result.getString("frameStatus"))}",
            "ROLE=${safe(result.getString("syntheticRole"))}", "BUFFER_MEMORY=${safe(result.getString("bufferMemory"))}",
            "BLOCK=${safe(result.getString("block"))}", "EVIDENCE=${safe(result.getString("evidence"))}", "DETAIL=${safe(result.getString("detail"))}")
            .joinToString(";")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "SYNTHETIC_PATTERN_OBSERVED;$detail\n") })
        return outcome
    }
    private fun safe(value: String?): String = DiagnosticCode.safe(value)
    private inline fun <reified T : Enum<T>> parse(value: String?, unknown: T): T = enumValues<T>().firstOrNull { it.name == value } ?: unknown
    private class ProbeFailure(val code: PatternModeResult) : Exception()
}
