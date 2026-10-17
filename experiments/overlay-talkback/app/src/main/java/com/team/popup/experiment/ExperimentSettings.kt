package com.team.popup.experiment

import android.content.Context
import androidx.annotation.StringRes

/** 오버레이가 떴다는 사실을 TalkBack 사용자에게 알리는 방법 (docs/experiments/overlay-talkback.md). */
enum class AnnounceMode(@param:StringRes val labelRes: Int) {
    NONE(R.string.mode_none),
    PANE_TITLE(R.string.mode_pane_title),
    LIVE_REGION(R.string.mode_live_region),
    TTS(R.string.mode_tts),
}

/**
 * 실험 조건 저장소. 실험 앱 전용이라 SharedPreferences를 쓴다.
 * (본 앱의 설정 저장 방식은 팀 회의 후 DataStore/Room 중에서 정한다.)
 */
class ExperimentSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var announceMode: AnnounceMode
        get() = prefs.getString(KEY_MODE, null)
            ?.let { name -> AnnounceMode.entries.firstOrNull { it.name == name } }
            ?: AnnounceMode.PANE_TITLE
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    var ttsDelayMs: Int
        get() = prefs.getInt(KEY_TTS_DELAY, TTS_DELAY_OPTIONS_MS[1])
        set(value) = prefs.edit().putInt(KEY_TTS_DELAY, value).apply()

    var popupDelaySec: Int
        get() = prefs.getInt(KEY_POPUP_DELAY, POPUP_DELAY_OPTIONS_SEC[1])
        set(value) = prefs.edit().putInt(KEY_POPUP_DELAY, value).apply()

    companion object {
        val TTS_DELAY_OPTIONS_MS = listOf(0, 700, 1500)
        val POPUP_DELAY_OPTIONS_SEC = listOf(3, 5, 10)

        private const val PREFS_NAME = "overlay_experiment"
        private const val KEY_MODE = "announce_mode"
        private const val KEY_TTS_DELAY = "tts_delay_ms"
        private const val KEY_POPUP_DELAY = "popup_delay_sec"
    }
}
