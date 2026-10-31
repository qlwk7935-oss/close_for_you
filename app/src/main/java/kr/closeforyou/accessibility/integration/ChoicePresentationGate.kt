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
internal enum class ManualReviewAdmission { FIRST_OFFER, UNCHANGED_RECOVERY, IDENTITY_CHANGED, EXHAUSTED }
internal enum class ManualReviewChange { UNKNOWN, INITIAL, UNCHANGED, APP_CHANGED, WINDOW_CHANGED, STRUCTURE_CHANGED, CONTENT_CHANGED, STRUCTURE_AND_CONTENT_CHANGED }
internal class ChoiceReviewBudget(private val boundGeometryRecovery: Boolean = false) {
    private data class Identity(val app: String, val window: Int, val structure: String, val content: String) {
        override fun toString() = "ReviewIdentity(redacted)"
    }
    private var identity: Identity? = null
    private var offered = 0
    var lastAdmission = ManualReviewAdmission.FIRST_OFFER
        private set
    var lastChange = ManualReviewChange.INITIAL
        private set
    fun admit(token: SnapshotToken): Boolean {
        val key = Identity(token.packageName, token.windowId, token.structureDigest, token.contentDigest)
        val previous = identity
        lastChange = when {
            previous == null -> ManualReviewChange.INITIAL
            previous.app != key.app -> ManualReviewChange.APP_CHANGED
            previous.window != key.window -> ManualReviewChange.WINDOW_CHANGED
            previous.structure != key.structure && previous.content != key.content -> ManualReviewChange.STRUCTURE_AND_CONTENT_CHANGED
            previous.structure != key.structure -> ManualReviewChange.STRUCTURE_CHANGED
            previous.content != key.content -> ManualReviewChange.CONTENT_CHANGED
            else -> ManualReviewChange.UNCHANGED
        }
        if (identity != key) {
            // Debug B rollout: bounds are part of structureDigest, so layout churn must not
            // reopen the first+one recovery budget. This is a DENIAL context, not popup identity;
            // every offered snapshot still requires complete fresh F0/F1/B and the final Gate.
            val sameContext = previous != null && previous.app == key.app && previous.window == key.window && previous.content == key.content
            val preserveBudget = boundGeometryRecovery && sameContext
            lastAdmission = when {
                identity == null -> ManualReviewAdmission.FIRST_OFFER
                preserveBudget -> ManualReviewAdmission.UNCHANGED_RECOVERY
                else -> ManualReviewAdmission.IDENTITY_CHANGED
            }
            identity = key
            if (!preserveBudget) offered = 0
        } else lastAdmission = ManualReviewAdmission.UNCHANGED_RECOVERY
        if (offered >= 2) { lastAdmission = ManualReviewAdmission.EXHAUSTED; return false } // first + one recovery
        offered++
        return true
    }
}

/** Native callback and event receipt may both finish the SAME cleanup. Schedule one new read
 * per actual barrier permit; never replay a candidate or reset the presentation budget. */
internal class ManualCleanupReview {
    private var requested: Any? = null
    fun take(permit: Any?, closedOrFailed: Boolean, selected: Boolean): Boolean {
        if (permit == null || closedOrFailed || selected || requested === permit) return false
        requested = permit
        return true
    }
}

/** A late receipt after native completion must not clear a NEW native View or schedule another
 * recovery. Used around the real Android cleanup.processed call, never as a cleanup certificate. */
internal inline fun completeManualCleanupReview(wasPending: Boolean, ready: Boolean, hidden: Boolean,
    completed: () -> Unit) {
    if (wasPending && ready && hidden) completed()
}
