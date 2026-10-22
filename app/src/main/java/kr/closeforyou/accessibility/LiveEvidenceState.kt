package kr.closeforyou.accessibility

import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.accessibility.capture.EvidenceIssue
import kr.closeforyou.core.model.*

/** Opaque A-local operation identity, NOT user authorization or an F0 certificate. */
internal sealed interface EvidencePublicationPermit

/** Runtime-owned state with injectable clock, policy and cancellation checks; no Android node/bitmap objects.
 * The operation cancellation callback is discarded with its snapshot; Runtime owns the policy callback lifetime.
 * Runtime serializes state changes and flow emission together. All direct state access is also locked.
 */
internal class LiveEvidenceState(private val now: () -> Long,
    private val allowed: (SnapshotToken) -> Boolean = { true }) {
    private class Permit : EvidencePublicationPermit { override fun toString() = "PublicationPermit(redacted)" }
    private var connected = false
    private var version = 0L
    private var lastStartedVersion = Long.MIN_VALUE
    private var source: PopupSnapshot? = null
    private var publishedSnapshot: PopupSnapshot? = null
    private var analysis: PopupAnalysis? = null
    private var permit: Permit? = null
    private var deadline = 0L
    private var delivered = false
    private var workCancelled: (() -> Boolean)? = null

    @Synchronized fun connect() { clear(); connected = true; version = 0; lastStartedVersion = Long.MIN_VALUE }
    @Synchronized fun disconnect() { connected = false; clear() }
    @Synchronized fun invalidate(epoch: Long): Boolean {
        if (epoch < version) return false
        version = epoch; clear()
        return true
    }
    @Synchronized fun block(epoch: Long): Boolean {
        if (epoch != version) return false
        clear(); return true
    }
    @Synchronized fun block(epoch: Long, operation: EvidencePublicationPermit): Boolean {
        if (epoch != version || !live(operation)) return false
        clear(); return true
    }
    @Synchronized fun discard(operation: EvidencePublicationPermit): Boolean {
        if (permit !== operation) return false
        clear(); return true
    }
    @Synchronized fun begin(snapshot: PopupSnapshot, cancelled: () -> Boolean = { false }): EvidencePublicationPermit? {
        val time = now()
        if (cancelled() || !allowed(snapshot.token) || !connected || snapshot.completeness != SnapshotCompleteness.COMPLETE ||
            snapshot.token.screenVersion != version || snapshot.token.captureEpoch != version ||
            version <= lastStartedVersion || snapshot.capturedAtUptimeMillis < 0 ||
            time < snapshot.capturedAtUptimeMillis || time - snapshot.capturedAtUptimeMillis >= TTL_MILLIS ||
            snapshot.capturedAtUptimeMillis > Long.MAX_VALUE - TTL_MILLIS) return null
        clear()
        lastStartedVersion = version
        deadline = snapshot.capturedAtUptimeMillis + TTL_MILLIS
        source = snapshot
        workCancelled = cancelled
        return Permit().also { permit = it }
    }
    @Synchronized fun currentToken(): SnapshotToken? {
        expire(); return if (connected) source?.token else null
    }
    @Synchronized fun publish(operation: EvidencePublicationPermit, snapshot: PopupSnapshot,
        detection: PopupDetection, evidence: PopupAnalysis, issue: EvidenceIssue): Boolean {
        if (!live(operation) || analysis != null) return false
        val original = source ?: return false
        if (snapshot.token != original.token || detection.token != original.token || evidence.token != original.token ||
            snapshot.completeness != SnapshotCompleteness.COMPLETE || !detection.requiresUserReview) return false
        // DecisionEvidence intentionally removes all original nodes. No other mutation is accepted.
        if (snapshot != original && !(issue == EvidenceIssue.DECISION_SUMMARY_ONLY &&
            snapshot == original.copy(nodes = persistentListOf()))) return false
        if (!live(operation) || analysis != null) return false // Re-read time at the commit boundary.
        source = snapshot // Drop original decision nodes after the permitted redacted publication.
        publishedSnapshot = snapshot; analysis = evidence
        return true
    }
    @Synchronized fun extract(snapshot: PopupSnapshot): PopupAnalysis? {
        expire()
        if (!connected || publishedSnapshot != snapshot || snapshot.token != source?.token) return null
        return analysis
    }
    @Synchronized fun deliver(operation: EvidencePublicationPermit, snapshot: PopupSnapshot, emit: () -> Unit): Boolean {
        if (!live(operation) || delivered || publishedSnapshot != snapshot || analysis == null) return false
        delivered = true
        try { emit() } catch (failure: Throwable) {
            if (permit === operation) clear()
            throw failure
        }
        return true
    }
    private fun live(operation: EvidencePublicationPermit): Boolean {
        expire(); return connected && permit === operation && source != null
    }
    private fun expire() {
        val time = now()
        val active = source
        if (active != null) {
            if (time >= deadline || time < active.capturedAtUptimeMillis || workCancelled?.invoke() == true || !allowed(active.token)) clear()
            else {
                val afterPolicy = now()
                if (source === active && (afterPolicy >= deadline || afterPolicy < active.capturedAtUptimeMillis ||
                        workCancelled?.invoke() == true)) clear()
            }
        }
    }
    private fun clear() { source = null; publishedSnapshot = null; analysis = null; permit = null; deadline = 0; delivered = false; workCancelled = null }
    override fun toString(): String = "LiveEvidenceState(redacted)"
    companion object { const val TTL_MILLIS = 5_000L }
}
