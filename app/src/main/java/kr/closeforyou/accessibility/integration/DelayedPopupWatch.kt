package kr.closeforyou.accessibility.integration

import kr.closeforyou.accessibility.DetectionFrame
import kr.closeforyou.accessibility.action.F9AutoCloser
import kr.closeforyou.accessibility.action.F9ContentSafety
import kr.closeforyou.accessibility.action.F9PageProof
import kr.closeforyou.accessibility.action.F9Scene
import kr.closeforyou.core.model.*

internal enum class DelayedAutoPhase {
    ARMED, OFF, PAGE_VERIFIED, CANDIDATE, WAITING_FOR_TARGET, RECOLLECT_REQUESTED, REOBSERVED,
    CONTINUITY_REJECTED, CANCELLED, EXPIRED, B_DECISION, F9_RESULT, STALE,
}
internal data class DelayedContentEvent(val window: Int, val pkg: String?, val boundsOnly: Boolean = false) {
    override fun toString() = "DelayedContentEvent(redacted)"
}

/** B's WAIT retains identity only, never an executable token or an eligibility certificate.
 * An event invalidates all old work before a NEW complete F0 read can continue this watch.
 * Conservative first implementation: stable old descriptors, geometry may move, at most one
 * appended native labelled CLOSE Button. No replaced/deleted text, guessed countdown or node skip.
 */
internal class DelayedPopupWatch(private val now: () -> Long,
    private val onEnd: (DelayedAutoPhase) -> Unit = {}) {
    private var trial: Pair<String, Int>? = null
    private var seed: PopupSnapshot? = null
    private var seedFrame: DetectionFrame? = null
    private var pageIdentity: Pair<Int, String>? = null
    private var started = -1L
    private var expectedEpoch: Long? = null
    private var validated: SnapshotToken? = null
    private var executing = false
    val active get() = trial != null
    val waiting get() = seed != null
    fun track(snapshot: PopupSnapshot): Boolean {
        val key = snapshot.token.packageName to snapshot.token.windowId
        if (trial != null && trial != key) { cancel(DelayedAutoPhase.CONTINUITY_REJECTED); return false }
        trial = key; return true
    }
    fun arm(snapshot: PopupSnapshot, detection: PopupDetection, frame: DetectionFrame?, fresh: F9Scene?, analysis: PopupAnalysis,
        page: F9PageProof? = null): Boolean {
        if (executing || !track(snapshot)) return false
        if (frame == null || fresh == null) {
            cancel(DelayedAutoPhase.CONTINUITY_REJECTED); return false
        }
        val popup = snapshot.windows.singleOrNull { it.id == snapshot.token.windowId }
        if (snapshot.completeness != SnapshotCompleteness.COMPLETE || detection.token != snapshot.token || analysis.token != snapshot.token ||
            PopupSignal.NEW_WINDOW !in detection.signals || popup?.type != WindowType.APPLICATION ||
            !popup.active || !popup.focused || popup.bounds == snapshot.screenBounds ||
            !F9AutoCloser.contains(snapshot.screenBounds, popup.bounds) || analysis.closeCandidates.isNotEmpty() ||
            analysis.classification !in setOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON) ||
            snapshot.nodes.any { n -> listOfNotNull(n.text, n.contentDescription).any(F9ContentSafety::blocked) } ||
            !fresh.complete || fresh.packageName != snapshot.token.packageName || fresh.windowId != popup.id ||
            fresh.screen != snapshot.screenBounds || fresh.nodes != snapshot.nodes || fresh.windows != snapshot.windows ||
            frame.activeWindowId != popup.id || frame.windowBounds != popup.bounds || frame.screenBounds != snapshot.screenBounds ||
            frame.structureDigest != snapshot.token.structureDigest || frame.applicationDigest.isBlank() ||
            frame.applicationWindowIds != snapshot.windows.filter { it.type == WindowType.APPLICATION }.mapTo(mutableSetOf()) { it.id }) {
            cancel(DelayedAutoPhase.CONTINUITY_REJECTED); return false
        }
        val pageKey = pageKey(snapshot, fresh, page)
        if (pageKey == null || (seed != null && pageIdentity != pageKey)) {
            cancel(DelayedAutoPhase.CONTINUITY_REJECTED); return false
        }
        if (seed == null) { seed = snapshot; seedFrame = frame; pageIdentity = pageKey; started = now() }
        return true
    }
    fun invalidated(epoch: Long, permissionOrInteraction: Boolean, cause: ManualPromptCause,
        content: DelayedContentEvent?) {
        if (!active) return
        validated = null
        if (executing) {
            // Ordinary post-press events must not cancel result observation. They still invalidate
            // Runtime and raw evidence. User/policy/service changes always stop permission.
            if (permissionOrInteraction || cause in terminalCauses) cancel(DelayedAutoPhase.CANCELLED)
            return
        }
        if (cause == ManualPromptCause.ORIGINAL_EVIDENCE_EXPIRED) { expectedEpoch = null; return }
        val key = trial
        if (!permissionOrInteraction && content != null && key != null && content.window == key.second &&
            (content.pkg == key.first || (content.boundsOnly && content.pkg == null))) {
            expectedEpoch = epoch; return
        }
        cancel(DelayedAutoPhase.CANCELLED)
    }
    fun reobserved(snapshot: PopupSnapshot, frame: DetectionFrame): Boolean {
        val old = seed ?: return false
        val initial = seedFrame ?: return false
        if (remaining() <= 0) { cancel(DelayedAutoPhase.EXPIRED); return false }
        val window = snapshot.windows.singleOrNull { it.id == snapshot.token.windowId }
        val previousWindow = old.windows.single { it.id == old.token.windowId }
        val knownPaths = old.nodes.mapTo(mutableSetOf()) { it.path }
        val valid = expectedEpoch == snapshot.token.screenVersion && snapshot.token.captureEpoch == snapshot.token.screenVersion &&
            snapshot.token != old.token && snapshot.token.packageName == old.token.packageName && snapshot.token.windowId == old.token.windowId &&
            snapshot.completeness == SnapshotCompleteness.COMPLETE && snapshot.screenBounds == old.screenBounds &&
            frame.applicationDigest == initial.applicationDigest && frame.applicationWindowIds == initial.applicationWindowIds &&
            frame.activeWindowId == old.token.windowId && frame.screenBounds == snapshot.screenBounds &&
            window != null && frame.windowBounds == window.bounds && window.copy(bounds = previousWindow.bounds) == previousWindow &&
            F9AutoCloser.contains(snapshot.screenBounds, window.bounds) && window.bounds != snapshot.screenBounds &&
            snapshot.windows.filter { it.id != window.id } == old.windows.filter { it.id != window.id } &&
            sameCoreAndAppendOnly(old.nodes, snapshot.nodes) &&
            snapshot.nodes.filter { it.path !in knownPaths }.all { F9AutoCloser.contains(window.bounds, it.bounds) }
        if (!valid) { cancel(DelayedAutoPhase.CONTINUITY_REJECTED); return false }
        validated = snapshot.token
        return true
    }
    fun permitsExecution(snapshot: PopupSnapshot, fresh: F9Scene? = null, page: F9PageProof? = null): Boolean {
        if (seed == null || validated != snapshot.token || remaining() <= 0) return false
        if (fresh == null || !fresh.complete || fresh.packageName != snapshot.token.packageName ||
            fresh.windowId != snapshot.token.windowId || pageIdentity != pageKey(snapshot, fresh, page)) {
            cancel(DelayedAutoPhase.CONTINUITY_REJECTED); return false
        }
        return true
    }
    private fun pageKey(snapshot: PopupSnapshot, fresh: F9Scene, page: F9PageProof?): Pair<Int, String>? {
        if (fresh.pageWindowId != snapshot.token.windowId)
            return fresh.pageDigest?.takeIf { it.isNotBlank() }?.let { fresh.pageWindowId to it }
        val at = now()
        return page?.takeIf { it.packageName == snapshot.token.packageName && it.windowId != snapshot.token.windowId &&
            it.capturedAtUptimeMillis in 0..snapshot.capturedAtUptimeMillis && it.digest.isNotBlank() &&
            at >= it.capturedAtUptimeMillis && at - it.capturedAtUptimeMillis < AutoEligibility.TTL_MILLIS
        }?.let { it.windowId to it.digest }
    }
    fun executing() { executing = true; seed = null; seedFrame = null; expectedEpoch = null }
    fun remaining(): Long {
        val at = now()
        return if (started < 0 || at < started || at - started >= MAX_WAIT_MILLIS) 0 else MAX_WAIT_MILLIS - (at - started)
    }
    fun clear() { trial = null; seed = null; seedFrame = null; pageIdentity = null; expectedEpoch = null; validated = null; started = -1; executing = false }
    fun cancel(why: DelayedAutoPhase = DelayedAutoPhase.CANCELLED) { val had = active; clear(); if (had) onEnd(why) }
    override fun toString() = "DelayedPopupWatch(redacted)"
    private fun sameCoreAndAppendOnly(old: List<NodeSnapshot>, fresh: List<NodeSnapshot>): Boolean {
        if (old.isEmpty() || old.size > 1_200 || fresh.size > 1_200 ||
            old.map { it.path }.toSet().size != old.size || fresh.map { it.path }.toSet().size != fresh.size) return false
        val known = old.associateBy { it.path }
        val retained = fresh.filter { it.path in known }
        if (retained.size != old.size || retained.zip(old).any { (a, b) -> a.copy(bounds = b.bounds) != b }) return false
        val added = fresh.filter { it.path !in known }
        if (added.isEmpty()) return true
        val button = added.singleOrNull() ?: return false
        val parent = button.path.substringBeforeLast('/', "")
        val index = button.path.substringAfterLast('/').toIntOrNull() ?: return false
        val previousChildren = old.count { it.path.substringBeforeLast('/', "") == parent }
        val labels = listOfNotNull(button.text, button.contentDescription).filter { it.isNotBlank() }
        return parent in known && index == previousChildren && button.className == "android.widget.Button" &&
            button.enabled && button.clickable && NodeAction.CLICK in button.actions &&
            labels.isNotEmpty() && labels.all { F9AutoCloser.role(it) == ButtonRole.CLOSE }
    }
    companion object {
        const val MAX_WAIT_MILLIS = 30_000L // identity deadline, never the 5 s execution evidence TTL
        private val terminalCauses = setOf(ManualPromptCause.POLICY_CHANGED, ManualPromptCause.SERVICE_STOPPED,
            ManualPromptCause.CONFIGURATION_CHANGED, ManualPromptCause.HEALTH_FAILED, ManualPromptCause.CLEANUP_FAILED)
    }
}
