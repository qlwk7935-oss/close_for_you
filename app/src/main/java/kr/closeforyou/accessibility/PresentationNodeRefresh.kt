package kr.closeforyou.accessibility

/** Shared Android/JVM boundary. refresh requests the current remote View state, not an action.
 * No native object/ID is retained. A true result is supplementary evidence, never native ABA proof.
 */
internal fun refreshPresentationNodeRead(started: Long, clock: () -> Long, checkpoint: () -> Unit,
    window: () -> Int, refresh: () -> Boolean): Boolean {
    checkpoint()
    val id = window()
    val before = clock()
    if (id < 0 || started < 0 || before < started || before - started >= 100L) return false
    if (!refresh()) return false
    checkpoint()
    val after = clock()
    return window() == id && after >= before && after - started < 100L
}
