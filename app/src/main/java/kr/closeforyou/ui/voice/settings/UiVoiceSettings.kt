package kr.closeforyou.ui.voice.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.closeforyou.ui.voice.overlay.PromptAnnounceMode

enum class AnnouncementDetail { SHORT, DETAILED }

/**
 * C settings. Auto close is per app and off by default: an app listed here only lets B consider
 * repeating an exact remembered choice (F12). First-seen popups are always asked (AGENTS.md 6-2).
 */
data class UiVoiceSettings(
    val autoClosePackages: Set<String> = emptySet(),
    val detail: AnnouncementDetail = AnnouncementDetail.SHORT,
    val promptAnnounceMode: PromptAnnounceMode = PromptAnnounceMode.PANE_TITLE_AND_TTS,
    /** F13 [말하기] on the prompt (default on). Off hides it; listening still needs a press. */
    val voiceCommandsEnabled: Boolean = true,
) {
    fun withAutoClose(packageName: String, enabled: Boolean) = copy(
        autoClosePackages = if (enabled) autoClosePackages + packageName else autoClosePackages - packageName,
    )

    /** For B's future DecisionContext field: only apps A still allows can ever be on. */
    fun autoCloseAllowed(packageName: String, coreAllowedPackages: Set<String>): Boolean =
        packageName in coreAllowedPackages && packageName in autoClosePackages
}

/** Application singleton over C's own SharedPreferences file (no DataStore, no new library). */
class UiVoicePreferences private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(read())
    val state: StateFlow<UiVoiceSettings> = mutableState.asStateFlow()

    // SharedPreferences holds listeners weakly, so keep a strong reference.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> mutableState.value = read() }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun current(): UiVoiceSettings = mutableState.value

    fun setAutoClose(packageName: String, enabled: Boolean) = write(current().withAutoClose(packageName, enabled))

    fun setDetail(detail: AnnouncementDetail) = write(current().copy(detail = detail))

    fun setPromptAnnounceMode(mode: PromptAnnounceMode) = write(current().copy(promptAnnounceMode = mode))

    fun setVoiceCommandsEnabled(enabled: Boolean) = write(current().copy(voiceCommandsEnabled = enabled))

    private fun write(settings: UiVoiceSettings) {
        preferences.edit {
            putStringSet(KEY_AUTO_CLOSE, settings.autoClosePackages)
            putString(KEY_DETAIL, settings.detail.name)
            putString(KEY_PROMPT_MODE, settings.promptAnnounceMode.name)
            putBoolean(KEY_VOICE_COMMANDS, settings.voiceCommandsEnabled)
        }
        mutableState.value = settings
    }

    private fun read() = UiVoiceSettings(
        autoClosePackages = preferences.getStringSet(KEY_AUTO_CLOSE, emptySet()).orEmpty().toSet(),
        detail = enumOrDefault(preferences.getString(KEY_DETAIL, null), AnnouncementDetail.SHORT),
        promptAnnounceMode = enumOrDefault(
            preferences.getString(KEY_PROMPT_MODE, null), PromptAnnounceMode.PANE_TITLE_AND_TTS,
        ),
        voiceCommandsEnabled = preferences.getBoolean(KEY_VOICE_COMMANDS, true),
    )

    companion object {
        private const val FILE = "ui_voice_settings"
        private const val KEY_AUTO_CLOSE = "auto_close_packages_v1"
        private const val KEY_DETAIL = "announcement_detail"
        private const val KEY_PROMPT_MODE = "prompt_announce_mode"
        private const val KEY_VOICE_COMMANDS = "voice_commands_enabled"
        @Volatile private var instance: UiVoicePreferences? = null

        fun get(context: Context): UiVoicePreferences = instance ?: synchronized(this) {
            instance ?: UiVoicePreferences(context).also { instance = it }
        }

        /** Unknown or missing stored values fall back to the safe default. */
        internal inline fun <reified T : Enum<T>> enumOrDefault(stored: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == stored } ?: default
    }
}
