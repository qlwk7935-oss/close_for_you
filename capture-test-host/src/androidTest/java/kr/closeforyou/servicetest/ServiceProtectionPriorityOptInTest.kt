package kr.closeforyou.servicetest

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.ViewParent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.capturetest.*
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Four separate sessions; real service collection, separate native and UiAutomation observations.
 * No atomic binding between these sources is claimed. No screenshots, gestures or injected results. */
@RunWith(AndroidJUnit4::class)
class ServiceProtectionPriorityOptInTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uri = Uri.parse("content://kr.closeforyou.syntheticdetectiontest")
    private enum class Failure {
        OPT_IN_REQUIRED, FIXTURE_UNAVAILABLE, MAIN_FAILED, NATIVE_NOT_ESTABLISHED, REMOTE_NOT_ESTABLISHED,
        WINDOW_MISMATCH, TREE_LIMIT, CHILD_UNAVAILABLE, PROBE_REJECTED, SESSION_UNAVAILABLE,
        SERVICE_UNOBSERVED, UNEXPECTED_ATTEMPT, INVALID_DIAGNOSTIC, OBSERVATION_OVERFLOW,
        SESSION_CLEANUP_FAILED, HOST_CLEANUP_FAILED, FLAGS_RESTORE_FAILED, OBSERVATION_FAILED,
    }
    private class ProbeFailure(val code: Failure) : RuntimeException(code.name)

    @Test fun hiddenFieldsAreBlockedBeforeSnapshotGeometry() {
        try { runBlocking { runObservation() } }
        catch (_: CancellationException) { throw CancellationException("PRIORITY_OBSERVATION_CANCELLED") }
        catch (_: AssertionError) { throw AssertionError("PRIORITY_OBSERVATION_INCOMPLETE") }
        catch (_: Exception) { throw AssertionError("PRIORITY_OBSERVATION_FAILED") }
    }

    private suspend fun runObservation() {
        val arguments = InstrumentationRegistry.getArguments()
        // Missing opt-in is a failure, not a skipped/successful experiment.
        assertTrue(Failure.OPT_IN_REQUIRED.name, arguments.getString("f1ProtectionPriority") == "true" &&
            arguments.getString("captureOcrService") == "true")
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val originalFlags = automation.serviceInfo.flags
        val failures = mutableListOf<Failure>()
        var completed = 0
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            }
            for (fixture in PriorityFixture.entries) {
                var session: String? = null
                var activity: ProtectionPriorityActivity? = null
                try {
                    // Acquire the cleanup key before cancellation can discard an IPC return value.
                    withContext(NonCancellable) {
                        val begun = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true) })
                        requireCode(begun, "STARTED")
                        session = begun.getString("session") ?: throw ProbeFailure(Failure.SESSION_UNAVAILABLE)
                    }
                    val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, ProtectionPriorityActivity::class.java)
                        .putExtra("fixture", fixture.name)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as? ProtectionPriorityActivity
                        ?: throw ProbeFailure(Failure.FIXTURE_UNAVAILABLE)
                    activity = shown
                    instrumentation.waitForIdleSync()
                    val window = awaitFixture(automation, shown, fixture)
                    emit("F0_PRIORITY_SAMPLE;FIXTURE=${fixture.name};SOURCE=NATIVE_SEPARATE_SAMPLE;PHASE=BEFORE;FIRST=REVERSED_Y_HIDDEN_SCROLL;LATER=HIDDEN;ATOMIC_BINDING=NONE")
                    emit("F0_PRIORITY_SAMPLE;FIXTURE=${fixture.name};SOURCE=UIAUTOMATION_SEPARATE_SAMPLE;PHASE=BEFORE;ORDER=FIRST_BEFORE_LATER;FIELDS=EXPECTED;ATOMIC_BINDING=NONE")
                    val activeSession = session ?: throw ProbeFailure(Failure.SESSION_UNAVAILABLE)
                    requireCode(call("RESET", activeSession), "RESET")
                    onMain { shown.findViewById<View>(android.R.id.content).contentDescription = PriorityFixtureText.STIMULUS }
                    observe(activeSession, window, fixture)
                    // Fresh samples, not proof of stability during the service's earlier attempt.
                    if (nativeSample(shown, fixture) != window) throw ProbeFailure(Failure.WINDOW_MISMATCH)
                    remoteSample(automation, window, fixture)
                    // Observation cutoff: a fresh RESULT after ALL follow-up node checks.
                    // RESULT and END are not atomic; this does not drain later/in-flight collections.
                    val finalReply = call("RESULT", activeSession, Bundle().apply { putInt("ownedWindowId", window) })
                    val finalAttempts = finalReply.getStringArrayList("attempts").orEmpty()
                    requireExpected(fixture, finalAttempts, ProtectionPriorityContract.assessResponse(fixture,
                        finalReply.getString("code"), finalAttempts, finalReply.getBoolean("overflow")))
                    emit("F0_PRIORITY_SAMPLE;FIXTURE=${fixture.name};SOURCE=NATIVE_SEPARATE_SAMPLE;PHASE=AFTER;FIRST=REVERSED_Y_HIDDEN_SCROLL;LATER=HIDDEN;ATOMIC_BINDING=NONE")
                    emit("F0_PRIORITY_SAMPLE;FIXTURE=${fixture.name};SOURCE=UIAUTOMATION_SEPARATE_SAMPLE;PHASE=AFTER;ORDER=FIRST_BEFORE_LATER;FIELDS=EXPECTED;ATOMIC_BINDING=NONE")
                    finalAttempts.forEach { emit("F0_PRIORITY_ATTEMPT;FIXTURE=${fixture.name};SOURCE=REAL_SERVICE;${DetectionAttemptOutput.format(it)}") }
                    emit("F0_PRIORITY_CASE;FIXTURE=${fixture.name};RESULT=EXPECTED_BLOCK;CUTOFF=FINAL_RESULT_SNAPSHOT;F0_COMPLETENESS=NOT_PROVEN;F1_ACCURACY=NOT_TESTED")
                    completed++
                } catch (cancelled: CancellationException) {
                    // Do not rethrow a framework message/cause that could contain captured values.
                    throw CancellationException("PRIORITY_OBSERVATION_CANCELLED")
                } catch (_: PriorityReleaseException) {
                    failures += Failure.OBSERVATION_FAILED
                    emit("F0_PRIORITY_CASE;FIXTURE=${fixture.name};RESULT=INCONCLUSIVE;DETAIL=FRAMEWORK_RELEASE_FAILED")
                } catch (failure: ProbeFailure) {
                    failures += failure.code
                    emit("F0_PRIORITY_CASE;FIXTURE=${fixture.name};RESULT=INCONCLUSIVE;DETAIL=${failure.code.name}")
                } catch (_: Exception) {
                    failures += Failure.OBSERVATION_FAILED
                    emit("F0_PRIORITY_CASE;FIXTURE=${fixture.name};RESULT=INCONCLUSIVE;DETAIL=OBSERVATION_FAILED")
                } finally {
                    withContext(NonCancellable) {
                        try { session?.let { requireCode(call("END", it), "ENDED") } }
                        catch (_: Exception) { failures += Failure.SESSION_CLEANUP_FAILED }
                        try { activity?.let { own -> onMain { own.finishAndRemoveTask() } } }
                        catch (_: Exception) { failures += Failure.HOST_CLEANUP_FAILED }
                    }
                }
            }
        } finally {
            try { automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags } }
            catch (_: Exception) { failures += Failure.FLAGS_RESTORE_FAILED }
            failures.distinct().forEach { emit("F0_PRIORITY_FAILURE;DETAIL=${it.name}") }
        }
        emit("F0_PRIORITY_SUMMARY;RESULT=${if (failures.isEmpty() && completed == 4) "OBSERVATIONS_COMPLETE" else "INCONCLUSIVE"};ATOMIC_BINDING=NONE;F0_COMPLETENESS=NOT_PROVEN;F1_ACCURACY=NOT_TESTED;ACTIONS=NONE")
        assertTrue("PRIORITY_OBSERVATION_INCOMPLETE", failures.isEmpty() && completed == 4)
    }

    private suspend fun awaitFixture(automation: UiAutomation, activity: ProtectionPriorityActivity, fixture: PriorityFixture): Int {
        // Bounded test-only observation, never service retry or acceptance based on elapsed time.
        var last = Failure.NATIVE_NOT_ESTABLISHED
        val result = withTimeoutOrNull(3_000) {
            while (true) {
                try {
                    val window = nativeSample(activity, fixture)
                    remoteSample(automation, window, fixture)
                    return@withTimeoutOrNull window
                } catch (failure: ProbeFailure) { last = failure.code }
                delay(150)
            }
            @Suppress("UNREACHABLE_CODE") -1
        }
        return result ?: throw ProbeFailure(last)
    }

    @Suppress("DEPRECATION")
    private fun nativeSample(activity: ProtectionPriorityActivity, fixture: PriorityFixture): Int {
        var window = -1
        var established = false
        onMain {
            PriorityReleaseScope().protect { releases ->
                val decor = activity.window.decorView
                val ready = decor.isAttachedToWindow && decor.isLaidOut && !decor.isLayoutRequested &&
                    decor.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == false && activity.scroller.scrollY == 0
                fun underScroll(view: View): Boolean {
                    var parent: ViewParent? = view.parent
                    var count = 0
                    while (parent != null && ++count <= 60) {
                        if (parent === activity.scroller) return true
                        parent = parent.parent
                    }
                    return false
                }
                val first = activity.first.createAccessibilityNodeInfo()
                try {
                    val later = activity.later.createAccessibilityNodeInfo()
                    try {
                        window = first.windowId
                        established = ready && window >= 0 && later.windowId == window &&
                            priorityTreeEstablished(fixture, listOf(probe(first, PriorityMarker.FIRST, underScroll(activity.first), fixture),
                                probe(later, PriorityMarker.LATER, underScroll(activity.later), fixture)))
                        // This two-node native list establishes attributes only. Actual ordering is checked remotely.
                    } finally { releases.release { later.recycle() } }
                } finally { releases.release { first.recycle() } }
            }
        }
        if (!established) throw ProbeFailure(Failure.NATIVE_NOT_ESTABLISHED)
        return window
    }

    @Suppress("DEPRECATION")
    private fun remoteSample(automation: UiAutomation, windowId: Int, fixture: PriorityFixture) = PriorityReleaseScope().protect { releases ->
        val windows = automation.windows
        try {
            val window = windows.singleOrNull { it.id == windowId }
                ?: throw ProbeFailure(Failure.WINDOW_MISMATCH)
            if (window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || !window.isActive)
                throw ProbeFailure(Failure.WINDOW_MISMATCH)
            val root = window.root ?: throw ProbeFailure(Failure.REMOTE_NOT_ESTABLISHED)
            try {
                val nodes = mutableListOf<PriorityProbeNode>()
                fun visit(node: AccessibilityNodeInfo, underScroll: Boolean, depth: Int) {
                    if (depth > 60 || nodes.size >= 256) throw ProbeFailure(Failure.TREE_LIMIT)
                    if (node.windowId != windowId || node.packageName?.toString() != HOST)
                        throw ProbeFailure(Failure.WINDOW_MISMATCH)
                    val marker = when (node.viewIdResourceName) {
                        "$HOST:id/priority_first" -> PriorityMarker.FIRST
                        "$HOST:id/priority_later" -> PriorityMarker.LATER
                        else -> PriorityMarker.OTHER
                    }
                    nodes += probe(node, marker, underScroll, fixture)
                    val scroll = underScroll || node.className?.toString() == "android.widget.ScrollView"
                    val count = node.childCount
                    if (count !in 0..256) throw ProbeFailure(Failure.TREE_LIMIT)
                    for (index in 0 until count) {
                        val child = node.getChild(index) ?: throw ProbeFailure(Failure.CHILD_UNAVAILABLE)
                        try { visit(child, scroll, depth + 1) } finally { releases.release { child.recycle() } }
                    }
                }
                visit(root, false, 0)
                if (!priorityTreeEstablished(fixture, nodes)) throw ProbeFailure(Failure.REMOTE_NOT_ESTABLISHED)
            } finally { releases.release { root.recycle() } }
        } finally { releases.releaseAll(windows) { it.recycle() } }
    }

    private fun probe(node: AccessibilityNodeInfo, marker: PriorityMarker, scroll: Boolean, fixture: PriorityFixture): PriorityProbeNode {
        val rect = Rect().also(node::getBoundsInScreen)
        val text = node.text?.toString()
        val description = node.contentDescription?.toString()
        val isLater = marker == PriorityMarker.LATER
        val safeLabel = { value: CharSequence? -> value.isNullOrEmpty() || value.toString() in setOf(PriorityFixtureText.ROW, PriorityFixtureText.STIMULUS) }
        val labelsSafe = safeLabel(description) && listOf(node.hintText, node.error, node.paneTitle,
            node.tooltipText, node.stateDescription).all(safeLabel)
        val field = when {
            !labelsSafe -> PriorityField.UNEXPECTED
            isLater && fixture == PriorityFixture.PASSWORD && node.isPassword && !node.isEditable && text.isNullOrEmpty() -> PriorityField.PASSWORD
            isLater && fixture == PriorityFixture.EDITABLE && !node.isPassword && node.isEditable && safeLabel(text) -> PriorityField.EDITABLE
            isLater && fixture == PriorityFixture.SENSITIVE_TEXT && !node.isPassword && !node.isEditable && node.inputType == 0 && text == PriorityFixtureText.SENSITIVE -> PriorityField.SENSITIVE_TEXT
            !node.isPassword && !node.isEditable && node.inputType == 0 && safeLabel(text) -> PriorityField.NONE
            else -> PriorityField.UNEXPECTED
        }
        return PriorityProbeNode(marker, scroll, node.isVisibleToUser, rect.right >= rect.left && rect.bottom < rect.top, field)
    }

    private suspend fun observe(session: String, window: Int, fixture: PriorityFixture): List<String> {
        val start = SystemClock.uptimeMillis()
        var attempts = emptyList<String>()
        while (SystemClock.uptimeMillis() - start < 3_000) {
            val reply = call("RESULT", session, Bundle().apply { putInt("ownedWindowId", window) })
            attempts = reply.getStringArrayList("attempts").orEmpty()
            val verdict = ProtectionPriorityContract.assessResponse(fixture, reply.getString("code"), attempts, reply.getBoolean("overflow"))
            if (verdict != PriorityVerdict.UNOBSERVED) {
                requireExpected(fixture, attempts, verdict)
                if (SystemClock.uptimeMillis() - start >= 2_000) return attempts
            }
            delay(200)
        }
        throw ProbeFailure(Failure.SERVICE_UNOBSERVED)
    }

    private fun requireExpected(fixture: PriorityFixture, attempts: List<String>, verdict: PriorityVerdict) {
        when (verdict) {
            PriorityVerdict.EXPECTED_BLOCK -> Unit
            PriorityVerdict.UNOBSERVED -> throw ProbeFailure(Failure.SERVICE_UNOBSERVED)
            PriorityVerdict.RESPONSE_REJECTED -> throw ProbeFailure(Failure.PROBE_REJECTED)
            PriorityVerdict.OVERFLOW -> throw ProbeFailure(Failure.OBSERVATION_OVERFLOW)
            PriorityVerdict.INVALID_DIAGNOSTIC -> throw ProbeFailure(Failure.INVALID_DIAGNOSTIC)
            PriorityVerdict.UNEXPECTED_ATTEMPT -> {
                attempts.forEach { emit("F0_PRIORITY_ATTEMPT;FIXTURE=${fixture.name};SOURCE=REAL_SERVICE;${DetectionAttemptOutput.format(it)}") }
                throw ProbeFailure(Failure.UNEXPECTED_ATTEMPT)
            }
        }
    }

    private suspend fun call(method: String, key: String? = null, extras: Bundle = Bundle()): Bundle = withContext(Dispatchers.IO) {
        instrumentation.targetContext.contentResolver.call(uri, method, key, extras) ?: throw ProbeFailure(Failure.PROBE_REJECTED)
    }
    private fun requireCode(bundle: Bundle, expected: String) {
        if (bundle.getString("code") != expected) throw ProbeFailure(Failure.PROBE_REJECTED)
    }
    private fun onMain(block: () -> Unit) {
        var failure: Failure? = null
        var releaseFailed = false
        var cancelled = false
        instrumentation.runOnMainSync {
            try { block() }
            catch (_: CancellationException) { cancelled = true }
            catch (failed: ProbeFailure) { failure = failed.code }
            catch (_: PriorityReleaseException) { releaseFailed = true }
            catch (_: Exception) { failure = Failure.MAIN_FAILED }
        }
        // Carry only codes across the UI boundary, preserving cancellation and primary guard kind.
        if (cancelled) throw CancellationException("PRIORITY_OBSERVATION_CANCELLED")
        if (releaseFailed) throw PriorityReleaseException()
        failure?.let { throw ProbeFailure(it) }
    }
    private fun emit(value: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$value\n") })
    private companion object { const val HOST = "kr.closeforyou.capturetest" }
}
