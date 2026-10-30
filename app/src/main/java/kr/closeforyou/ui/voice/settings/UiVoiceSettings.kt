package kr.closeforyou.ui.voice.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kr.closeforyou.ui.voice.overlay.PromptAnnounceMode
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverFeature

enum class AnnouncementDetail { SHORT, DETAILED }

/**
 * C settings. Auto close is per app and off by default: an app listed here only lets B consider
 * repeating an exact remembered choice (F12). First-seen popups are closed without asking only under
 * AGENTS.md 6-2 (preset + [autoCloseConsent] + B approval + A eligibility); otherwise they are asked.
 * [handlingPreset] is separate: the person's preference per popup kind, for B to read later
 * (docs/c-notes/preset-contract-proposal.md). The two are never mixed.
 * [autoCloseConsent] is a third, separate thing: the person's explicit "자동 닫기" switch (off by
 * default). Choosing a preset is not consent; see AutoCloseConsent.kt.
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
    /** The "자동 닫기" switch, turned on only after its confirmation dialog. Off by default. */
    val autoCloseConsent: Boolean = false,
    /** The preset whose kinds the person confirmed; the exported kinds never go beyond it. */
    val autoCloseConsentPreset: HandlingPreset? = null,
    /** When the switch or its confirmed range last changed (epoch millis); null = never. */
    val autoCloseConsentChangedAt: Long? = null,
    /** "선택 창이 뜨면 안내를 맡기기" ([TalkBackTakeoverFeature]); the default is that one constant. */
    val talkBackTakeover: Boolean = TalkBackTakeoverFeature.DEFAULT_ON,
    /** Our TTS rate; by default the device's TTS rate, like TalkBack ([SpeechRates]). */
    val speechRate: SpeechRateChoice = SpeechRateChoice.FOLLOW_SYSTEM,
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

    fun setTalkBackTakeover(enabled: Boolean) = write(current().copy(talkBackTakeover = enabled))

    fun setSpeechRate(rate: SpeechRateChoice) = write(current().copy(speechRate = rate))

    /** [widerConfirmed]: the person confirmed a wider range in the dialog (see [UiVoiceSettings.withPreset]). */
    fun setHandlingPreset(preset: HandlingPreset, widerConfirmed: Boolean = false) =
        write(current().withPreset(preset, System.currentTimeMillis(), widerConfirmed))

    /** The "자동 닫기" switch. Turn on only after the confirmation dialog; off needs none. */
    fun setAutoCloseConsent(on: Boolean) = write(current().withAutoCloseConsent(on, System.currentTimeMillis()))

    /** Consent as A's F9 reads it ([AutoCloseConsentSource]); reads the latest settings each call. */
    val autoCloseConsentSource = AutoCloseConsentSource { current().autoCloseConsentNow() }

    /**
     * [시작하기] on the first-run screen: saves the chosen preset and does not show the screen again.
     * [autoCloseConsent] is the step-1 switch (already confirmed in its dialog for [preset]).
     */
    fun completeOnboarding(preset: HandlingPreset, autoCloseConsent: Boolean = false) =
        write(current().completedOnboarding(preset, autoCloseConsent, System.currentTimeMillis()))

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
        private const val KEY_CONSENT = "auto_close_consent_v1"
        private const val KEY_CONSENT_PRESET = "auto_close_consent_preset_v1"
        private const val KEY_CONSENT_AT = "auto_close_consent_changed_at_v1"
        private const val KEY_TAKEOVER = "talkback_takeover_v1"
        private const val KEY_SPEECH_RATE = "speech_rate_v1"
        private const val NO_TIME = Long.MIN_VALUE
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
            editor.putBoolean(KEY_CONSENT, settings.autoCloseConsent)
            editor.putString(KEY_CONSENT_PRESET, settings.autoCloseConsentPreset?.name)
            settings.autoCloseConsentChangedAt?.let { editor.putLong(KEY_CONSENT_AT, it) } ?: editor.remove(KEY_CONSENT_AT)
            editor.putBoolean(KEY_TAKEOVER, settings.talkBackTakeover)
            editor.putString(KEY_SPEECH_RATE, settings.speechRate.name)
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
            autoCloseConsent = preferences.getBoolean(KEY_CONSENT, false),
            // Unknown stored preset = no confirmed range, so nothing is exported (safe side).
            autoCloseConsentPreset = HandlingPreset.entries.firstOrNull {
                it.name == preferences.getString(KEY_CONSENT_PRESET, null)
            },
            autoCloseConsentChangedAt = preferences.getLong(KEY_CONSENT_AT, NO_TIME).takeIf { it != NO_TIME },
            talkBackTakeover = preferences.getBoolean(KEY_TAKEOVER, TalkBackTakeoverFeature.DEFAULT_ON),
            speechRate = enumOrDefault(preferences.getString(KEY_SPEECH_RATE, null), SpeechRateChoice.FOLLOW_SYSTEM),
        )

        /** Unknown or missing stored values fall back to the safe default. */
        internal inline fun <reified T : Enum<T>> enumOrDefault(stored: String?, default: T): T =
            enumValues<T>().firstOrNull { it.name == stored } ?: default
    }
}
