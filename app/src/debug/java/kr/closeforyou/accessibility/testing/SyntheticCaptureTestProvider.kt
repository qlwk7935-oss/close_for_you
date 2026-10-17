package kr.closeforyou.accessibility.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.view.accessibility.AccessibilityManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.accessibility.CaptureStage
import kr.closeforyou.accessibility.CaptureAttemptDiagnostic
import kr.closeforyou.accessibility.CaptureDiagnostics
import kr.closeforyou.accessibility.CaptureDiagnosticSession
import kr.closeforyou.accessibility.capture.SyntheticBufferInspection
import kr.closeforyou.synthetictest.PatternPlan
import kr.closeforyou.synthetictest.PatternRect
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.model.TextSource
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Debug-only, signature + fixed caller protected. All returned data is codes/counts/booleans.
 * Runs in the real app's default process/classloader; the external test never loads Runtime. */
class SyntheticCaptureTestProvider : ContentProvider() {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var session: String? = null
    private var collector: Job? = null
    private var expiry: Runnable? = null
    private var result = "NOT_STARTED"
    private var diagnostics: CaptureDiagnosticSession? = null
    private var completedDiagnostic: CaptureAttemptDiagnostic? = null
    private var patternProbe: SyntheticPatternProbe? = null
    private var ocrCount = 0
    private val prefs get() = CorePreferences.get(requireNotNull(context))

    override fun onCreate() = true

    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        // Manifest signature permission also gates the call. No shell/public query entry point.
        val ctx = requireNotNull(context)
        ctx.enforceCallingPermission("kr.closeforyou.permission.SYNTHETIC_CAPTURE_TEST", "SYNTHETIC_CALLER_DENIED")
        if (callingPackage != HOST) throw SecurityException("SYNTHETIC_CALLER_DENIED")
        return when (method) {
            "STATUS" -> status()
            "BEGIN" -> {
                if (extras?.getBoolean("explicitOptIn") != true) return code("EXPLICIT_OPT_IN_REQUIRED")
                if (session != null) return code("SESSION_BUSY")
                val state = prefs.currentSettings()
                // Read only: the person must grant consent and start analysis in the existing UI.
                // Restrict this test to the single synthetic host; never modify any user settings.
                if (!syntheticSettingsReady(state)) {
                    return code("SYNTHETIC_SETTINGS_REQUIRED")
                }
                if (status().getString("code") != "READY") return code("SERVICE_NOT_READY")
                val key = UUID.randomUUID().toString()
                session = key
                result = "WAITING"
                completedDiagnostic = null
                diagnostics = CaptureDiagnosticSession(HOST).also { CaptureDiagnostics.observer = it }
                ocrCount = 0
                if (extras.getBoolean("patternProbe")) {
                    patternProbe = SyntheticPatternProbe(prefs).also { SyntheticBufferInspection.probe = it }
                }
                collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    AccessibilityRuntime.events.collect { event ->
                        when (event) {
                            is PopupEvent.Candidate -> if (event.snapshot.token.packageName == HOST) {
                                // F0-A/F0-B and token validation are still enforced by the real extractor.
                                val analysis = try { AccessibilityRuntime.extract(event.snapshot) }
                                    catch (_: CancellationException) { currentCoroutineContext().ensureActive(); return@collect }
                                synchronized(this@SyntheticCaptureTestProvider) {
                                    val matching = diagnostics?.completed(event.snapshot.token.screenVersion, event.snapshot.token.windowId)
                                    if (session == key && result == "WAITING" && matching != null) {
                                        ocrCount = analysis.texts.count { it.source == TextSource.OCR }
                                        if (ocrCount > 0) {
                                            // Receiving this real Candidate proves publication, even if the
                                            // service thread has not yet appended its post-publish trace step.
                                            completedDiagnostic = matching.copy(stage = CaptureStage.PUBLISHED,
                                                reached = matching.reached + CaptureStage.PUBLISHED)
                                            result = if (analysis.texts.any {
                                                it.source == TextSource.OCR && it.text.contains("점검")
                                            }) "OCR_MATCH" else "OCR_NO_EXPECTED_TEXT"
                                        }
                                    }
                                }
                            }
                            is PopupEvent.Blocked -> Unit // Unscoped events must never become host diagnostics.
                            is PopupEvent.Invalidated -> Unit
                        }
                    }
                }
                expiry = Runnable { synchronized(this) { if (session == key) finish() } }
                    .also { main.postDelayed(it, 30_000) }
                code("STARTED").apply { putString("session", key) }
            }
            "ARM_PATTERN" -> {
                if (session == null || session != arg) return code("INVALID_SESSION")
                if (!syntheticSettingsReady(prefs.currentSettings())) return code("SYNTHETIC_SETTINGS_REQUIRED")
                val values = extras?.getIntArray("ownedGeometry")
                if (values == null || values.size != 9) return code("PATTERN_PLAN_INVALID")
                val expected = PatternPlan(values[0], PatternRect(values[1], values[2], values[3], values[4]),
                    PatternRect(values[5], values[6], values[7], values[8]), extras.getBoolean("decorElevated"))
                code(if (patternProbe?.arm(expected) == true) "PATTERN_ARMED" else "PATTERN_PLAN_INVALID")
            }
            "RESULT" -> if (session == null || session != arg) code("INVALID_SESSION") else code(result).apply {
                putInt("ocrCount", ocrCount)
                patternProbe?.report()?.let { (report, released) ->
                    putString("patternCode", report.code.name)
                    putString("patternDecorBuffer", report.decorBuffer.name)
                    putString("patternOuter", report.outer.name)
                    putBoolean("patternReleased", released)
                }
                val diagnostic = patternProbe?.diagnostic() ?: completedDiagnostic ?: diagnostics?.snapshot()
                putInt("attempt", diagnostic?.attempt ?: 0)
                putString("stage", diagnostic?.stage?.name ?: "NO_HOST_ROOT")
                putStringArrayList("reached", ArrayList(diagnostic?.reached?.map { it.name }.orEmpty()))
                putString("block", diagnostic?.block?.name ?: "NONE")
                putString("evidence", diagnostic?.evidence?.name ?: "NONE")
                putString("detail", diagnostic?.detail?.name ?: "NONE")
                putString("geometry", diagnostic?.geometry?.name ?: "UNKNOWN")
                putString("frameStatus", diagnostic?.frameStatus?.name ?: "UNAVAILABLE")
                putString("legacyFrame", diagnostic?.legacyFrame?.name ?: "UNKNOWN")
                putStringArrayList("windowOutside", ArrayList(diagnostic?.windowOutside?.map { it.name }.orEmpty()))
                putStringArrayList("legacyOutside", ArrayList(diagnostic?.legacyOutside?.map { it.name }.orEmpty()))
                putString("rootWithinWindow", diagnostic?.rootWithinWindow?.let { if (it) "YES" else "NO" } ?: "UNKNOWN")
                putString("dialogClass", diagnostic?.dialogClassPresent?.let { if (it) "PRESENT" else "ABSENT" } ?: "UNKNOWN")
                putString("syntheticRole", diagnostic?.syntheticRole?.name ?: "UNKNOWN")
                val buffer = diagnostic?.buffer
                putString("bufferWidth", buffer?.width?.name ?: "NOT_OBSERVED")
                putString("bufferHeight", buffer?.height?.name ?: "NOT_OBSERVED")
                putString("bufferExtent", buffer?.extent?.name ?: "NOT_OBSERVED")
                putString("bufferMapping", buffer?.mapping?.name ?: "NOT_OBSERVED")
                putString("bufferMemory", buffer?.memory?.name ?: "NOT_OBSERVED")
                putString("bufferDisplaySized", buffer?.let { if (it.displaySized) "YES" else "NO" } ?: "UNKNOWN")
                putString("bufferSwapped", buffer?.let { if (it.windowDimensionsSwapped) "YES" else "NO" } ?: "UNKNOWN")
                putBoolean("bufferReleased", buffer?.released == true)
                putString("api", diagnostic?.api?.name ?: "NONE")
                putString("targetType", diagnostic?.targetType?.name ?: "UNKNOWN")
                putString("relatedType", diagnostic?.relatedType?.name ?: "NONE")
                putString("eventWindow", diagnostic?.eventWindow?.name ?: "UNKNOWN")
                putInt("ignoredEvents", diagnostics?.ignoredEvents() ?: 0)
            }
            "END" -> if (session == null || session != arg) code("INVALID_SESSION") else {
                finish()
                code("ENDED")
            }
            else -> code("UNKNOWN_METHOD")
        }
    }

    private fun finish() {
        session = null
        if (SyntheticBufferInspection.probe === patternProbe) SyntheticBufferInspection.probe = null
        patternProbe?.close()
        patternProbe = null
        collector?.cancel()
        collector = null
        expiry?.let(main::removeCallbacks)
        expiry = null
        // The probe never changes user consent, app permissions or pause state.
        result = "ENDED"
        if (CaptureDiagnostics.observer === diagnostics) CaptureDiagnostics.observer = null
        diagnostics?.close()
        diagnostics = null
        completedDiagnostic = null
        ocrCount = 0
    }

    private fun status(): Bundle {
        val ctx = requireNotNull(context)
        val manager = ctx.getSystemService(AccessibilityManager::class.java)
        val expected = ComponentName(ctx.packageName, "kr.closeforyou.accessibility.CloseForYouAccessibilityService")
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            ComponentName(info.packageName, info.name) == expected
        }
        val tick = CountDownLatch(1)
        val responsive = if (Looper.myLooper() == Looper.getMainLooper()) true else {
            val callback = Runnable { tick.countDown() }
            main.post(callback)
            try { tick.await(250, TimeUnit.MILLISECONDS) } finally { main.removeCallbacks(callback) }
        }
        val lifecycle = AccessibilityRuntime.serviceLifecycle.snapshot()
        val connected = AccessibilityRuntime.status.value.connected
        return code(connectionCode(enabled, connected, lifecycle, Process.myPid(), responsive).name).apply {
            putBoolean("enabled", enabled)
            putBoolean("connected", connected)
            putBoolean("mainResponsive", responsive)
            putBoolean("sameProcess", lifecycle.ownerPid == Process.myPid())
            putBoolean("sameLoader", lifecycle.loaderMatchesRuntime)
            putString("phase", lifecycle.phase.name)
            putInt("creates", lifecycle.creates)
            putInt("connections", lifecycle.connections)
        }
    }

    private fun code(value: String) = Bundle().apply { putString("code", value) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    private companion object { const val HOST = "kr.closeforyou.capturetest" }
}
