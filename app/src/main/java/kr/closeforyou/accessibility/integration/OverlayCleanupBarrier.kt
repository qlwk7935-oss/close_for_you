package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import kr.closeforyou.accessibility.CollectionReleaseBatch
import kr.closeforyou.accessibility.CollectionResourceSafety
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.overlay.OverlayListener

internal enum class CleanupWindowPresence { ABSENT, PRESENT, UNKNOWN }

/** Main-thread A lifetime, independent of binding and of any popup authorization.
 * A receipt proves handling of ONE observed pure removal, never draining Android's whole queue.
 * No event, node, window, token, text or exception is retained. Optional Debug observer has no authority.
 */
internal class OverlayCleanupBarrier(private val timeoutMillis: Long = 5_000L, private val onUncertain: () -> Unit = {},
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L },
    private val diagnostic: OverlayEventOriginDiagnostic? = null) {
    internal class Receipt internal constructor(internal val identity: Any) {
        override fun toString() = "CleanupReceipt(redacted)"
    }
    private class Retiring(val window: Int, val started: Long, val identity: Any = Any()) {
        val completed = CompletableDeferred<Unit>()
        var returned = false
        var observed = false
    }
    private var retiring: Retiring? = null
    private var revision = Any()
    private var failed = false
    val ready: Boolean get() = !failed && retiring == null
    val pending: Boolean get() = retiring != null

    fun permit(): Any? = if (ready) revision else null
    fun permits(permit: Any): Boolean = ready && revision === permit

    /** Notification is intent only. Unknown identity makes this lifetime unavailable. */
    fun retiring(window: Int?, generation: Long, targetWindow: Int?) {
        if (failed) return
        if (retiring != null || window == null || window < 0 || generation <= 0 ||
            targetWindow == null || targetWindow < 0 || window == targetWindow) { fail(); return }
        revision = Any()
        retiring = Retiring(window, clock())
        diagnostic?.retiring(window, generation)
    }

    fun removalReturned(hidden: Boolean) {
        if (failed) return
        val previous = retiring ?: return
        if (!hidden) { fail(); return }
        previous.returned = true
        diagnostic?.removalReturned()
    }

    /** Called before ordinary event handling. This NEVER decides whether to ignore an event. */
    fun observe(window: Int, windowsChanged: Boolean, pureRemoval: Boolean): Receipt? {
        val previous = retiring ?: return null
        if (failed || window != previous.window) return null
        if (!windowsChanged || !pureRemoval || previous.observed) { fail(); return null }
        previous.observed = true
        return Receipt(previous.identity)
    }

    /** Only after the service handler returned normally; query must release all acquired windows. */
    fun processed(receipt: Receipt, hidden: Boolean, beforeRelease: () -> Unit = {}, presence: (Int) -> CleanupWindowPresence) {
        val previous = retiring ?: return
        if (failed || previous.identity !== receipt.identity) return
        diagnostic?.removalHandled()
        val absent = try { presence(previous.window) } catch (_: Exception) { CleanupWindowPresence.UNKNOWN }
        if (remaining(previous) <= 0 || !previous.returned || !previous.observed || !hidden || absent != CleanupWindowPresence.ABSENT) {
            fail(); return
        }
        // Cancel obsolete observation waiters and schedule fresh F0 BEFORE waking admission.
        // Otherwise an inline-resumed old waiter could publish and then be cancelled by refresh.
        try { beforeRelease() } catch (_: Exception) { fail(); return }
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
            if (!finished) fail()
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

    fun fail() {
        if (failed) return
        failed = true; revision = Any()
        val previous = retiring; retiring = null
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
