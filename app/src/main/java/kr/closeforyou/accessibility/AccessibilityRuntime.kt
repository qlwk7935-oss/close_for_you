package kr.closeforyou.accessibility

import android.os.SystemClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onSubscription
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.CaptureCapability
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kr.closeforyou.core.model.*

/** Safe settings-screen status: codes/counts only, never app identity or screen text. */
data class AccessibilityStatus(
    val connected: Boolean = false,
    val screenVersion: Long = 0L,
    val candidateCount: Long = 0L,
    val lastBlockReason: PrivacyBlockReason? = null,
    val lastEvidenceIssue: EvidenceIssue? = null,
    val ocrTextCount: Int = 0,
    val lastCompletedOcrTextCount: Int = 0,
)

/**
 * Process-local integration point for B/C/D. No replay and one bounded transient event slot.
 * Consumers MUST drop their copies on Invalidated/Blocked, disconnect, and the five-second TTL.
 * Never persist or log events; use synthetic Mock data for development outside a live session.
 */
object AccessibilityRuntime : SnapshotSource, EvidenceExtractor, CaptureCapability {
    internal val serviceLifecycle = ServiceLifecycleMonitor()
    private val mutableEvents = MutableSharedFlow<PopupEvent>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<PopupEvent> = mutableEvents.asSharedFlow()
    /** Assembly-only readiness hook; invoked after the replay=0 consumer is registered. */
    internal fun integrationEvents(subscribed: () -> Unit): Flow<PopupEvent> =
        mutableEvents.onSubscription { subscribed() }
    private val mutableStatus = MutableStateFlow(AccessibilityStatus())
    val status: StateFlow<AccessibilityStatus> = mutableStatus.asStateFlow()
    private var serviceGuard: ((SnapshotToken) -> Boolean)? = null
    private val live = LiveEvidenceState(SystemClock::uptimeMillis) { serviceGuard?.invoke(it) == true }
    @Volatile private var captureCapability: CaptureCapability? = null

    @Synchronized override fun currentToken(): SnapshotToken? = live.currentToken()

    /** Completed tree/OCR evidence only, with the same published snapshot and original TTL. */
    override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
        currentCoroutineContext().ensureActive()
        val result = synchronized(this) { live.extract(snapshot) }
            ?: throw CancellationException(EvidenceIssue.STALE_SCREEN.name)
        currentCoroutineContext().ensureActive()
        return result
    }

    override fun availability(token: SnapshotToken): CaptureAvailability =
        captureCapability?.availability(token) ?: CaptureAvailability.PRIVACY_BLOCKED

    internal fun attachCaptureCapability(capability: CaptureCapability?) { captureCapability = capability }

    @Synchronized internal fun begin(snapshot: PopupSnapshot, owner: Job): EvidencePublicationPermit? =
        live.begin(snapshot) { owner.isCancelled }

    internal fun recordEvidenceIssue(issue: EvidenceIssue) {
        mutableStatus.value = mutableStatus.value.copy(lastEvidenceIssue = issue)
    }

    @Synchronized internal fun abandon(operation: EvidencePublicationPermit, version: Long) {
        if (!live.discard(operation)) return
        mutableStatus.value = mutableStatus.value.copy(ocrTextCount = 0)
        mutableEvents.tryEmit(PopupEvent.Invalidated(version))
    }

    @Synchronized internal fun connect(guard: (SnapshotToken) -> Boolean) {
        serviceGuard = guard
        live.connect()
        mutableStatus.value = mutableStatus.value.copy(connected = true, lastBlockReason = null, ocrTextCount = 0)
    }

    @Synchronized internal fun invalidate(version: Long) {
        if (!live.invalidate(version)) return
        mutableStatus.value = mutableStatus.value.copy(screenVersion = version, ocrTextCount = 0)
        mutableEvents.tryEmit(PopupEvent.Invalidated(version))
    }

    @Synchronized internal fun block(reason: PrivacyBlockReason, version: Long, operation: EvidencePublicationPermit? = null) {
        if (!(if (operation == null) live.block(version) else live.block(version, operation))) return
        mutableStatus.value = mutableStatus.value.copy(screenVersion = version, lastBlockReason = reason, ocrTextCount = 0)
        mutableEvents.tryEmit(PopupEvent.Blocked(reason, version))
    }

    @Synchronized internal fun publish(operation: EvidencePublicationPermit, snapshot: PopupSnapshot,
        detection: PopupDetection, evidence: PopupAnalysis, issue: EvidenceIssue): Boolean {
        val count = evidence.texts.count { it.source == TextSource.OCR }
        if (!live.publish(operation, snapshot, detection, evidence, issue)) return false
        if (!live.deliver(operation, snapshot) { mutableEvents.tryEmit(PopupEvent.Candidate(snapshot, detection)) }) return false
        val stillCurrent = currentToken() == snapshot.token
        mutableStatus.value = mutableStatus.value.copy(
            candidateCount = mutableStatus.value.candidateCount + 1,
            lastBlockReason = if (stillCurrent) null else mutableStatus.value.lastBlockReason,
            lastEvidenceIssue = issue, ocrTextCount = if (stillCurrent) count else 0,
            lastCompletedOcrTextCount = count,
        )
        return true
    }

    @Synchronized internal fun disconnect(version: Long) {
        captureCapability = null
        serviceGuard = null
        live.disconnect()
        invalidate(version)
        mutableStatus.value = mutableStatus.value.copy(connected = false)
    }
}
