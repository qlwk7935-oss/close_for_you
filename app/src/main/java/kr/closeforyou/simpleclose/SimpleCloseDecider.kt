package kr.closeforyou.simpleclose

import kotlinx.collections.immutable.ImmutableSet
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.CloseCandidate
import kr.closeforyou.core.model.PopupClassification
import kr.closeforyou.ui.voice.settings.UiVoiceSettings
import kr.closeforyou.ui.voice.settings.autoCloseKinds

/** What the engine does with one classified popup. */
sealed interface SimpleDecision {
    /** Preset says close this kind and exactly one close button is verified: press it without asking. */
    data class AutoClose(val candidate: CloseCandidate) : SimpleDecision

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
    ): SimpleDecision = decide(classification, suspicious, candidates, settings.autoCloseKinds(live = true))

    fun decide(
        classification: PopupClassification,
        suspicious: Boolean,
        candidates: List<CloseCandidate>,
        autoCloseKinds: ImmutableSet<PopupClassification>,
    ): SimpleDecision {
        if (suspicious) return SimpleDecision.DenySuspicious
        if (classification == PopupClassification.USER_DECISION) return SimpleDecision.DenyDecision
        if (classification == PopupClassification.UNKNOWN || classification !in autoCloseKinds) return SimpleDecision.Ask
        // Two close buttons ("닫기" and "오늘 하루 닫기" would already be one): let the person pick.
        val close = candidates.filter { it.role == ButtonRole.CLOSE }
        val single = close.singleOrNull() ?: return SimpleDecision.Ask
        return SimpleDecision.AutoClose(single)
    }
}
