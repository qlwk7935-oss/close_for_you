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
    // F28 (11/1, 재호 승인): on for the demo. C still asks unless all four conditions of [isPromoPurchase] hold.
    const val PROMO_PURCHASE: Boolean = true

    // Whole label only: a purchase call to action with nothing else in it. "구매 확정", "구매하시겠어요?",
    // "구매 동의" or "1개 구매" are not calls to action from a promotion.
    private val promoPurchaseLabel = Regex("""^(지금|바로|오늘|특가로?)?\s?(구매|주문)\s?(하기|하러\s?가기)?$|^(buy|shop|order) now$""")

    // B_DEMO_RULES.md 1-8 (재호 LG 11/2 옥션: "삼성 BIG 혜택 구매하러가기" 광고 이미지): a link to a promotion's page,
    // named by the promotion itself. Only "~하러 가기" (it moves, nothing is bought by that press), and the words before
    // it must promote and hold no decision word. "상품 구매하러 가기", "구독 혜택 구매하러 가기" stay decisions.
    private val promoLinkLabel = Regex("""^(.{1,24}?)\s?(구매|주문)\s?하러\s?가기\s?[>›»]?$""")
    private val promoLinkWord = Regex("혜택|특가|할인|쿠폰|세일|이벤트|적립|사은품|최저가|프로모션|기획전")

    // Anything that belongs to a real checkout or contract, in any wide form (B reads warnings widely):
    // paying, card or account, a password, terms or consent, recurring money, quantity or address, a
    // total, an order record, or a question asking to buy.
    private val checkoutSignal = Regex(
        "결제|카드|계좌|비밀번호|약관|동의|자동\\s?(갱신|연장|결제|이체)|정기|구독|수량|배송지|" +
            "총\\s?(상품\\s?)?(금액|가격)|주문\\s?(내역|확인|금액|정보|하시겠)|(구매|주문|결제)\\s?하시겠|할까요|드릴까요|" +
            "(?<![a-z0-9_])(checkout|payment|subscribe|terms|agree)(?![a-z0-9_])",
        RegexOption.IGNORE_CASE,
    )

    /**
     * B_DEMO_RULES.md 8-5 B안 (11/1, 재호 결정): a promotion popup whose only decision signal is its own login invitation
     * ("로그인하러 가기", "로그인하고 혜택 받기") is a promotion, like [PROMO_PURCHASE]. Same four conditions ([isPromoLogin]).
     * The login button itself is never pressed; only the popup's close may be. "로그인이 필요한 서비스입니다" (no promotion
     * words) stays a decision.
     */
    const val PROMO_LOGIN: Boolean = true

    // Whole label only: a login invitation with nothing else in it ("로그인하고 구매하기", "로그인 후 결제" are not).
    private val promoLoginLabel = Regex(
        """^(지금|바로)?\s?로그인\s?(하기|하러\s?가기|하고\s?(혜택|쿠폰|할인|적립금?|포인트|특가)\s?(받기|받으러\s?가기|보기|확인하기)?)?\s?[>›»]?$|^(log\s?in|sign\s?in)( now)?\s?[>›»]?$""",
    )

    fun isPromoLoginLabel(text: String): Boolean =
        DecisionKeywords.guardForms(text).any { promoLoginLabel.matches(it.trim()) }

    /** [isPromoPurchase]'s four conditions, with a login invitation as the call to action. */
    fun isPromoLogin(texts: List<ExtractedText>): Boolean = isPromoCallToAction(texts, ::isPromoLoginLabel)

    fun isPromoPurchaseLabel(text: String): Boolean =
        DecisionKeywords.guardForms(text).any { promoPurchaseLabel.matches(it.trim()) } || isPromoPurchaseLink(text)

    /** B_DEMO_RULES.md 1-8: "○○ 혜택 구매하러 가기". Its own words are the promotion, so nothing else has to say so. */
    fun isPromoPurchaseLink(text: String): Boolean = DecisionKeywords.guardForms(text).any { form ->
        val prefix = promoLinkLabel.matchEntire(form.trim())?.groupValues?.get(1) ?: return@any false
        promoLinkWord.containsMatchIn(prefix) && !DecisionKeywords.isUserDecision(prefix) && !DecisionKeywords.isDecisionWide(prefix)
    }

    /**
     * True when [texts] are a promotion with a purchase button and nothing of a checkout: at least one
     * purchase call to action, a promotion or coupon wording in the other tree texts, and no checkout
     * signal in any text. The decision check then skips only the call-to-action texts.
     */
    fun isPromoPurchase(texts: List<ExtractedText>): Boolean = isPromoCallToAction(texts, ::isPromoPurchaseLabel)

    private fun isPromoCallToAction(texts: List<ExtractedText>, isCallToAction: (String) -> Boolean): Boolean {
        val tree = texts.filter { it.source == TextSource.ACCESSIBILITY_TREE && it.text.isNotBlank() }
        val (cta, rest) = tree.partition { isCallToAction(it.text) }
        if (cta.isEmpty()) return false
        if (rest.none { DecisionKeywords.isPromotion(it.text) || DecisionKeywords.isCoupon(it.text) } &&
            cta.none { isPromoPurchaseLink(it.text) }) return false
        // The popup's own words may not urge the decision either, not even as a benefit condition ("지금 구매하면
        // 쿠폰 증정", over-warning study de-08, recorded as a decision): only the button may carry it.
        if (rest.any { t -> DecisionKeywords.guardForms(DecisionKeywords.withoutPaymentBenefits(t.text)).any(DecisionKeywords::isUserDecision) }) return false
        // 1-7: "결제" inside a payment benefit ("카드 결제 시 10% 할인") is not a checkout trace.
        return texts.none { t -> DecisionKeywords.guardForms(DecisionKeywords.withoutPaymentBenefits(t.text)).any(checkoutSignal::containsMatchIn) }
    }
}
