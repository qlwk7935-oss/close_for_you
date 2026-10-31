package kr.closeforyou.accessibility

/** No diagnostic state or output in Release. Never installed by a production caller. */
@Suppress("UNUSED_PARAMETER")
internal class CaptureReadTrace {
    val detail: CaptureDetail get() = CaptureDetail.NONE
    val failurePoint: CaptureReadPoint get() = CaptureReadPoint.UNKNOWN
    val release: CaptureReadRelease get() = CaptureReadRelease.UNKNOWN
    fun point(value: CaptureReadPoint) = Unit
    fun reject(value: CaptureDetail) = Unit
    fun <T> body(body: () -> T): T = body()
    fun release(at: CaptureReadPoint, cleanup: () -> Unit) = cleanup()
    fun released() = Unit
    override fun toString(): String = "CaptureReadTrace(redacted)"
}
