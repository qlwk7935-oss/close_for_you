package kr.closeforyou.accessibility

import android.os.SystemClock
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.closeforyou.core.contract.PopupEvent
import kr.closeforyou.core.contract.SnapshotSource
import kr.closeforyou.core.contract.EvidenceExtractor
import kr.closeforyou.core.contract.CaptureCapability
import kr.closeforyou.core.contract.CaptureAvailability
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kotlinx.coroutines.CancellationException
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
    internal val engineActivity = AEngineActivity()
    private val mutableEvents = MutableSharedFlow<PopupEvent>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<PopupEvent> = mutableEvents.asSharedFlow()
    private val mutableStatus = MutableStateFlow(AccessibilityStatus())
    val status: StateFlow<AccessibilityStatus> = mutableStatus.asStateFlow()
    @Volatile private var token: SnapshotToken? = null
    @Volatile private var analysis: PopupAnalysis? = null
    @Volatile private var expiresAt: Long = 0L
    @Volatile private var captureCapability: CaptureCapability? = null

    @Synchronized override fun currentToken(): SnapshotToken? {
        if (SystemClock.uptimeMillis() >= expiresAt) {
            token = null
            analysis = null
        }
        return token
    }

    /** B's existing EvidenceExtractor contract; returns only a completed F0-B-filtered result. */
    override suspend fun extract(snapshot: PopupSnapshot): PopupAnalysis {
        currentCoroutineContext().ensureActive()
        val result = analysis
        if (result == null || result.token != snapshot.token || currentToken() != snapshot.token) {
            throw CancellationException(EvidenceIssue.STALE_SCREEN.name)
        }
        return result
    }

    override fun availability(token: SnapshotToken): CaptureAvailability =
        captureCapability?.availability(token) ?: CaptureAvailability.PRIVACY_BLOCKED

    internal fun attachCaptureCapability(capability: CaptureCapability?) { captureCapability = capability }

    @Synchronized internal fun begin(candidate: SnapshotToken) {
        analysis = null
        expiresAt = SystemClock.uptimeMillis() + CloseForYouAccessibilityService.SNAPSHOT_TTL_MILLIS
        token = candidate
    }

    internal fun recordEvidenceIssue(issue: EvidenceIssue) {
        mutableStatus.value = mutableStatus.value.copy(lastEvidenceIssue = issue)
    }

    @Synchronized internal fun connect() {
        token = null
        analysis = null
        mutableStatus.value = mutableStatus.value.copy(connected = true, lastBlockReason = null)
    }

    @Synchronized internal fun invalidate(version: Long) {
        token = null
        analysis = null
        mutableStatus.value = mutableStatus.value.copy(screenVersion = version, ocrTextCount = 0)
        mutableEvents.tryEmit(PopupEvent.Invalidated(version))
    }

    @Synchronized internal fun block(reason: PrivacyBlockReason, version: Long) {
        token = null
        analysis = null
        mutableStatus.value = mutableStatus.value.copy(screenVersion = version, lastBlockReason = reason, ocrTextCount = 0)
        mutableEvents.tryEmit(PopupEvent.Blocked(reason, version))
    }

    @Synchronized internal fun publish(snapshot: PopupSnapshot, detection: PopupDetection, evidence: PopupAnalysis) {
        if (!mutableStatus.value.connected || currentToken() != snapshot.token || evidence.token != snapshot.token) return
        analysis = evidence
        mutableStatus.value = mutableStatus.value.copy(
            candidateCount = mutableStatus.value.candidateCount + 1, lastBlockReason = null,
            ocrTextCount = evidence.texts.count { it.source == TextSource.OCR },
            lastCompletedOcrTextCount = evidence.texts.count { it.source == TextSource.OCR },
        )
        mutableEvents.tryEmit(PopupEvent.Candidate(snapshot, detection))
    }

    @Synchronized internal fun disconnect(version: Long) {
        captureCapability = null
        invalidate(version)
        mutableStatus.value = mutableStatus.value.copy(connected = false)
    }
}
