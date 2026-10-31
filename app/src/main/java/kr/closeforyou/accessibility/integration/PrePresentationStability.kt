package kr.closeforyou.accessibility.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.action.F9AutoCloser
import kr.closeforyou.accessibility.action.F9ContentSafety
import kr.closeforyou.core.model.*

/** Observation is a Debug presentation contract, never native generation or click authority.
 * Strict remains the default. There is no public-API generation witness in this collector. */
internal enum class PresentationAssurance {
    STRICT_GENERATION_REQUIRED, OBSERVATION_ONLY_DEMO,
}

internal enum class PresentationStabilityEnd {
    STABLE_FRESH, STABLE_RECOLLECTED, UNAVAILABLE, IDENTITY_CHANGED, METADATA_UNAVAILABLE,
    UNSTABLE, DEADLINE, STALE, CANCELLED, FAILED, GENERATION_UNPROVEN,
}

/** One bounded pre-publication operation, NOT a popup lifetime machine or a quiet-time timer.
 * The Android read port must refresh every visited node and check its endpoint. Two separately
 * returned full reads must agree; a dispatcher turn alone never counts as an observation.
 * No old F1 signal, B candidate, token or timestamp is transferred to the accepted last read.
 */
internal object PrePresentationStability {
    const val MAX_FRESH_READS = 3
    const val BUDGET_MILLIS = 100L

    /** Narrow rollout: only independently bounded application-window popups. This probe owns
     * no service baseline and no signal from it is transferred to the eventual candidate. */
    fun applies(frame: DetectionFrame): Boolean = PopupSignal.NEW_WINDOW in PopupCandidateDetector().detect(frame)

    suspend fun collect(seed: CollectionResult.Ready, now: () -> Long, current: () -> Boolean,
        nextTurn: suspend () -> Boolean, read: (() -> Unit) -> CollectionResult,
        report: (PresentationStabilityEnd) -> Unit = {},
        contract: PresentationAssurance = PresentationAssurance.STRICT_GENERATION_REQUIRED): CollectionResult.Ready? {
        val context = currentCoroutineContext()
        val started = now()
        var terminal = PresentationStabilityEnd.UNAVAILABLE
        fun live(): Boolean {
            context.ensureActive()
            if (!current()) { terminal = PresentationStabilityEnd.STALE; return false }
            val time = now()
            if (started < 0 || time < started || time - started >= BUDGET_MILLIS ||
                seed.snapshot.capturedAtUptimeMillis < 0 || time < seed.snapshot.capturedAtUptimeMillis ||
                time - seed.snapshot.capturedAtUptimeMillis >= AutoEligibility.TTL_MILLIS) {
                terminal = PresentationStabilityEnd.DEADLINE; return false
            }
            return true
        }
        fun checkpoint() { if (!live()) throw CancellationException("PRESENTATION_READ_STALE") }
        try {
            if (!live()) return null
            // No caller-supplied UUID, timestamp or boolean can manufacture native proof.
            if (contract != PresentationAssurance.OBSERVATION_ONLY_DEMO) {
                terminal = PresentationStabilityEnd.GENERATION_UNPROVEN
                return null
            }
            if (!validResult(seed)) { terminal = PresentationStabilityEnd.METADATA_UNAVAILABLE; return null }
            var previous = seed
            var previousWasFresh = false
            var changed = false
            // Remember only per-operation object identity, never a long-lived native handle.
            val seen = java.util.IdentityHashMap<PopupSnapshot, Boolean>()
            val tokens = mutableSetOf(seed.snapshot.token)
            seen[seed.snapshot] = true
            repeat(MAX_FRESH_READS) {
                if (!nextTurn() || !live()) return null
                val before = now()
                val fresh = read(::checkpoint) as? CollectionResult.Ready ?: return null
                if (!live()) return null
                val after = now()
                if (fresh.snapshot === previous.snapshot || seen.put(fresh.snapshot, true) != null ||
                    !tokens.add(fresh.snapshot.token) || fresh.snapshot.capturedAtUptimeMillis < before ||
                    fresh.snapshot.capturedAtUptimeMillis > after ||
                    fresh.snapshot.capturedAtUptimeMillis < previous.snapshot.capturedAtUptimeMillis) return null
                if (!validResult(fresh)) {
                    terminal = PresentationStabilityEnd.METADATA_UNAVAILABLE; return null
                }
                if (!sameIdentityChecked(previous.snapshot, previous.nativeActions, fresh.snapshot, fresh.nativeActions)) {
                    terminal = PresentationStabilityEnd.IDENTITY_CHANGED; return null
                }
                val equal = previous.snapshot.nodes == fresh.snapshot.nodes && previous.snapshot.windows == fresh.snapshot.windows &&
                    previous.snapshot.token.structureDigest == fresh.snapshot.token.structureDigest
                if (previousWasFresh && equal) {
                    if (!live()) return null
                    terminal = if (changed) PresentationStabilityEnd.STABLE_RECOLLECTED else PresentationStabilityEnd.STABLE_FRESH
                    return fresh // actual new collector object, never copy(token=..., capturedAt=...).
                }
                if (!equal) changed = true
                previous = fresh
                previousWasFresh = true
            }
            terminal = PresentationStabilityEnd.UNSTABLE
            return null
        } catch (cancelled: CancellationException) {
            if (terminal != PresentationStabilityEnd.STALE && terminal != PresentationStabilityEnd.DEADLINE)
                terminal = PresentationStabilityEnd.CANCELLED
            throw cancelled
        } catch (_: Exception) {
            terminal = PresentationStabilityEnd.FAILED
            return null
        } finally {
            reportEnd(report, terminal)
        }
    }

    // Non-inline boundary also isolates callback failures on non-local returns from suspend finally.
    private fun reportEnd(report: (PresentationStabilityEnd) -> Unit, terminal: PresentationStabilityEnd) {
        try { report(terminal) } catch (_: Exception) { /* Codes only; logging has no authority. */ }
    }

    private fun validResult(result: CollectionResult.Ready): Boolean {
        val s = result.snapshot; val frame = result.frame
        return valid(s, result.nativeActions) && frame.activeWindowId == s.token.windowId &&
            frame.screenBounds == s.screenBounds && frame.structureDigest == s.token.structureDigest &&
            frame.windowBounds == s.windows.single { it.id == s.token.windowId }.bounds && frame.trigger == s.trigger &&
            frame.applicationWindowIds == s.windows.filter { it.type == WindowType.APPLICATION }.map { it.id }.toSet() &&
            frame.outlines.size == s.nodes.size && frame.outlines.indices.all { frame.outlines[it].bounds == s.nodes[it].bounds }
    }

    /** Exact observable comparison used by the Debug Gate's last full F0 verification.
     * A true result is neither STRICT_GENERATION_REQUIRED proof nor execution permission. */
    fun sameExact(a: PopupSnapshot, actionsA: NativeNodeActions?, b: PopupSnapshot, actionsB: NativeNodeActions?): Boolean =
        a !== b && a.token != b.token && b.capturedAtUptimeMillis >= a.capturedAtUptimeMillis &&
            valid(a, actionsA) && valid(b, actionsB) && sameIdentityChecked(a, actionsA, b, actionsB) &&
            a.nodes == b.nodes && a.windows == b.windows &&
            a.token.structureDigest == b.token.structureDigest

    // Both inputs have already passed valid() in this operation; do not repeatedly normalize
    // the same protected text or rebuild parent maps on every field comparison.
    private fun sameIdentityChecked(a: PopupSnapshot, actionsA: NativeNodeActions?, b: PopupSnapshot, actionsB: NativeNodeActions?): Boolean {
        if (a.token.packageName != b.token.packageName ||
            a.token.windowId != b.token.windowId || a.token.screenVersion != b.token.screenVersion ||
            a.token.captureEpoch != b.token.captureEpoch || a.screenBounds != b.screenBounds ||
            a.trigger != b.trigger || a.token.contentDigest != b.token.contentDigest || a.nodes.size != b.nodes.size ||
            a.windows.size != b.windows.size) return false
        for (i in a.windows.indices) {
            val x = a.windows[i]; val y = b.windows[i]
            if (x.id != y.id || x.type != y.type || x.active != y.active || x.focused != y.focused || x.layer != y.layer ||
                (x.id != a.token.windowId && x != y)) return false
        }
        for (i in a.nodes.indices) {
            val x = a.nodes[i]; val y = b.nodes[i]
            if (x.path != y.path || x.viewId != y.viewId || x.className != y.className || x.text != y.text ||
                x.contentDescription != y.contentDescription || x.clickable != y.clickable || x.enabled != y.enabled ||
                x.actions != y.actions || actionsA?.observedSummary(a, a.nodes.size, i) != actionsB?.observedSummary(b, b.nodes.size, i)) return false
        }
        return true
    }

    /** Deny-only structural/geometry checks; never substitutes for either F0 pass, F1 or B. */
    private fun valid(snapshot: PopupSnapshot, actions: NativeNodeActions?): Boolean {
        if (snapshot.completeness != SnapshotCompleteness.COMPLETE || snapshot.nodes.size !in 1..1_200 ||
            snapshot.windows.size !in 1..32 || snapshot.token.captureEpoch != snapshot.token.screenVersion ||
            actions == null || snapshot.windows.any { it.id < 0 } || snapshot.windows.map { it.id }.toSet().size != snapshot.windows.size) return false
        val target = snapshot.windows.singleOrNull { it.id == snapshot.token.windowId && it.type == WindowType.APPLICATION } ?: return false
        fun positive(b: ScreenBounds) = b.right > b.left && b.bottom > b.top
        if (!positive(snapshot.screenBounds) || !positive(target.bounds) || !target.active || !target.focused ||
            !F9AutoCloser.contains(snapshot.screenBounds, target.bounds)) return false
        fun overlap(a: ScreenBounds, b: ScreenBounds) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
        if (snapshot.windows.any { !positive(it.bounds) || it.type == WindowType.INPUT_METHOD ||
                it.type == WindowType.ACCESSIBILITY_OVERLAY || (it.id != target.id &&
                (it.active || it.focused || (it.layer >= target.layer && overlap(it.bounds, target.bounds)))) }) return false
        val parents = mutableMapOf<String, NodeSnapshot>()
        for ((i, node) in snapshot.nodes.withIndex()) {
            val summary = actions.observedSummary(snapshot, snapshot.nodes.size, i)
            if (!summary.hasExactKnownSet || !summary.matchesProjection(node.actions) ||
                node.className.isNullOrBlank() || !positive(node.bounds) || !F9AutoCloser.contains(target.bounds, node.bounds)) return false
            if (i == 0) {
                if (node.path != "0" || node.bounds != target.bounds) return false
            } else {
                val segments = node.path.split('/')
                if (segments.size !in 2..61 || segments.first() != "0" || segments.drop(1).any {
                    it.toIntOrNull()?.let { n -> n < 0 || n.toString() != it } != false }) return false
                val parent = parents[node.path.substringBeforeLast('/')] ?: return false
                if (!F9AutoCloser.contains(parent.bounds, node.bounds)) return false
            }
            if (parents.put(node.path, node) != null) return false
        }
        return !F9ContentSafety.protectedPackage(snapshot.token.packageName) &&
            !F9ContentSafety.blocked(snapshot.nodes.flatMap { listOfNotNull(it.text, it.contentDescription) }.joinToString("\n"))
    }
}
