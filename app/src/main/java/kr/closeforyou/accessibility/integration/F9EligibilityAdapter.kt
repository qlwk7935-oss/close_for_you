package kr.closeforyou.accessibility.integration

import kotlinx.collections.immutable.toPersistentSet
import kr.closeforyou.accessibility.action.*
import kr.closeforyou.core.model.*
import kr.closeforyou.decision.DecisionPopupClassifier

/** Only complete tree-based separate-window popups. No OCR summaries, inferred bounds or
 * blanket EligibilityCheck.entries grant. The B wiring must preserve every original popup text.
 */
internal object F9EligibilityAdapter {
    suspend fun prepare(snapshot: PopupSnapshot, detection: PopupDetection, wired: PopupAnalysis,
        fresh: F9Scene?, now: Long): PopupAnalysis {
        val classified = wired.copy(classification = DecisionPopupClassifier().classify(wired), autoEligibility = null, title = null)
        if (snapshot.capturedAtUptimeMillis < 0 || now < snapshot.capturedAtUptimeMillis || now - snapshot.capturedAtUptimeMillis >= AutoEligibility.TTL_MILLIS ||
            detection.token != snapshot.token || wired.token != snapshot.token || fresh == null) return classified
        val checks = mutableSetOf<EligibilityCheck>()
        val same = fresh.complete && fresh.packageName == snapshot.token.packageName && fresh.windowId == snapshot.token.windowId &&
            fresh.screen == snapshot.screenBounds && fresh.nodes == snapshot.nodes && fresh.windows == snapshot.windows &&
            !F9ContentSafety.protectedPackage(fresh.packageName) &&
            !F9ContentSafety.blocked(F9CollectedSource.texts(snapshot).joinToString(" ") { it.text }) &&
            fresh.nodes.none { listOfNotNull(it.text, it.contentDescription).any(F9ContentSafety::blocked) }
        if (same) checks += EligibilityCheck.F0_PASSED
        if (same && snapshot.completeness == SnapshotCompleteness.COMPLETE) checks += EligibilityCheck.TREE_COMPLETE
        val popup = snapshot.windows.singleOrNull { it.id == snapshot.token.windowId }
        val scoped = PopupSignal.NEW_WINDOW in detection.signals && popup?.type == WindowType.APPLICATION &&
            popup.active && popup.focused && popup.bounds != snapshot.screenBounds &&
            F9AutoCloser.contains(snapshot.screenBounds, popup.bounds)
        if (scoped) checks += EligibilityCheck.POPUP_SCOPE_CONFIRMED
        // B's classifier has completed its decision/scam guards on exactly the full tree text.
        // No summary, OCR-only line, missing line or out-of-scope truncation can earn this check.
        if (scoped && wired.texts == F9CollectedSource.texts(snapshot) &&
            classified.classification in setOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON, PopupClassification.NOTICE)) {
            checks += EligibilityCheck.DECISION_TEXT_SCOPED
        }
        if (scoped && wired.closeCandidates.isNotEmpty() && wired.closeCandidates.all { candidate ->
                snapshot.nodes.any { it.path == candidate.nodePath && it.bounds == candidate.bounds } &&
                    F9AutoCloser.contains(requireNotNull(popup).bounds, candidate.bounds)
            }) checks += EligibilityCheck.TARGETS_IN_POPUP
        val eligibility = AutoEligibility(snapshot.token, checks.toPersistentSet(), snapshot.capturedAtUptimeMillis)
        return classified.copy(autoEligibility = eligibility.takeIf { it.complete })
    }
}
