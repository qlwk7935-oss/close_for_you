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
 * [handlingPreset] is separate: the person's preference per popup kind, for B to read later
 * (docs/c-notes/preset-contract-proposal.md). The two are never mixed.
 */
data class UiVoiceSettings(
    val autoClosePackages: Set<String> = emptySet(),
    val detail: AnnouncementDetail = AnnouncementDetail.SHORT,
    val promptAnnounceMode: PromptAnnounceMode = PromptAnnounceMode.PANE_TITLE_AND_TTS,
    /** F13 [말하기] on the prompt (default on). Off hides it; listening still needs a press. */
    val voiceCommandsEnabled: Boolean = true,
    val handlingPreset: HandlingPreset = HandlingPreset.DEFAULT,
    /** First-run setup finished ([시작하기] pressed). Until then [UiVoiceOnboardingGate] shows it. */
    val onboardingDone: Boolean = false,
    /** "도와줄 앱" way; null = not chosen yet (skipped or never asked). See HelpApps.kt. */
    val helpAppsMode: HelpAppsMode? = null,
    /** Apps saved in "도와줄 앱" that A has not accepted yet (before collection consent). */
    val pendingHelpPackages: Set<String> = emptySet(),
    /** Apps the person took out of "모든 앱" (A's planned exclusion list can take these over). */
    val helpAppsExcluded: Set<String> = emptySet(),
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
    private val mutableState = MutableStateFlow(readFrom(preferences))
    val state: StateFlow<UiVoiceSettings> = mutableState.asStateFlow()

    // SharedPreferences holds listeners weakly, so keep a strong reference.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        mutableState.value = readFrom(preferences)
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun current(): UiVoiceSettings = mutableState.value

    fun setAutoClose(packageName: String, enabled: Boolean) = write(current().withAutoClose(packageName, enabled))

    fun setDetail(detail: AnnouncementDetail) = write(current().copy(detail = detail))

    fun setPromptAnnounceMode(mode: PromptAnnounceMode) = write(current().copy(promptAnnounceMode = mode))

    fun setVoiceCommandsEnabled(enabled: Boolean) = write(current().copy(voiceCommandsEnabled = enabled))

    fun setHandlingPreset(preset: HandlingPreset) = write(current().copy(handlingPreset = preset))

    /** [시작하기] on the first-run screen: saves the chosen preset and does not show the screen again. */
    fun completeOnboarding(preset: HandlingPreset) =
        write(current().copy(handlingPreset = preset, onboardingDone = true))

    /** "도와줄 앱" saved: the way, the person's exclusions, and the additions still waiting for A's consent. */
    fun setHelpApps(mode: HelpAppsMode, excluded: Set<String>, pending: Set<String>) =
        write(current().copy(helpAppsMode = mode, helpAppsExcluded = excluded, pendingHelpPackages = pending))

    fun setPendingHelpPackages(pending: Set<String>) = write(current().copy(pendingHelpPackages = pending))

    private fun write(settings: UiVoiceSettings) {
        preferences.edit { writeTo(this, settings) }
        mutableState.value = settings
    }

    companion object {
        private const val FILE = "ui_voice_settings"
        private const val KEY_AUTO_CLOSE = "auto_close_packages_v1"
        private const val KEY_DETAIL = "announcement_detail"
        private const val KEY_PROMPT_MODE = "prompt_announce_mode"
        private const val KEY_VOICE_COMMANDS = "voice_commands_enabled"
        private const val KEY_PRESET = "handling_preset_v1"
        private const val KEY_ONBOARDING_DONE = "onboarding_done_v1"
        private const val KEY_HELP_APPS_MODE = "help_apps_mode_v1"
        private const val KEY_PENDING_HELP = "pending_help_packages_v1"
        private const val KEY_HELP_EXCLUDED = "help_apps_excluded_v1"
        @Volatile private var instance: UiVoicePreferences? = null

        fun get(context: Context): UiVoicePreferences = instance ?: synchronized(this) {
            instance ?: UiVoicePreferences(context).also { instance = it }
        }

        internal fun writeTo(editor: SharedPreferences.Editor, settings: UiVoiceSettings) {
            editor.putStringSet(KEY_AUTO_CLOSE, settings.autoClosePackages)
            editor.putString(KEY_DETAIL, settings.detail.name)
            editor.putString(KEY_PROMPT_MODE, settings.promptAnnounceMode.name)
            editor.putBoolean(KEY_VOICE_COMMANDS, settings.voiceCommandsEnabled)
            editor.putString(KEY_PRESET, settings.handlingPreset.name)
            editor.putBoolean(KEY_ONBOARDING_DONE, settings.onboardingDone)
            editor.putString(KEY_HELP_APPS_MODE, settings.helpAppsMode?.name)
            editor.putStringSet(KEY_PENDING_HELP, settings.pendingHelpPackages)
            editor.putStringSet(KEY_HELP_EXCLUDED, settings.helpAppsExcluded)
        }

        internal fun readFrom(preferences: SharedPreferences) = UiVoiceSettings(
            autoClosePackages = preferences.getStringSet(KEY_AUTO_CLOSE, emptySet()).orEmpty().toSet(),
            detail = enumOrDefault(preferences.getString(KEY_DETAIL, null), AnnouncementDetail.SHORT),
            promptAnnounceMode = enumOrDefault(
                preferences.getString(KEY_PROMPT_MODE, null), PromptAnnounceMode.PANE_TITLE_AND_TTS,
            ),
            voiceCommandsEnabled = preferences.getBoolean(KEY_VOICE_COMMANDS, true),
            handlingPreset = enumOrDefault(preferences.getString(KEY_PRESET, null), HandlingPreset.DEFAULT),
            onboardingDone = preferences.getBoolean(KEY_ONBOARDING_DONE, false),
            helpAppsMode = HelpAppsMode.entries.firstOrNull { it.name == preferences.getString(KEY_HELP_APPS_MODE, null) },
            pendingHelpPackages = preferences.getStringSet(KEY_PENDING_HELP, emptySet()).orEmpty().toSet(),
            helpAppsExcluded = preferences.getStringSet(KEY_HELP_EXCLUDED, emptySet()).orEmpty().toSet(),
        )

        /** Unknown or missing stored values fall back to the safe default. */
        internal inline fun <reified T : Enum<T>> enumOrDefault(stored: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == stored } ?: default
    }
}
