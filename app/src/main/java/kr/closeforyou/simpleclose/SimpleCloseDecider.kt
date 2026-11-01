package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.ImmutableSet
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.decision.DecisionDemoRules
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import kr.closeforyou.ui.voice.settings.autoCloseKinds

/** What the engine does with one classified popup. */
sealed interface SimpleDecision {
    /**
     * Preset says close this kind and exactly one close button is verified: press it without asking.
     * [unknown]: a popup B could not judge, closed under [DemoUnknownAutoClose] ("전부 자동 닫기" only).
     */
    data class AutoClose(val candidate: CloseCandidate, val unknown: Boolean = false, val strong: Boolean = false) : SimpleDecision

    /** Show C's prompt (a kind the preset asks about, an unknown popup, no or several close buttons). */
    data object Ask : SimpleDecision

    /** An important decision: explain only, nothing is pressed for the person (AGENTS 6-1). */
    data object DenyDecision : SimpleDecision

    /** Scam-like wording: warn, nothing is pressed. */
    data object DenySuspicious : SimpleDecision
}

/**
 * Pure decision of the engine. The person's consent is read the way the rest of C reads it:
 * [UiVoiceSettings.autoCloseKinds] with `live = true` (onboarding done, the "자동 닫기" switch on and the
 * confirmed preset), so a preset alone never closes anything. USER_DECISION and the scam check come
 * first and are never closed in any preset (6-0 "끝까지 유지").
 */
object SimpleCloseDecider {
    fun decide(
        classification: PopupClassification,
        suspicious: Boolean,
        candidates: List<CloseCandidate>,
        settings: UiVoiceSettings,
        /** Buttons with a decision word in the popup ([ButtonScan.forbiddenCount]); any one keeps an UNKNOWN popup asked. */
        forbiddenCount: Int = 0,
        /** F16: buttons with a decision word ([ButtonScan.riskCount]); any one keeps the popup asked. */
        riskCount: Int = 0,
        /** B_DEMO_RULES.md 1절: of [riskCount], promotion purchase buttons only ([ButtonScan.promoBuyCount]). */
        promoBuyCount: Int = 0,
    ): SimpleDecision = decide(classification, suspicious, candidates, settings.autoCloseKinds(live = true), forbiddenCount,
        riskCount = riskCount, promoBuyCount = promoBuyCount)

    fun decide(
        classification: PopupClassification,
        suspicious: Boolean,
        candidates: List<CloseCandidate>,
        autoCloseKinds: ImmutableSet<PopupClassification>,
        forbiddenCount: Int = 0,
        unknownEnabled: Boolean = DemoUnknownAutoClose.ENABLED,
        riskCount: Int = 0,
        strongEnabled: Boolean = SimpleCloseEngineFeature.STRONG_CLOSE_ALL,
        promoBuyCount: Int = 0,
        promoPurchase: Boolean = DecisionDemoRules.PROMO_PURCHASE,
    ): SimpleDecision {
        if (suspicious) return SimpleDecision.DenySuspicious
        if (classification == PopupClassification.USER_DECISION) return SimpleDecision.DenyDecision
        // B_DEMO_RULES.md 1절: B already looked at the whole popup and found no checkout, so a promotion's own
        // "지금 구매하기" no longer keeps an ad or coupon asked. It is still never a press target.
        val promoted = promoPurchase && classification in PROMO_KINDS
        val risk = if (promoted) riskCount - promoBuyCount.coerceIn(0, riskCount) else riskCount
        // F16: a 결제·동의·구독·삭제·권한 button beside the close keeps the popup asked, in every preset.
        if (risk > 0) return SimpleDecision.Ask
        if (strongEnabled && unknownEnabled && DemoUnknownAutoClose.presetAllows(autoCloseKinds)) {
            val pick = StrongCloseRules.pick(candidates) ?: return SimpleDecision.Ask
            return SimpleDecision.AutoClose(pick, unknown = classification == PopupClassification.UNKNOWN, strong = true)
        }
        if (classification == PopupClassification.UNKNOWN) {
            if (!unknownEnabled || !DemoUnknownAutoClose.presetAllows(autoCloseKinds) || forbiddenCount > 0) return SimpleDecision.Ask
            // Exactly one verified button in the whole popup, and it is a labeled close (an unlabeled X is never a candidate, 6-3).
            val only = candidates.singleOrNull()?.takeIf { it.role == ButtonRole.CLOSE } ?: return SimpleDecision.Ask
            return SimpleDecision.AutoClose(only, unknown = true)
        }
        if (classification !in autoCloseKinds) return SimpleDecision.Ask
        // Two close buttons ("닫기" and "오늘 하루 닫기" would already be one): let the person pick.
        val close = candidates.filter { it.role == ButtonRole.CLOSE }
        val single = close.singleOrNull() ?: return SimpleDecision.Ask
        return SimpleDecision.AutoClose(single)
    }

    private val PROMO_KINDS = setOf(PopupClassification.ADVERTISEMENT, PopupClassification.COUPON)
}

/**
 * Demo mode 6-0 relaxation (재호 decision 10/31, F7): with "전부 자동 닫기" and its "자동 닫기" switch on,
 * a popup B cannot judge (UNKNOWN) is closed without asking only when all of these hold: no button with
 * a decision word in it (결제·구매·동의·권한·구독·삭제·설치·앱에서 열기…, [SimpleLabelReject.FORBIDDEN]),
 * exactly one verified button and it is a labeled close, not suspicious, not USER_DECISION; the target
 * is still re-checked right before the press. "광고·쿠폰 닫기" and "광고만 닫기" keep asking.
 * AGENTS 6-2 is not changed; this switch turns it off.
 */
object DemoUnknownAutoClose {
    const val ENABLED: Boolean = true

    /** "전부 자동 닫기" in effect: every closable kind is in the live consented set. */
    fun presetAllows(autoCloseKinds: Set<PopupClassification>): Boolean =
        autoCloseKinds.containsAll(ALL_KINDS)

    private val ALL_KINDS = setOf(PopupClassification.ADVERTISEMENT, PopupClassification.NOTICE, PopupClassification.COUPON)
}

/**
 * F16 강력 모드 ("전부 닫기" + "자동 닫기", demo mode 6-0, F7 extended; [SimpleCloseEngineFeature.STRONG_CLOSE_ALL]):
 * whatever B says the popup is (광고·쿠폰·공지·판단 불가), it is closed without asking when it has no risky
 * button ([SimpleLabelRules.isRisky]), is neither a decision nor scam-like, and has at least one named
 * close button; of several, the plainest ([SimpleLabelRules.clarity]: "닫기"/"○○ 닫기" before
 * "오늘 하루 보지 않기"·"모바일 웹으로 볼래요" before "괜찮아요"), the first in reading order on a tie: the
 * same button C's prompt offers first (F20). A button with a navigation
 * word ("앱에서 열기", "설치", "쿠폰 받기") is never pressed but does not keep the popup asked. An unnamed X
 * is never picked (6-3). The target is still re-checked right before the press. Other presets are unchanged.
 */
object StrongCloseRules {
    fun pick(candidates: List<CloseCandidate>): CloseCandidate? =
        candidates.filter { it.role == ButtonRole.CLOSE }.minByOrNull { SimpleLabelRules.clarity(it.label) }
}
