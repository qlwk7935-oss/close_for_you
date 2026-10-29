package kr.closeforyou.ui.voice.choice

import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.decision.DecisionKind
import kr.closeforyou.decision.DecisionKinds
import kr.closeforyou.ui.voice.settings.HandlingPreset

/**
 * When the prompt offers [앞으로 항상 닫기] (C 재호 decision 10/30, demo mode). It remembers this one
 * popup (D's fingerprint), not the app. Showing it never authorizes anything: the one-time press goes
 * first and the rule is saved only after a confirmed close (PopupChoiceFlow), and every later auto close
 * re-checks B's policy, A's gate and the exact popup.
 *
 * Shown: asked ads, coupons and notices under "광고만 닫기"·"광고·쿠폰 닫기", and UNKNOWN popups under any
 * preset. Hidden: important decisions, scam-like popups, kinds "전부 자동 닫기" already closes, and popups
 * with nothing to recognise them by again (docs/c-notes/remember-choice-rules.md).
 */
object RememberChoiceRules {
    fun offers(
        classification: PopupClassification,
        popupText: String?,
        preset: HandlingPreset?,
        suspicious: Boolean,
    ): Boolean = when {
        classification == PopupClassification.USER_DECISION -> false
        suspicious -> false
        preset == HandlingPreset.CLOSE_ALL && classification in preset.autoCloseKinds() -> false
        // Image only, no words: "the same popup" could not be told apart next time.
        popupText.isNullOrBlank() -> false
        else -> true
    }

    /**
     * Per button: D's provider needs the node path of a non-visual target
     * (DeviceKeyedFingerprintProvider fails for E4 or a null path), so without one nothing could be saved.
     */
    fun keyable(candidate: CloseCandidate): Boolean = candidate.nodePath != null

    /** B's own scam check (shown to C as UNKNOWN, SUSPICIOUS_CONTENT in the policy). Read-only use of B's rule. */
    fun suspiciousByB(analysis: PopupAnalysis): Boolean = DecisionKinds.of(analysis) == DecisionKind.SUSPICIOUS
}
