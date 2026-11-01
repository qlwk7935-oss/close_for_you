package kr.closeforyou.accessibility

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kr.closeforyou.app.CoreSettings

/** observe/start/stop/callbacks are service-main confined; read runs on the supplied worker. */
internal interface EnabledEngineSource {
    fun observe(onChange: () -> Unit): AutoCloseable
    fun read(): OtherEngineState
}

/** A-only lifecycle gate. Unknown, initial query, dirty observation and disconnect all deny.
 * One query at a time; notifications coalesce, no periodic polling or automatic collection.
 * These revisions reject stale local callbacks, never certify remote native generations. */
internal class AEngineExclusion(
    private val source: EnabledEngineSource,
    private val worker: CoroutineDispatcher,
    private val onStateObserved: (OtherEngineState) -> Unit = {},
    private val onRevoked: () -> Unit,
) {
    @Volatile private var allowed = false
    private var connection = 0L
    private var revision = 0L
    private var scope: CoroutineScope? = null
    private var subscription: AutoCloseable? = null
    private var query: Job? = null
    private var broken = false

    val engineAvailable: Boolean get() = allowed
    fun allowsCollection(settings: CoreSettings): Boolean = engineAvailable && settings.collectionEnabled

    fun start(scope: CoroutineScope) {
        stop()
        if (broken) return
        this.scope = scope
        val owner = connection
        try {
            subscription = source.observe { if (owner == connection && this.scope != null) changed() }
        } catch (_: Exception) {
            // A partial registration cannot be trusted/retried in this service instance.
            broken = true
            this.scope = null
            return
        }
        changed()
    }

    private fun revoke() {
        allowed = false // Close admission before invalidating consumers or cancelling work.
        try { onRevoked() } catch (_: Exception) { broken = true }
        reportState(OtherEngineState.UNKNOWN)
    }

    /** Observation only: a diagnostic consumer must not affect the gate or its cleanup. */
    private fun reportState(state: OtherEngineState) {
        try { onStateObserved(state) } catch (_: Exception) { /* No authority or retry. */ }
    }

    private fun changed() {
        revision++
        revoke()
        val activeScope = scope ?: return
        if (broken || subscription == null || query?.isActive == true) return
        val owner = connection
        val next = activeScope.launch(start = CoroutineStart.LAZY) {
            // A superseded read is discarded; only a new notification permits another read.
            while (owner == connection && !broken) {
                val expected = revision
                val state = try { withContext(worker) { source.read() } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { OtherEngineState.UNKNOWN }
                currentCoroutineContext().ensureActive()
                if (owner != connection || scope == null || broken) return@launch
                if (expected != revision) continue
                allowed = state == OtherEngineState.DISABLED
                reportState(state)
                return@launch
            }
        }
        query = next
        next.start()
    }

    fun stop() {
        connection++
        revision++
        scope = null
        revoke()
        query?.cancel()
        query = null
        val old = subscription
        subscription = null
        try { old?.close() } catch (_: Exception) { broken = true }
    }
}
