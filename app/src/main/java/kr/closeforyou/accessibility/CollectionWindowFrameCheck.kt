package kr.closeforyou.accessibility

import kr.closeforyou.core.model.PrivacyBlockReason
import kr.closeforyou.core.model.ScreenBounds
import kr.closeforyou.core.model.WindowSnapshot
import kr.closeforyou.core.model.WindowType

/** Capture-local scalar sample, never a completeness/currentness receipt. Do not log members. */
internal class CollectionWindowFrameSample(
    val packageName: String?,
    val rootWindowId: Int,
    val targetDisplayId: Int,
    val frame: ScreenBounds?,
    val frameStatus: ScreenFrameStatus,
    val rotation: Int?,
    val windows: List<WindowSnapshot>,
) {
    override fun toString(): String = "CollectionWindowFrameSample(redacted)"
}

/** Compare two bounded observations within ONE capture. No retries, Android reads or repair here.
 * The original first sample remains the source of DTO/frame fields. Same endpoints cannot prove
 * atomicity, unseen ABA, root content identity, protected background coverage or final click safety.
 */
internal class CollectionWindowFrameCheck private constructor(private val start: CollectionWindowFrameSample) {
    fun finish(retainedRootWindowId: Int, checkBudget: () -> Unit, read: () -> CollectionWindowFrameSample) {
        checkBudget()
        val end = read() // Exactly one observation; exceptions/cancellation reach capture's existing boundary.
        checkBudget()
        validate(end)
        requireGuard(retainedRootWindowId == start.rootWindowId && end.rootWindowId == start.rootWindowId,
            CollectionFinding.ROOT_WINDOW_MISMATCH)
        requireGuard(end.packageName == start.packageName, CollectionFinding.PACKAGE_MISMATCH)
        requireGuard(end.targetDisplayId == start.targetDisplayId, CollectionFinding.WINDOW_GUARD)
        requireGuard(end.frame == start.frame && end.rotation == start.rotation, CollectionFinding.GEOMETRY_GUARD)
        requireGuard(end.windows.size == start.windows.size, CollectionFinding.WINDOW_GUARD)
        for (window in start.windows) {
            requireGuard(end.windows.singleOrNull { it.id == window.id } == window, CollectionFinding.WINDOW_GUARD)
        }
        // Raw equality above is essential; equal relation codes alone are never sufficient.
        val before = start.windows.single { it.id == start.rootWindowId }
        val after = end.windows.single { it.id == end.rootWindowId }
        requireGuard(DetectionGeometryCheck.inspect(requireNotNull(start.frame), before.bounds) ==
            DetectionGeometryCheck.inspect(requireNotNull(end.frame), after.bounds), CollectionFinding.GEOMETRY_GUARD)
        checkBudget() // Validation cost also counts, without restarting the original deadline.
    }

    override fun toString(): String = "CollectionWindowFrameCheck(redacted)"

    companion object {
        fun requireCaptureDisplayIsolation(required: Boolean, hasSecondaryDisplay: () -> Boolean) {
            if (required && hasSecondaryDisplay()) throw CollectionGuardFailure(PrivacyBlockReason.SYSTEM_WINDOW,
                finding = CollectionFinding.WINDOW_GUARD)
        }

        fun begin(sample: CollectionWindowFrameSample): CollectionWindowFrameCheck {
            validate(sample)
            // At most 32 scalar DTOs; no node/body copy or third Android tree traversal.
            return CollectionWindowFrameCheck(CollectionWindowFrameSample(sample.packageName, sample.rootWindowId,
                sample.targetDisplayId, sample.frame, sample.frameStatus, sample.rotation, sample.windows.toList()))
        }

        /** Same > boundary as the existing traversal budget. No clock/deadline is created here. */
        fun checkDeadline(now: Long, deadline: Long) {
            if (now > deadline) throw CollectionGuardFailure(PrivacyBlockReason.TRUNCATED_TREE,
                finding = CollectionFinding.TIME_LIMIT)
        }

        private fun validate(sample: CollectionWindowFrameSample) {
            requireGuard(!sample.packageName.isNullOrEmpty(), CollectionFinding.PACKAGE_MISMATCH)
            requireGuard(sample.targetDisplayId == 0, CollectionFinding.WINDOW_GUARD)
            requireGuard(sample.frameStatus == ScreenFrameStatus.VERIFIED && sample.frame != null &&
                sample.rotation != null && sample.rotation in 0..3, CollectionFinding.GEOMETRY_GUARD)
            val frame = requireNotNull(sample.frame)
            requireGuard(frame.left == 0 && frame.top == 0 && frame.right > 0 && frame.bottom > 0,
                CollectionFinding.GEOMETRY_GUARD)
            requireGuard(sample.windows.isNotEmpty() && sample.windows.size <= 32, CollectionFinding.WINDOW_GUARD)
            requireGuard(sample.windows.map { it.id }.toSet().size == sample.windows.size, CollectionFinding.WINDOW_GUARD)
            val target = sample.windows.singleOrNull { it.id == sample.rootWindowId }
            requireGuard(target != null && target.type == WindowType.APPLICATION, CollectionFinding.WINDOW_GUARD)
            requireGuard(sample.windows.none { (it.active || it.focused) && it.type != WindowType.APPLICATION },
                CollectionFinding.WINDOW_GUARD)
            // Unchanged outside/nonpositive window geometry remains subject to the existing F1
            // rejection. This gate never turns a repeated invalid relation into a valid baseline.
        }

        private fun requireGuard(condition: Boolean, finding: CollectionFinding) {
            if (!condition) throw CollectionGuardFailure(PrivacyBlockReason.UNREADABLE_TREE, finding = finding)
        }
    }
}
