package kr.closeforyou.decision

import kr.closeforyou.core.model.PopupAnalysis
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.core.model.TextSource
import kotlinx.collections.immutable.toPersistentList

/**
 * B: the six kinds the auto-close presets need (AUTO_CLOSE.md 3절). COUPON and SUSPICIOUS are not
 * in the shared [PopupClassification] yet, so [toDisplay] maps them until the contract PR adds them.
 * A kind is never an authorization; [DecisionAutoClose] decides separately.
 */
internal enum class DecisionKind {
    /** Prize-win, infection or urgent-install claims: ask and warn, never close by itself. */
    SUSPICIOUS,
    USER_DECISION,
    UNKNOWN,
    COUPON,
    ADVERTISEMENT,
    NOTICE;

    fun toDisplay(): PopupClassification = when (this) {
        USER_DECISION -> PopupClassification.USER_DECISION
        COUPON, ADVERTISEMENT -> PopupClassification.ADVERTISEMENT
        NOTICE -> PopupClassification.NOTICE
        SUSPICIOUS, UNKNOWN -> PopupClassification.UNKNOWN
    }
}

internal object DecisionKinds {
    /** True when [kind] also follows from the accessibility-tree text alone: the only text B closes on. */
    fun isFromTree(analysis: PopupAnalysis, kind: DecisionKind): Boolean =
        of(analysis.copy(texts = analysis.texts.filter { it.source == TextSource.ACCESSIBILITY_TREE }.toPersistentList())) == kind

    /**
     * First match wins, from the most cautious kind down. Warnings (1~3 and the notice guard) come
     * from any text and any source, in wide forms ([DecisionKeywords.guardForms]); coupons and ads
     * only from accessibility-tree text, because OCR can misread.
     */
    fun of(analysis: PopupAnalysis): DecisionKind {
        val texts = analysis.texts.filter { it.text.isNotBlank() }
        val tree = texts.filter { it.source == TextSource.ACCESSIBILITY_TREE }
        val treePromotion = tree.any { DecisionKeywords.isCoupon(it.text) || DecisionKeywords.isPromotion(it.text) }
        return when {
            // 1. A scam pattern outranks everything else so C can warn about it.
            texts.any { DecisionKeywords.isSuspicious(it.text) } -> DecisionKind.SUSPICIOUS
            // 2. Important decisions: today's signal, plus wide forms, requests to act, questions and
            // recurring charges, in texts and in every button label (AUTO_CLOSE.md 3절).
            analysis.hasUserDecisionSignal() || texts.any { DecisionKeywords.isDecisionWide(it.text) } ||
                analysis.closeCandidates.any { c -> c.label?.let(DecisionKeywords::isDecisionLabelWide) == true } -> DecisionKind.USER_DECISION
            // 3. Nothing to go on, or wording that says its own meaning is unconfirmed.
            texts.isEmpty() || texts.any { DecisionKeywords.isUncertainWide(it.text) } -> DecisionKind.UNKNOWN
            // 4. A notice about the service or the account wins over coupon and ad words next to it.
            treePromotion && texts.any { DecisionKeywords.isStrongNotice(it.text) } -> DecisionKind.NOTICE
            // 5. A claimable benefit wins over selling words: preset ③ then announces it instead of closing.
            tree.any { DecisionKeywords.isCoupon(it.text) } -> DecisionKind.COUPON
            // 6. Selling or promoting.
            treePromotion -> DecisionKind.ADVERTISEMENT
            // 7. Promotion seen only by OCR is an unconfirmed visual ad, not a notice.
            texts.any { DecisionKeywords.isCoupon(it.text) || DecisionKeywords.isPromotion(it.text) } -> DecisionKind.UNKNOWN
            // 8. A notice from either source (as today); closing one still needs tree text (DecisionAutoClose).
            texts.any { DecisionKeywords.isNotice(it.text) || DecisionKeywords.isStrongNotice(it.text) } -> DecisionKind.NOTICE
            else -> DecisionKind.UNKNOWN
        }
    }
}
