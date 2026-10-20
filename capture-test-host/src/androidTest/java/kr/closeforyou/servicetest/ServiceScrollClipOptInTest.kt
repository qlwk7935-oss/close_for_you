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
import kr.closeforyou.capturetest.DetectionScenarioActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Observation of one owned child's actual native/remote identity, NOT F1 accuracy or F0 proof. */
@RunWith(AndroidJUnit4::class)
class ServiceScrollClipOptInTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uri = Uri.parse("content://kr.closeforyou.syntheticdetectiontest")

    @Test fun sameScrollChildOutsideInsideOutside() {
        try { runBlocking { experiment() } }
        catch (_: CancellationException) { throw CancellationException("SCROLL_CLIP_CANCELLED") }
        catch (_: AssertionError) { throw AssertionError("SCROLL_CLIP_INCONCLUSIVE") }
        catch (_: Exception) { throw AssertionError("SCROLL_CLIP_INCONCLUSIVE") }
    }

    private suspend fun experiment() {
        val args = InstrumentationRegistry.getArguments()
        assertTrue("EXPLICIT_OPT_IN_REQUIRED", args.getString("f1ScrollClip") == "true" && args.getString("captureOcrService") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val flags = automation.serviceInfo.flags
        val progress = ScrollClipProgress()
        val cleanup = PreparedDialogCleanup()
        var activity: DetectionScenarioActivity? = null
        var reader: ScrollClipReader? = null
        var session: String? = null
        var phase = ScrollClipPhase.OUTSIDE_BEFORE
        var failed = false
        val nativeShapes = mutableListOf<ScrollClipShape>()
        val remoteShapes = mutableListOf<ScrollClipShape>()
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                this.flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            withContext(NonCancellable) {
                val reply = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true) })
                requireCode(reply, "STARTED")
                session = reply.getString("session") ?: throw ScrollClipFailure(ScrollClipIssue.RESPONSE_REJECTED)
            }
            val key = session ?: throw ScrollClipFailure(ScrollClipIssue.RESPONSE_REJECTED)
            val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetectionScenarioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as? DetectionScenarioActivity
                ?: throw ScrollClipFailure(ScrollClipIssue.FIXTURE_INVALID)
            activity = shown
            instrumentation.waitForIdleSync()
            val owner = ScrollClipReader(instrumentation, automation, shown).also { reader = it }
            // Initial readiness is not service/baseline evidence. Subsequent phases never rebind the child.
            MinimalGeometryPolling.firstCoherent(SystemClock::uptimeMillis, owner.safety,
                { owner.bind(); owner.sample(ScrollClipPhase.OUTSIDE_BEFORE, ScrollClipReadStage.INITIAL_PROBE) }, { true }, { delay(it) })
            for (next in ScrollClipPhase.entries) {
                phase = next
                currentCoroutineContext().ensureActive()
                progress.phase(next) { scope ->
                    // Preserve this session's real launch observations in the first outside state.
                    // Later states get one records-only reset BEFORE the actual scroll, never a retry reset.
                    ScrollClipTransition.run(next, { requireCode(call("RESET", key), "RESET") },
                        { currentCoroutineContext().ensureActive() }, { owner.move(next) })
                    val first = MinimalGeometryPolling.firstCoherent(SystemClock::uptimeMillis, owner.safety,
                        { owner.sample(next, ScrollClipReadStage.PHASE_FIRST_SAMPLE) }, { true }, { delay(it) })
                    val stable = MinimalGeometryPolling.stable(SystemClock::uptimeMillis, { owner.sample(next, ScrollClipReadStage.STABILITY_CHECK) },
                        { true }, { delay(it) }, anchor = first)
                    fun anchor() {
                        owner.safety.requireClean()
                        if (owner.sample(next, ScrollClipReadStage.ANCHOR_CHECK) != stable) throw ScrollClipFailure(ScrollClipIssue.UNSTABLE)
                    }
                    emit(stable.codes(next, ScrollClipSource.HOST_NATIVE))
                    emit(stable.codes(next, ScrollClipSource.UIAUTOMATION_SEPARATE))
                    ScrollClipObservation.await(SystemClock::uptimeMillis, {
                        anchor()
                        val reply = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", stable.native.environment.window) })
                        anchor()
                        assess(progress, scope, reply)
                    }, { delay(it) })
                    MinimalGeometryPolling.stable(SystemClock::uptimeMillis, { owner.sample(next, ScrollClipReadStage.STABILITY_CHECK) }, { true }, { delay(it) }, anchor = stable)
                    anchor()
                    PreparedDialogObservation.finalSnapshot(SystemClock::uptimeMillis, {
                        anchor()
                        val reply = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", stable.native.environment.window) })
                        anchor()
                        reply
                    }) { reply, deadline ->
                        emitAttempts(next, reply)
                        val observation = assess(progress, scope, reply, deadline)
                        emit("F1_SCROLL_CLIP_SERVICE;PHASE=${next.name};SOURCE=REAL_SERVICE;COLLECTION=${observation.kind.name};" +
                            "SELECTED_NODE_BINDING=UNAVAILABLE;CUTOFF=FINAL_RESULT_SNAPSHOT;ATOMIC_BINDING=NONE")
                        deadline.check()
                    }
                    nativeShapes += ScrollClipContract.shape(stable.native.bounds)
                    remoteShapes += ScrollClipContract.shape(stable.remoteBounds)
                }
            }
        } catch (_: CancellationException) {
            progress.abort(); throw CancellationException("SCROLL_CLIP_CANCELLED")
        } catch (failure: ScrollClipFailure) {
            failed = true; progress.abort()
            cleanup.report { emit("F1_SCROLL_CLIP_PROBLEM;PHASE=${phase.name};DETAIL=${failure.issue.name};RESULT=INCONCLUSIVE" +
                (failure.environment?.let { ";${it.codes()}" } ?: "")) }
        } catch (failure: GeometryDiagnosticFailure) {
            failed = true; progress.abort()
            cleanup.report { emit("F1_SCROLL_CLIP_PROBLEM;PHASE=${phase.name};DETAIL=${failure.issue.name};RESULT=INCONCLUSIVE") }
        } catch (_: PriorityReleaseException) {
            failed = true; progress.abort()
            cleanup.report { emit("F1_SCROLL_CLIP_PROBLEM;PHASE=${phase.name};DETAIL=RELEASE_FAILED;RESULT=INCONCLUSIVE") }
        } catch (_: Exception) {
            failed = true; progress.abort()
            cleanup.report { emit("F1_SCROLL_CLIP_PROBLEM;PHASE=${phase.name};DETAIL=OBSERVATION_FAILED;RESULT=INCONCLUSIVE") }
        } finally {
            reader?.close() // Clear View/raw sample references, never the reader-lifetime failure latch.
            withContext(NonCancellable) {
                cleanup.run(
                    end = { session?.let { requireCode(call("END", it), "ENDED") }; Unit },
                    dismiss = {}, // No Dialog is created. Activity.onDestroy retains its existing owned-dialog cleanup.
                    finish = { activity?.let { finish(it) }; Unit },
                    restoreFlags = {
                        automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags }
                        if (automation.serviceInfo.flags != flags) throw GeometryDiagnosticFailure(GeometryIssue.FLAGS_RESTORE_FAILED)
                    })
            }
            cleanup.report {
                cleanup.failures.forEach { emit("F1_SCROLL_CLIP_CLEANUP;DETAIL=${it.name};RESULT=INCONCLUSIVE") }
                reader?.safety?.failure()?.let {
                    emit("F1_SCROLL_CLIP_RELEASE;INSPECTION=${it.inspection.name};RELEASE=${it.release.name};RESULT=INCONCLUSIVE")
                }
            }
            if (failed || !cleanup.clean || reader?.safety?.failure() != null) progress.abort()
        }
        cleanup.propagateCancellation()
        currentCoroutineContext().ensureActive()
        val result = progress.outcome(cleanup.clean, reader?.safety?.failure() == null)
        emit("F1_SCROLL_CLIP_SUMMARY;RESULT=$result;NATIVE_CONTRAST=${ScrollClipContract.contrast(nativeShapes).name};REMOTE_CONTRAST=${ScrollClipContract.contrast(remoteShapes).name};" +
            "ATOMIC_BINDING=NONE;SERVICE_FRAME_BINDING=UNAVAILABLE;F0_COMPLETENESS=NOT_PROVEN;F1_ACCURACY=NOT_TESTED;ACTIONS=NONE;OCR=NOT_TESTED;PRODUCTION_POLICY=UNCHANGED")
        currentCoroutineContext().ensureActive()
        assertTrue("SCROLL_CLIP_INCONCLUSIVE", result == "OBSERVATIONS_COMPLETE" && cleanup.clean)
    }

    @Suppress("DEPRECATION")
    private fun assess(progress: ScrollClipProgress, scope: ScrollClipProgress.Scope, reply: Bundle,
        deadline: PreparedFinalDeadline? = null) = progress.assess(scope, reply.getString("code"),
        reply.getStringArrayList("attempts"), reply.get("overflow"), deadline)

    private fun emitAttempts(phase: ScrollClipPhase, reply: Bundle) {
        val rows = reply.getStringArrayList("attempts") ?: return
        if (rows.size > 256) return
        rows.forEach { row ->
            val safe = if (row.length <= 2_048) DetectionAttemptOutput.format(row) else "ATTEMPT_DIAGNOSTIC_INVALID"
            // Keep original block/reason tuple codes, but do not print even test ordinals.
            emit("F1_SCROLL_CLIP_ATTEMPT;PHASE=${phase.name};SOURCE=REAL_SERVICE;" +
                if (safe == "ATTEMPT_DIAGNOSTIC_INVALID") "DETAIL=INVALID_DIAGNOSTIC" else safe.substringAfter(';'))
        }
    }
    private suspend fun finish(activity: DetectionScenarioActivity) {
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
        instrumentation.targetContext.contentResolver.call(uri, method, key, extras)
            ?: throw ScrollClipFailure(ScrollClipIssue.RESPONSE_REJECTED)
    }
    private fun requireCode(reply: Bundle, expected: String) {
        if (reply.getString("code") != expected) throw ScrollClipFailure(ScrollClipIssue.RESPONSE_REJECTED)
    }
    private fun emit(code: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$code\n") })
}
