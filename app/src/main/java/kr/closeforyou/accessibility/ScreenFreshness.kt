package kr.closeforyou.accessibility

/** Every observed interaction/change invalidates outstanding analyses before debounce starts. */
internal class ScreenFreshness {
    var version: Long = 0L
        private set

    fun invalidate(): Long = ++version

    fun isCurrent(epoch: Long): Boolean = epoch == version
}
