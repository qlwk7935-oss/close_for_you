package kr.closeforyou.accessibility

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException

/** Every observed interaction/change invalidates outstanding analyses before debounce starts. */
internal class ScreenFreshness {
    @Volatile var version: Long = 0L
        private set

    @Synchronized fun invalidate(): Long = ++version

    fun isCurrent(epoch: Long): Boolean = epoch == version

    fun checkActive(epoch: Long, owner: Job, available: () -> Boolean) {
        owner.ensureActive()
        if (!isCurrent(epoch) || !available()) throw CancellationException("STALE_COLLECTION")
        owner.ensureActive()
        if (!isCurrent(epoch)) throw CancellationException("STALE_COLLECTION")
    }
}
