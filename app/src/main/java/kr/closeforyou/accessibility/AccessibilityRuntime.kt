package kr.closeforyou.accessibility

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.model.*

/** Safe settings-screen status: codes/counts only, never app identity or screen text. */
data class AccessibilityStatus(
    val connected: Boolean = false,
    val screenVersion: Long = 0L,
    val candidateCount: Long = 0L,
    val lastBlockReason: PrivacyBlockReason? = null,
)

/**
 * Process-local integration point for B/C/D. No replay and one bounded transient event slot.
 * Consumers MUST drop their copies on Invalidated/Blocked, disconnect, and the five-second TTL.
 * Never persist or log events; use synthetic Mock data for development outside a live session.
 */
object AccessibilityRuntime : SnapshotSource {
    private val mutableEvents = MutableSharedFlow<PopupEvent>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<PopupEvent> = mutableEvents.asSharedFlow()
    private val mutableStatus = MutableStateFlow(AccessibilityStatus())
    val status: StateFlow<AccessibilityStatus> = mutableStatus.asStateFlow()
    @Volatile private var token: SnapshotToken? = null

    override fun currentToken(): SnapshotToken? = token

    internal fun connect() {
        token = null
        mutableStatus.value = mutableStatus.value.copy(connected = true, lastBlockReason = null)
    }

    internal fun invalidate(version: Long) {
        token = null
        mutableStatus.value = mutableStatus.value.copy(screenVersion = version)
        mutableEvents.tryEmit(PopupEvent.Invalidated(version))
    }

    internal fun block(reason: PrivacyBlockReason, version: Long) {
        token = null
        mutableStatus.value = mutableStatus.value.copy(screenVersion = version, lastBlockReason = reason)
        mutableEvents.tryEmit(PopupEvent.Blocked(reason, version))
    }

    internal fun publish(snapshot: PopupSnapshot, detection: PopupDetection) {
        if (!mutableStatus.value.connected || mutableStatus.value.screenVersion != snapshot.token.captureEpoch) return
        token = snapshot.token
        mutableStatus.value = mutableStatus.value.copy(
            candidateCount = mutableStatus.value.candidateCount + 1, lastBlockReason = null,
        )
        mutableEvents.tryEmit(PopupEvent.Candidate(snapshot, detection))
    }

    internal fun disconnect(version: Long) {
        invalidate(version)
        mutableStatus.value = mutableStatus.value.copy(connected = false)
    }
}
