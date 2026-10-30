package kr.closeforyou.servicetest

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.capturetest.MinimalDialogActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** One controlled real Dialog, not the original F1 accuracy matrix or a capture/OCR/action test. */
@RunWith(AndroidJUnit4::class)
class ServiceMinimalDialogOptInTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uri = Uri.parse("content://kr.closeforyou.syntheticdetectiontest")
    private enum class Problem {
        OPT_IN_REQUIRED, SESSION_REJECTED, HOST_UNAVAILABLE, NATIVE_NOT_READY, WINDOW_MISMATCH,
        REMOTE_NOT_READY, TREE_LIMIT, CHILD_MISSING, FIXTURE_NOT_MINIMAL, FRAMEWORK_RELEASE_FAILED,
        MAIN_FAILED, OBSERVATION_FAILED, DIALOG_CLEANUP_FAILED, HOST_CLEANUP_FAILED,
        SESSION_CLEANUP_FAILED, FLAGS_RESTORE_FAILED,
    }
    private class ProbeFailure(val problem: Problem) : RuntimeException(problem.name)
    private data class OwnedWindows(val background: Int, val dialog: Int?) {
        fun target(phase: MinimalDialogPhase): Int = if (phase == MinimalDialogPhase.BASELINE) background
            else dialog ?: throw ProbeFailure(Problem.WINDOW_MISMATCH)
        override fun toString() = "OwnedWindows(redacted)"
    }

    @Test fun detectsRealDialogAfterAnObservedMinimalBaseline() {
        try { runBlocking { observeExperiment() } }
        catch (_: CancellationException) { throw CancellationException("MINIMAL_DIALOG_CANCELLED") }
        catch (_: AssertionError) { throw AssertionError("MINIMAL_DIALOG_NOT_CONFIRMED") }
        catch (_: Exception) { throw AssertionError("MINIMAL_DIALOG_OBSERVATION_FAILED") }
    }

    private suspend fun observeExperiment() {
        val args = InstrumentationRegistry.getArguments()
        assertTrue(Problem.OPT_IN_REQUIRED.name, args.getString("f1MinimalDialog") == "true" && args.getString("captureOcrService") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val oldFlags = automation.serviceInfo.flags
        var session: String? = null
        var activity: MinimalDialogActivity? = null
        var phase = MinimalDialogPhase.BASELINE
        var outcome = "INCONCLUSIVE"
        val progress = MinimalDialogProgress()
        val cleanup = mutableListOf<Problem>()
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            withContext(NonCancellable) {
                val begun = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true) })
                requireCode(begun, "STARTED")
                session = begun.getString("session") ?: throw ProbeFailure(Problem.SESSION_REJECTED)
            }
            val key = session ?: throw ProbeFailure(Problem.SESSION_REJECTED)
            val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, MinimalDialogActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as? MinimalDialogActivity
                ?: throw ProbeFailure(Problem.HOST_UNAVAILABLE)
            activity = shown
            instrumentation.waitForIdleSync()
            val base = awaitOwned(automation, shown, phase)
            emitSample(phase, "BEFORE")
            // No RESET here: it could erase the only actual BASELINE_ONLY launch observation.
            onMain { shown.findViewById<View>(android.R.id.content).contentDescription = "합성 관측" }
            progress.verifyBaseline {
                observeService(key, base.target(phase), phase)
                if (nativeSample(shown, phase) != base) throw ProbeFailure(Problem.WINDOW_MISMATCH)
                remoteSample(automation, base, phase)
                verifyFinal(key, base.target(phase), phase)
            }
            emitSample(phase, "AFTER")
            emit("F1_MINIMAL_DIALOG_STAGE;PHASE=BASELINE;RESULT=BASELINE_CONFIRMED;CUTOFF=FINAL_RESULT_SNAPSHOT")

            val dialog = progress.showAndVerifyDialogWindow {
                // Records only. Never clear detector, generation, settings or collection budget.
                requireCode(call("RESET", key), "RESET")
                phase = MinimalDialogPhase.DIALOG
                onMain { shown.showOwnedDialog() } // One real host-owned stimulus; no accessibility action.
                val owned = awaitOwned(automation, shown, phase)
                if (owned.background != base.background || owned.dialog == base.background) throw ProbeFailure(Problem.WINDOW_MISMATCH)
                owned
            }
            emitSample(phase, "BEFORE")
            // Never RESET after show: the initial candidate must not be erased.
            progress.verifyDialog {
                observeService(key, dialog.target(phase), phase)
                if (nativeSample(shown, phase) != dialog) throw ProbeFailure(Problem.WINDOW_MISMATCH)
                remoteSample(automation, dialog, phase)
                verifyFinal(key, dialog.target(phase), phase)
            }
            emitSample(phase, "AFTER")
            emit("F1_MINIMAL_DIALOG_STAGE;PHASE=DIALOG;RESULT=DETECTOR_CANDIDATE;CUTOFF=FINAL_RESULT_SNAPSHOT")
            outcome = progress.outcome()
        } catch (_: CancellationException) {
            progress.abort()
            throw CancellationException("MINIMAL_DIALOG_CANCELLED")
        } catch (failure: MinimalDialogObservationFailure) {
            progress.abort()
            outcome = MinimalDialogContract.failureOutcome(failure.verdict)
            emit("F1_MINIMAL_DIALOG_PROBLEM;PHASE=${phase.name};RESULT=$outcome;DETAIL=${failure.verdict.name}")
        } catch (failure: ProbeFailure) {
            progress.abort()
            outcome = "INCONCLUSIVE"
            emit("F1_MINIMAL_DIALOG_PROBLEM;PHASE=${phase.name};RESULT=INCONCLUSIVE;DETAIL=${failure.problem.name}")
        } catch (_: PriorityReleaseException) {
            progress.abort()
            outcome = "INCONCLUSIVE"
            emit("F1_MINIMAL_DIALOG_PROBLEM;PHASE=${phase.name};RESULT=INCONCLUSIVE;DETAIL=FRAMEWORK_RELEASE_FAILED")
        } catch (_: Exception) {
            progress.abort()
            outcome = "INCONCLUSIVE"
            emit("F1_MINIMAL_DIALOG_PROBLEM;PHASE=${phase.name};RESULT=INCONCLUSIVE;DETAIL=OBSERVATION_FAILED")
        } finally {
            withContext(NonCancellable) {
                try { session?.let { requireCode(call("END", it), "ENDED") } }
                catch (_: Exception) { cleanup += Problem.SESSION_CLEANUP_FAILED }
                try { activity?.let { own -> onMain { own.closeOwnedDialog() } } }
                catch (_: Exception) { cleanup += Problem.DIALOG_CLEANUP_FAILED }
                try { activity?.let { own -> onMain { own.finishAndRemoveTask() } } }
                catch (_: Exception) { cleanup += Problem.HOST_CLEANUP_FAILED }
                try { automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags } }
                catch (_: Exception) { cleanup += Problem.FLAGS_RESTORE_FAILED }
            }
            cleanup.forEach { emit("F1_MINIMAL_DIALOG_CLEANUP;DETAIL=${it.name}") }
        }
        if (cleanup.isNotEmpty()) {
            progress.abort()
            if (outcome == "OBSERVED") outcome = "INCONCLUSIVE"
        }
        emit("F1_MINIMAL_DIALOG_SUMMARY;RESULT=$outcome;SCOPE=ONE_SYNTHETIC_DIALOG;ATOMIC_BINDING=NONE;F0_COMPLETENESS=NOT_PROVEN;F1_ACCURACY=NOT_TESTED;ACTIONS=NONE;OCR=NOT_TESTED")
        assertTrue("MINIMAL_DIALOG_NOT_CONFIRMED", outcome == "OBSERVED" && progress.stage == MinimalDialogStage.DIALOG_VERIFIED && cleanup.isEmpty())
    }

    private suspend fun observeService(key: String, window: Int, phase: MinimalDialogPhase) {
        MinimalDialogObservation.await(phase, SystemClock::uptimeMillis, read = {
            val report = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) })
            assess(phase, report).also { result ->
                if (result != MinimalDialogContract.expected(phase) && result !in setOf(
                        MinimalDialogVerdict.UNOBSERVED, MinimalDialogVerdict.BASELINE_ONLY_NOT_SEEN,
                        MinimalDialogVerdict.DIALOG_CANDIDATE_NOT_SEEN)) emitAttempts(phase, report)
            }
        }, pause = { delay(it) })
    }

    private suspend fun verifyFinal(key: String, window: Int, phase: MinimalDialogPhase) {
        val report = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", window) })
        val result = assess(phase, report)
        emitAttempts(phase, report)
        MinimalDialogContract.requireExpected(phase, result)
        // This RESULT snapshot is the cutoff. It is not atomic with END or the next RESET.
    }
    private fun assess(phase: MinimalDialogPhase, report: Bundle) = MinimalDialogContract.assess(phase,
        report.getString("code"), report.getStringArrayList("attempts").orEmpty(), report.getBoolean("overflow"))
    private fun emitAttempts(phase: MinimalDialogPhase, report: Bundle) {
        val rows = report.getStringArrayList("attempts").orEmpty()
        if (rows.size <= 256) rows.forEach { row ->
            val safe = if (row.length <= 2_048) DetectionAttemptOutput.format(row) else "ATTEMPT_DIAGNOSTIC_INVALID"
            emit("F1_MINIMAL_DIALOG_ATTEMPT;PHASE=${phase.name};SOURCE=REAL_SERVICE;$safe")
        }
    }

    private suspend fun awaitOwned(automation: UiAutomation, activity: MinimalDialogActivity, phase: MinimalDialogPhase): OwnedWindows {
        var last = Problem.NATIVE_NOT_READY
        val result = withTimeoutOrNull(3_000) {
            while (true) {
                try {
                    val ids = nativeSample(activity, phase)
                    remoteSample(automation, ids, phase)
                    return@withTimeoutOrNull ids
                } catch (failure: ProbeFailure) { last = failure.problem }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") null
        }
        return result ?: throw ProbeFailure(last)
    }

    @Suppress("DEPRECATION")
    private fun nativeSample(activity: MinimalDialogActivity, phase: MinimalDialogPhase): OwnedWindows {
        var result: OwnedWindows? = null
        onMain {
            PriorityReleaseScope().protect { releases ->
                val decor = activity.window.decorView
                if (!decor.isAttachedToWindow || !decor.isLaidOut || decor.isLayoutRequested ||
                    decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != false) throw ProbeFailure(Problem.NATIVE_NOT_READY)
                fun windowId(view: View): Int {
                    if (!view.isAttachedToWindow || !view.isLaidOut || view.isLayoutRequested) throw ProbeFailure(Problem.NATIVE_NOT_READY)
                    val node = view.createAccessibilityNodeInfo()
                    try { return node.windowId.takeIf { it >= 0 } ?: throw ProbeFailure(Problem.NATIVE_NOT_READY) }
                    finally { releases.release { node.recycle() } }
                }
                val baseId = windowId(decor)
                val dialog = activity.ownedDialog
                if (phase == MinimalDialogPhase.BASELINE) {
                    if (dialog != null) throw ProbeFailure(Problem.WINDOW_MISMATCH)
                    result = OwnedWindows(baseId, null)
                } else {
                    val dialogDecor = dialog?.takeIf { it.isShowing }?.window?.decorView ?: throw ProbeFailure(Problem.NATIVE_NOT_READY)
                    val dialogId = windowId(dialogDecor)
                    if (dialogId == baseId) throw ProbeFailure(Problem.WINDOW_MISMATCH)
                    result = OwnedWindows(baseId, dialogId)
                }
            }
        }
        return result ?: throw ProbeFailure(Problem.NATIVE_NOT_READY)
    }

    @Suppress("DEPRECATION")
    private fun remoteSample(automation: UiAutomation, ids: OwnedWindows, phase: MinimalDialogPhase) = PriorityReleaseScope().protect { releases ->
        val windows = automation.windows
        try {
            if (windows.isEmpty() || windows.size > 32) throw ProbeFailure(Problem.REMOTE_NOT_READY)
            val target = ids.target(phase)
            if (windows.any { (it.isActive || it.isFocused) && it.id !in setOf(target, ids.background) })
                throw ProbeFailure(Problem.WINDOW_MISMATCH) // Metadata only; never read other apps' roots.
            val own = windows.singleOrNull { it.id == target } ?: throw ProbeFailure(Problem.REMOTE_NOT_READY)
            if (own.type != AccessibilityWindowInfo.TYPE_APPLICATION || !own.isActive) throw ProbeFailure(Problem.WINDOW_MISMATCH)
            if (phase == MinimalDialogPhase.DIALOG) windows.singleOrNull { it.id == ids.background }?.let {
                if (own.layer <= it.layer) throw ProbeFailure(Problem.WINDOW_MISMATCH)
            }
            val root = own.root ?: throw ProbeFailure(Problem.REMOTE_NOT_READY)
            try {
                var count = 0
                var sawContent = false
                fun visit(node: AccessibilityNodeInfo, depth: Int) {
                    if (++count > 128 || depth > 30) throw ProbeFailure(Problem.TREE_LIMIT)
                    if (node.windowId != target || node.packageName?.toString() != HOST) throw ProbeFailure(Problem.WINDOW_MISMATCH)
                    val className = node.className?.toString().orEmpty()
                    if (node.isScrollable || className.contains("scroll", true) || node.isPassword || node.isEditable || node.inputType != 0)
                        throw ProbeFailure(Problem.FIXTURE_NOT_MINIMAL)
                    val permitted = setOf(MinimalDialogActivity.BASE_TEXT, MinimalDialogActivity.DIALOG_TEXT, "합성 관측")
                    val values = listOf(node.text, node.contentDescription, node.hintText, node.error, node.paneTitle, node.tooltipText, node.stateDescription)
                    if (values.any { !it.isNullOrEmpty() && it.toString() !in permitted }) throw ProbeFailure(Problem.FIXTURE_NOT_MINIMAL)
                    val expectedText = if (phase == MinimalDialogPhase.BASELINE) MinimalDialogActivity.BASE_TEXT else MinimalDialogActivity.DIALOG_TEXT
                    sawContent = sawContent || (node.isVisibleToUser && node.text?.toString() == expectedText)
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (bounds.right < bounds.left || bounds.bottom < bounds.top) throw ProbeFailure(Problem.FIXTURE_NOT_MINIMAL)
                    val children = node.childCount
                    if (children !in 0..128) throw ProbeFailure(Problem.TREE_LIMIT)
                    for (i in 0 until children) {
                        val child = node.getChild(i) ?: throw ProbeFailure(Problem.CHILD_MISSING)
                        try { visit(child, depth + 1) } finally { releases.release { child.recycle() } }
                    }
                }
                visit(root, 0)
                if (!sawContent) throw ProbeFailure(Problem.REMOTE_NOT_READY)
            } finally { releases.release { root.recycle() } }
        } finally { releases.releaseAll(windows) { it.recycle() } }
    }

    private suspend fun call(method: String, key: String? = null, extras: Bundle = Bundle()): Bundle = withContext(Dispatchers.IO) {
        instrumentation.targetContext.contentResolver.call(uri, method, key, extras) ?: throw ProbeFailure(Problem.SESSION_REJECTED)
    }
    private fun requireCode(reply: Bundle, expected: String) {
        if (reply.getString("code") != expected) throw ProbeFailure(Problem.SESSION_REJECTED)
    }
    private fun onMain(block: () -> Unit) {
        var problem: Problem? = null
        var cancelled = false
        instrumentation.runOnMainSync {
            try { block() }
            catch (_: CancellationException) { cancelled = true }
            catch (failure: ProbeFailure) { problem = failure.problem }
            catch (_: PriorityReleaseException) { problem = Problem.FRAMEWORK_RELEASE_FAILED }
            catch (_: Exception) { problem = Problem.MAIN_FAILED }
        }
        if (cancelled) throw CancellationException("MINIMAL_DIALOG_CANCELLED")
        problem?.let { throw ProbeFailure(it) }
    }
    private fun emitSample(phase: MinimalDialogPhase, moment: String) = emit("F1_MINIMAL_DIALOG_SAMPLE;PHASE=${phase.name};MOMENT=$moment;SOURCE=NATIVE_UIAUTOMATION_SEPARATE;OWNED_WINDOW=CONFIRMED;MINIMAL_TREE=CONFIRMED;ATOMIC_BINDING=NONE")
    private fun emit(code: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$code\n") })
    private companion object { const val HOST = "kr.closeforyou.capturetest" }
}
