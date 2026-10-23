package kr.closeforyou.ui.voice.settings

import kr.closeforyou.core.model.PopupClassification

/** What the person would like done with a kind of popup. A preference only, never an action permission. */
enum class PopupHandlingPreference { CLOSE, ANNOUNCE, ASK }

/**
 * Popup kinds a preset speaks about. COUPON has no classification yet (B, to be agreed), so its
 * value is kept but nothing maps to it; [preferenceFor] by [PopupClassification] never returns it.
 */
enum class PresetPopupKind { ADVERTISEMENT, NOTICE, COUPON }

/**
 * The person's handling presets (team 10/23: C owns the UI). B's policy and A's gate still decide;
 * a preset can not make an important decision, a risky button or an unlabeled X pressable.
 * USER_DECISION and UNKNOWN are ASK in every preset and can not be changed.
 */
enum class HandlingPreset(
    private val advertisement: PopupHandlingPreference,
    private val notice: PopupHandlingPreference,
    private val coupon: PopupHandlingPreference,
) {
    /** Ads, coupons and notices that pass the safety checks. */
    CLOSE_ALL(PopupHandlingPreference.CLOSE, PopupHandlingPreference.CLOSE, PopupHandlingPreference.CLOSE),
    CLOSE_ADS_AND_COUPONS(PopupHandlingPreference.CLOSE, PopupHandlingPreference.ANNOUNCE, PopupHandlingPreference.CLOSE),
    CLOSE_ADS_ONLY(PopupHandlingPreference.CLOSE, PopupHandlingPreference.ANNOUNCE, PopupHandlingPreference.ANNOUNCE),
    ;

    fun preferenceFor(kind: PresetPopupKind): PopupHandlingPreference = when (kind) {
        PresetPopupKind.ADVERTISEMENT -> advertisement
        PresetPopupKind.NOTICE -> notice
        PresetPopupKind.COUPON -> coupon
    }

    /** What B would receive. Important decisions and unknown popups are always asked. */
    fun preferenceFor(classification: PopupClassification): PopupHandlingPreference = when (classification) {
        PopupClassification.ADVERTISEMENT -> advertisement
        PopupClassification.NOTICE -> notice
        PopupClassification.USER_DECISION, PopupClassification.UNKNOWN -> PopupHandlingPreference.ASK
    }

    companion object {
        /** Safest of the three (only ads). To be confirmed by the person (C, 10/23). */
        val DEFAULT = CLOSE_ADS_ONLY
    }
}

/**
 * Real clicks (F9) are not connected yet (A gate NOT_EXECUTED), so choosing a preset changes nothing
 * today. While false, the screens add one honest line that the app still asks before closing.
 * Turn on together with A's gate and B's policy; then the line disappears.
 */
object PresetAutoCloseFeature {
    const val live: Boolean = false
}
