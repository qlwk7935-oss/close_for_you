package kr.closeforyou.accessibility.capture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import android.view.Display
import androidx.core.graphics.get
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.accessibility.CaptureApi
import kr.closeforyou.accessibility.CaptureTrace
import kr.closeforyou.accessibility.CaptureStage
import kr.closeforyou.accessibility.CaptureDetail
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.SnapshotToken
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException

/** An internal reference-counted pixel lease. Native ML Kit owns a lease until its Task completes. */
internal class BitmapFrame(
    private val bitmap: Bitmap,
    override val bounds: ScreenBounds,
    private val onReleased: () -> Unit,
) : CaptureFrame {
    private var references = 1
    @Synchronized fun retain(): AutoCloseable {
        check(references > 0)
        references++
        val closed = AtomicBoolean()
        return AutoCloseable { if (closed.compareAndSet(false, true)) release() }
    }
    @Synchronized fun pixels(): Bitmap { check(references > 0); return bitmap }
    private val ownerClosed = AtomicBoolean()
    override fun close() { if (ownerClosed.compareAndSet(false, true)) release() }
    @Synchronized private fun release() {
        if (--references == 0) {
            try { bitmap.eraseColor(Color.TRANSPARENT) } finally { bitmap.recycle(); onReleased() }
        }
    }
}

/** The only component calling Android screenshot APIs. No automatic fallback/retry. */
internal class AndroidScreenshotManager(
    private val service: AccessibilityService,
    private val preferences: CorePreferences,
    private val current: (SnapshotToken) -> Boolean,
    private val connected: () -> Boolean,
) : ScreenshotPort {
    override val isolatedWindowCapture: Boolean get() = Build.VERSION.SDK_INT >= 34
    override fun availability(token: SnapshotToken): CaptureAvailability =
        if (denial(token) == null) CaptureAvailability.AVAILABLE else CaptureAvailability.PRIVACY_BLOCKED

    override fun denial(token: SnapshotToken): EvidenceIssue? {
        if (!connected()) return EvidenceIssue.SERVICE_DISABLED
        if (!current(token)) return EvidenceIssue.STALE_SCREEN
        val settings = preferences.currentSettings()
        if (!settings.collectionEnabled || !settings.captureConsentGranted ||
            token.packageName !in settings.allowedPackages || token.packageName !in settings.captureAllowedPackages) {
            return EvidenceIssue.NOT_REQUESTED
        }
        if (service.serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) {
            return EvidenceIssue.CAPABILITY_MISSING
        }
        @Suppress("DEPRECATION")
        if (service.magnificationController.scale != 1f) return EvidenceIssue.REGION_UNCERTAIN
        return budget.denial()
    }

    override fun denialTraced(token: SnapshotToken, trace: CaptureTrace): EvidenceIssue? = denial(token).also {
        if (it == EvidenceIssue.REGION_UNCERTAIN) trace.detail(CaptureDetail.MAGNIFIED)
    }
    override suspend fun capture(snapshot: PopupSnapshot, region: CaptureRegion): CaptureFrame =
        captureTraced(snapshot, region, CaptureTrace(null))

    override suspend fun captureTraced(snapshot: PopupSnapshot, region: CaptureRegion, trace: CaptureTrace): CaptureFrame {
        denialTraced(snapshot.token, trace)?.let { throw EvidenceFailure(it) }
        if (PopupRegionResolver.resolve(snapshot, isolatedWindowCapture) != region) {
            trace.detail(CaptureDetail.REGION_REVALIDATION)
            throw EvidenceFailure(EvidenceIssue.REGION_UNCERTAIN)
        }
        val requestedSource = if (Build.VERSION.SDK_INT >= 34) region.window.bounds else snapshot.screenBounds
        if (PopupRegionResolver.area(requestedSource) > MAX_SOURCE_PIXELS ||
            PopupRegionResolver.area(region.bounds) > MAX_ROI_PIXELS) throw EvidenceFailure(EvidenceIssue.MEMORY_LIMIT)
        val permit = budget.acquire()
        try {
            return withTimeout(1_000) {
                suspendCancellableCoroutine { continuation ->
                    val windowCapture = Build.VERSION.SDK_INT >= 34
                    val callback = object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            var wrapped: Bitmap? = null
                            var cropped: Bitmap? = null
                            var software: Bitmap? = null
                            var frame: BitmapFrame? = null
                            var issue = EvidenceIssue.CAPTURE_FAILED
                            try {
                                if (!continuation.isActive || denialIgnoringBudget(snapshot.token) != null) {
                                    throw EvidenceFailure(EvidenceIssue.STALE_SCREEN)
                                }
                                val source = if (windowCapture) region.window.bounds else snapshot.screenBounds
                                if (buffer.width != source.width || buffer.height != source.height ||
                                    !PopupRegionResolver.contains(source, region.bounds)) {
                                    trace.detail(CaptureDetail.BUFFER_GEOMETRY)
                                    throw EvidenceFailure(EvidenceIssue.REGION_UNCERTAIN)
                                }
                                if ((buffer.format != HardwareBuffer.RGBA_8888 && buffer.format != HardwareBuffer.RGBX_8888) ||
                                    buffer.layers != 1 || buffer.width.toLong() * buffer.height > MAX_SOURCE_PIXELS ||
                                    PopupRegionResolver.area(region.bounds) > MAX_ROI_PIXELS) {
                                    throw EvidenceFailure(EvidenceIssue.MEMORY_LIMIT)
                                }
                                wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    ?: throw EvidenceFailure(EvidenceIssue.CAPTURE_FAILED)
                                // Crop BEFORE OCR/pixel inspection. Outside pixels are never copied into CPU memory.
                                cropped = Bitmap.createBitmap(wrapped, region.bounds.left - source.left,
                                    region.bounds.top - source.top, region.bounds.width, region.bounds.height)
                                software = cropped.copy(Bitmap.Config.ARGB_8888, true)
                                    ?: throw EvidenceFailure(EvidenceIssue.MEMORY_LIMIT)
                                if (!hasVisibleVariation(software)) throw EvidenceFailure(EvidenceIssue.EMPTY_CAPTURE)
                                if (!continuation.isActive || denialIgnoringBudget(snapshot.token) != null) {
                                    throw EvidenceFailure(EvidenceIssue.STALE_SCREEN)
                                }
                                frame = BitmapFrame(software, region.bounds) { permit.close() }
                            } catch (failure: EvidenceFailure) {
                                issue = failure.issue
                            } catch (_: OutOfMemoryError) {
                                issue = EvidenceIssue.MEMORY_LIMIT
                            } catch (_: Exception) {
                                issue = EvidenceIssue.CAPTURE_FAILED
                            } finally {
                                if (frame == null) {
                                    software?.let { it.eraseColor(Color.TRANSPARENT); it.recycle() }
                                    permit.close()
                                }
                                if (cropped !== wrapped) cropped?.recycle()
                                wrapped?.recycle()
                                buffer.close()
                            }
                            // Source hardware buffer is already closed before the OCR coroutine resumes.
                            val ready = frame
                            if (ready != null && continuation.isActive) {
                                continuation.resume(ready, onCancellation = { _, rejected, _ -> rejected.close() })
                            } else {
                                ready?.close()
                                if (continuation.isActive) continuation.resumeWithException(EvidenceFailure(issue))
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) budget.rateLimited()
                            permit.close()
                            // API 35's callback IntDef omits the documented secure-window code.
                            val issue = if (Build.VERSION.SDK_INT >= 34 &&
                                errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW) {
                                EvidenceIssue.SECURE_WINDOW
                            } else when (errorCode) {
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> EvidenceIssue.RATE_LIMITED
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> EvidenceIssue.CAPABILITY_MISSING
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> EvidenceIssue.INVALID_DISPLAY
                                AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> EvidenceIssue.INVALID_WINDOW
                                else -> EvidenceIssue.CAPTURE_FAILED
                            }
                            if (continuation.isActive) continuation.resumeWithException(EvidenceFailure(issue))
                        }
                    }
                    try {
                        trace.api(if (windowCapture) CaptureApi.WINDOW else CaptureApi.DISPLAY)
                        trace.stage(CaptureStage.CAPTURE_API)
                        if (Build.VERSION.SDK_INT >= 34) {
                            service.takeScreenshotOfWindow(snapshot.token.windowId, service.mainExecutor, callback)
                        } else {
                            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
                        }
                    } catch (_: Exception) {
                        permit.close()
                        if (continuation.isActive) continuation.resumeWithException(EvidenceFailure(EvidenceIssue.CAPTURE_FAILED))
                    }
                    // An issued Android call cannot be cancelled. Its late callback must close the buffer
                    // and release the permit. Keeping that permit prevents overlapping abandoned captures.
                }
            }
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw EvidenceFailure(EvidenceIssue.CAPTURE_TIMEOUT)
        }
    }

    private fun denialIgnoringBudget(token: SnapshotToken): EvidenceIssue? {
        if (!connected() || !current(token)) return EvidenceIssue.STALE_SCREEN
        val s = preferences.currentSettings()
        return if (!s.collectionEnabled || !s.captureConsentGranted || token.packageName !in s.allowedPackages ||
            token.packageName !in s.captureAllowedPackages) EvidenceIssue.NOT_REQUESTED else null
    }

    private fun hasVisibleVariation(bitmap: Bitmap): Boolean {
        var min = 255
        var max = 0
        var opaque = 0
        for (y in 0 until bitmap.height step maxOf(1, bitmap.height / 32)) {
            for (x in 0 until bitmap.width step maxOf(1, bitmap.width / 32)) {
                val pixel = bitmap[x, y]
                if (Color.alpha(pixel) > 16) opaque++
                val luminance = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                min = minOf(min, luminance)
                max = maxOf(max, luminance)
            }
        }
        return opaque > 0 && max - min > 4
    }

    companion object {
        private const val MAX_SOURCE_PIXELS = 8_000_000L // <=32 MB at RGBA8888; reject before copy.
        private const val MAX_ROI_PIXELS = 2_000_000L // <=8 MB software input.
        private val budget = CaptureBudget(SystemClock::elapsedRealtime)
    }
}
