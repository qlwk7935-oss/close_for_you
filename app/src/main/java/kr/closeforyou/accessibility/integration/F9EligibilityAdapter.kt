package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.toPersistentSet
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionPopupClassifier

internal enum class F9EligibilityMode { AUTOMATIC, USER_CHOICE }
internal enum class F9EligibilityCheck {
    CAPTURE_CURRENT, TOKEN_BINDING, SCREEN_AVAILABLE, MANUAL_CHOICE,
    F0_PASSED, TREE_COMPLETE, POPUP_SCOPE_CONFIRMED, DECISION_TEXT_SCOPED, TARGETS_IN_POPUP,
}
internal enum class F9EligibilityDetail {
    NONE, NEGATIVE_CAPTURE, CLOCK_REVERSED, TTL_EXPIRED, DETECTION_TOKEN, ANALYSIS_TOKEN,
    SCENE_UNAVAILABLE, CHOICE_TOKEN, CHOICE_KIND, CHOICE_MEANING, CHOICE_TARGET,
    SCENE_INCOMPLETE, PACKAGE_CHANGED, WINDOW_CHANGED, FRAME_CHANGED, NODES_CHANGED, WINDOWS_CHANGED,
    PACKAGE_PROTECTED, TEXT_PROTECTED, SNAPSHOT_INCOMPLETE, NEW_WINDOW_REQUIRED, DETECTION_UNCERTAIN,
    POPUP_WINDOW_UNAVAILABLE, WINDOW_TYPE, WINDOW_INACTIVE, WINDOW_UNFOCUSED, WINDOW_UNBOUNDED,
    TEXT_NOT_COMPLETE, CLASSIFICATION_UNSAFE, TARGET_UNAVAILABLE, TARGET_OUTSIDE_POPUP, DEPENDENCY_FAILED,
}
/** Fixed codes only: never retain an analysis, screen, node, identifier or Throwable. */
internal data class F9EligibilityCode(val mode: F9EligibilityMode, val check: F9EligibilityCheck,
    val state: ManualActionState, val detail: F9EligibilityDetail)

/** Complete tree-based separate-window popups only. Every original eligibility check is derived,
 * never granted wholesale. Manual scope is current geometry + bound choice, not a NEW_WINDOW
 * history requirement; same-window/fullscreen/uncertain targets still cannot acquire it. */
internal object F9EligibilityAdapter {
    suspend fun prepare(snapshot: PopupSnapshot, detection: PopupDetection, wired: PopupAnalysis,
        fresh: F9Scene?, now: Long): PopupAnalysis =
        prepareChecked(snapshot, detection, wired, fresh, now, F9EligibilityMode.AUTOMATIC, null, null)

    /** Called by executeUser ONLY after the driver's native removal + new F0 + exact original
     * popup/target continuity. Does not authorize a click, renew time or enable automatic consent. */
    suspend fun prepareUser(snapshot: PopupSnapshot, detection: PopupDetection, wired: PopupAnalysis,
        fresh: F9Scene?, now: Long, choice: UserChoice, trace: ManualActionTrace? = null): PopupAnalysis =
        prepareChecked(snapshot, detection, wired, fresh, now, F9EligibilityMode.USER_CHOICE, choice, trace)

    private suspend fun prepareChecked(snapshot: PopupSnapshot, detection: PopupDetection, wired: PopupAnalysis,
        fresh: F9Scene?, now: Long, mode: F9EligibilityMode, choice: UserChoice?, trace: ManualActionTrace?): PopupAnalysis {
        val classified = wired.copy(classification = DecisionPopupClassifier().classify(wired), autoEligibility = null, title = null)
        fun report(check: F9EligibilityCheck, failure: F9EligibilityDetail?) {
            trace?.eligibility(F9EligibilityCode(mode, check,
                if (failure == null) ManualActionState.PASSED else ManualActionState.REJECTED,
                failure ?: F9EligibilityDetail.NONE))
        }
        val captureFailure = when {
            snapshot.capturedAtUptimeMillis < 0 -> F9EligibilityDetail.NEGATIVE_CAPTURE
            now < snapshot.capturedAtUptimeMillis -> F9EligibilityDetail.CLOCK_REVERSED
            now - snapshot.capturedAtUptimeMillis >= AutoEligibility.TTL_MILLIS -> F9EligibilityDetail.TTL_EXPIRED
            else -> null
        }
        val tokenFailure = when {
            detection.token != snapshot.token -> F9EligibilityDetail.DETECTION_TOKEN
            wired.token != snapshot.token -> F9EligibilityDetail.ANALYSIS_TOKEN
            else -> null
        }
        report(F9EligibilityCheck.CAPTURE_CURRENT, captureFailure)
        report(F9EligibilityCheck.TOKEN_BINDING, tokenFailure)
        report(F9EligibilityCheck.SCREEN_AVAILABLE, if (fresh == null) F9EligibilityDetail.SCENE_UNAVAILABLE else null)
        if (captureFailure != null || tokenFailure != null || fresh == null) {
            for (check in F9EligibilityCheck.entries.filter { it !in setOf(F9EligibilityCheck.CAPTURE_CURRENT,
                F9EligibilityCheck.TOKEN_BINDING, F9EligibilityCheck.SCREEN_AVAILABLE) }) {
                trace?.eligibility(F9EligibilityCode(mode, check, ManualActionState.NOT_ATTEMPTED, F9EligibilityDetail.DEPENDENCY_FAILED))
            }
            return classified
        }
        val selected = wired.closeCandidates.singleOrNull()
        val choiceFailure = if (mode == F9EligibilityMode.AUTOMATIC) null else when {
            choice == null || choice.token != snapshot.token -> F9EligibilityDetail.CHOICE_TOKEN
            choice.kind != ChoiceKind.CLOSE_ONCE -> F9EligibilityDetail.CHOICE_KIND
            !choice.buttonMeaningConfirmed -> F9EligibilityDetail.CHOICE_MEANING
            selected == null || selected.id != choice.candidateId || selected.evidence != EvidenceLevel.E2_LABELED_NODE ||
                selected.role != ButtonRole.CLOSE || F9AutoCloser.role(selected.label) != ButtonRole.CLOSE -> F9EligibilityDetail.CHOICE_TARGET
            else -> null
        }
        if (mode == F9EligibilityMode.USER_CHOICE) report(F9EligibilityCheck.MANUAL_CHOICE, choiceFailure)
        val checks = mutableSetOf<EligibilityCheck>()
        val sameFailure = when {
            !fresh.complete -> F9EligibilityDetail.SCENE_INCOMPLETE
            fresh.packageName != snapshot.token.packageName -> F9EligibilityDetail.PACKAGE_CHANGED
            fresh.windowId != snapshot.token.windowId -> F9EligibilityDetail.WINDOW_CHANGED
            fresh.screen != snapshot.screenBounds -> F9EligibilityDetail.FRAME_CHANGED
            fresh.nodes != snapshot.nodes -> F9EligibilityDetail.NODES_CHANGED
            fresh.windows != snapshot.windows -> F9EligibilityDetail.WINDOWS_CHANGED
            F9ContentSafety.protectedPackage(fresh.packageName) -> F9EligibilityDetail.PACKAGE_PROTECTED
            F9ContentSafety.blocked(F9CollectedSource.texts(snapshot).joinToString(" ") { it.text }) ||
                fresh.nodes.any { listOfNotNull(it.text, it.contentDescription).any(F9ContentSafety::blocked) } -> F9EligibilityDetail.TEXT_PROTECTED
            else -> null
        }
        val same = sameFailure == null
        report(F9EligibilityCheck.F0_PASSED, sameFailure)
        if (same) checks += EligibilityCheck.F0_PASSED
        val treeFailure = if (!same) F9EligibilityDetail.DEPENDENCY_FAILED else
            if (snapshot.completeness != SnapshotCompleteness.COMPLETE) F9EligibilityDetail.SNAPSHOT_INCOMPLETE else null
        report(F9EligibilityCheck.TREE_COMPLETE, treeFailure)
        if (treeFailure == null) checks += EligibilityCheck.TREE_COMPLETE
        val popup = snapshot.windows.singleOrNull { it.id == snapshot.token.windowId }
        val scopeFailure = when {
            choiceFailure != null -> choiceFailure
            mode == F9EligibilityMode.AUTOMATIC && PopupSignal.NEW_WINDOW !in detection.signals -> F9EligibilityDetail.NEW_WINDOW_REQUIRED
            mode == F9EligibilityMode.USER_CHOICE && (detection.signals.none { it == PopupSignal.NEW_WINDOW || it == PopupSignal.SAME_WINDOW_OVERLAY } ||
                PopupSignal.INSUFFICIENT_EVIDENCE in detection.signals || PopupSignal.FULL_SCREEN_TRANSITION in detection.signals) -> F9EligibilityDetail.DETECTION_UNCERTAIN
            popup == null -> F9EligibilityDetail.POPUP_WINDOW_UNAVAILABLE
            popup.type != WindowType.APPLICATION -> F9EligibilityDetail.WINDOW_TYPE
            !popup.active -> F9EligibilityDetail.WINDOW_INACTIVE
            !popup.focused -> F9EligibilityDetail.WINDOW_UNFOCUSED
            popup.bounds == snapshot.screenBounds || !F9AutoCloser.contains(snapshot.screenBounds, popup.bounds) -> F9EligibilityDetail.WINDOW_UNBOUNDED
            else -> null
        }
        val scoped = scopeFailure == null
        report(F9EligibilityCheck.POPUP_SCOPE_CONFIRMED, scopeFailure)
        if (scoped) checks += EligibilityCheck.POPUP_SCOPE_CONFIRMED
        // B's classifier has completed its decision/scam guards on exactly the full tree text.
        // No summary, OCR-only line, missing line or out-of-scope truncation can earn this check.
        val textFailure = when {
            !scoped -> F9EligibilityDetail.DEPENDENCY_FAILED
            wired.texts != F9CollectedSource.texts(snapshot) -> F9EligibilityDetail.TEXT_NOT_COMPLETE
            classified.classification !in setOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON, PopupClassification.NOTICE) -> F9EligibilityDetail.CLASSIFICATION_UNSAFE
            else -> null
        }
        report(F9EligibilityCheck.DECISION_TEXT_SCOPED, textFailure)
        if (textFailure == null) checks += EligibilityCheck.DECISION_TEXT_SCOPED
        val targetFailure = when {
            !scoped -> F9EligibilityDetail.DEPENDENCY_FAILED
            wired.closeCandidates.isEmpty() -> F9EligibilityDetail.TARGET_UNAVAILABLE
            !wired.closeCandidates.all { candidate ->
                snapshot.nodes.any { it.path == candidate.nodePath && it.bounds == candidate.bounds } &&
                    F9AutoCloser.contains(requireNotNull(popup).bounds, candidate.bounds)
            } -> F9EligibilityDetail.TARGET_OUTSIDE_POPUP
            else -> null
        }
        report(F9EligibilityCheck.TARGETS_IN_POPUP, targetFailure)
        if (targetFailure == null) checks += EligibilityCheck.TARGETS_IN_POPUP
        val eligibility = AutoEligibility(snapshot.token, checks.toPersistentSet(), snapshot.capturedAtUptimeMillis)
        return classified.copy(autoEligibility = eligibility.takeIf { it.complete })
    }
}
