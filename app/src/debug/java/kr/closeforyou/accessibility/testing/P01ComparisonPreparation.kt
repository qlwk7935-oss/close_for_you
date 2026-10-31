package kr.closeforyou.accessibility.testing

import java.util.concurrent.CancellationException
import kotlinx.collections.immutable.toPersistentSet
import kr.closeforyou.accessibility.*
import kr.closeforyou.accessibility.action.F9AutoCloser
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionCloseCandidateFinder

internal enum class PreparationState { READY, WAITING, REJECTED }
internal enum class PreparationEnd { READY, ATTEMPT_LIMIT, TIME_LIMIT, REJECTED, CANCELLED, EXCEPTION }
internal data class PreparationVerdict(val state: PreparationState, val issue: ComparisonIssue)

/** Debug test readiness only. No snapshot/token/candidate is returned to the measurement driver.
 * Bounds changes remain observations; this neither certifies layout completion nor native ABA. */
internal object P01ComparisonPreparation {
    const val MAX_READS = 4
    const val BUDGET_MS = 1_500L

    fun assess(read: P01ReaderComparisonService.Read): PreparationVerdict {
        fun reject(issue: ComparisonIssue) = PreparationVerdict(PreparationState.REJECTED,issue)
        fun waitFor(issue: ComparisonIssue) = PreparationVerdict(PreparationState.WAITING,issue)
        if (read.f0Release in setOf(CaptureReadRelease.FAILED,CaptureReadRelease.CANCELLED,
                CaptureReadRelease.FAILED_AND_CANCELLED)) return reject(ComparisonIssue.CLEANUP_FAILED)
        if (read.status == ComparisonStatus.BLOCKED && read.ready == null && read.sample == null) {
            // This exact early branch acquired no node/window. UNKNOWN is NOT generally retryable.
            if (read.block == PrivacyBlockReason.UNREADABLE_TREE && read.f0Detail == CaptureDetail.ROOT_UNAVAILABLE &&
                read.f0Point == CaptureReadPoint.ROOT && read.f0Release == CaptureReadRelease.UNKNOWN)
                return waitFor(ComparisonIssue.READ_UNAVAILABLE)
            if (read.block in setOf(PrivacyBlockReason.UNREADABLE_TREE,PrivacyBlockReason.TRUNCATED_TREE) &&
                read.f0Detail == CaptureDetail.TIME_LIMIT && read.f0Release == CaptureReadRelease.COMPLETED)
                return waitFor(ComparisonIssue.READ_UNAVAILABLE)
            return reject(if (read.block != null && read.block !in setOf(PrivacyBlockReason.UNREADABLE_TREE,
                PrivacyBlockReason.TRUNCATED_TREE)) ComparisonIssue.POLICY_BLOCKED else ComparisonIssue.READ_UNAVAILABLE)
        }
        if (read.status != ComparisonStatus.COMPLETE || read.f0Detail != CaptureDetail.NONE ||
            read.f0Release != CaptureReadRelease.COMPLETED) return reject(ComparisonIssue.READ_UNAVAILABLE)
        val ready=read.ready ?: return reject(ComparisonIssue.READ_UNAVAILABLE)
        val sample=read.sample ?: return reject(ComparisonIssue.READ_UNAVAILABLE)
        val s=ready.snapshot
        if (s.completeness != SnapshotCompleteness.COMPLETE || sample.actionOwner !== s ||
            sample.nodes !== s.nodes || sample.actions !== ready.nativeActions ||
            s.token.packageName != P01ReaderComparisonService.HOST || sample.packageName != s.token.packageName ||
            sample.window != s.token.windowId || sample.frame != s.screenBounds || sample.windows !== s.windows ||
            ready.frame.activeWindowId != s.token.windowId || ready.frame.screenBounds != s.screenBounds)
            return reject(ComparisonIssue.TARGET_UNCONFIRMED)
        val w=s.windows.singleOrNull { it.id == s.token.windowId } ?: return reject(ComparisonIssue.TARGET_UNCONFIRMED)
        if (w.type != WindowType.APPLICATION || !w.active || !w.focused || ready.frame.windowBounds != w.bounds ||
            s.windows.map { it.id }.distinct().size != s.windows.size || !F9AutoCloser.contains(s.screenBounds,w.bounds) ||
            s.windows.any { it.type in setOf(WindowType.ACCESSIBILITY_OVERLAY,WindowType.INPUT_METHOD) ||
                (it.id != w.id && (it.active || it.focused)) }) return reject(ComparisonIssue.TARGET_UNCONFIRMED)
        // No passive-system-bar exception: bounded P01 needs no overlapping higher/equal layer.
        if (s.windows.any { other -> other.id != w.id && other.layer >= w.layer &&
                other.bounds.left < w.bounds.right && w.bounds.left < other.bounds.right &&
                other.bounds.top < w.bounds.bottom && w.bounds.top < other.bounds.bottom })
            return reject(ComparisonIssue.TARGET_UNCONFIRMED)
        if (sample.regions?.get(w.id).isNullOrEmpty() || s.nodes.isEmpty() ||
            s.nodes.map { it.path }.distinct().size != s.nodes.size ||
            s.nodes.any { !F9AutoCloser.contains(w.bounds,it.bounds) } ||
            s.nodes.indices.any { i -> ready.nativeActions?.observedSummary(s,s.nodes.size,i)?.let {
                it.hasExactKnownSet && it.matchesProjection(s.nodes[i].actions) } != true })
            return reject(ComparisonIssue.TARGET_UNCONFIRMED)
        val signals=PopupCandidateDetector().detect(ready.frame)
        // A protected, fully read background Activity is not yet an observed bounded popup.
        if (PopupSignal.NEW_WINDOW !in signals) return waitFor(ComparisonIssue.TARGET_UNCONFIRMED)
        val candidates=DecisionCloseCandidateFinder().find(s,PopupDetection(s.token,signals.toPersistentSet(),true))
        val close=candidates.singleOrNull { it.role == ButtonRole.CLOSE } ?: return reject(ComparisonIssue.TARGET_UNCONFIRMED)
        if (s.nodes.singleOrNull { it.path == close.nodePath }?.bounds != close.bounds ||
            !F9AutoCloser.contains(w.bounds,close.bounds)) return reject(ComparisonIssue.TARGET_UNCONFIRMED)
        return PreparationVerdict(PreparationState.READY,ComparisonIssue.NONE)
    }

    /** Shared by the real service and JVM tests. Next observation requires an actual new host
     * event, never a fixed sleep or repeated test launch. A blocking Android call cannot be
     * interrupted by this deadline; any late result is rejected and its actual interval logged. */
    suspend fun run(clock: () -> Long, checkpoint: () -> Unit,
        read: (Int) -> P01ReaderComparisonService.Read,
        awaitChange: suspend (Long) -> Boolean,
        accept: (P01ReaderComparisonService.Read) -> Unit,
        report: (String) -> Unit): PreparationEnd {
        val start=clock()
        var attempts=0; var unavailable=0; var waiting=0
        var first=CaptureDetail.NONE; var firstPoint=CaptureReadPoint.UNKNOWN
        var firstRelease=CaptureReadRelease.UNKNOWN
        var end=PreparationEnd.EXCEPTION
        var problem=ComparisonIssue.NONE
        fun remaining(): Long = (clock()-start).let { if(it < 0) 0 else BUDGET_MS-it }
        try {
            while (attempts < MAX_READS) {
                checkpoint()
                if (remaining() <= 0) { end=PreparationEnd.TIME_LIMIT; break }
                if (attempts > 0) {
                    if (!awaitChange(remaining())) { end=PreparationEnd.TIME_LIMIT; break }
                    checkpoint()
                    if (remaining() <= 0) { end=PreparationEnd.TIME_LIMIT; break }
                }
                val before=clock()
                val observed=read(++attempts)
                if (observed.sample == null) unavailable++
                if (first == CaptureDetail.NONE && observed.f0Detail != CaptureDetail.NONE) {
                    first=observed.f0Detail; firstPoint=observed.f0Point; firstRelease=observed.f0Release
                }
                val verdict=assess(observed)
                val after=clock()
                report("PREP_ATTEMPT=$attempts;START_MS=${before-start};END_MS=${after-start};"+
                    "OBSERVATION=${verdict.state};ISSUE=${verdict.issue}")
                checkpoint()
                if (verdict.state == PreparationState.REJECTED) {
                    end=PreparationEnd.REJECTED; problem=verdict.issue; break
                }
                if (after < before || remaining() <= 0) { end=PreparationEnd.TIME_LIMIT; break }
                if (verdict.state == PreparationState.READY) {
                    accept(observed); checkpoint()
                    end=if (remaining() > 0) PreparationEnd.READY else PreparationEnd.TIME_LIMIT
                    break
                }
                waiting++
                if (attempts == MAX_READS) end=PreparationEnd.ATTEMPT_LIMIT
            }
            if (end != PreparationEnd.READY && problem == ComparisonIssue.NONE) problem=ComparisonIssue.PREPARATION_NOT_READY
            return end
        } catch (cancelled: CancellationException) {
            end=PreparationEnd.CANCELLED; problem=ComparisonIssue.CANCELLED; throw cancelled
        } catch (failure: Exception) {
            end=PreparationEnd.EXCEPTION
            problem=(failure as? ComparisonAbort)?.issue ?: ComparisonIssue.UNEXPECTED_FAILURE
            throw failure
        } finally {
            // Reporting must not mask an existing cancellation. The service's sink latches failure.
            try { report("PREPARATION=${if(end == PreparationEnd.READY) "READY" else "NOT_READY"};END=$end;ISSUE=$problem;ATTEMPTS=$attempts;UNAVAILABLE_READS=$unavailable;"+
                "WAITING_OBSERVATIONS=$waiting;ELAPSED_MS=${clock()-start};FIRST_F0_DETAIL=$first;"+
                "FIRST_F0_POINT=$firstPoint;FIRST_F0_RELEASE=$firstRelease;EXTERNAL_CLICK=NOT_WIRED") }
            catch (_: Exception) { }
        }
    }
}

/** Prepared anchor detects observable replacement; geometry is measured, not approved here.
 * Exact action metadata remains tied to each real producer. Unknown/ABA is never certified. */
internal fun preparedTargetMatches(a: ReaderSample, b: ReaderSample): Boolean =
    a.packageName == b.packageName && a.window == b.window && a.frame == b.frame &&
    a.nodes.size == b.nodes.size && a.nodes.indices.all { i -> a.nodes[i].copy(bounds=b.nodes[i].bounds)==b.nodes[i] } &&
    ReaderComparison.compare(a,b).actions == ComparisonValue.SAME
