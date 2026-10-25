package kr.closeforyou.accessibility

import java.util.concurrent.CancellationException

/** Fixed local failure only: never wrap, suppress or retain a framework exception/message. */
internal class CollectionCleanupFailure : IllegalStateException("COLLECTION_CLEANUP_FAILED")

/** Synchronous collector/child lifetime boundary, shared with JVM fault-injection tests.
 * An existing thrown failure wins over an ordinary cleanup exception. Cancellation is never
 * converted to a result: retain the original cancellation, or propagate a cleanup cancellation.
 * This does not poll a Job or interrupt blocking framework calls.
 */
internal object CollectionResourceSafety {
    /** Attempt both pixel cleanups, but quarantine the memory permit unless both succeeded. */
    fun releasePixels(erase: () -> Unit, recycle: () -> Unit, releasePermit: () -> Unit) {
        val pixels = CollectionReleaseBatch()
        pixels.attempt(erase); pixels.attempt(recycle); pixels.finish()
        val permit = CollectionReleaseBatch()
        permit.attempt(releasePermit); permit.finish()
    }

    fun <T> run(body: () -> T, cleanup: () -> Unit, cleanupFailure: (T) -> T): T {
        var primary: Throwable? = null
        var cleanupFailed = false
        val result = try {
            body()
        } catch (failure: Throwable) {
            // Track only for finally precedence; even VM Errors are immediately rethrown.
            primary = failure
            throw failure
        } finally {
            try {
                cleanup()
            } catch (cancelled: CancellationException) {
                if (primary !is CancellationException && primary !is Error) throw cancelled
            } catch (_: Exception) {
                cleanupFailed = true
            }
        }
        return if (cleanupFailed) cleanupFailure(result) else result
    }
}

/** Capture-local root/window cleanup. Each callback executes once and is never retained.
 * Ordinary exceptions are reduced to a boolean; retain only a cancellation until finish().
 * Fatal VM Errors are not recoverable cleanup outcomes.
 */
internal class CollectionReleaseBatch {
    private var failed = false
    private var cancellation: CancellationException? = null

    fun attempt(release: () -> Unit) {
        try {
            release()
        } catch (cancelled: CancellationException) {
            if (cancellation == null) cancellation = cancelled
        } catch (_: Exception) {
            failed = true
        }
    }

    fun finish() {
        val cancelled = cancellation
        cancellation = null
        if (cancelled != null) throw cancelled
        if (failed) throw CollectionCleanupFailure()
    }

    override fun toString(): String = "CollectionReleaseBatch(redacted)"
}

/** One native submission's lease, not a cancellation/force-stop facility.
 * Once submission may have started, an exception cannot prove that native readers stopped.
 * The caller must retain this owner until an actual completion callback (or process exit).
 */
internal class CollectionTaskLease(private var release: (() -> Unit)?) {
    private var submitted = false
    private var finished = false
    private var cleanupFailed = false

    @Synchronized fun beforeSubmit() {
        check(!submitted && !finished) { "TASK_LEASE_STATE" }
        submitted = true
    }

    @Synchronized fun submissionFailed() {
        if (!submitted) finish()
    }

    @Synchronized fun completed() { finish() }

    private fun finish() {
        if (finished) {
            if (cleanupFailed) throw CollectionCleanupFailure()
            return
        }
        finished = true
        val cleanup = release
        release = null
        try { cleanup?.invoke() } catch (failure: Throwable) {
            cleanupFailed = true
            throw failure
        }
    }

    override fun toString(): String = "CollectionTaskLease(redacted)"
}
