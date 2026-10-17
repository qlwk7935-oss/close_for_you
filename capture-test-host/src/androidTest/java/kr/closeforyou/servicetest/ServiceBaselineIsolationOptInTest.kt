package kr.closeforyou.servicetest

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.capturetest.DetectionScenarioActivity
import kr.closeforyou.capturetest.MinimalBaselineActivity
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic comparison only. Never substitutes the minimal screen into the fourteen F1 cases. */
@RunWith(AndroidJUnit4::class)
class ServiceBaselineIsolationOptInTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val geometry = OwnedIsolationGeometry()
    private val uri = Uri.parse("content://kr.closeforyou.syntheticdetectiontest")
    private enum class Fixture { ORIGINAL_SCROLL, MINIMAL }
    private enum class Phase { LAUNCH_WINDOW, OBSERVED_STABLE }

    @Test fun separateTreeCollectionFromWindowGeometry() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("f1Isolation") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val settled = mutableMapOf<Fixture, IsolationEvidence>()
        val failures = mutableListOf<String>()
        try {
            for (fixture in Fixture.entries) {
                var key: String? = null
                var activity: Activity? = null
                try {
                    val begun = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true) })
                    requireCode(begun, "STARTED")
                    key = begun.getString("session") ?: throw IsolationProbeFailure("SESSION_UNAVAILABLE")
                    val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
                        if (fixture == Fixture.ORIGINAL_SCROLL) DetectionScenarioActivity::class.java else MinimalBaselineActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    activity = shown
                    // First coherent sample, NOT a claim of ongoing animation. Reports remain separate sources.
                    val early = firstSample(automation, shown)
                    emitGeometry(fixture, Phase.LAUNCH_WINDOW, early)
                    val launch = observeService(key, early.own.window)
                    emitService(fixture, Phase.LAUNCH_WINDOW, launch)
                    // Observe exact values unchanged across samples + laid-out views. Elapsed time alone is insufficient.
                    val stable = stableSample(automation, shown)
                    val expectedFixture = if (fixture == Fixture.ORIGINAL_SCROLL)
                        stable.own.hasScroll && stable.own.hasClipped
                    else !stable.own.hasScroll && stable.own.controlFits && !stable.own.hasMalformed
                    if (!expectedFixture || stable.own.protectedField) throw IsolationProbeFailure("FIXTURE_CONTROL_NOT_ESTABLISHED")
                    requireCode(call("RESET", key), "RESET") // Records only. No detector/settings/budget reset.
                    onMain {
                        // Real owned metadata change triggers fresh collection; no injected result or fake bounds.
                        shown.findViewById<View>(android.R.id.content).contentDescription = "합성 기준 관측"
                    }
                    val fresh = observeService(key, stable.own.window, stable, automation, shown)
                    emitGeometry(fixture, Phase.OBSERVED_STABLE, stable)
                    val evidence = emitService(fixture, Phase.OBSERVED_STABLE, fresh)
                    if (evidence.collection in setOf(IsolationCollection.INVALID, IsolationCollection.UNOBSERVED) ||
                        (!evidence.sawReady && !evidence.sawMalformed))
                        throw IsolationProbeFailure("STABLE_COLLECTION_NOT_ISOLATED")
                    settled[fixture] = evidence
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: IsolationProbeFailure) { failures += failure.code; emit("F1_ISOLATION_FAILURE;FIXTURE=${fixture.name};DETAIL=${failure.code}") }
                catch (_: Exception) { failures += "ISOLATION_PROBE_FAILED"; emit("F1_ISOLATION_FAILURE;FIXTURE=${fixture.name};DETAIL=ISOLATION_PROBE_FAILED") }
                finally {
                    try { key?.let { requireCode(call("END", it), "ENDED") } }
                    catch (_: Exception) { failures += "SESSION_CLEANUP_FAILED" }
                    try { activity?.let { own -> onMain { own.finishAndRemoveTask() } } }
                    catch (_: Exception) { failures += "HOST_CLEANUP_FAILED" }
                }
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
        }
        emit("F1_ISOLATION_COMPARISON;NODE_CONTRAST=${isolationContrast(settled[Fixture.ORIGINAL_SCROLL], settled[Fixture.MINIMAL]).name};SCROLL_FRAME=${settled[Fixture.ORIGINAL_SCROLL]?.frame?.name ?: "UNOBSERVED"};MINIMAL_FRAME=${settled[Fixture.MINIMAL]?.frame?.name ?: "UNOBSERVED"};F1_ACCURACY=NOT_TESTED;PRODUCTION_POLICY=UNCHANGED")
        assertTrue("ISOLATION_OBSERVATION_INCOMPLETE", failures.isEmpty() && settled.size == Fixture.entries.size)
    }
    private suspend fun firstSample(automation: UiAutomation, activity: Activity): IsolationGeometrySample {
        var lastFailure = "FIRST_SAMPLE_UNOBSERVED"
        val sample = withTimeoutOrNull(3_000) {
            while (true) {
                try {
                    val next = geometry.sample(instrumentation, automation, activity)
                    if (next.coherent) return@withTimeoutOrNull next
                } catch (failure: IsolationProbeFailure) { lastFailure = failure.code } // Test polling only; no production capture retry.
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return sample ?: throw IsolationProbeFailure(lastFailure)
    }
    private suspend fun stableSample(automation: UiAutomation, activity: Activity): IsolationGeometrySample {
        val gate = ObservedGeometryStability<IsolationGeometrySample>()
        val sample = withTimeoutOrNull(6_000) {
            while (true) {
                val next = geometry.sample(instrumentation, automation, activity)
                if (gate.observe(next, next.coherent && next.own.layoutReady, SystemClock.uptimeMillis())) return@withTimeoutOrNull next
                delay(200)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return sample ?: throw IsolationProbeFailure("STABLE_GEOMETRY_UNOBSERVED")
    }
    private suspend fun observeService(key: String, window: Int, stable: IsolationGeometrySample? = null,
        automation: UiAutomation? = null, activity: Activity? = null): Bundle {
        val start = SystemClock.uptimeMillis()
        var report = Bundle()
        while (SystemClock.uptimeMillis() - start < 3_000) {
            if (stable != null) {
                val current = geometry.sample(instrumentation, requireNotNull(automation), requireNotNull(activity))
                if (!current.coherent || !current.own.layoutReady || current != stable)
                    throw IsolationProbeFailure("STABLE_PHASE_CHANGED")
            }
            report = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) })
            requireCode(report, "OBSERVED")
            if (report.getBoolean("overflow")) throw IsolationProbeFailure("OBSERVATION_OVERFLOW")
            if (SystemClock.uptimeMillis() - start >= 2_000 && report.getStringArrayList("attempts").orEmpty().isNotEmpty()) return report
            delay(200)
        }
        throw IsolationProbeFailure("SERVICE_COLLECTION_UNOBSERVED")
    }
    private fun emitService(fixture: Fixture, phase: Phase, report: Bundle): IsolationEvidence {
        val attempts = report.getStringArrayList("attempts").orEmpty()
        val evidence = IsolationEvidence.from(attempts, report.getBoolean("overflow"))
        emit("F1_ISOLATION_SERVICE;FIXTURE=${fixture.name};PHASE=${phase.name};SOURCE=REAL_SERVICE;COLLECTION=${evidence.collection.name};FRAME=${evidence.frame.name};ACCURACY=NOT_TESTED")
        attempts.forEach { emit("F1_ISOLATION_ATTEMPT;FIXTURE=${fixture.name};PHASE=${phase.name};${DetectionAttemptOutput.format(it)}") }
        return evidence
    }
    private fun emitGeometry(fixture: Fixture, phase: Phase, sample: IsolationGeometrySample) =
        emit("F1_ISOLATION_GEOMETRY;FIXTURE=${fixture.name};PHASE=${phase.name};${sample.codes()};AUTHORIZATION=NONE")
    private suspend fun call(method: String, key: String? = null, extras: Bundle = Bundle()): Bundle = withContext(Dispatchers.IO) {
        try { instrumentation.targetContext.contentResolver.call(uri, method, key, extras) ?: throw IsolationProbeFailure("PROBE_UNAVAILABLE") }
        catch (_: SecurityException) { throw IsolationProbeFailure("PROBE_PERMISSION_DENIED") }
    }
    private fun requireCode(bundle: Bundle, expected: String) {
        val code = bundle.getString("code")
        if (code != expected) throw IsolationProbeFailure(if (code in setOf("F1_SETTINGS_REQUIRED", "SESSION_BUSY",
            "F1_SESSION_INVALIDATED", "INVALID_SESSION", "OWNED_WINDOW_UNAVAILABLE", "EXPLICIT_OPT_IN_REQUIRED"))
            requireNotNull(code) else "PROBE_STATE_REJECTED")
    }
    private fun onMain(block: () -> Unit) {
        var failed = false
        instrumentation.runOnMainSync { try { block() } catch (_: Exception) { failed = true } }
        if (failed) throw IsolationProbeFailure("OWN_MAIN_OPERATION_FAILED")
    }
    private fun emit(code: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$code\n") })
}
