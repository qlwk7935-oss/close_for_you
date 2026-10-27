package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.diagnosticWrite
import kr.closeforyou.ui.voice.overlay.OverlayWindowStatus
import kr.closeforyou.ui.voice.overlay.OverlayNativeWindowLifetime

internal enum class ManualOwnership { OWNED, NOT_OWNED, UNKNOWN }
internal enum class OwnershipCheck {
    NOT_CHECKED, CLOSED, HEALTH, DISPLAY_LEASE, DISPLAY_STATE, GENERATION, EVENT_TIME,
    PACKAGE, BEFORE_SET, NATIVE_LOOKUP, NATIVE_ID, EVENT_WINDOW, LIVE_SET, COMPLETE,
}
internal enum class OwnershipRelation { NOT_CHECKED, MATCH, MISMATCH, UNAVAILABLE }
internal enum class OwnershipTime { NOT_CHECKED, BEFORE_SHOW, WITHIN_SHOW, FUTURE }
internal enum class OwnershipLease { NOT_CHECKED, ACTIVE, NOT_STARTED, EXPIRED, CLOCK_REVERSED }
internal enum class OwnershipBefore { NOT_CHECKED, AVAILABLE, UNAVAILABLE, NATIVE_ABSENT, NATIVE_ALREADY_PRESENT }
internal enum class OwnershipLive { NOT_CHECKED, UNAVAILABLE, EMPTY, MULTIPLE, SINGLE_MATCH, SINGLE_OTHER }
internal enum class OwnershipNative { NOT_CHECKED, ABSENT, REGISTERED_OBJECT }

/** Enum-only observation of the actual short-circuit path. NOT_CHECKED never means a pass.
 * NOT_OWNED means an affirmative mismatch for this show, not proof of another app's origin. */
internal data class ManualOwnershipCode(val result: ManualOwnership, val check: OwnershipCheck,
    val window: OwnershipRelation, val pkg: OwnershipRelation,
    val generation: OwnershipRelation, val nativeGeneration: OwnershipRelation,
    val time: OwnershipTime, val lease: OwnershipLease, val display: OverlayWindowStatus?,
    val before: OwnershipBefore, val live: OwnershipLive,
    val native: OwnershipNative, val lookup: OwnershipRelation)

internal class ManualOwnershipProbe {
    var result = ManualOwnership.UNKNOWN
    var check = OwnershipCheck.NOT_CHECKED
    var window = OwnershipRelation.NOT_CHECKED
    var pkg = OwnershipRelation.NOT_CHECKED
    var generation = OwnershipRelation.NOT_CHECKED
    var nativeGeneration = OwnershipRelation.NOT_CHECKED
    var time = OwnershipTime.NOT_CHECKED
    var lease = OwnershipLease.NOT_CHECKED
    var display: OverlayWindowStatus? = null
    var before = OwnershipBefore.NOT_CHECKED
    var live = OwnershipLive.NOT_CHECKED
    var native = OwnershipNative.NOT_CHECKED
    var lookup = OwnershipRelation.NOT_CHECKED
    fun reject(at: OwnershipCheck, verdict: ManualOwnership = ManualOwnership.UNKNOWN): Boolean {
        check = at; result = verdict; return false
    }
    fun accepted(): Boolean { check = OwnershipCheck.COMPLETE; result = ManualOwnership.OWNED; return true }
    fun code() = ManualOwnershipCode(result, check, window, pkg, generation, nativeGeneration,
        time, lease, display, before, live, native, lookup)
    override fun toString() = "ManualOwnershipProbe(redacted)"
}

/** The exact native lookup used by the real reader. REGISTERED_OBJECT is only the C/A
 * observer handshake; only liveWindow success confirms the complete native lifetime guard. */
internal fun readManualNativeWindow(native: OverlayNativeWindowLifetime?, owner: Any,
    generation: Long, diagnostic: ManualOwnershipProbe?): Int? {
    diagnostic?.native = if (native == null) OwnershipNative.ABSENT else OwnershipNative.REGISTERED_OBJECT
    diagnostic?.nativeGeneration = if (native == null) OwnershipRelation.UNAVAILABLE else
        if (native.generation == generation) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH
    return native?.liveWindow(owner, generation)
}

internal enum class ManualCleanupSite {
    BARRIER, OVERLAY_REMOVE, TTS_STOP, CALLBACK_SHOWN, CALLBACK_DISMISSED,
    GUARDED_PRESENTER, NATIVE_CALLBACK, EVENT_HANDLER, WORKER, FLOW, SERVICE_CLOSE, TTS_CLOSE,
}
internal enum class ManualCleanupIssue {
    BARRIER_REJECTED, EXCEPTION, CANCELLED, REMOVE_FAILED_STATE, GUARD_REJECTED,
    HANDLER_NOT_COMPLETED, FAILURE_CALLBACK_EXCEPTION,
}
internal enum class ManualCleanupPhase { FIRST, CHAIN }
internal enum class CleanupNativeProgress {
    NOT_AVAILABLE, REGISTERED, WAITING_ID, WAITING_NATIVE_COMPLETION, WAITING_WINDOW_ABSENCE, CONFIRMED,
}
internal data class ManualCleanupIncident(val site: ManualCleanupSite, val issue: ManualCleanupIssue,
    val barrier: CleanupFailure? = null, val native: CleanupNativeProgress = CleanupNativeProgress.NOT_AVAILABLE)
internal data class ManualCleanupCode(val phase: ManualCleanupPhase, val first: ManualCleanupIncident,
    val current: ManualCleanupIncident)

/** Per service, intentionally NOT reset by a new offer. Preserve the first failure, and bound
 * repeated chain diagnostics. No exception, event, token, ID or text is retained. */
internal class ManualCleanupTrace(private val sink: (ManualCleanupCode) -> Unit) {
    private var first: ManualCleanupIncident? = null
    private val recorded = mutableSetOf<ManualCleanupIncident>()
    fun record(site: ManualCleanupSite, issue: ManualCleanupIssue,
        barrier: CleanupFailure? = null, native: CleanupNativeProgress = CleanupNativeProgress.NOT_AVAILABLE) {
        val current = ManualCleanupIncident(site, issue, barrier, native)
        val initial = first ?: current.also { first = it }
        if (recorded.size >= 32 || !recorded.add(current)) return
        diagnosticWrite { sink(ManualCleanupCode(if (initial === current) ManualCleanupPhase.FIRST
            else ManualCleanupPhase.CHAIN, initial, current)) }
    }
    override fun toString() = "ManualCleanupTrace(redacted)"
}

/** A's real cleanup callback boundary, also used by JVM throwing/cancellation regressions.
 * Report fixed codes, then preserve the exact original thrown object. */
internal inline fun <T> observeManualCleanup(site: ManualCleanupSite, trace: ManualCleanupTrace?, body: () -> T): T =
    try { body() }
    catch (cancelled: CancellationException) {
        trace?.record(site, ManualCleanupIssue.CANCELLED); throw cancelled
    } catch (failure: Exception) {
        trace?.record(site, ManualCleanupIssue.EXCEPTION); throw failure
    }

/** C can catch a listener exception and still return normally. Revoke A permission before
 * returning that original failure to C. A failing notification cannot replace the primary error. */
internal inline fun <T> manualCleanupCall(site: ManualCleanupSite, trace: ManualCleanupTrace?,
    barrier: OverlayCleanupBarrier, body: () -> T): T = try {
    observeManualCleanup(site, trace, body)
} catch (failure: Exception) {
    try { barrier.fail() } catch (_: Exception) {
        trace?.record(ManualCleanupSite.BARRIER, ManualCleanupIssue.FAILURE_CALLBACK_EXCEPTION)
    }
    throw failure
}

/** The real A dismiss boundary for ALL callers (Flow, invalidation and close). HIDDEN/return
 * records only the completed remove call, never a permit. The existing barrier's native-backed
 * detach or legacy receipt proof AND full window absence checks are unchanged.
 * No retry, deadline reset, window query or forced-ready state. */
internal fun dismissManualOverlay(dismiss: () -> Unit, status: () -> OverlayWindowStatus,
    barrier: OverlayCleanupBarrier, trace: ManualCleanupTrace?) {
    manualCleanupCall(ManualCleanupSite.OVERLAY_REMOVE, trace, barrier) { dismiss() }
    val state = manualCleanupCall(ManualCleanupSite.OVERLAY_REMOVE, trace, barrier) { status() }
    if (state == OverlayWindowStatus.REMOVE_FAILED) {
        trace?.record(ManualCleanupSite.OVERLAY_REMOVE, ManualCleanupIssue.REMOVE_FAILED_STATE)
        barrier.fail(CleanupFailure.REMOVE_NOT_HIDDEN)
    } else if (barrier.pending) barrier.removalReturned(state == OverlayWindowStatus.HIDDEN)
}
