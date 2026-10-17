package kr.closeforyou.decision

import kr.closeforyou.core.contract.PopupClassifier
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.TextSource

/**
 * B: four-step display classification. The result is never an action authorization;
 * ActionPolicy decides separately. No clicks, gestures, focus changes or logging of screen text.
 *
 * Analysis limitations are deliberately not used here: A marks every analysis
 * INCOMPLETE_EVIDENCE, which restricts automation (ActionPolicy) rather than what the user is told.
 */
class DecisionPopupClassifier : PopupClassifier {
    override suspend fun classify(analysis: PopupAnalysis): PopupClassification {
        val texts = analysis.texts.filter { it.text.isNotBlank() }

        // 1. Important decisions first, from any source: the cautious direction.
        if (analysis.hasUserDecisionSignal()) return PopupClassification.USER_DECISION

        // 2. No text, or wording that says its own meaning is unconfirmed.
        if (texts.isEmpty() || texts.any { DecisionKeywords.isUncertain(it.text) }) {
            return PopupClassification.UNKNOWN
        }

        // 3. Advertisement needs tree text: OCR alone or a video surface alone is not enough (F5).
        // Advertisement wording seen only by OCR is an unconfirmed visual ad, not a notice.
        val adTexts = texts.filter { DecisionKeywords.isAdvertisement(it.text) }
        if (adTexts.any { it.source == TextSource.ACCESSIBILITY_TREE }) return PopupClassification.ADVERTISEMENT
        if (adTexts.isNotEmpty()) return PopupClassification.UNKNOWN

        // 4. Notice, from tree or OCR text.
        if (texts.any { DecisionKeywords.isNotice(it.text) }) return PopupClassification.NOTICE

        return PopupClassification.UNKNOWN
    }
}

/** A decision signal in any text, button label or button role. Shared with DecisionActionPolicy. */
internal fun PopupAnalysis.hasUserDecisionSignal(): Boolean =
    closeCandidates.any { it.role == ButtonRole.USER_DECISION } ||
        texts.any { DecisionKeywords.isUserDecision(it.text) } ||
        closeCandidates.any { candidate -> candidate.label?.let(DecisionKeywords::isDecisionButtonLabel) == true }
