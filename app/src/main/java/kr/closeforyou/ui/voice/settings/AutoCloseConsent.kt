package kr.closeforyou.ui.voice.settings

import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kr.closeforyou.core.model.PopupClassification

/**
 * The person's explicit "자동 닫기" consent, as A's F9 reads it (A 10/25: analysis consent,
 * screenshot/OCR consent and auto close consent are three separate things; a preset alone is not
 * consent). A policy input only: B's policy and A's gate still decide every press.
 *
 * - [granted]: first-run finished, the switch is on and [PresetAutoCloseFeature.live]. False today.
 * - [autoCloseKinds]: the same set C gives `DecisionContext.autoCloseKinds`; empty when not [granted].
 *   Never USER_DECISION or UNKNOWN.
 * - [changedAtEpochMillis]: when the switch or its range last changed (null = never), so A can
 *   invalidate an approval made before the change.
 */
data class AutoCloseConsent(
    val granted: Boolean,
    val autoCloseKinds: ImmutableSet<PopupClassification>,
    val changedAtEpochMillis: Long?,
) {
    companion object {
        val NONE = AutoCloseConsent(granted = false, autoCloseKinds = persistentSetOf(), changedAtEpochMillis = null)
    }
}

/** For A's F9 (`presetAndFirstSeenConsent`). Read on every check; never cache the result. */
fun interface AutoCloseConsentSource {
    fun current(): AutoCloseConsent

    companion object {
        /** Absent C wiring means no consent. */
        val NONE = AutoCloseConsentSource { AutoCloseConsent.NONE }
    }
}

fun UiVoiceSettings.autoCloseConsentNow(live: Boolean = PresetAutoCloseFeature.live): AutoCloseConsent {
    val kinds = autoCloseKinds(live)
    return AutoCloseConsent(granted = kinds.isNotEmpty(), autoCloseKinds = kinds, changedAtEpochMillis = autoCloseConsentChangedAt)
}

/** Pure rules for the switch and for preset changes while it is on (JVM tested). */
object AutoCloseConsentPolicy {
    /** True when [to] closes a kind [from] does not; then the person confirms again. */
    fun isWider(from: HandlingPreset?, to: HandlingPreset): Boolean =
        from == null || !from.autoCloseKinds().containsAll(to.autoCloseKinds())

    /** Selecting [to] needs the "범위를 넓힐까요?" dialog first. [current] null = none chosen yet. */
    fun needsReconfirm(consentOn: Boolean, current: HandlingPreset?, to: HandlingPreset): Boolean =
        consentOn && isWider(current, to)

    /** The switch can be turned on only once a preset is chosen (first run starts with none, A #34). */
    fun switchEnabled(selected: HandlingPreset?): Boolean = selected != null
}

/** Switch on = consent for the preset chosen now; off clears the range at once (no dialog). */
fun UiVoiceSettings.withAutoCloseConsent(on: Boolean, nowEpochMillis: Long): UiVoiceSettings = copy(
    autoCloseConsent = on,
    autoCloseConsentPreset = if (on) handlingPreset else null,
    autoCloseConsentChangedAt = nowEpochMillis,
)

/**
 * Preset change. Consent stays on. A narrower (or equal) range follows at once; a wider one only
 * when [widerConfirmed] (the dialog), otherwise the exported kinds stay within the old range.
 */
fun UiVoiceSettings.withPreset(preset: HandlingPreset, nowEpochMillis: Long, widerConfirmed: Boolean = false): UiVoiceSettings {
    if (!autoCloseConsent) return copy(handlingPreset = preset)
    val follows = widerConfirmed || !AutoCloseConsentPolicy.isWider(autoCloseConsentPreset, preset)
    if (!follows) return copy(handlingPreset = preset)
    if (preset == autoCloseConsentPreset) return copy(handlingPreset = preset)
    return copy(handlingPreset = preset, autoCloseConsentPreset = preset, autoCloseConsentChangedAt = nowEpochMillis)
}

/** [시작하기]: the preset, the done flag, and the step-1 switch as shown (confirmed for [preset]). */
fun UiVoiceSettings.completedOnboarding(preset: HandlingPreset, autoCloseConsent: Boolean, nowEpochMillis: Long) =
    copy(handlingPreset = preset, onboardingDone = true).let {
        if (autoCloseConsent || it.autoCloseConsent) it.withAutoCloseConsent(autoCloseConsent, nowEpochMillis) else it
    }
