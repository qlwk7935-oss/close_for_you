package kr.closeforyou.accessibility.integration

import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.overlay.OverlayListener
import kr.closeforyou.ui.voice.overlay.OverlayNativeWindowLifetime

internal enum class CleanupWindowPresence { ABSENT, PRESENT, UNKNOWN }
internal enum class CleanupFailure {
    RETIREMENT_UNCONFIRMED, REMOVE_NOT_HIDDEN, RETIRED_ID_EVENT, DUPLICATE_REMOVAL,
    DEADLINE, REMOVE_NOT_RETURNED, RECEIPT_UNCONFIRMED, WINDOW_PRESENT, WINDOW_QUERY_UNKNOWN,
    REFRESH_FAILED, EXTERNAL_UNCERTAINTY,
}

/** Main-thread A lifetime, independent of binding and of any popup authorization.
 * A receipt proves handling of ONE observed pure removal, never draining Android's whole queue.
 * No event, node, window, token, text or exception is retained. Optional Debug observer has no authority.
 */
internal class OverlayCleanupBarrier(private val timeoutMillis: Long = 5_000L, private val onUncertain: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val diagnostic: OverlayEventOriginDiagnostic? = null,
    private val failureDiagnostic: ((CleanupFailure) -> Unit)? = null,
    private val failureAttemptDiagnostic: ((CleanupFailure, CleanupNativeProgress) -> Unit)? = null,
    private val eventDiagnostic: ((ManualCleanupEventCode) -> Unit)? = null) {
    internal class Receipt internal constructor(internal val identity: Any) {
        override fun toString() = "CleanupReceipt(redacted)"
    }
    private class Retiring(var window: Int?, val started: Long, val identity: Any = Any(),
        val generation: Long = 0, val native: OverlayNativeWindowLifetime? = null, val nativeSession: Any? = null,
        val targetWindow: Int = -1) {
        val completed = CompletableDeferred<Unit>()
        var returned = false
        var observed = false
        var nativeProgress = if (native == null) CleanupNativeProgress.NOT_AVAILABLE else CleanupNativeProgress.REGISTERED
    }
    private var retiring: Retiring? = null
    private var revision = Any()
    private var failed = false
    val ready: Boolean get() = !failed && retiring == null
    val pending: Boolean get() = retiring != null

    fun permit(): Any? = if (ready) revision else null
    fun permits(permit: Any): Boolean = ready && revision === permit

    /** Notification is intent only. Unknown identity makes this lifetime unavailable. */
    fun retiring(window: Int?, generation: Long, targetWindow: Int?,
        native: OverlayNativeWindowLifetime? = null, nativeSession: Any? = null) {
        if (failed) return
        val nativeId = if (native != null && nativeSession != null)
            native.retirementWindow(nativeSession, generation) else null
        val registeredNative = native != null && nativeSession != null &&
            native.registrationConfirmed(nativeSession, generation) && !native.identityUnavailable
        // A's event binding and C's own attached View are independent identity observations.
        // A same-View registration may reserve BLOCKED cleanup while awaiting identity.
        // It cannot complete without ID + real detach + absence; conflicting evidence fails.
        val retiredId = window ?: nativeId
        if (retiring != null || generation <= 0 || targetWindow == null || targetWindow < 0 ||
            (retiredId != null && (retiredId < 0 || retiredId == targetWindow)) ||
            (native != null && nativeId != null && window != null && nativeId != window) ||
            (retiredId == null && !registeredNative)) {
            fail(CleanupFailure.RETIREMENT_UNCONFIRMED); return
        }
        revision = Any()
        retiring = Retiring(retiredId, clock(), generation = generation,
            native = native.takeIf { registeredNative && (window == null || nativeId == retiredId) },
            nativeSession = nativeSession, targetWindow = targetWindow)
        if (retiredId != null) diagnostic?.retiring(retiredId, generation, nativeIdentity = window == null)
        else diagnostic?.nativeIdentityPending(generation, beforeAttach = native?.hasAttached != true)
    }

    /** Direct C View proof, processed after its invalidating callback/keeper removal returned.
     * Never unblocks an already failed lifetime, or treats removeView return/HIDDEN as detach. */
    fun nativeProcessed(native: OverlayNativeWindowLifetime, hidden: Boolean,
        beforeRelease: () -> Unit = {}, presence: (Int) -> CleanupWindowPresence): Boolean {
        val previous = retiring ?: return false
        val owner = previous.nativeSession ?: return false
        if (failed || previous.native !== native) return false
        if (remaining(previous) <= 0) { fail(CleanupFailure.DEADLINE); return false }
        if (!native.registrationConfirmed(owner, previous.generation) || native.identityUnavailable) {
            fail(CleanupFailure.RETIREMENT_UNCONFIRMED); return false
        }
        val observedId = native.retirementWindow(owner, previous.generation)
        var window = previous.window
        if (window == null) {
            if (observedId == null) {
                previous.nativeProgress = CleanupNativeProgress.WAITING_ID
                return false // No permit or window-list guess while awaiting an ID.
            }
            if (observedId < 0 || observedId == previous.targetWindow) {
                fail(CleanupFailure.RETIREMENT_UNCONFIRMED); return false
            }
            window = observedId; previous.window = observedId
            diagnostic?.nativeIdentityResolved(observedId, previous.generation)
            if (previous.returned) diagnostic?.removalReturned()
        } else if (observedId != null && observedId != window) {
            fail(CleanupFailure.RETIREMENT_UNCONFIRMED); return false
        }
        if (!native.removalComplete(owner, previous.generation, window)) {
            previous.nativeProgress = CleanupNativeProgress.WAITING_NATIVE_COMPLETION
            return false
        }
        val absent = try { presence(window) } catch (_: Exception) { CleanupWindowPresence.UNKNOWN }
        if (failed || retiring !== previous) return false
        val failure = when {
            remaining(previous) <= 0 -> CleanupFailure.DEADLINE
            !previous.returned -> CleanupFailure.REMOVE_NOT_RETURNED
            !hidden -> CleanupFailure.REMOVE_NOT_HIDDEN
            absent == CleanupWindowPresence.PRESENT && previous.observed -> CleanupFailure.WINDOW_PRESENT
            absent == CleanupWindowPresence.UNKNOWN -> CleanupFailure.WINDOW_QUERY_UNKNOWN
            else -> null
        }
        if (failure != null) { fail(failure); return false }
        // View detach may precede the system window-list removal/cache update. PRESENT before
        // the removal receipt is pending, never success or a renewed deadline. A later real
        // REMOVED callback performs the same full absence check; no timer/polling releases it.
        if (absent == CleanupWindowPresence.PRESENT) {
            previous.nativeProgress = CleanupNativeProgress.WAITING_WINDOW_ABSENCE
            return false
        }
        try { beforeRelease() } catch (_: Exception) { fail(CleanupFailure.REFRESH_FAILED); return false }
        if (failed || retiring !== previous || !native.removalComplete(owner, previous.generation, window)) return false
        if (remaining(previous) <= 0) { fail(CleanupFailure.DEADLINE); return false }
        diagnostic?.nativeRemovalHandled()
        previous.nativeProgress = CleanupNativeProgress.CONFIRMED
        retiring = null
        diagnostic?.cleanupConfirmed()
        previous.completed.complete(Unit)
        return true
    }

    fun removalReturned(hidden: Boolean) {
        if (failed) return
        val previous = retiring ?: return
        if (!hidden) { fail(CleanupFailure.REMOVE_NOT_HIDDEN); return }
        previous.returned = true
        diagnostic?.removalReturned()
    }

    /** Called before ordinary event handling. This NEVER decides whether to ignore an event. */
    fun observeWindowEvent(window: Int, eventType: Int, changes: Int, nativeTransientConfirmed: Boolean = false): Receipt? {
        val windowsChanged = eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        // Freeze the existing barrier state before observe() can fail/clear it. No new Android
        // reads or ownership authority: the service still handles this event normally.
        val code = if (eventDiagnostic != null) retiring?.let { previous ->
            val known = AccessibilityEvent.WINDOWS_CHANGE_BOUNDS or AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED
            ManualCleanupEventCode(when (eventType) {
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> ManualEventKind.WINDOW_SET
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> ManualEventKind.WINDOW_STATE
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> ManualEventKind.CONTENT
                AccessibilityEvent.TYPE_VIEW_CLICKED -> ManualEventKind.CLICK
                AccessibilityEvent.TYPE_VIEW_FOCUSED, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED -> ManualEventKind.FOCUS
                else -> ManualEventKind.OTHER
            }, if (windowsChanged) manualWindowMask(changes) else ManualWindowMask.NOT_APPLICABLE,
                windowsChanged && changes and AccessibilityEvent.WINDOWS_CHANGE_BOUNDS != 0,
                windowsChanged && changes and AccessibilityEvent.WINDOWS_CHANGE_ADDED != 0,
                windowsChanged && changes and AccessibilityEvent.WINDOWS_CHANGE_REMOVED != 0,
                windowsChanged && changes and known.inv() != 0,
                if (previous.window == null || window < 0) OwnershipRelation.UNAVAILABLE else
                    if (window == previous.window) OwnershipRelation.MATCH else OwnershipRelation.MISMATCH,
                nativeTransientConfirmed, previous.returned, previous.observed, previous.nativeProgress)
        } else null
        val wasFailed = failed
        var receipt: Receipt? = null
        try {
            return observe(window, windowsChanged,
                windowsChanged && changes == AccessibilityEvent.WINDOWS_CHANGE_REMOVED,
                windowsChanged && changes == AccessibilityEvent.WINDOWS_CHANGE_LAYER,
                detachedContent = eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
                nativeTransient = nativeTransientConfirmed && nativeRetirementTransientEvent(eventType, changes)).also { receipt = it }
        } finally {
            // Report AFTER the original observe, including if its failure callback throws. The
            // fixed outcome labels this event; it never grants removal/ownership authority.
            if (code != null) kr.closeforyou.accessibility.diagnosticWrite {
                eventDiagnostic?.invoke(code.copy(result = when {
                    !wasFailed && failed -> ManualCleanupEventResult.REJECTED
                    receipt != null -> ManualCleanupEventResult.REMOVAL_RECEIPT
                    else -> ManualCleanupEventResult.NO_RECEIPT
                }))
            }
        }
    }

    fun observe(window: Int, windowsChanged: Boolean, pureRemoval: Boolean, pureLayerChange: Boolean = false,
        detachedContent: Boolean = false, nativeTransient: Boolean = false): Receipt? {
        val previous = retiring ?: return null
        if (failed || window != previous.window) return null
        // Content does not resurrect an exact View whose removal-return AND detach are already
        // authenticated. It remains an ordinary EVENT invalidation, with admission blocked until
        // full window absence is verified. No mask/focus exception and no deadline renewal.
        val owner = previous.nativeSession
        val retiredId = previous.window
        // THIS registered C View can emit a queued notification after removeView returned but
        // before detach/system-window completion, also when dismissed without a user choice.
        // Wait only: no acknowledgement/permit/choice permission. Legacy/unknown stays blocked.
        if (nativeTransient && !pureRemoval && owner != null && retiredId != null && previous.returned &&
            previous.native?.registrationConfirmed(owner, previous.generation) == true &&
            !previous.native.identityUnavailable && previous.native.retirementWindow(owner, previous.generation) == retiredId) {
            if (remaining(previous) <= 0) fail(CleanupFailure.DEADLINE)
            return null
        }
        if (detachedContent && !windowsChanged && !previous.observed && previous.returned && owner != null && retiredId != null &&
            previous.native?.removalComplete(owner, previous.generation, retiredId) == true) {
            if (remaining(previous) <= 0) fail(CleanupFailure.DEADLINE)
            return null
        }
        // Removing the target can change C's layer before C's own removal event arrives. LAYER is
        // neither removal nor reappearance evidence. Keep waiting, without renewing the deadline
        // or skipping normal EVENT invalidation. After a removal receipt, any such event remains
        // uncertain. ADDED, mixed masks, content/focus and unknown events still fail below.
        if (windowsChanged && !pureRemoval && pureLayerChange && !previous.observed) return null
        if (!windowsChanged || !pureRemoval) { fail(CleanupFailure.RETIRED_ID_EVENT); return null }
        if (previous.observed) { fail(CleanupFailure.DUPLICATE_REMOVAL); return null }
        previous.observed = true
        return Receipt(previous.identity)
    }

    /** Only after the service handler returned normally; query must release all acquired windows. */
    fun processed(receipt: Receipt, hidden: Boolean, beforeRelease: () -> Unit = {}, presence: (Int) -> CleanupWindowPresence) {
        val previous = retiring ?: return
        if (failed || previous.identity !== receipt.identity) return
        diagnostic?.removalHandled()
        // Android removal events have an ID but no C generation. When the exact native View
        // lifetime is available, even a delayed event for a reused ID must wait for THAT detach.
        previous.native?.let {
            nativeProcessed(it, hidden, beforeRelease, presence)
            return
        }
        val window = previous.window ?: run { fail(CleanupFailure.RETIREMENT_UNCONFIRMED); return }
        val absent = try { presence(window) } catch (_: Exception) { CleanupWindowPresence.UNKNOWN }
        val failure = when {
            remaining(previous) <= 0 -> CleanupFailure.DEADLINE
            !previous.returned -> CleanupFailure.REMOVE_NOT_RETURNED
            !previous.observed -> CleanupFailure.RECEIPT_UNCONFIRMED
            !hidden -> CleanupFailure.REMOVE_NOT_HIDDEN
            absent == CleanupWindowPresence.PRESENT -> CleanupFailure.WINDOW_PRESENT
            absent != CleanupWindowPresence.ABSENT -> CleanupFailure.WINDOW_QUERY_UNKNOWN
            else -> null
        }
        if (failure != null) { fail(failure); return }
        // Cancel obsolete observation waiters and schedule fresh F0 BEFORE waking admission.
        // Otherwise an inline-resumed old waiter could publish and then be cancelled by refresh.
        try { beforeRelease() } catch (_: Exception) { fail(CleanupFailure.REFRESH_FAILED); return }
        retiring = null
        diagnostic?.cleanupConfirmed()
        previous.completed.complete(Unit)
    }

    suspend fun awaitObservationPermit(): Any {
        currentCoroutineContext().ensureActive()
        if (failed) throw CancellationException("OVERLAY_CLEANUP_UNAVAILABLE")
        val previous = retiring
        if (previous != null) {
            // Cancelling a waiter never cancels the shared cleanup completion.
            val budget = remaining(previous)
            val finished = budget > 0 && withTimeoutOrNull(budget) { previous.completed.await(); true } == true
            if (!finished) fail(CleanupFailure.DEADLINE)
        }
        currentCoroutineContext().ensureActive()
        return permit() ?: throw CancellationException("OVERLAY_CLEANUP_UNAVAILABLE")
    }

    private fun remaining(previous: Retiring): Long {
        val elapsed = clock() - previous.started
        return if (elapsed < 0 || elapsed >= timeoutMillis) 0 else timeoutMillis - elapsed
    }

    /** A prompt captured before a wait must not be replayed, even if its token was not cleared. */
    suspend fun awaitPresentation(): Boolean {
        val before = permit()
        val after = awaitObservationPermit()
        return before != null && before === after
    }

    fun fail(reason: CleanupFailure = CleanupFailure.EXTERNAL_UNCERTAINTY) {
        if (failed) {
            kr.closeforyou.accessibility.diagnosticWrite { failureAttemptDiagnostic?.invoke(reason, CleanupNativeProgress.NOT_AVAILABLE) }
            return
        }
        failed = true; revision = Any()
        val previous = retiring; retiring = null
        kr.closeforyou.accessibility.diagnosticWrite {
            failureAttemptDiagnostic?.invoke(reason, previous?.nativeProgress ?: CleanupNativeProgress.NOT_AVAILABLE)
        }
        kr.closeforyou.accessibility.diagnosticWrite { failureDiagnostic?.invoke(reason) }
        diagnostic?.uncertain()
        previous?.completed?.complete(Unit)
        onUncertain()
    }
    fun close() {
        diagnostic?.uncertain()
        if (retiring != null) fail()
        else { failed = true; revision = Any() }
    }
    override fun toString() = "OverlayCleanupBarrier(redacted)"
}

/** Shared by the Android assembly and keeper tests. A notification records intent, not success. */
internal class CleanupOverlayListener(private val delegate: OverlayListener, private val beforeRemove: () -> Unit) : OverlayListener {
    override fun onShown(prompt: ChoicePrompt, speakPressed: () -> Boolean) = delegate.onShown(prompt, speakPressed)
    override fun onDismissed() { beforeRemove(); delegate.onDismissed() }
}

/** Full list absence, not the filtered owned-root list. Empty/unreadable/duplicate IDs are unknown. */
internal fun <W : Any> cleanupWindowPresence(
    target: Int, read: () -> List<W>, id: (W) -> Int, release: (W) -> Unit,
): CleanupWindowPresence {
    var windows: List<W>? = null
    return try {
        CollectionResourceSafety.run(body = {
            val live = read(); windows = live
            if (live.isEmpty() || live.size > 32) return@run CleanupWindowPresence.UNKNOWN
            val ids = mutableSetOf<Int>()
            for (window in live) {
                val value = id(window)
                if (value < 0 || !ids.add(value)) return@run CleanupWindowPresence.UNKNOWN
            }
            if (target in ids) CleanupWindowPresence.PRESENT else CleanupWindowPresence.ABSENT
        }, cleanup = {
            val batch = CollectionReleaseBatch()
            val seen = java.util.IdentityHashMap<W, Boolean>()
            windows?.forEach { window -> if (seen.put(window, true) == null) batch.attempt { release(window) } }
            batch.finish()
        }, cleanupFailure = { CleanupWindowPresence.UNKNOWN })
    } catch (_: Exception) { CleanupWindowPresence.UNKNOWN }
    finally { windows = null }
}
