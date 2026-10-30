package kr.closeforyou.accessibility.action

import kotlinx.coroutines.CancellationException
import kr.closeforyou.accessibility.diagnosticWrite

internal enum class F9ReadStage { MAIN, GEOMETRY, WINDOWS, ACTIVE_WINDOW, FOCUSED_WINDOW, OVERLAY_WINDOW,
    APP_COUNT, BACKGROUND_WINDOW, WINDOW_BOUNDS, OCCLUSION, ROOT, TREE, ENDPOINT, TARGET, COMPLETE }
internal enum class F9ReadPurpose { UNSCOPED, PAGE, AUTO_ELIGIBILITY, MANUAL_ELIGIBILITY, DISPLAY_REVIEW, PRE_CLICK, PRESS, POST_CLICK }
internal enum class F9ReadDetail {
    NONE, MAIN_THREAD_REQUIRED, READER_UNHEALTHY, GEOMETRY_UNAVAILABLE, WINDOW_COUNT, NONDEFAULT_DISPLAY,
    ACTIVE_APPLICATION_UNCONFIRMED, DISPLAY_TARGET_UNCONFIRMED, ACTIVE_NOT_FOCUSED,
    ACCESSIBILITY_OVERLAY_PRESENT, INPUT_METHOD_PRESENT, OTHER_ACTIVE_OR_FOCUSED,
    APPLICATION_COUNT, BACKGROUND_LAYER, BACKGROUND_BOUNDS, WINDOW_OUTSIDE_FRAME, OCCLUDING_WINDOW,
    ROOT_UNAVAILABLE, ROOT_PACKAGE_UNAVAILABLE, ROOT_WINDOW_MISMATCH, ROOT_PACKAGE_MISMATCH,
    PACKAGE_PROTECTED, APP_NOT_ALLOWED, NODE_LIMIT, DEPTH_LIMIT, NODE_PACKAGE_MISMATCH, NODE_WINDOW_MISMATCH,
    NODE_ALIAS, PROTECTED_FIELD, TEXT_FIELD_LIMIT, TEXT_TOTAL_LIMIT, TEXT_CHANGED, PROTECTED_TEXT,
    ACTION_LIMIT, CHILD_LIMIT, CHILD_UNAVAILABLE, CHILDREN_CHANGED, REVERSED_BOUNDS,
    BACKGROUND_ROOT_UNAVAILABLE, BACKGROUND_ROOT_WINDOW_MISMATCH,
    END_WINDOWS_CHANGED, END_FRAME_UNAVAILABLE, END_FRAME_CHANGED, END_ROOT_UNAVAILABLE,
    END_ROOT_WINDOW_MISMATCH, END_ROOT_PACKAGE_MISMATCH,
    TARGET_REFRESH_FAILED, TARGET_PACKAGE_MISMATCH, TARGET_WINDOW_MISMATCH, TARGET_TEXT_CHANGED,
    TARGET_DESCRIPTION_CHANGED, TARGET_VIEW_ID_CHANGED, TARGET_CLASS_CHANGED, TARGET_BOUNDS_CHANGED,
    TARGET_NOT_VISIBLE, TARGET_DISABLED, TARGET_NOT_CLICKABLE, TARGET_PROTECTED_FIELD,
    TARGET_CLICK_UNAVAILABLE, TARGET_ACTIONS_CHANGED, TARGET_METADATA_UNSAFE,
    CHECKPOINT_FAILED, CANCELLED, TIME_LIMIT, EXCEPTION, NODE_RELEASE_FAILED, WINDOW_RELEASE_FAILED,
}
internal enum class F9ReadOutcome { COMPLETE, IDENTITY_ONLY, REJECTED, CANCELLED, EXCEPTION, CLEANUP_FAILED }
internal enum class F9ReadRelease { NONE, NODE, WINDOW, BOTH }
/** One actual scan's immutable report. No raw input, timestamp, identifier or exception. */
internal data class F9ReadCode(val purpose: F9ReadPurpose, val stage: F9ReadStage,
    val detail: F9ReadDetail, val outcome: F9ReadOutcome, val release: F9ReadRelease, val consumed: Boolean, val occlusion: F9OcclusionCode? = null)

/** The real scan uses these guards/exception/release/report boundaries; JVM supplies callbacks.
 * Recording grants nothing and never replaces the original exception/cancellation. */
internal class F9ReadObservation(private val purpose: F9ReadPurpose, private val sink: (F9ReadCode) -> Unit) {
    private var occlusion: F9OcclusionCode? = null
    fun occluding(code: F9OcclusionCode) { if (occlusion == null) occlusion = code }
    var stage = F9ReadStage.MAIN
    private var firstStage: F9ReadStage? = null
    private var detail = F9ReadDetail.NONE
    private var outcome = F9ReadOutcome.REJECTED
    private var nodeRelease = false
    private var windowRelease = false
    private var consumed = false
    private var guardFailure = false
    private var reported = false
    fun reject(value: F9ReadDetail) {
        if (detail == F9ReadDetail.NONE) { detail = value; firstStage = stage }
    }
    fun require(condition: Boolean, failure: F9ReadDetail) {
        if (!condition) { reject(failure); guardFailure = true; check(false) { "F9_READ_REJECTED" } }
    }
    fun checkpoint(check: () -> Unit) {
        try { check() }
        catch (cancelled: CancellationException) { reject(F9ReadDetail.CANCELLED); throw cancelled }
        catch (failure: Exception) { reject(F9ReadDetail.CHECKPOINT_FAILED); throw failure }
    }
    fun cancelled() { reject(F9ReadDetail.CANCELLED); outcome = F9ReadOutcome.CANCELLED }
    fun exception() {
        reject(F9ReadDetail.EXCEPTION)
        outcome = if (guardFailure) F9ReadOutcome.REJECTED else F9ReadOutcome.EXCEPTION
    }
    /** Same Android catch/finally order. Inlined so existing scan early returns still release. */
    inline fun <T> run(body: () -> T, cleanup: () -> Unit): T? = try { body() }
    catch (cancelled: CancellationException) { this.cancelled(); throw cancelled }
    catch (_: Exception) { exception(); null }
    finally { cleanup(); report() }
    fun complete(identityOnly: Boolean = false) {
        consumed = true; outcome = if (identityOnly) F9ReadOutcome.IDENTITY_ONLY else F9ReadOutcome.COMPLETE
    }
    /** Return false on release failure, so the SAME real reader marks itself unhealthy. */
    fun release(node: Boolean, cleanup: () -> Unit): Boolean = try { cleanup(); true }
    catch (_: Exception) {
        if (node) nodeRelease = true else windowRelease = true
        reject(if (node) F9ReadDetail.NODE_RELEASE_FAILED else F9ReadDetail.WINDOW_RELEASE_FAILED)
        false
    }
    fun report() {
        if (reported) return
        reported = true
        val release = when { nodeRelease && windowRelease -> F9ReadRelease.BOTH; nodeRelease -> F9ReadRelease.NODE
            windowRelease -> F9ReadRelease.WINDOW; else -> F9ReadRelease.NONE }
        val finalOutcome = if (release != F9ReadRelease.NONE && outcome != F9ReadOutcome.CANCELLED)
            F9ReadOutcome.CLEANUP_FAILED else outcome
        val code = F9ReadCode(purpose, firstStage ?: stage, detail, finalOutcome, release, consumed, occlusion)
        diagnosticWrite { sink(code) }
    }
    override fun toString() = "F9ReadObservation(redacted)"
}

/** Scalar results from the first actual rejecting window. No raw bounds/IDs or handles retained. */
internal enum class F9OcclusionLayer { EQUAL, ABOVE }
internal enum class F9OcclusionOverlap { EQUAL, COVERS_TARGET, WITHIN_TARGET, PARTIAL }
internal enum class F9PassiveBarResult {
    NOT_CHECKED, NOT_CHECKED_TARGET_PATH, NOT_CHECKED_BOUNDED_TARGET, NOT_SYSTEM, ACTIVE, FOCUSED,
    WIDTH, HEIGHT, EDGE, ROOT_UNAVAILABLE, OWNER_UNCONFIRMED, ROOT_WINDOW_MISMATCH, PASSED,
}
internal enum class F9OcclusionShape { NOT_CHECKED, EMPTY, OUTSIDE_FRAME, FULL_FRAME, TOP_EDGE_STRIP, BOTTOM_EDGE_STRIP, OTHER }
/** Geometry label only. Even EDGE_STRIP proves no owner, opacity or safe execution. */
internal fun f9OcclusionShape(bounds: kr.closeforyou.core.model.ScreenBounds,
    frame: kr.closeforyou.core.model.ScreenBounds): F9OcclusionShape = when {
    bounds.width <= 0 || bounds.height <= 0 || frame.width <= 0 || frame.height <= 0 -> F9OcclusionShape.EMPTY
    !F9AutoCloser.contains(frame, bounds) -> F9OcclusionShape.OUTSIDE_FRAME
    bounds == frame -> F9OcclusionShape.FULL_FRAME
    bounds.left != frame.left || bounds.right != frame.right || bounds.height !in 1..(frame.height / 8) -> F9OcclusionShape.OTHER
    bounds.top == frame.top -> F9OcclusionShape.TOP_EDGE_STRIP
    bounds.bottom == frame.bottom -> F9OcclusionShape.BOTTOM_EDGE_STRIP
    else -> F9OcclusionShape.OTHER
}
internal data class F9OcclusionCode(val type: kr.closeforyou.core.model.WindowType,
    val layer: F9OcclusionLayer, val overlap: F9OcclusionOverlap, val passive: F9PassiveBarResult, val shape: F9OcclusionShape = F9OcclusionShape.NOT_CHECKED)
internal class F9PassiveBarProbe {
    var result = F9PassiveBarResult.NOT_CHECKED
    fun rejected(value: F9PassiveBarResult): Boolean { result = value; return false }
}

/** EXACT original short-circuit/getter order. Callbacks are the existing Android reads only. */
internal fun f9OccludingWindow(isTarget: () -> Boolean, isOwnedOverlay: () -> Boolean,
    type: () -> kr.closeforyou.core.model.WindowType, layers: () -> Pair<Int, Int>,
    bounds: () -> kr.closeforyou.core.model.ScreenBounds, target: kr.closeforyou.core.model.ScreenBounds,
    frame: kr.closeforyou.core.model.ScreenBounds, noTargetPath: Boolean,
    passive: (F9PassiveBarProbe) -> Boolean, record: (F9OcclusionCode) -> Unit): Boolean {
    if (isTarget() || isOwnedOverlay()) return false
    val kind = type()
    if (kind == kr.closeforyou.core.model.WindowType.APPLICATION) return false
    val (otherLayer, targetLayer) = layers()
    if (otherLayer < targetLayer) return false
    val other = bounds()
    if (!(other.left < target.right && target.left < other.right && other.top < target.bottom && target.top < other.bottom)) return false
    val probe = F9PassiveBarProbe()
    val allowed = noTargetPath && target == frame && passive(probe)
    if (allowed) return false
    val passiveCode = when {
        !noTargetPath -> F9PassiveBarResult.NOT_CHECKED_TARGET_PATH
        target != frame -> F9PassiveBarResult.NOT_CHECKED_BOUNDED_TARGET
        else -> probe.result
    }
    val overlap = when {
        other == target -> F9OcclusionOverlap.EQUAL
        other.left <= target.left && other.top <= target.top && other.right >= target.right && other.bottom >= target.bottom -> F9OcclusionOverlap.COVERS_TARGET
        target.left <= other.left && target.top <= other.top && target.right >= other.right && target.bottom >= other.bottom -> F9OcclusionOverlap.WITHIN_TARGET
        else -> F9OcclusionOverlap.PARTIAL
    }
    diagnosticWrite { record(F9OcclusionCode(kind, if (otherLayer == targetLayer) F9OcclusionLayer.EQUAL else F9OcclusionLayer.ABOVE, overlap, passiveCode, f9OcclusionShape(other, frame))) }
    return true
}

/** Original passive-bar exception, still restricted by the caller to full-frame read (not press).
 * No retries or extra root traversal. The metadata callback reads ONLY the original owner/ID. */
internal fun f9PassiveSystemBar(isSystem: () -> Boolean, active: () -> Boolean, focused: () -> Boolean,
    bounds: () -> kr.closeforyou.core.model.ScreenBounds, frame: kr.closeforyou.core.model.ScreenBounds,
    owner: (F9PassiveBarProbe) -> Boolean, probe: F9PassiveBarProbe): Boolean {
    if (!isSystem()) return probe.rejected(F9PassiveBarResult.NOT_SYSTEM)
    if (active()) return probe.rejected(F9PassiveBarResult.ACTIVE)
    if (focused()) return probe.rejected(F9PassiveBarResult.FOCUSED)
    val bar = bounds()
    if (bar.left != frame.left || bar.right != frame.right) return probe.rejected(F9PassiveBarResult.WIDTH)
    if (bar.height !in 1..(frame.height / 8)) return probe.rejected(F9PassiveBarResult.HEIGHT)
    if (bar.top != frame.top && bar.bottom != frame.bottom) return probe.rejected(F9PassiveBarResult.EDGE)
    if (!owner(probe)) return false
    probe.result = F9PassiveBarResult.PASSED
    return true
}

/** Same existing SystemUI owner metadata guards. Shared to test their short-circuit order;
 * it is called only where the ORIGINAL full-frame passive exception already read a root. */
internal fun <N : Any> f9PassiveBarOwner(readRoot: () -> N?, own: (N) -> N,
    packageMatches: (N) -> Boolean, windowMatches: (N) -> Boolean, probe: F9PassiveBarProbe): Boolean {
    val root = own(readRoot() ?: return probe.rejected(F9PassiveBarResult.ROOT_UNAVAILABLE))
    if (!packageMatches(root)) return probe.rejected(F9PassiveBarResult.OWNER_UNCONFIRMED)
    if (!windowMatches(root)) return probe.rejected(F9PassiveBarResult.ROOT_WINDOW_MISMATCH)
    return true
}
