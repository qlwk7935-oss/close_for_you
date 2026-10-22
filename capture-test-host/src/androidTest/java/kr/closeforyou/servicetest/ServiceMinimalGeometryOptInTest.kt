package kr.closeforyou.servicetest

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.capturetest.MinimalDialogActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Geometry diagnostic only. No Dialog, F1 baseline approval, capture/OCR or action. */
@RunWith(AndroidJUnit4::class)
class ServiceMinimalGeometryOptInTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uri = Uri.parse("content://kr.closeforyou.syntheticdetectiontest")

    @Test fun compareLaunchAndStableOwnedGeometry() {
        try { runBlocking { experiment() } }
        catch (_: CancellationException) { throw CancellationException("MINIMAL_GEOMETRY_CANCELLED") }
        catch (_: AssertionError) { throw AssertionError("MINIMAL_GEOMETRY_INCONCLUSIVE") }
        catch (_: Exception) { throw AssertionError("MINIMAL_GEOMETRY_INCONCLUSIVE") }
    }

    private suspend fun experiment() {
        val args = InstrumentationRegistry.getArguments()
        assertTrue(GeometryIssue.OPT_IN_REQUIRED.name,
            args.getString("f1MinimalGeometry") == "true" && args.getString("captureOcrService") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val originalFlags = automation.serviceInfo.flags
        val reader = MinimalGeometryReader()
        var session: String? = null
        var activity: MinimalDialogActivity? = null
        var issue = GeometryIssue.NONE
        var phase = GeometryPhase.LAUNCH
        val cleanup = mutableListOf<GeometryIssue>()
        val service = mutableMapOf<GeometryPhase, GeometryServiceObservation>()
        val host = mutableMapOf<GeometryPhase, GeometryState>()
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            withContext(NonCancellable) {
                val begun = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true) })
                requireCode(begun, "STARTED")
                session = begun.getString("session") ?: throw GeometryDiagnosticFailure(GeometryIssue.SESSION_REJECTED)
            }
            val key = session ?: throw GeometryDiagnosticFailure(GeometryIssue.SESSION_REJECTED)
            val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MinimalDialogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as? MinimalDialogActivity
                ?: throw GeometryDiagnosticFailure(GeometryIssue.HOST_UNAVAILABLE)
            activity = shown
            instrumentation.waitForIdleSync()
            val launch = MinimalGeometryPolling.firstCoherent<MinimalGeometrySample>(SystemClock::uptimeMillis,
                reader.readSafety, { reader.sample(instrumentation, automation, shown) }, { it.geometry.coherent }, { delay(it) })
            emitHost(phase, "BEFORE", launch)
            observe(key, launch.geometry.own.window, phase)
            val launchAfter = reader.sample(instrumentation, automation, shown)
            requireOwned(launchAfter, launch.geometry.own.window)
            if (launchAfter.configuration != launch.configuration) throw GeometryDiagnosticFailure(GeometryIssue.CONFIGURATION_CHANGED)
            if (!launchAfter.geometry.coherent) throw GeometryDiagnosticFailure(GeometryIssue.STABLE_GEOMETRY_CHANGED)
            emitHost(phase, "AFTER", launchAfter)
            emitChange(phase, launch == launchAfter)
            // First and last host relations are not assumed equal during launch.
            val hostLaunch = mergeHost(launch, launchAfter)
            val finalLaunch = finalResult(key, launch.geometry.own.window, phase)
            host[phase] = hostLaunch
            service[phase] = finalLaunch

            phase = GeometryPhase.STABLE
            suspend fun sample(): MinimalGeometrySample = reader.sample(instrumentation, automation, shown).also {
                requireOwned(it, launch.geometry.own.window)
                if (it.configuration != launch.configuration) throw GeometryDiagnosticFailure(GeometryIssue.CONFIGURATION_CHANGED)
            }
            val stable = MinimalGeometryPolling.stable<MinimalGeometrySample>(SystemClock::uptimeMillis, ::sample,
                { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) })
            emitHost(phase, "BEFORE", stable)
            requireCode(call("RESET", key), "RESET") // Records only. Never detector.clear/settings/service restart.
            MinimalGeometryReader.onMain(instrumentation) {
                shown.findViewById<View>(android.R.id.content).contentDescription = "합성 경계 관측"
            }
            suspend fun requireAnchor() {
                if (sample() != stable) throw GeometryDiagnosticFailure(GeometryIssue.STABLE_GEOMETRY_CHANGED)
            }
            observe(key, stable.geometry.own.window, phase, ::requireAnchor)
            // Re-establish the same >=3 samples / >=1 second raw-value condition AFTER observation.
            val after = MinimalGeometryPolling.stable<MinimalGeometrySample>(SystemClock::uptimeMillis, ::sample,
                { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) }, anchor = stable)
            emitHost(phase, "AFTER", after)
            emitChange(phase, stable == after)
            // Last RESULT follows all host checks; validates every completed row at this cutoff.
            val finalStable = finalResult(key, stable.geometry.own.window, phase)
            host[phase] = MinimalGeometryContract.host(after.geometry.remoteWindow, after.geometry.own.logical)
            service[phase] = finalStable
        } catch (_: CancellationException) {
            throw CancellationException("MINIMAL_GEOMETRY_CANCELLED")
        } catch (failure: GeometryDiagnosticFailure) {
            issue = failure.issue
            emit("F1_MINIMAL_GEOMETRY_PROBLEM;PHASE=${phase.name};RESULT=INCONCLUSIVE;DETAIL=${issue.name}")
        } catch (_: PriorityReleaseException) {
            issue = GeometryIssue.RELEASE_FAILED
            emit("F1_MINIMAL_GEOMETRY_PROBLEM;PHASE=${phase.name};RESULT=INCONCLUSIVE;DETAIL=RELEASE_FAILED")
        } catch (_: Exception) {
            issue = GeometryIssue.OBSERVATION_FAILED
            emit("F1_MINIMAL_GEOMETRY_PROBLEM;PHASE=${phase.name};RESULT=INCONCLUSIVE;DETAIL=OBSERVATION_FAILED")
        } finally {
            reader.close()
            withContext(NonCancellable) {
                try { session?.let { requireCode(call("END", it), "ENDED") } }
                catch (_: Exception) { cleanup += GeometryIssue.SESSION_CLEANUP_FAILED }
                try { activity?.let { own -> finishOwned(own) } }
                catch (_: Exception) { cleanup += GeometryIssue.HOST_CLEANUP_FAILED }
                try {
                    automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
                    if (automation.serviceInfo.flags != originalFlags) throw GeometryDiagnosticFailure(GeometryIssue.FLAGS_RESTORE_FAILED)
                }
                catch (_: Exception) { cleanup += GeometryIssue.FLAGS_RESTORE_FAILED }
            }
            cleanup.forEach { emit("F1_MINIMAL_GEOMETRY_CLEANUP;DETAIL=${it.name}") }
            reader.readSafety.failure()?.let {
                emit("F1_MINIMAL_GEOMETRY_READ_FAILURE;INSPECTION=${it.inspection.name};RELEASE=${it.release.name};RESULT=INCONCLUSIVE")
            }
        }
        currentCoroutineContext().ensureActive() // NonCancellable cleanup must not turn cancellation into completion.
        val result = MinimalGeometryContract.completion(service[GeometryPhase.LAUNCH], service[GeometryPhase.STABLE],
            host[GeometryPhase.LAUNCH], host[GeometryPhase.STABLE], issue, cleanup.isEmpty() && reader.readSafety.failure() == null)
        val serviceContrast = MinimalGeometryContract.contrast(service[GeometryPhase.LAUNCH]?.geometry, service[GeometryPhase.STABLE]?.geometry)
        val hostContrast = MinimalGeometryContract.contrast(host[GeometryPhase.LAUNCH], host[GeometryPhase.STABLE])
        emit("F1_MINIMAL_GEOMETRY_SUMMARY;RESULT=$result;SERVICE_CONTRAST=${serviceContrast.name};HOST_CONTRAST=${hostContrast.name};ATOMIC_BINDING=NONE;SERVICE_FRAME_BINDING=UNAVAILABLE;F0_COMPLETENESS=NOT_PROVEN;F1_ACCURACY=NOT_TESTED;ACTIONS=NONE;OCR=NOT_TESTED;PRODUCTION_POLICY=UNCHANGED")
        assertTrue("MINIMAL_GEOMETRY_INCONCLUSIVE", result == "OBSERVATIONS_COMPLETE")
    }

    private suspend fun observe(key: String, window: Int, phase: GeometryPhase, anchor: suspend () -> Unit = {}) {
        MinimalGeometryPolling.await(SystemClock::uptimeMillis, read = {
            anchor()
            val reply = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) })
            anchor()
            val result = assess(reply)
            if (result.issue != GeometryIssue.NONE && result.issue != GeometryIssue.UNOBSERVED) emitAttempts(phase, reply)
            result
        }, pause = { delay(it) })
    }
    private suspend fun finalResult(key: String, window: Int, phase: GeometryPhase): GeometryServiceObservation {
        val reply = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) })
        val result = assess(reply)
        emitAttempts(phase, reply)
        MinimalGeometryContract.requireComplete(result)
        emit("F1_MINIMAL_GEOMETRY_SERVICE;PHASE=${phase.name};SOURCE=REAL_SERVICE;GEOMETRY=${result.geometry.name};CUTOFF=FINAL_RESULT_SNAPSHOT;ATOMIC_BINDING=NONE")
        return result // Snapshot only, not atomic with RESET/END and no in-flight collection drain.
    }
    private fun assess(reply: Bundle) = MinimalGeometryContract.assess(reply.getString("code"),
        reply.getStringArrayList("attempts").orEmpty(), reply.getBoolean("overflow"))
    private fun emitAttempts(phase: GeometryPhase, reply: Bundle) {
        val rows = reply.getStringArrayList("attempts").orEmpty()
        if (rows.size <= 256) rows.forEach { row ->
            val safe = if (row.length <= 2_048) DetectionAttemptOutput.format(row) else "ATTEMPT_DIAGNOSTIC_INVALID"
            emit("F1_MINIMAL_GEOMETRY_ATTEMPT;PHASE=${phase.name};SOURCE=REAL_SERVICE;$safe")
        }
    }
    private fun requireOwned(sample: MinimalGeometrySample, window: Int) {
        if (sample.geometry.own.window != window) throw GeometryDiagnosticFailure(GeometryIssue.WINDOW_MISMATCH)
    }
    private fun mergeHost(before: MinimalGeometrySample, after: MinimalGeometrySample): GeometryState {
        val first = MinimalGeometryContract.host(before.geometry.remoteWindow, before.geometry.own.logical)
        val last = MinimalGeometryContract.host(after.geometry.remoteWindow, after.geometry.own.logical)
        return if (first == last) first else GeometryState.MIXED
    }
    private suspend fun finishOwned(activity: MinimalDialogActivity) {
        MinimalGeometryReader.onMain(instrumentation) { activity.finishAndRemoveTask() }
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 3_000) {
            var destroyed = false
            MinimalGeometryReader.onMain(instrumentation) { destroyed = activity.isDestroyed }
            if (destroyed) return
            delay(100)
        }
        throw GeometryDiagnosticFailure(GeometryIssue.HOST_CLEANUP_FAILED)
    }
    private suspend fun call(method: String, key: String? = null, extras: Bundle = Bundle()): Bundle = withContext(Dispatchers.IO) {
        instrumentation.targetContext.contentResolver.call(uri, method, key, extras) ?: throw GeometryDiagnosticFailure(GeometryIssue.RESPONSE_REJECTED)
    }
    private fun requireCode(reply: Bundle, expected: String) {
        if (reply.getString("code") != expected) throw GeometryDiagnosticFailure(GeometryIssue.SESSION_REJECTED)
    }
    private fun emitHost(phase: GeometryPhase, moment: String, sample: MinimalGeometrySample) =
        emit("F1_MINIMAL_GEOMETRY_SAMPLE;PHASE=${phase.name};MOMENT=$moment;${sample.codes()}")
    private fun emitChange(phase: GeometryPhase, unchanged: Boolean) =
        emit("F1_MINIMAL_GEOMETRY_CHANGE;PHASE=${phase.name};SOURCE=HOST_UIAUTOMATION_SEPARATE;SAMPLE_VALUES=${if (unchanged) "UNCHANGED" else "CHANGED"};ATOMIC_BINDING=NONE")
    private fun emit(code: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$code\n") })
}
