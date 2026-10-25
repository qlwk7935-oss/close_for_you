package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.SnapshotCompleteness
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.accessibility.CaptureObservationInterval
import kr.closeforyou.accessibility.EventCapturePhase
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
    private class Observation(val ticket: Ticket, val baseline: PopupSnapshot, val cleanupPermit: Any,
        val initial: CaptureObservationInterval?) {
        var final: CaptureObservationInterval? = null
    }
    private var observation: Observation? = null

    /** Install before publish can notify C; unsuccessful publication abandons only its own ticket.
     * Redact nodes even for ordinary advertisements, preserving original capture time/digests/windows.
     */
    fun prepare(snapshot: PopupSnapshot, cleanupPermit: Any, initial: CaptureObservationInterval? = null): Ticket {
        check(snapshot.completeness == SnapshotCompleteness.COMPLETE) { "PRESENTATION_BASELINE_INCOMPLETE" }
        return Ticket().also { observation = Observation(it, snapshot.copy(nodes = persistentListOf()), cleanupPermit, initial) }
    }
    fun abandon(ticket: Ticket) { if (observation?.ticket === ticket) observation = null }
    fun clear() { observation = null }

    /** Only requests a fresh F1 review after interruption; never authorizes showing or an action.
     * A published candidate can be interrupted BEFORE C exists, so no owned C binding is required.
     */
    fun hasCurrentObservation(): Boolean = try {
        observation?.let(::live) == true
    } catch (_: Exception) { false }

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
            val started = clock()
            if (!verify(active.baseline, checkActive)) return decline(active)
            active.final = CaptureObservationInterval(started, clock())
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
    /** First-rejection diagnostic only, from the SAME still-valid observation. No window reads. */
    fun eventPhase(at: Long?): EventCapturePhase = try {
        val active = observation
        val initial = active?.initial
        val final = active?.final
        when {
            active == null || !live(active) || at == null || at <= 0 || at > clock() ||
                initial?.valid != true || final?.valid != true || initial.completed > final.started -> EventCapturePhase.UNKNOWN
            at < initial.started -> EventCapturePhase.BEFORE_INITIAL_F0
            at > initial.started && at < initial.completed -> EventCapturePhase.DURING_INITIAL_F0
            at > initial.completed && at < final.started -> EventCapturePhase.BETWEEN_INITIAL_AND_FINAL_F0
            at > final.started && at < final.completed -> EventCapturePhase.DURING_FINAL_F0
            at > final.completed -> EventCapturePhase.AFTER_FINAL_F0
            else -> EventCapturePhase.UNKNOWN
        }
    } catch (_: Exception) { EventCapturePhase.UNKNOWN }
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

/** A fresh read after cleanup must still discover the NEXT popup. Limit repeated presentation of
 * an unchanged interrupted popup instead of suppressing that read. No authorization or TTL grant. */
internal class ChoiceReviewBudget {
    private data class Identity(val app: String, val window: Int, val structure: String, val content: String) {
        override fun toString() = "ReviewIdentity(redacted)"
    }
    private var identity: Identity? = null
    private var offered = 0
    fun admit(token: SnapshotToken): Boolean {
        val key = Identity(token.packageName, token.windowId, token.structureDigest, token.contentDigest)
        if (identity != key) { identity = key; offered = 0 }
        if (offered >= 2) return false // first presentation and at most one fresh recovery
        offered++
        return true
    }
}
