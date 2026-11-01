package kr.closeforyou.decision

import kr.closeforyou.core.model.ExtractedText
import kr.closeforyou.core.model.TextSource

/**
 * B's demo-mode rule proposals (B_DEMO_RULES.md). Each switch is off until the team agrees; the rule
 * is written and tested either way, so turning it on is a one-line change.
 */
object DecisionDemoRules {
    /**
     * B_DEMO_RULES.md 1절 "구매 오판": a promotion popup whose only decision signal is its own purchase
     * button ("지금 구매하기", "바로 구매") is a promotion, not a purchase decision. The purchase button
     * itself is still never pressed; only the popup's close may be.
     */
    const val PROMO_PURCHASE: Boolean = false

    // Whole label only: a purchase call to action with nothing else in it. "구매 확정", "구매하시겠어요?",
    // "구매 동의" or "1개 구매" are not calls to action from a promotion.
    private val promoPurchaseLabel = Regex("""^(지금|바로|오늘|특가로?)?\s?(구매|주문)\s?(하기|하러\s?가기)?$|^(buy|shop|order) now$""")

    // Anything that belongs to a real checkout or contract, in any wide form (B reads warnings widely):
    // paying, card or account, a password, terms or consent, recurring money, quantity or address, a
    // total, an order record, or a question asking to buy.
    private val checkoutSignal = Regex(
        "결제|카드|계좌|비밀번호|약관|동의|자동\\s?(갱신|연장|결제|이체)|정기|구독|수량|배송지|" +
            "총\\s?(상품\\s?)?(금액|가격)|주문\\s?(내역|확인|금액|정보|하시겠)|(구매|주문|결제)\\s?하시겠|할까요|드릴까요|" +
            "(?<![a-z0-9_])(checkout|payment|subscribe|terms|agree)(?![a-z0-9_])",
        RegexOption.IGNORE_CASE,
    )

    fun isPromoPurchaseLabel(text: String): Boolean =
        DecisionKeywords.guardForms(text).any { promoPurchaseLabel.matches(it.trim()) }

    /**
     * True when [texts] are a promotion with a purchase button and nothing of a checkout: at least one
     * purchase call to action, a promotion or coupon wording in the other tree texts, and no checkout
     * signal in any text. The decision check then skips only the call-to-action texts.
     */
    fun isPromoPurchase(texts: List<ExtractedText>): Boolean {
        val tree = texts.filter { it.source == TextSource.ACCESSIBILITY_TREE && it.text.isNotBlank() }
        val (cta, rest) = tree.partition { isPromoPurchaseLabel(it.text) }
        if (cta.isEmpty()) return false
        if (rest.none { DecisionKeywords.isPromotion(it.text) || DecisionKeywords.isCoupon(it.text) }) return false
        // The popup's own words may not urge the decision either, not even as a benefit condition ("지금 구매하면
        // 쿠폰 증정", over-warning study de-08, recorded as a decision): only the button may carry it.
        if (rest.any { t -> DecisionKeywords.guardForms(t.text).any(DecisionKeywords::isUserDecision) }) return false
        return texts.none { t -> DecisionKeywords.guardForms(t.text).any(checkoutSignal::containsMatchIn) }
    }
}
