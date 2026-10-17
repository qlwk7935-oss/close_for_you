package kr.closeforyou.servicetest

import android.app.Instrumentation
import android.app.UiAutomation
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kr.closeforyou.capturetest.DetectionScenarioActivity
import kr.closeforyou.capturetest.SyntheticPlainActivity
import kr.closeforyou.synthetictest.*
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real F1, external host runner. No target-app singleton imports or capture/OCR dependency. */
@RunWith(AndroidJUnit4::class)
class ServiceDetectionScenariosOptInTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val uri = Uri.parse("content://kr.closeforyou.syntheticdetectiontest")
    private data class Outcome(val scenario: DetectionScenario, val actual: DetectionActual,
        val grade: DetectionGrade, val detail: String, val baselineFalsePositive: Boolean)

    @Test fun fixedSyntheticScenariosCompareRealDetectionAndProtection() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("f1Detection") == "true")
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // This legacy flag opts in to real service connection only; BEGIN below REQUIRES capture OFF.
        SyntheticServiceSession.run(bufferOnly = false, connectionCheckOnly = true)
        val outcomes = mutableListOf<Outcome>()
        var activity: DetectionScenarioActivity? = null
        try {
            for (scenario in DetectionScenario.entries) {
                var key: String? = null
                var page: SyntheticPlainActivity? = null
                var monitor: Instrumentation.ActivityMonitor? = null
                var baselineFp = false
                var actual = DetectionActual.UNOBSERVED
                var detail = "NONE"
                try {
                    if (scenario == DetectionScenario.SEQUENCE_SECOND && outcomes.lastOrNull()?.let {
                        it.scenario == DetectionScenario.SEQUENCE_FIRST && it.grade == DetectionGrade.DETECTION_SUCCESS &&
                            it.detail == "NONE" && !it.baselineFalsePositive
                    } != true) throw ProbeFailure("SEQUENCE_PREREQUISITE_MISSING")
                    val begun = call("BEGIN", extras = Bundle().apply { putBoolean("explicitOptIn", true) })
                    requireCode(begun, "STARTED")
                    key = begun.getString("session") ?: throw ProbeFailure("SESSION_UNAVAILABLE")
                    if (scenario != DetectionScenario.SEQUENCE_SECOND) {
                        val shown = instrumentation.startActivitySync(Intent(instrumentation.targetContext, DetectionScenarioActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)) as DetectionScenarioActivity
                        activity = shown
                        val baseline = awaitReport(key, { ownedWindow(shown) }) { result -> observed(result) }
                        baselineFp = baseline.getInt("candidates") > 0
                        if (baselineFp) throw ProbeFailure("BASELINE_FALSE_POSITIVE")
                        if (baseline.getInt("blocks") > 0) throw ProbeFailure("BASELINE_BLOCKED")
                        if (!observed(baseline)) throw ProbeFailure("BASELINE_UNOBSERVED")
                    }
                    val shown = activity ?: throw ProbeFailure("SEQUENCE_HOST_MISSING")
                    val previousWindow = ownedWindow(shown)
                    requireCode(call("RESET", key), "RESET")
                    if (scenario == DetectionScenario.SCREEN_TRANSITION) {
                        monitor = instrumentation.addMonitor(SyntheticPlainActivity::class.java.name, null, false)
                    }
                    instrumentation.runOnMainSync { shown.performScenario(scenario) }
                    if (monitor != null) page = instrumentation.waitForMonitorWithTimeout(monitor, 5_000) as? SyntheticPlainActivity
                        ?: throw ProbeFailure("TRANSITION_ACTIVITY_UNOBSERVED")
                    val result = awaitReport(key, {
                        page?.let { next -> var id = -1; instrumentation.runOnMainSync { id = next.ownedTargetWindowId() }; id }
                            ?: ownedWindow(shown)
                    }) { report ->
                        val stimulus = when (scenario) {
                            DetectionScenario.SCROLL -> report.getInt("scrolls") > 0
                            DetectionScenario.TOAST -> report.getInt("toasts") > 0
                            DetectionScenario.KEYBOARD -> { var visible = false; instrumentation.runOnMainSync { visible = shown.ownedImeVisible() }; visible }
                            else -> true
                        }
                        observed(report) && stimulus
                    }
                    actual = DetectionActual.entries.firstOrNull { it.name == result.getString("actual") } ?: DetectionActual.UNOBSERVED
                    if (result.getBoolean("overflow")) { actual = DetectionActual.UNOBSERVED; detail = "OBSERVATION_OVERFLOW" }
                    if (scenario == DetectionScenario.SEQUENCE_SECOND && ownedWindow(shown) == previousWindow) {
                        actual = DetectionActual.UNOBSERVED; detail = "SEQUENCE_WINDOW_NOT_REPLACED"
                    }
                    if (result.getString("complete") != "YES") { actual = DetectionActual.UNOBSERVED; detail = "STIMULUS_OR_RESULT_UNOBSERVED" }
                    if (actual == DetectionActual.BLOCKED && scenario.expected == DetectionExpectation.PROTECTED) {
                        val allowed = when (scenario) {
                            DetectionScenario.KEYBOARD -> setOf("EDITABLE_FIELD", "SYSTEM_WINDOW")
                            DetectionScenario.LOGIN -> setOf("PASSWORD", "EDITABLE_FIELD", "SENSITIVE_CONTENT")
                            DetectionScenario.PAYMENT_INPUT -> setOf("EDITABLE_FIELD", "SENSITIVE_CONTENT")
                            else -> setOf("SENSITIVE_CONTENT")
                        }
                        val reasons = result.getStringArrayList("blockReasons").orEmpty()
                        if (reasons.isEmpty() || reasons.any { it !in allowed }) detail = "PROTECTION_REASON_NOT_VERIFIED"
                    }
                    emit("F1_EVIDENCE;CASE=${scenario.name};REASONS=${codes(result, "reasons")};BLOCKS=${codes(result, "blockReasons")};SCROLL=${result.getInt("scrolls") > 0};TOAST=${result.getInt("toasts") > 0}")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: ProbeFailure) { detail = failure.code }
                catch (_: Exception) { detail = "OBSERVATION_FAILED" }
                finally {
                    withContext(NonCancellable) {
                        try { key?.let { requireCode(call("END", it), "ENDED") } }
                        catch (_: Exception) { detail = "SESSION_CLEANUP_FAILED" }
                        try {
                            monitor?.let(instrumentation::removeMonitor)
                            page?.let { next -> instrumentation.runOnMainSync { next.finish() } }
                            if (scenario != DetectionScenario.SEQUENCE_FIRST) {
                                activity?.let { own -> instrumentation.runOnMainSync { own.finish() } }
                                activity = null
                                delay(500)
                            }
                        } catch (_: Exception) { detail = "HOST_CLEANUP_FAILED" }
                    }
                }
                val grade = DetectionComparison.grade(scenario.expected, actual)
                outcomes += Outcome(scenario, actual, grade, detail, baselineFp)
                emit("F1_CASE;CASE=${scenario.name};EXPECTED=${scenario.expected.name};ACTUAL=${actual.name};GRADE=${grade.name};DETAIL=$detail")
            }
        } finally { activity?.let { own -> instrumentation.runOnMainSync { own.finish() } } }
        val summary = DetectionGrade.entries.joinToString(";") { grade -> "${grade.name}=${outcomes.count { it.grade == grade }}" }
        emit("F1_SUMMARY;$summary;BASELINE_FP=${outcomes.count { it.baselineFalsePositive }};OCR=NOT_TESTED;ACTIONS=NONE")
        assertTrue("F1_SCENARIO_FAILURE;$summary", outcomes.size == DetectionScenario.entries.size &&
            outcomes.all { DetectionComparison.accepted(it.grade) && it.detail == "NONE" && !it.baselineFalsePositive })
    }
    private suspend fun awaitReport(key: String, window: () -> Int, done: (Bundle) -> Boolean): Bundle {
        val start = SystemClock.uptimeMillis()
        var last = Bundle()
        while (SystemClock.uptimeMillis() - start < 8_000) {
            val id = window()
            if (id >= 0) {
                last = call("RESULT", key, Bundle().apply { putInt("ownedWindowId", id) })
                requireCode(last, "OBSERVED")
                // Negative evidence requires a real observed stimulus AND a sustained observation period.
                if (SystemClock.uptimeMillis() - start >= 2_000 && done(last)) return last.apply { putString("complete", "YES") }
            }
            delay(100)
        }
        return last
    }
    private fun observed(report: Bundle) = report.getInt("candidates") + report.getInt("nonPopups") +
        report.getInt("uncertain") + report.getInt("blocks") + report.getInt("scrolls") + report.getInt("toasts") > 0
    private fun ownedWindow(activity: DetectionScenarioActivity): Int {
        var id = -1
        instrumentation.runOnMainSync { id = activity.ownedTargetWindowId() }
        return id
    }
    private suspend fun call(method: String, key: String? = null, extras: Bundle = Bundle()): Bundle = withContext(Dispatchers.IO) {
        try { instrumentation.targetContext.contentResolver.call(uri, method, key, extras) ?: Bundle() }
        catch (_: SecurityException) { throw ProbeFailure("PROBE_PERMISSION_DENIED") }
    }
    private fun requireCode(value: Bundle, expected: String) {
        if (value.getString("code") != expected) throw ProbeFailure(DiagnosticCode.safe(value.getString("code")))
    }
    private fun codes(result: Bundle, name: String): String = result.getStringArrayList(name).orEmpty()
        .map(DiagnosticCode::safe).joinToString(",").ifEmpty { "NONE" }
    private fun emit(code: String) = instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$code\n") })
    private class ProbeFailure(val code: String) : RuntimeException()
}
