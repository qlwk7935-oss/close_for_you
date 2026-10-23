package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.SnapshotToken
import kotlin.coroutines.resume

/** A-only final presentation boundary. It grants no event exemption and never refreshes a token.
 * One original, node-free comparison baseline; no Android handles, evidence texts, or event history.
 * A posted turn gives already queued work a chance to invalidate BEFORE C exists. It is not proof
 * that Binder/Android's queues are drained. Every event after presentation still uses normal policy.
 */
internal class ChoicePresentationGate(
    private val clock: () -> Long,
    private val current: (SnapshotToken) -> Boolean,
    private val cleanupCurrent: (Any) -> Boolean,
    private val nextTurn: suspend () -> Boolean,
    private val verify: (PopupSnapshot, () -> Unit) -> Boolean,
    private val reject: (SnapshotToken) -> Unit,
) {
    internal class Ticket internal constructor() {
        override fun toString() = "PresentationTicket(redacted)"
    }
    private class Observation(val ticket: Ticket, val baseline: PopupSnapshot, val cleanupPermit: Any)
    private var observation: Observation? = null

    /** Install before publish can notify C; unsuccessful publication abandons only its own ticket.
     * Redact nodes even for ordinary advertisements, preserving original capture time/digests/windows.
     */
    fun prepare(snapshot: PopupSnapshot, cleanupPermit: Any): Ticket {
        check(snapshot.completeness == SnapshotCompleteness.COMPLETE) { "PRESENTATION_BASELINE_INCOMPLETE" }
        return Ticket().also { observation = Observation(it, snapshot.copy(nodes = persistentListOf()), cleanupPermit) }
    }
    fun abandon(ticket: Ticket) { if (observation?.ticket === ticket) observation = null }
    fun clear() { observation = null }

    suspend fun awaitReady(token: SnapshotToken): Boolean {
        currentCoroutineContext().ensureActive()
        val active = observation ?: return false
        if (active.baseline.token != token) return false // an old caller must not clear a newer baseline
        try {
            if (!live(active)) { abandon(active.ticket); return false }
            if (!nextTurn()) return decline(active)
            currentCoroutineContext().ensureActive()
            if (!live(active)) { abandon(active.ticket); return false }
            val owner = currentCoroutineContext()
            val checkActive = {
                owner.ensureActive()
                if (!live(active)) throw CancellationException("STALE_PRESENTATION")
            }
            // Fresh collector runs both F0 passes and full snapshot comparison, before C is added.
            if (!verify(active.baseline, checkActive)) return decline(active)
            checkActive()
            // Collector/handle release can enqueue events. Let that already queued work run before
            // C exists, then recheck the SAME epoch, authorization, permit and original deadline.
            if (!nextTurn()) return decline(active)
            currentCoroutineContext().ensureActive()
            if (!live(active)) { abandon(active.ticket); return false }
            // No suspension from this final currentness check through caller's present guards.
            return true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return decline(active)
        }
    }

    private fun live(active: Observation): Boolean {
        if (observation !== active) return false
        val captured = active.baseline.capturedAtUptimeMillis
        val now = clock()
        return captured >= 0 && now >= captured && now - captured < 5_000L &&
            current(active.baseline.token) && cleanupCurrent(active.cleanupPermit)
    }
    private fun decline(active: Observation): Boolean {
        if (observation === active) {
            observation = null
            reject(active.baseline.token)
        }
        return false
    }
    override fun toString() = "ChoicePresentationGate(redacted)"
}

/** One cancellable dispatcher post, not a delay, debounce extension or queue-drain claim. */
internal suspend fun awaitChoicePresentationTurn(post: (Runnable) -> Boolean, remove: (Runnable) -> Unit): Boolean =
    suspendCancellableCoroutine { continuation ->
        val task = Runnable { if (continuation.isActive) continuation.resume(true) }
        fun removeSafely() { try { remove(task) } catch (_: Exception) { /* No retained callback payload. */ } }
        continuation.invokeOnCancellation { removeSafely() }
        val posted = if (continuation.isActive) try { post(task) } catch (_: Exception) { false } else false
        if (!continuation.isActive) removeSafely()
        else if (!posted) continuation.resume(false)
    }
