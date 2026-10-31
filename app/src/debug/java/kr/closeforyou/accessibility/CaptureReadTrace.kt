package kr.closeforyou.accessibility

import java.util.concurrent.CancellationException

/** One capture only; fixed codes, no Android objects/values/exceptions are stored or logged. */
internal class CaptureReadTrace {
    var detail = CaptureDetail.NONE; private set
    var failurePoint = CaptureReadPoint.UNKNOWN; private set
    var release = CaptureReadRelease.UNKNOWN; private set
    private var current = CaptureReadPoint.UNKNOWN
    private var releaseAttempted = false
    fun point(value: CaptureReadPoint) { current = value }
    fun reject(value: CaptureDetail) { rejectAt(value, current) }
    private fun rejectAt(value: CaptureDetail, at: CaptureReadPoint) {
        if (detail == CaptureDetail.NONE && value != CaptureDetail.NONE) {
            detail = value; failurePoint = at
        }
    }
    fun <T> body(body: () -> T): T = try { body() } catch (failure: Throwable) {
        reject(if (failure is CancellationException) CaptureDetail.COLLECTION_CANCELLED
            else CaptureDetail.COLLECTION_EXCEPTION)
        throw failure
    }
    fun release(at: CaptureReadPoint, cleanup: () -> Unit) {
        if (at != CaptureReadPoint.RELEASE_BATCH) releaseAttempted = true
        try { cleanup() } catch (failure: Throwable) {
            val cancelled = failure is CancellationException
            release = when {
                release == CaptureReadRelease.FAILED_AND_CANCELLED -> release
                cancelled && release == CaptureReadRelease.FAILED ||
                    !cancelled && release == CaptureReadRelease.CANCELLED -> CaptureReadRelease.FAILED_AND_CANCELLED
                cancelled -> CaptureReadRelease.CANCELLED
                else -> CaptureReadRelease.FAILED
            }
            rejectAt(if (cancelled) CaptureDetail.RELEASE_CANCELLED else CaptureDetail.RELEASE_FAILED, at)
            throw failure
        }
    }
    // Called only after the complete top-level release batch returns, never from Ready/Blocked.
    fun released() { if (releaseAttempted && release == CaptureReadRelease.UNKNOWN) release = CaptureReadRelease.COMPLETED }
    override fun toString(): String = "CaptureReadTrace(redacted)"
}
