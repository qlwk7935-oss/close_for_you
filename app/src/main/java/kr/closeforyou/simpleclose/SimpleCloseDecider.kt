package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.ImmutableSet
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import kr.closeforyou.ui.voice.settings.autoCloseKinds

/** What the engine does with one classified popup. */
sealed interface SimpleDecision {
    /**
     * Preset says close this kind and exactly one close button is verified: press it without asking.
     * [unknown]: a popup B could not judge, closed under [DemoUnknownAutoClose] ("전부 자동 닫기" only).
     */
    data class AutoClose(val candidate: CloseCandidate, val unknown: Boolean = false) : SimpleDecision

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
    ): SimpleDecision = decide(classification, suspicious, candidates, settings.autoCloseKinds(live = true), forbiddenCount)

    fun decide(
        classification: PopupClassification,
        suspicious: Boolean,
        candidates: List<CloseCandidate>,
        autoCloseKinds: ImmutableSet<PopupClassification>,
        forbiddenCount: Int = 0,
        unknownEnabled: Boolean = DemoUnknownAutoClose.ENABLED,
    ): SimpleDecision {
        if (suspicious) return SimpleDecision.DenySuspicious
        if (classification == PopupClassification.USER_DECISION) return SimpleDecision.DenyDecision
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
