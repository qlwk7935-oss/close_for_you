package kr.closeforyou.accessibility

/** Shared Android/JVM boundary. refresh requests the current remote View state, not an action.
 * No native object/ID is retained. A true result is supplementary evidence, never native ABA proof.
 */
internal fun refreshPresentationNodeRead(started: Long, clock: () -> Long, checkpoint: () -> Unit,
    window: () -> Int, refresh: () -> Boolean,
    onRejected: (CaptureDetail) -> Unit = {}): Boolean {
    fun rejected(detail: CaptureDetail): Boolean {
        // A diagnostic callback cannot change refresh/cancellation semantics.
        try { onRejected(detail) } catch (_: Exception) { }
        return false
    }
    checkpoint()
    val id = window()
    val before = clock()
    if (id < 0) return rejected(CaptureDetail.INVALID_WINDOW)
    if (started < 0 || before < started) return rejected(CaptureDetail.INVALID_CLOCK)
    if (before - started >= 100L) return rejected(CaptureDetail.TIME_LIMIT)
    if (!refresh()) return rejected(CaptureDetail.REFRESH_FAILED)
    checkpoint()
    val after = clock()
    if (window() != id) return rejected(CaptureDetail.REFRESH_WINDOW_CHANGED)
    if (after < before) return rejected(CaptureDetail.INVALID_CLOCK)
    if (after - started >= 100L) return rejected(CaptureDetail.TIME_LIMIT)
    return true
}
