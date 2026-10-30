package kr.closeforyou.servicetest

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.capturetest.MinimalDialogActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One separate negative control. Existing positive Dialog instrumentation stays unchanged. */
@RunWith(AndroidJUnit4::class)
class ServiceLayoutChangeOptInTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uri = Uri.parse("content://kr.closeforyou.syntheticdetectiontest")

    @Test fun ordinaryLayoutChangeDoesNotBecomePopupCandidate() {
        try { runBlocking { experiment() } }
        catch (_: CancellationException) { throw CancellationException("LAYOUT_CONTROL_CANCELLED") }
        catch (_: AssertionError) { throw AssertionError("LAYOUT_CONTROL_NOT_CONFIRMED") }
        catch (_: Exception) { throw AssertionError("LAYOUT_CONTROL_INCONCLUSIVE") }
    }
    private suspend fun experiment() {
        val args = InstrumentationRegistry.getArguments()
        assertTrue("EXPLICIT_OPT_IN_REQUIRED", args.getString("f1LayoutChange") == "true" && args.getString("captureOcrService") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val flags = automation.serviceInfo.flags
        val minimal = MinimalGeometryReader()
        val progress = LayoutChangeProgress()
        val cleanup = PreparedDialogCleanup()
        val waits = LayoutPhase.entries.associateWith(::LayoutWaitDiagnostics)
        var activity: MinimalDialogActivity? = null
        var session: String? = null
        var phase = LayoutPhase.PREPARATION
        var failureOutcome: String? = null
        var waitFailure: LayoutWaitFailure? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            withContext(NonCancellable) {
                val reply = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true) })
                requireCode(reply, "STARTED")
                session = reply.getString("session") ?: fail(GeometryIssue.SESSION_REJECTED)
            }
            val key = session ?: fail(GeometryIssue.SESSION_REJECTED)
            val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MinimalDialogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as? MinimalDialogActivity
                ?: fail(GeometryIssue.HOST_UNAVAILABLE)
            activity = shown
            instrumentation.waitForIdleSync()
            val reader = LayoutChangeReader(instrumentation, automation, shown, minimal)
            var baseline: MinimalGeometrySample? = null
            progress.preparation { scope ->
                val launch = MinimalGeometryPolling.firstCoherent(SystemClock::uptimeMillis, reader.safety,
                    { minimal.sample(instrumentation, automation, shown) }, { it.geometry.coherent }, { delay(it) })
                suspend fun sample() = minimal.sample(instrumentation, automation, shown).also {
                    if (it.geometry.own.window != launch.geometry.own.window) fail(GeometryIssue.WINDOW_MISMATCH)
                    if (it.configuration != launch.configuration) fail(GeometryIssue.CONFIGURATION_CHANGED)
                }
                val stable = MinimalGeometryPolling.stable(SystemClock::uptimeMillis, ::sample,
                    { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) })
                fun anchor() = reader.anchor(stable, stimulated = false)
                anchor(); emitHost(phase, "BEFORE")
                observe(progress, scope, key, stable.geometry.own.window, waits.getValue(scope.phase), ::anchor)
                MinimalGeometryPolling.stable(SystemClock::uptimeMillis, ::sample,
                    { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) }, anchor = stable)
                anchor(); emitHost(phase, "AFTER")
                finalResult(progress, scope, key, stable.geometry.own.window, waits.getValue(scope.phase))
                baseline = stable
            }
            emit("F1_LAYOUT_STAGE;PHASE=PREPARATION;RESULT=PREPARATION_RECORDED;BASELINE_EVIDENCE=NONE")
            val base = baseline ?: fail(GeometryIssue.HOST_UNAVAILABLE)
            phase = LayoutPhase.BASELINE
            progress.baseline { scope ->
                requireCode(call("RESET", key), "RESET") // Records only; exactly once before baseline stimulus.
                currentCoroutineContext().ensureActive()
                reader.stimulateBaseline()
                fun anchor() = reader.anchor(base, stimulated = false)
                anchor(); emitHost(phase, "BEFORE")
                observe(progress, scope, key, base.geometry.own.window, waits.getValue(scope.phase), ::anchor)
                MinimalGeometryPolling.stable(SystemClock::uptimeMillis,
                    { minimal.sample(instrumentation, automation, shown) },
                    { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) }, anchor = base)
                reader.anchor(base, stimulated = true); emitHost(phase, "AFTER")
                finalResult(progress, scope, key, base.geometry.own.window, waits.getValue(scope.phase))
            }
            emit("F1_LAYOUT_STAGE;PHASE=BASELINE;RESULT=OBSERVED_NON_POPUP_BASELINE;CUTOFF=FINAL_RESULT_SNAPSHOT")
            phase = LayoutPhase.LAYOUT_CHANGE
            val changed = progress.changeAndVerifyHost {
                reader.anchor(base, stimulated = true)
                LayoutBeforeRequestCheckpoint.run(SystemClock::uptimeMillis,
                    reset = { requireCode(call("RESET", key), "RESET") },
                    active = { currentCoroutineContext().ensureActive() },
                    anchor = { reader.anchor(base, stimulated = true) },
                    query = { call("RESULT", key, Bundle().apply { putInt("ownedWindowId", base.geometry.own.window) }) },
                    assess = { reply -> checkpointAssessment(progress, reply) },
                    report = ::emit,
                    request = { reader.requestChange(base) })
                val first = MinimalGeometryPolling.firstCoherent(SystemClock::uptimeMillis, reader.safety,
                    { reader.afterChange(base) }, { it.geometry.coherent }, { delay(it) })
                MinimalGeometryPolling.stable(SystemClock::uptimeMillis, { reader.afterChange(base) },
                    { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) }, anchor = first)
            }
            emit("F1_LAYOUT_HOST;RESULT=LAYOUT_CHANGED;SOURCE=HOST_UIAUTOMATION_SEPARATE;ATOMIC_BINDING=NONE")
            progress.change { scope ->
                fun anchor() {
                    if (reader.afterChange(base) != changed) fail(GeometryIssue.STABLE_GEOMETRY_CHANGED)
                }
                anchor(); emitHost(phase, "BEFORE")
                observe(progress, scope, key, changed.geometry.own.window, waits.getValue(scope.phase), ::anchor)
                MinimalGeometryPolling.stable(SystemClock::uptimeMillis, { reader.afterChange(base) },
                    { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) }, anchor = changed)
                anchor(); emitHost(phase, "AFTER")
                finalResult(progress, scope, key, changed.geometry.own.window, waits.getValue(scope.phase))
            }
        } catch (_: CancellationException) {
            waitFailure = LayoutWaitFailure.CANCELLED
            progress.abort(); throw CancellationException("LAYOUT_CONTROL_CANCELLED")
        } catch (failure: PreparedFailure) {
            waitFailure = if (failure.verdict == PreparedVerdict.OBSERVATION_TIMEOUT) LayoutWaitFailure.OBSERVATION_TIMEOUT
                else LayoutWaitFailure.ASSESSMENT_REJECTED
            progress.abort(); failureOutcome = LayoutChangeContract.failureOutcome(failure.verdict)
            emit("F1_LAYOUT_PROBLEM;PHASE=${phase.name};DETAIL=${failure.verdict.name};RESULT=$failureOutcome")
        } catch (failure: LayoutFailure) {
            waitFailure = LayoutWaitFailure.HOST_REJECTED
            progress.abort(); failureOutcome = "INCONCLUSIVE"
            emit("F1_LAYOUT_PROBLEM;PHASE=${phase.name};DETAIL=${failure.issue.name};RESULT=INCONCLUSIVE")
        } catch (failure: GeometryDiagnosticFailure) {
            waitFailure = LayoutWaitFailure.HOST_REJECTED
            progress.abort(); failureOutcome = "INCONCLUSIVE"
            emit("F1_LAYOUT_PROBLEM;PHASE=${phase.name};DETAIL=${failure.issue.name};RESULT=INCONCLUSIVE")
        } catch (_: Exception) {
            waitFailure = LayoutWaitFailure.OBSERVATION_FAILED
            progress.abort(); failureOutcome = "INCONCLUSIVE"
            emit("F1_LAYOUT_PROBLEM;PHASE=${phase.name};DETAIL=OBSERVATION_FAILED;RESULT=INCONCLUSIVE")
        } finally {
            minimal.close()
            withContext(NonCancellable) {
                cleanup.run(
                    end = { session?.let { requireCode(call("END", it), "ENDED") }; Unit },
                    dismiss = { activity?.let { own -> MinimalGeometryReader.onMain(instrumentation) {
                        val unexpected = own.ownedDialog
                        own.closeOwnedDialog()
                        if (unexpected != null || own.ownedDialog != null) fail(GeometryIssue.HOST_CLEANUP_FAILED)
                    } }; Unit },
                    finish = { activity?.let { finish(it) }; Unit },
                    restoreFlags = {
                        automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                        if (automation.serviceInfo.flags != flags) fail(GeometryIssue.FLAGS_RESTORE_FAILED)
                    })
            }
            waitFailure?.let { waits.getValue(phase).reportFailure(it, cleanup, ::emit) }
            cleanup.report {
                cleanup.failures.forEach { emit("F1_LAYOUT_CLEANUP;DETAIL=${it.name}") }
                minimal.readSafety.failure()?.let {
                    emit("F1_LAYOUT_READ_FAILURE;INSPECTION=${it.inspection.name};RELEASE=${it.release.name};RESULT=INCONCLUSIVE")
                }
            }
            if (!cleanup.clean || minimal.readSafety.failure() != null) progress.abort()
        }
        cleanup.propagateCancellation()
        currentCoroutineContext().ensureActive()
        val result = failureOutcome ?: progress.outcome(cleanup.clean, minimal.readSafety.failure() == null)
        emit("F1_LAYOUT_SUMMARY;RESULT=$result;SCOPE=ONE_SYNTHETIC_LAYOUT_CHANGE;ATOMIC_BINDING=NONE;SERVICE_FRAME_BINDING=UNAVAILABLE;F0_COMPLETENESS=NOT_PROVEN;F1_ACCURACY=NOT_TESTED;ACTIONS=NONE;OCR=NOT_TESTED;PRODUCTION_POLICY=UNCHANGED")
        assertTrue("LAYOUT_CONTROL_NOT_CONFIRMED", result == "OBSERVED_NEGATIVE_CONTROL" && progress.stage == LayoutStage.CHANGE_VERIFIED && cleanup.clean)
    }
    private suspend fun observe(progress: LayoutChangeProgress, scope: LayoutChangeProgress.Scope, key: String,
        window: Int, diagnostics: LayoutWaitDiagnostics, anchor: () -> Unit) = diagnostics.await(SystemClock::uptimeMillis, anchor,
        { call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) }) },
        { assess(progress, scope, it, final = false) }, { delay(it) })

    private suspend fun finalResult(progress: LayoutChangeProgress, scope: LayoutChangeProgress.Scope, key: String, window: Int,
        diagnostics: LayoutWaitDiagnostics) {
        diagnostics.beginFinalResult()
        PreparedDialogObservation.finalSnapshot(SystemClock::uptimeMillis, {
            call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) }).also { diagnostics.received() }
        }) { reply, deadline ->
            val rows = reply.getStringArrayList("attempts").orEmpty()
            if (rows.size <= 256) rows.forEach { row ->
                val safe = if (row.length <= 2_048) DetectionAttemptOutput.format(row) else "ATTEMPT_DIAGNOSTIC_INVALID"
                emit("F1_LAYOUT_ATTEMPT;PHASE=${scope.phase.name};SOURCE=REAL_SERVICE;$safe")
            }
            diagnostics.assessed { assess(progress, scope, reply, final = true, deadline = deadline) }
        }
    }
    private fun assess(progress: LayoutChangeProgress, scope: LayoutChangeProgress.Scope, reply: Bundle, final: Boolean,
        deadline: PreparedFinalDeadline? = null) = progress.assess(scope, reply.getString("code"),
        reply.getStringArrayList("attempts").orEmpty(), reply.getBoolean("overflow"), final, deadline)

    @Suppress("DEPRECATION") // Inspect the actual IPC type; getBoolean could silently default malformed data to false.
    private fun checkpointAssessment(progress: LayoutChangeProgress, reply: Bundle) = progress.inspectCheckpoint(
        reply.getString("code"), reply.getStringArrayList("attempts"), reply.get("overflow"))

    private suspend fun finish(activity: MinimalDialogActivity) {
        MinimalGeometryReader.onMain(instrumentation) { activity.finishAndRemoveTask() }
        val start = SystemClock.uptimeMillis()
        while (SystemClock.uptimeMillis() - start < 3_000) {
            var destroyed = false
            MinimalGeometryReader.onMain(instrumentation) { destroyed = activity.isDestroyed }
            if (destroyed) return
            delay(100)
        }
        fail(GeometryIssue.HOST_CLEANUP_FAILED)
    }
    private suspend fun call(method: String, key: String? = null, extras: Bundle = Bundle()): Bundle = withContext(Dispatchers.IO) {
        instrumentation.targetContext.contentResolver.call(uri, method, key, extras) ?: fail(GeometryIssue.RESPONSE_REJECTED)
    }
    private fun requireCode(reply: Bundle, code: String) { if (reply.getString("code") != code) fail(GeometryIssue.SESSION_REJECTED) }
    private fun emitHost(phase: LayoutPhase, moment: String) = emit("F1_LAYOUT_SAMPLE;PHASE=${phase.name};MOMENT=$moment;SOURCE=HOST_UIAUTOMATION_SEPARATE;OWNED_WINDOW=CONFIRMED;ATOMIC_BINDING=NONE")
    private fun emit(code: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$code\n") })
    private fun fail(issue: GeometryIssue): Nothing = throw GeometryDiagnosticFailure(issue)
}
