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
    val detail: F9ReadDetail, val outcome: F9ReadOutcome, val release: F9ReadRelease, val consumed: Boolean)

/** The real scan uses these guards/exception/release/report boundaries; JVM supplies callbacks.
 * Recording grants nothing and never replaces the original exception/cancellation. */
internal class F9ReadObservation(private val purpose: F9ReadPurpose, private val sink: (F9ReadCode) -> Unit) {
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
        val code = F9ReadCode(purpose, firstStage ?: stage, detail, finalOutcome, release, consumed)
        diagnosticWrite { sink(code) }
    }
    override fun toString() = "F9ReadObservation(redacted)"
}
