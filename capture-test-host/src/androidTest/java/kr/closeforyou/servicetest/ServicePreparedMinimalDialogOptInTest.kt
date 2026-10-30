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

/** New independent test: observed ordinary baseline, not the old fresh-BASELINE_ONLY test. */
@RunWith(AndroidJUnit4::class)
class ServicePreparedMinimalDialogOptInTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uri = Uri.parse("content://kr.closeforyou.syntheticdetectiontest")

    @Test fun detectsOneRealBoundedDialogAfterPreparedBaseline() {
        try { runBlocking { experiment() } }
        catch (_: CancellationException) { throw CancellationException("PREPARED_DIALOG_CANCELLED") }
        catch (_: AssertionError) { throw AssertionError("PREPARED_DIALOG_NOT_CONFIRMED") }
        catch (_: Exception) { throw AssertionError("PREPARED_DIALOG_INCONCLUSIVE") }
    }

    private suspend fun experiment() {
        val args = InstrumentationRegistry.getArguments()
        assertTrue("EXPLICIT_OPT_IN_REQUIRED", args.getString("f1PreparedMinimalDialog") == "true" && args.getString("captureOcrService") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val flags = automation.serviceInfo.flags
        val minimal = MinimalGeometryReader()
        val reader = PreparedDialogReader(instrumentation, automation, minimal)
        val progress = PreparedDialogProgress()
        var session: String? = null
        var activity: MinimalDialogActivity? = null
        var phase = PreparedPhase.PREPARATION
        var failureOutcome: String? = null
        val cleanup = PreparedDialogCleanup()
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            withContext(NonCancellable) {
                val reply = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true) })
                requireCode(reply, "STARTED")
                session = reply.getString("session") ?: throw GeometryDiagnosticFailure(GeometryIssue.SESSION_REJECTED)
            }
            val key = session ?: throw GeometryDiagnosticFailure(GeometryIssue.SESSION_REJECTED)
            val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MinimalDialogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as? MinimalDialogActivity
                ?: throw GeometryDiagnosticFailure(GeometryIssue.HOST_UNAVAILABLE)
            activity = shown
            instrumentation.waitForIdleSync()
            var baseline: MinimalGeometrySample? = null
            progress.verifyPreparation { scope ->
                val launch = MinimalGeometryPolling.firstCoherent<MinimalGeometrySample>(SystemClock::uptimeMillis, reader.safety,
                    { minimal.sample(instrumentation, automation, shown) }, { it.geometry.coherent }, { delay(it) })
                suspend fun sample(): MinimalGeometrySample = minimal.sample(instrumentation, automation, shown).also {
                    if (it.geometry.own.window != launch.geometry.own.window) fail(GeometryIssue.WINDOW_MISMATCH)
                    if (it.configuration != launch.configuration) fail(GeometryIssue.CONFIGURATION_CHANGED)
                }
                val stable = MinimalGeometryPolling.stable<MinimalGeometrySample>(SystemClock::uptimeMillis, ::sample,
                    { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) })
                fun anchor() = reader.baseline(shown, stable, stimulated = false)
                anchor()
                emitHost(phase, "BEFORE")
                observe(progress, scope, key, stable.geometry.own.window, ::anchor)
                MinimalGeometryPolling.stable<MinimalGeometrySample>(SystemClock::uptimeMillis, ::sample,
                    { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) }, anchor = stable)
                anchor()
                emitHost(phase, "AFTER")
                finalResult(progress, scope, key, stable.geometry.own.window, phase)
                baseline = stable
            }
            emit("F1_PREPARED_DIALOG_STAGE;PHASE=PREPARATION;RESULT=PREPARATION_RECORDED;BASELINE_EVIDENCE=NONE")
            val base = baseline ?: fail(GeometryIssue.HOST_UNAVAILABLE)
            phase = PreparedPhase.BASELINE
            progress.verifyBaseline { scope ->
                requireCode(call("RESET", key), "RESET") // Exactly one baseline reset; records only.
                currentCoroutineContext().ensureActive()
                reader.stimulate(shown)
                fun anchor() = reader.baseline(shown, base, stimulated = false)
                anchor()
                emitHost(phase, "BEFORE")
                observe(progress, scope, key, base.geometry.own.window, ::anchor)
                MinimalGeometryPolling.stable<MinimalGeometrySample>(SystemClock::uptimeMillis,
                    { minimal.sample(instrumentation, automation, shown) },
                    { it.geometry.coherent && it.geometry.own.layoutReady }, { delay(it) }, anchor = base)
                reader.baseline(shown, base, stimulated = true) // Verify the actual remote field, not native mutation alone.
                emitHost(phase, "AFTER")
                finalResult(progress, scope, key, base.geometry.own.window, phase)
            }
            emit("F1_PREPARED_DIALOG_STAGE;PHASE=BASELINE;RESULT=OBSERVED_NON_POPUP_BASELINE;CUTOFF=FINAL_RESULT_SNAPSHOT")

            val dialog = progress.showAndVerifyDialogWindow {
                requireCode(call("RESET", key), "RESET") // Exactly one dialog reset, BEFORE show.
                phase = PreparedPhase.DIALOG
                currentCoroutineContext().ensureActive()
                MinimalGeometryReader.onMain(instrumentation) { reader.safety.requireClean(); shown.showOwnedDialog() }
                val first = MinimalGeometryPolling.firstCoherent<PreparedDialogReader.DialogSample>(SystemClock::uptimeMillis,
                    reader.safety, { reader.dialog(shown, base) }, { true }, { delay(it) })
                suspend fun sample(): PreparedDialogReader.DialogSample = reader.dialog(shown, base).also {
                    if (it.target != first.target) fail(GeometryIssue.WINDOW_MISMATCH)
                }
                MinimalGeometryPolling.stable<PreparedDialogReader.DialogSample>(SystemClock::uptimeMillis, ::sample, { true }, { delay(it) })
            }
            progress.verifyDialog { scope ->
                fun anchor() {
                    if (reader.dialog(shown, base) != dialog) fail(GeometryIssue.STABLE_GEOMETRY_CHANGED)
                }
                anchor()
                emitHost(phase, "BEFORE")
                observe(progress, scope, key, dialog.target, ::anchor)
                MinimalGeometryPolling.stable<PreparedDialogReader.DialogSample>(SystemClock::uptimeMillis,
                    { reader.dialog(shown, base) }, { true }, { delay(it) }, anchor = dialog)
                anchor()
                emitHost(phase, "AFTER")
                finalResult(progress, scope, key, dialog.target, phase)
            }
            emit("F1_PREPARED_DIALOG_STAGE;PHASE=DIALOG;RESULT=BOUNDED_WINDOW_CANDIDATE;CUTOFF=FINAL_RESULT_SNAPSHOT")
        } catch (_: CancellationException) {
            progress.abort()
            throw CancellationException("PREPARED_DIALOG_CANCELLED")
        } catch (failure: PreparedFailure) {
            progress.abort()
            failureOutcome = PreparedMinimalDialogContract.failureOutcome(failure.verdict)
            emit("F1_PREPARED_DIALOG_PROBLEM;PHASE=${phase.name};DETAIL=${failure.verdict.name};RESULT=$failureOutcome")
        } catch (failure: GeometryDiagnosticFailure) {
            progress.abort(); failureOutcome = "INCONCLUSIVE"
            emit("F1_PREPARED_DIALOG_PROBLEM;PHASE=${phase.name};DETAIL=${failure.issue.name};RESULT=INCONCLUSIVE")
        } catch (_: PriorityReleaseException) {
            progress.abort(); failureOutcome = "INCONCLUSIVE"
            emit("F1_PREPARED_DIALOG_PROBLEM;PHASE=${phase.name};DETAIL=RELEASE_FAILED;RESULT=INCONCLUSIVE")
        } catch (_: Exception) {
            progress.abort(); failureOutcome = "INCONCLUSIVE"
            emit("F1_PREPARED_DIALOG_PROBLEM;PHASE=${phase.name};DETAIL=OBSERVATION_FAILED;RESULT=INCONCLUSIVE")
        } finally {
            minimal.close() // Does not reset the experiment-wide release-failure latch.
            withContext(NonCancellable) {
                cleanup.run(
                    end = { session?.let { requireCode(call("END", it), "ENDED") }; Unit },
                    dismiss = { activity?.let { own -> MinimalGeometryReader.onMain(instrumentation) {
                        val dialog = own.ownedDialog
                        own.closeOwnedDialog()
                        if (own.ownedDialog != null || dialog?.isShowing == true) fail(GeometryIssue.HOST_CLEANUP_FAILED)
                    } }; Unit },
                    finish = { activity?.let { finish(it) }; Unit },
                    restoreFlags = {
                        automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                        if (automation.serviceInfo.flags != flags) fail(GeometryIssue.FLAGS_RESTORE_FAILED)
                })
            }
            cleanup.report {
                cleanup.failures.forEach { emit("F1_PREPARED_DIALOG_CLEANUP;DETAIL=${it.name}") }
                reader.safety.failure()?.let {
                    emit("F1_PREPARED_DIALOG_READ_FAILURE;INSPECTION=${it.inspection.name};RELEASE=${it.release.name};RESULT=INCONCLUSIVE")
                }
            }
            if (!cleanup.clean || reader.safety.failure() != null) progress.abort()
        }
        cleanup.propagateCancellation() // Not reached while a pre-existing cancellation is unwinding through finally.
        currentCoroutineContext().ensureActive()
        val result = failureOutcome ?: progress.outcome(cleanup.clean, reader.safety.failure() == null)
        emit("F1_PREPARED_DIALOG_SUMMARY;RESULT=$result;SCOPE=ONE_SYNTHETIC_DIALOG;ATOMIC_BINDING=NONE;SERVICE_FRAME_BINDING=UNAVAILABLE;F0_COMPLETENESS=NOT_PROVEN;F1_ACCURACY=NOT_TESTED;ACTIONS=NONE;OCR=NOT_TESTED;PRODUCTION_POLICY=UNCHANGED")
        assertTrue("PREPARED_DIALOG_NOT_CONFIRMED", result == "OBSERVED" && progress.stage == PreparedStage.DIALOG_VERIFIED && cleanup.clean)
    }

    private suspend fun observe(progress: PreparedDialogProgress, scope: PreparedDialogProgress.Scope, key: String,
        window: Int, anchor: () -> Unit) = PreparedDialogObservation.await(scope.phase, SystemClock::uptimeMillis, read = {
        anchor()
        val reply = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) })
        anchor()
        assess(progress, scope, reply, final = false)
    }, pause = { delay(it) })

    private suspend fun finalResult(progress: PreparedDialogProgress, scope: PreparedDialogProgress.Scope, key: String,
        window: Int, phase: PreparedPhase) {
        PreparedDialogObservation.finalSnapshot(SystemClock::uptimeMillis, read = {
            call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) })
        }, accept = { reply, deadline ->
            val rows = reply.getStringArrayList("attempts").orEmpty()
            if (rows.size <= 256) rows.forEach { row ->
                val safe = if (row.length <= 2_048) DetectionAttemptOutput.format(row) else "ATTEMPT_DIAGNOSTIC_INVALID"
                emit("F1_PREPARED_DIALOG_ATTEMPT;PHASE=${phase.name};SOURCE=REAL_SERVICE;$safe")
            }
            assess(progress, scope, reply, final = true, deadline = deadline) // Same deadline at marker and phase transition.
        })
    }
    private fun assess(progress: PreparedDialogProgress, scope: PreparedDialogProgress.Scope, reply: Bundle, final: Boolean,
        deadline: PreparedFinalDeadline? = null) = progress.assess(scope, reply.getString("code"),
        reply.getStringArrayList("attempts").orEmpty(), reply.getBoolean("overflow"), final, deadline)

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
    private fun requireCode(reply: Bundle, expected: String) {
        if (reply.getString("code") != expected) fail(GeometryIssue.SESSION_REJECTED)
    }
    private fun emitHost(phase: PreparedPhase, moment: String) =
        emit("F1_PREPARED_DIALOG_SAMPLE;PHASE=${phase.name};MOMENT=$moment;SOURCE=HOST_UIAUTOMATION_SEPARATE;OWNED_WINDOW=CONFIRMED;MINIMAL_TREE=CONFIRMED;ATOMIC_BINDING=NONE")
    private fun emit(code: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$code\n") })
    private fun fail(issue: GeometryIssue): Nothing = throw GeometryDiagnosticFailure(issue)
}
