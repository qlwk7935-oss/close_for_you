package kr.closeforyou.decision

import kr.closeforyou.core.contract.PopupClassifier
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification

/**
 * B: display classification from the six kinds ([DecisionKinds], AUTO_CLOSE.md 3절): suspicious,
 * decision, unknown, coupon, advertisement, notice. The result is never an action authorization;
 * ActionPolicy decides separately. No clicks, gestures, focus changes or logging of screen text.
 *
 * Analysis limitations are deliberately not used here: A marks every analysis
 * INCOMPLETE_EVIDENCE, which restricts automation (ActionPolicy) rather than what the user is told.
 */
class DecisionPopupClassifier : PopupClassifier {
    override suspend fun classify(analysis: PopupAnalysis): PopupClassification = displayClassification(analysis)

    /** The same classification without suspending, for B's own readers such as [DecisionVideoAd]. */
    internal fun displayClassification(analysis: PopupAnalysis): PopupClassification = DecisionKinds.of(analysis).toDisplay()
}

/** A decision signal in any text, button label or button role. Shared with DecisionActionPolicy. */
internal fun PopupAnalysis.hasUserDecisionSignal(): Boolean =
    closeCandidates.any { it.role == ButtonRole.USER_DECISION } ||
        // A decision word only inside a benefit condition ("가입하면 포인트") is not a decision (R1,
        // OVERWARNING_STUDY.md); any other text with one, such as a 구매하기 button, a question or
        // A's fixed summary, still is.
        texts.any { DecisionKeywords.isUserDecision(it.text) && !DecisionKeywords.isBenefitMention(it.text) } ||
        closeCandidates.any { candidate -> candidate.label?.let(DecisionKeywords::isDecisionButtonLabel) == true }
