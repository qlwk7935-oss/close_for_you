package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.*

/** Codes only; neither rule IDs/counts nor Room results/exceptions are retained or published. */
internal enum class RuleCleanupState { NOT_STARTED, RUNNING, SUCCEEDED, FAILED, CANCELLED, CLOSED }

/** One maintenance call per service connection Job. No rule lookup/save or action authorization.
 * Cancellation revokes result ownership immediately; it cannot force a blocking DB call to stop
 * or roll back deletions already performed by D's idempotent maintenance implementation.
 */
internal class ServiceRuleCleanup(private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private var owner: Job? = null
    private var operation: Any? = null
    private var task: Job? = null
    @Volatile var state = RuleCleanupState.NOT_STARTED
        private set

    fun start(scope: CoroutineScope, cleanUp: suspend () -> Unit) {
        val parent = scope.coroutineContext[Job] ?: return
        val old: Job?
        synchronized(this) {
            // Even failed/cancelled/completed work is not retried inside one connection generation.
            if (owner === parent || !parent.isActive) return
            old = task
            val identity = Any()
            owner = parent; operation = identity; state = RuleCleanupState.RUNNING
            val launched = scope.launch(dispatcher) {
                try {
                    currentCoroutineContext().ensureActive()
                    cleanUp()
                    currentCoroutineContext().ensureActive()
                    record(identity, RuleCleanupState.SUCCEEDED)
                } catch (cancelled: CancellationException) {
                    record(identity, RuleCleanupState.CANCELLED)
                    throw cancelled
                } catch (_: Exception) {
                    if (!currentCoroutineContext().isActive) {
                        record(identity, RuleCleanupState.CANCELLED)
                        currentCoroutineContext().ensureActive()
                    }
                    record(identity, RuleCleanupState.FAILED)
                }
            }
            task = launched
            launched.invokeOnCompletion { cause ->
                // Covers a parent cancelled before the dispatcher's first instruction ran.
                if (cause is CancellationException) record(identity, RuleCleanupState.CANCELLED)
            }
        }
        old?.cancel()
    }
    @Synchronized private fun record(identity: Any, result: RuleCleanupState) {
        if (operation === identity) state = result
    }
    fun close() {
        val old = synchronized(this) {
            operation = null; state = RuleCleanupState.CLOSED
            task.also { task = null }
            // Keep only the last connection Job for duplicate-start refusal, never a Room result.
        }
        old?.cancel()
    }
    override fun toString() = "ServiceRuleCleanup(codes_only)"
}
