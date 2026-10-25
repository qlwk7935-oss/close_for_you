package kr.closeforyou.ui.voice.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kr.closeforyou.R
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.ui.voice.UiVoiceTheme

/** Which screen the app's first screen shows. Pure, so the gate is tested on the JVM. */
object OnboardingGatePolicy {
    fun showOnboarding(settings: UiVoiceSettings): Boolean = !settings.onboardingDone

    /**
     * Preset shown as chosen when step 1 opens (A #34 10/25). First run: none, even though
     * [UiVoiceSettings.handlingPreset] already holds the internal safe default
     * ([HandlingPreset.DEFAULT]); the person picks one before [다음]. After setup: the saved one.
     */
    fun initialPreset(settings: UiVoiceSettings): HandlingPreset? =
        if (settings.onboardingDone) settings.handlingPreset else null

    /** [다음] on step 1 waits for a preset. */
    fun canContinue(chosen: HandlingPreset?): Boolean = chosen != null
}

/**
 * For A's first screen: the first-run setup until [시작하기] is pressed once, then [content].
 * e.g. `UiVoiceOnboardingGate { CoreSetupScreen(...) }`. Nothing is shown over another app.
 * After setup, "도와줄 앱" saved before A's collection consent is applied as soon as it is given.
 */
@Composable
fun UiVoiceOnboardingGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val core = remember { CorePreferences.get(context) }
    val settings by preferences.state.collectAsState()
    if (OnboardingGatePolicy.showOnboarding(settings)) {
        // Own Scaffold: theme background and system-bar insets (targetSdk 35 draws edge to edge).
        Scaffold(modifier) { insets ->
            UiVoiceOnboardingScreen(
                initialPreset = OnboardingGatePolicy.initialPreset(settings),
                initialAutoCloseConsent = settings.autoCloseConsent,
                onStart = { preset, autoCloseConsent, helpApps ->
                    helpApps?.let { HelpAppsSync.save(it.draft, it.catalog, preferences, CoreAllowedApps(core)) }
                    preferences.completeOnboarding(preset, autoCloseConsent)
                },
                consentGranted = core.currentSettings().consentGranted,
                modifier = Modifier.padding(insets),
            )
        }
    } else {
        PendingHelpAppsEffect()
        content()
    }
}

/** What the "도와줄 앱" step decided; null from [UiVoiceOnboardingScreen] means it was skipped. */
data class OnboardingHelpApps(val draft: HelpAppsDraft, val catalog: AppCatalog)

private enum class OnboardingStep { PRESET, HELP_APPS }

/**
 * First-run setup in two steps.
 * 1. Short explanation → the preset radio group (none chosen on first run, A #34) → fixed lines →
 *    "자동 닫기" switch (off, disabled until a preset is chosen; turning it on asks first, see
 *    [HandlingPresetWithConsent]) → [다음] (disabled until a preset is chosen, the reason is shown
 *    and read as its state). Left off, every popup is asked.
 * 2. "도와줄 앱" ([HelpAppsEditor], no way pre-selected; [시작하기] waits for one) →
 *    [시작하기] / [이전 단계] / [건너뛰기].
 * Nothing is saved before [시작하기] or [건너뛰기]; rotation keeps the choices ([rememberSaveable]).
 * Reading order = layout order and focus is never moved, so TalkBack starts wherever it is.
 */
@Composable
fun UiVoiceOnboardingScreen(
    /** Null = nothing chosen yet (first run). See [OnboardingGatePolicy.initialPreset]. */
    initialPreset: HandlingPreset?,
    /** (preset, "자동 닫기" switch, "도와줄 앱" or null when skipped). */
    onStart: (HandlingPreset, Boolean, OnboardingHelpApps?) -> Unit,
    modifier: Modifier = Modifier,
    initialAutoCloseConsent: Boolean = false,
    /** A's collection consent; false adds a line that the apps apply after consent. */
    consentGranted: Boolean = false,
    /** Null = load the launcher apps; previews pass a fixed catalog. */
    catalogOverride: AppCatalog? = null,
) {
    var chosen by rememberSaveable { mutableStateOf(initialPreset) }
    // The switch can not be on without a chosen preset.
    var autoCloseConsent by rememberSaveable { mutableStateOf(initialAutoCloseConsent && initialPreset != null) }
    var step by rememberSaveable { mutableStateOf(OnboardingStep.PRESET) }
    val preset = chosen
    if (step == OnboardingStep.HELP_APPS && preset != null) {
        HelpAppsStep(
            consentGranted = consentGranted,
            catalog = catalogOverride ?: rememberAppCatalog(),
            onBack = { step = OnboardingStep.PRESET },
            onStart = { helpApps -> onStart(preset, autoCloseConsent, helpApps) },
            modifier = modifier,
        )
        return
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.ui_onboarding_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(R.string.ui_onboarding_intro))
        HorizontalDivider()
        Text(stringResource(R.string.ui_onboarding_step_preset), style = MaterialTheme.typography.bodySmall)
        Text(
            stringResource(R.string.ui_preset_section),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        HandlingPresetWithConsent(
            selected = chosen,
            consentOn = autoCloseConsent,
            onPresetChange = { preset, _ -> chosen = preset },
            onConsentChange = { autoCloseConsent = it },
        )
        Text(stringResource(R.string.ui_onboarding_later), style = MaterialTheme.typography.bodySmall)
        HelpAppsSaveButton(
            label = stringResource(R.string.ui_onboarding_next),
            enabled = OnboardingGatePolicy.canContinue(chosen),
            onClick = { step = OnboardingStep.HELP_APPS },
            reason = stringResource(R.string.ui_onboarding_choose_preset_first),
        )
    }
}

@Composable
private fun HelpAppsStep(
    consentGranted: Boolean,
    catalog: AppCatalog?,
    onBack: () -> Unit,
    onStart: (OnboardingHelpApps?) -> Unit,
    modifier: Modifier,
) {
    var draft by rememberSaveable(stateSaver = HelpAppsDraftSaver) { mutableStateOf(HelpAppsDraft()) }
    var page by rememberSaveable { mutableStateOf(HelpAppsPage.MODE) }
    if (page == HelpAppsPage.MODE) BackHandler(onBack = onBack)
    HelpAppsEditor(
        catalog = catalog,
        draft = draft,
        onDraftChange = { draft = it },
        page = page,
        onPageChange = { page = it },
        modifier = modifier,
        top = {
            Text(stringResource(R.string.ui_onboarding_step_help_apps), style = MaterialTheme.typography.bodySmall)
            Text(
                stringResource(R.string.ui_help_apps_section),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() },
            )
        },
        bottom = {
            if (!consentGranted) Text(stringResource(R.string.ui_onboarding_help_apps_consent))
            HelpAppsSaveButton(
                label = stringResource(R.string.ui_onboarding_start),
                enabled = HelpAppsPlan.canSave(draft, catalog),
                onClick = { onStart(catalog?.let { OnboardingHelpApps(draft, it) }) },
            )
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_onboarding_back))
            }
            OutlinedButton(onClick = { onStart(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_onboarding_skip))
            }
            Text(stringResource(R.string.ui_onboarding_skip_help), style = MaterialTheme.typography.bodySmall)
        },
    )
}

@Preview(name = "첫 실행", showBackground = true, heightDp = 900)
@Composable
private fun OnboardingPreview() {
    UiVoiceTheme { UiVoiceOnboardingScreen(initialPreset = null, onStart = { _, _, _ -> }) }
}
