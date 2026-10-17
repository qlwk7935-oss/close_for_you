package kr.closeforyou.accessibility.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import kr.closeforyou.accessibility.AccessibilityRuntime
import kr.closeforyou.accessibility.DetectionObservationHook
import kr.closeforyou.app.CorePreferences
import java.util.UUID

/** Independent F1 observation. No settings writes, OCR, screenshot or source-text IPC. */
class SyntheticDetectionTestProvider : ContentProvider() {
    private var key: String? = null
    private var session: DetectionTestSession? = null
    private val main = Handler(Looper.getMainLooper())
    private var expiry: Runnable? = null
    private val preferences get() = CorePreferences.get(requireNotNull(context))
    private fun allowed(): Boolean = preferences.currentSettings().let {
        AccessibilityRuntime.status.value.connected && it.collectionEnabled &&
            it.allowedPackages == setOf(DetectionTestSession.HOST) &&
            !it.captureConsentGranted && it.captureAllowedPackages.isEmpty()
    }
    override fun onCreate() = true
    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        requireNotNull(context).enforceCallingPermission(
            "kr.closeforyou.permission.SYNTHETIC_CAPTURE_TEST", "SYNTHETIC_CALLER_DENIED")
        if (callingPackage != DetectionTestSession.HOST) throw SecurityException("SYNTHETIC_CALLER_DENIED")
        if (method == "BEGIN") {
            if (extras?.getBoolean("explicitOptIn") != true) return code("EXPLICIT_OPT_IN_REQUIRED")
            if (session?.valid() == true) return code("SESSION_BUSY")
            if (!allowed()) return code("F1_SETTINGS_REQUIRED")
            finish()
            key = UUID.randomUUID().toString()
            session = DetectionTestSession(SystemClock::uptimeMillis, ::allowed).also { DetectionObservationHook.session = it }
            val current = key
            expiry = Runnable { synchronized(this) { if (key == current) finish() } }.also { main.postDelayed(it, 45_000) }
            return code("STARTED").apply { putString("session", key) }
        }
        if (key == null || key != arg) return code("INVALID_SESSION")
        if (method == "END") { finish(); return code("ENDED") }
        if (!allowed() || session?.valid() != true) { finish(); return code("F1_SESSION_INVALIDATED") }
        return when (method) {
            "RESET" -> { session?.reset(); code("RESET") } // Observation only; never resets detector/budget/settings.
            "RESULT" -> {
                val report = session?.report(extras?.getInt("ownedWindowId", -1) ?: -1)
                    ?: return code("OWNED_WINDOW_UNAVAILABLE")
                code("OBSERVED").apply {
                    putString("actual", report.actual)
                    putInt("candidates", report.candidates)
                    putInt("nonPopups", report.nonPopups)
                    putInt("uncertain", report.uncertain)
                    putInt("blocks", report.blocks)
                    putInt("scrolls", report.scrolls)
                    putInt("toasts", report.toasts)
                    putBoolean("overflow", report.overflow)
                    putStringArrayList("reasons", ArrayList(report.reasons.sorted()))
                    putStringArrayList("blockReasons", ArrayList(report.blockReasons.sorted()))
                    putStringArrayList("attempts", ArrayList(report.attempts))
                }
            }
            else -> code("UNKNOWN_METHOD")
        }
    }
    private fun finish() {
        expiry?.let(main::removeCallbacks); expiry = null
        session?.close(); session = null; key = null; DetectionObservationHook.session = null
    }
    override fun shutdown() { finish(); super.shutdown() }
    private fun code(value: String) = Bundle().apply { putString("code", value) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
