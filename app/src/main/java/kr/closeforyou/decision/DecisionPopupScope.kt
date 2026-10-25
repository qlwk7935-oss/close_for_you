package kr.closeforyou.decision

import kr.closeforyou.core.model.ButtonLabelRules
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.LabelRejectReason
import kr.closeforyou.core.model.PopupDetection
import kr.closeforyou.core.model.PopupSignal
import kr.closeforyou.core.model.PopupSnapshot
import kr.closeforyou.core.model.TextSource

/**
 * B: which texts belong to the popup, for a same-window overlay that shares its window with the
 * page behind (P13, AUTO_CLOSE.md 5절). Warnings are read widely and reasons to close narrowly:
 * a page text is dropped only when it is provably outside the popup AND carries no warning
 * (decision, scam or uncertain wording), so scoping can never remove a reason to ask.
 *
 * A new window holds only the popup (A collects the active window's tree), so its texts stay as
 * they are, as do the texts of a screen without one unambiguous popup scope (no candidates there).
 */
internal object DecisionPopupScope {
    fun texts(snapshot: PopupSnapshot, detection: PopupDetection, texts: List<ExtractedText>): List<ExtractedText> {
        if (detection.token != snapshot.token || PopupSignal.NEW_WINDOW in detection.signals) return texts
        val scope = DecisionCloseCandidateFinder().popupScope(snapshot, detection.signals) ?: return texts
        if (scope.rootPath == null) return texts
        val popupNodes = snapshot.nodes.filter(scope::contains)
        return texts.filter { text ->
            isWarning(text.text) || when (text.source) {
                // A tree text is the popup's only when one of the popup's own nodes carries it there.
                TextSource.ACCESSIBILITY_TREE -> popupNodes.any { node ->
                    node.bounds == text.bounds && (node.text == text.text || node.contentDescription == text.text)
                }
                // OCR has no node; A reads only the popup region, so keep what lies inside the popup.
                TextSource.OCR -> text.bounds.left >= scope.bounds.left && text.bounds.top >= scope.bounds.top &&
                    text.bounds.right <= scope.bounds.right && text.bounds.bottom <= scope.bounds.bottom
            }
        }
    }

    /**
     * The popup's title for C (contract v3 PopupAnalysis.title): the first of [popupTexts] (already
     * scoped to the popup, in A's top-to-bottom order) that is accessibility-tree text and is not a
     * button word (any label the shared contract does not call NOT_IN_LIST: 닫기, 확인, 자세히 보기…),
     * not a close candidate's label, and carries no warning. Null when none qualifies: a title is
     * only ever read aloud, so an unsure one is left out.
     */
    fun title(popupTexts: List<ExtractedText>, candidates: List<CloseCandidate>): String? {
        val labels = candidates.mapNotNull { it.label?.let(ButtonLabelRules::normalize) }.toSet()
        return popupTexts.asSequence()
            .filter { it.source == TextSource.ACCESSIBILITY_TREE }
            .map { it.text.trim() }
            .firstOrNull { text ->
                text.isNotEmpty() && ButtonLabelRules.normalize(text) !in labels &&
                    ButtonLabelRules.judge(text).reason == LabelRejectReason.NOT_IN_LIST && !isWarning(text)
            }
    }

    /**
     * Every safety signal [DecisionKinds] reads, in the same wide forms. Not the service-notice guard:
     * a page tab such as "공지사항" says nothing about the popup on top of it.
     */
    private fun isWarning(text: String): Boolean =
        DecisionKeywords.isUserDecision(text) || DecisionKeywords.isDecisionWide(text) || DecisionKeywords.isDecisionLabelWide(text) ||
            DecisionKeywords.isSuspicious(text) || DecisionKeywords.isUncertainWide(text)
}
