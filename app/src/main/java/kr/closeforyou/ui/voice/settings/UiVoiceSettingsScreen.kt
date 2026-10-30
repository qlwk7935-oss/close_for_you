package kr.closeforyou.ui.voice.settings

import kr.closeforyou.ui.voice.UiVoiceTheme
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.launch
import kr.closeforyou.R
import kr.closeforyou.app.CorePreferences
import kr.closeforyou.core.contract.PopupRuleRepository
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.PopupRule
import kr.closeforyou.core.model.RuleKey
import kr.closeforyou.ui.voice.command.AndroidMicrophoneAccess
import kr.closeforyou.ui.voice.command.SpeakButtonPolicy
import kr.closeforyou.ui.voice.command.SpeakButtonState
import kr.closeforyou.ui.voice.command.VoiceButtonFeature
import kr.closeforyou.ui.voice.integration.RememberRetention
import kr.closeforyou.ui.voice.overlay.PromptAnnounceMode
import kr.closeforyou.ui.voice.overlay.TalkBackTakeoverFeature
import java.time.Clock
import kotlin.coroutines.cancellation.CancellationException
import java.time.ZoneId

/**
 * Stateful entry: C settings plus A's pause state (read only) and D's remembered rules.
 * The repository is whatever is passed; the debug screen passes the in-memory mock, and the real
 * app uses [RoomBackedRuleSettings] (D's Room, `eraseAll`, `cleanUp`).
 * [deleteAll] is what [전체 삭제] runs. The default only deletes the rules; D's
 * `RuleStorage.eraseAll(context)` also destroys the fingerprint key.
 * [clock] decides which rules show as expired (re-read on resume).
 * [onOpenCleanUp] runs once each time the screen is opened (not on rotation); the default does nothing.
 */
@Composable
fun UiVoiceSettingsRoute(
    repository: PopupRuleRepository,
    modifier: Modifier = Modifier,
    deleteAll: suspend () -> Unit = repository::deleteAll,
    clock: Clock = Clock.systemDefaultZone(),
    onOpenCleanUp: suspend () -> Unit = {},
    /** A's "기본 설정" (consent, start/pause, OCR); null hides [기본 설정 열기]. See [UiVoiceAppRoot]. */
    onOpenCoreSettings: (() -> Unit)? = null,
    status: CoreSetupStatus? = null,
) {
    val context = LocalContext.current
    val setupStatus = status ?: remember { AndroidCoreSetupStatus(context) }
    var serviceOn by remember { mutableStateOf(setupStatus.serviceEnabled()) }
    var serviceOpenFailed by rememberSaveable { mutableStateOf(false) }
    val preferences = remember { UiVoicePreferences.get(context) }
    val core = remember { CorePreferences.get(context) }
    val settings by preferences.state.collectAsState()
    val coreSettings by core.state.collectAsState()
    val rules by repository.observeRules().collectAsState(initial = persistentListOf())
    val scope = rememberCoroutineScope()
    val currentDeleteAll by rememberUpdatedState(deleteAll)
    val actions = remember(repository) { RememberedRuleActions(repository) { currentDeleteAll() } }
    var deleteFailed by rememberSaveable { mutableStateOf(false) }
    var cleanedUp by rememberSaveable { mutableStateOf(false) }
    val currentCleanUp by rememberUpdatedState(onOpenCleanUp)
    LaunchedEffect(Unit) {
        if (cleanedUp) return@LaunchedEffect
        cleanedUp = true
        try {
            currentCleanUp()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Tidying only: the list still shows what D has, and the next open tries again.
        }
    }
    // "도와줄 앱" saved before A's consent is added once consent is there (also on opening).
    PendingHelpAppsEffect()
    // Which "도와줄 앱" page is open over the settings (null = none).
    var editingHelpApps by rememberSaveable { mutableStateOf<HelpAppsPage?>(null) }
    var now by remember { mutableLongStateOf(clock.millis()) }
    val labelSource = remember { PackageManagerAppLabelSource(context.packageManager) }
    val unknownApp = stringResource(R.string.ui_app_unknown)
    val packages = coreSettings.allowedPackages + rules.map { it.key.packageName }
    val appNames = remember(packages, unknownApp) { AppNames.displayNames(packages, labelSource, unknownApp) }
    val catalog = rememberAppCatalog()
    val helpLine = HelpAppsStatusPlan.line(
        mode = settings.helpAppsMode,
        allowed = coreSettings.allowedPackages,
        pending = settings.pendingHelpPackages,
        userExcluded = settings.helpAppsExcluded,
        catalog = catalog,
        consentGranted = coreSettings.consentGranted,
    )
    // Microphone: asked only here inside the Activity, never from the service's overlay.
    val microphone = remember { AndroidMicrophoneAccess(context) }
    fun micState() = SpeakButtonPolicy.state(featureEnabled = true, settingEnabled = true, microphone = microphone)
    var microphoneState by remember { mutableStateOf(micState()) }
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneDenied = !granted
        microphoneState = micState()
    }
    // The person may change the permission in system settings and come back.
    LifecycleResumeEffect(Unit) {
        microphoneState = micState()
        now = clock.millis()
        serviceOn = setupStatus.serviceEnabled()
        onPauseOrDispose {}
    }

    editingHelpApps?.let { page ->
        HelpAppsEditScreen(onClose = { editingHelpApps = null }, modifier = modifier, initialPage = page)
        return
    }

    UiVoiceSettingsScreen(
        settings = settings,
        coreAllowedPackages = coreSettings.allowedPackages,
        collectionEnabled = coreSettings.collectionEnabled,
        rules = rules,
        appName = { appNames[it] ?: unknownApp },
        onAutoCloseChange = preferences::setAutoClose,
        onDetailChange = preferences::setDetail,
        onPromptModeChange = preferences::setPromptAnnounceMode,
        takeoverFeatureEnabled = TalkBackTakeoverFeature.enabled,
        onTalkBackTakeoverChange = preferences::setTalkBackTakeover,
        onSpeechRateChange = preferences::setSpeechRate,
        voiceFeatureEnabled = VoiceButtonFeature.enabled,
        microphoneState = microphoneState,
        microphoneDenied = microphoneDenied,
        onVoiceCommandsChange = preferences::setVoiceCommandsEnabled,
        onPresetChange = preferences::setHandlingPreset,
        onAutoCloseConsentChange = preferences::setAutoCloseConsent,
        onAllowMicrophone = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onRevoke = { id -> scope.launch { actions.revoke(id) } },
        onDeleteAll = {
            deleteFailed = false
            scope.launch { deleteFailed = actions.deleteAll() == DeleteAllResult.FAILED }
        },
        modifier = modifier,
        nowEpochMillis = now,
        zone = clock.zone,
        deleteAllFailed = deleteFailed,
        helpLine = helpLine,
        onChangeHelpApps = { editingHelpApps = HelpAppsPage.MODE },
        onExcludeHelpApps = { editingHelpApps = HelpAppsPage.EXCLUDE_MORE },
        serviceOn = serviceOn,
        serviceOpenFailed = serviceOpenFailed,
        onOpenServiceSettings = rememberServiceTurnOn(serviceOn, onOpenFailed = { serviceOpenFailed = true }),
        onOpenCoreSettings = onOpenCoreSettings,
        onRestartSetup = preferences::restartOnboarding,
    )
}

/** Reading order = layout order. Every control has a visible label and a 48dp touch target. */
@Composable
fun UiVoiceSettingsScreen(
    settings: UiVoiceSettings,
    coreAllowedPackages: Set<String>,
    collectionEnabled: Boolean,
    rules: ImmutableList<PopupRule>,
    /** Package name → shown app name ("알 수 없는 앱" when unreadable; never the package name). */
    appName: (String) -> String,
    onAutoCloseChange: (String, Boolean) -> Unit,
    onDetailChange: (AnnouncementDetail) -> Unit,
    onPromptModeChange: (PromptAnnounceMode) -> Unit,
    onRevoke: (String) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
    /** [VoiceButtonFeature]; false hides the whole voice section. */
    voiceFeatureEnabled: Boolean = false,
    /** Microphone state ignoring the switch (the switch is shown separately). */
    microphoneState: SpeakButtonState = SpeakButtonState.NOT_IN_THIS_VERSION,
    microphoneDenied: Boolean = false,
    onVoiceCommandsChange: (Boolean) -> Unit = {},
    onAllowMicrophone: () -> Unit = {},
    /** "팝업 처리 방식" (per popup kind). Separate from the per-app F12 switches below.
     * Second value: a wider range was confirmed in the dialog ([HandlingPresetWithConsent]). */
    onPresetChange: (HandlingPreset, Boolean) -> Unit = { _, _ -> },
    /** The "자동 닫기" switch under the presets: true only after its dialog, false at once. */
    onAutoCloseConsentChange: (Boolean) -> Unit = {},
    /** Decides expired / on; the Route passes its clock. */
    nowEpochMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    deleteAllFailed: Boolean = false,
    /** The "도와줄 앱" line ([HelpAppsStatusPlan]). */
    helpLine: HelpAppsStatusLine = HelpAppsStatusLine(null, 0, null, waiting = false),
    /** Opens [HelpAppsEditScreen]; null hides the "도와줄 앱" block. */
    onChangeHelpApps: (() -> Unit)? = null,
    /** [제외할 앱 고르기] ("모든 앱" only): opens the editor on the exclusion list. */
    onExcludeHelpApps: () -> Unit = {},
    /** [TalkBackTakeoverFeature]; false hides its switch. */
    takeoverFeatureEnabled: Boolean = false,
    onTalkBackTakeoverChange: (Boolean) -> Unit = {},
    onSpeechRateChange: (SpeechRateChoice) -> Unit = {},
    /** A's accessibility service; null hides the block (previews). */
    serviceOn: Boolean? = null,
    serviceOpenFailed: Boolean = false,
    onOpenServiceSettings: () -> Unit = {},
    /** A's "기본 설정"; null hides [기본 설정 열기]. */
    onOpenCoreSettings: (() -> Unit)? = null,
    /** [처음 설정 다시 하기]; null hides it. */
    onRestartSetup: (() -> Unit)? = null,
) {
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        StepTitle(stringResource(R.string.ui_settings_title))

        if (serviceOn != null) {
            Heading(R.string.ui_settings_section_service)
            // Polite: back from system settings, TalkBack reads the new state once; focus stays.
            Box(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) { ServiceStateBox(serviceOn) }
            if (!serviceOn) {
                val appName = stringResource(R.string.app_name)
                HelpText(stringResource(R.string.ui_setup_service_how, appName))
                BrandButton(stringResource(R.string.ui_setup_service_turn_on), onOpenServiceSettings)
                if (serviceOpenFailed) BodyText(stringResource(R.string.ui_setup_service_open_failed, appName))
            }
            HorizontalDivider()
        }

        Heading(R.string.ui_preset_section)
        BodyText(stringResource(R.string.ui_preset_section_help))
        HandlingPresetWithConsent(
            selected = settings.handlingPreset,
            consentOn = settings.autoCloseConsent,
            onPresetChange = onPresetChange,
            onConsentChange = onAutoCloseConsentChange,
        )

        if (onChangeHelpApps != null) {
            HorizontalDivider()
            Heading(R.string.ui_help_apps_section)
            HelpAppsStatus(line = helpLine, onChange = onChangeHelpApps, onExclude = onExcludeHelpApps)
        }

        HorizontalDivider()
        Heading(R.string.ui_settings_section_status)
        Text(
            stringResource(
                if (collectionEnabled) R.string.ui_settings_status_running else R.string.ui_settings_status_paused,
            ),
        )
        if (onOpenCoreSettings != null) {
            HelpText(stringResource(R.string.ui_settings_open_core_help))
            OutlinedButton(onClick = onOpenCoreSettings, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.ui_settings_open_core), fontSize = UiType.body)
            }
        } else {
            HelpText(stringResource(R.string.ui_settings_status_where))
        }

        // Demo mode (10/30): no per-app switch; every helped app follows the preset and its remembered popups.
        if (PerAppAutoCloseGateFeature.enabled) {
            HorizontalDivider()
            Heading(R.string.ui_settings_section_auto_close)
            BodyText(stringResource(R.string.ui_settings_auto_close_help))
            val autoCloseApps = AutoCloseApps.candidates(
                rememberedPackages = rules.filter { it.choice == ChoiceKind.REMEMBER_EXACT_POPUP }.map { it.key.packageName },
                autoClosePackages = settings.autoClosePackages,
                allowed = coreAllowedPackages,
            )
            if (autoCloseApps.isEmpty()) {
                HelpText(stringResource(R.string.ui_settings_auto_close_no_apps))
            } else {
                autoCloseApps.sortedBy(appName).forEach { packageName ->
                    SwitchRow(
                        label = stringResource(R.string.ui_settings_auto_close_app, appName(packageName)),
                        checked = settings.autoCloseAllowed(packageName, coreAllowedPackages),
                        onCheckedChange = { onAutoCloseChange(packageName, it) },
                        iconPackage = packageName,
                    )
                }
            }
        }

        HorizontalDivider()
        Heading(R.string.ui_settings_section_announce)
        BodyText(stringResource(R.string.ui_settings_detail_label))
        Column(Modifier.selectableGroup()) {
            AnnouncementDetail.entries.forEach { detail ->
                RadioRow(
                    label = stringResource(detail.labelRes()),
                    description = stringResource(detail.descriptionRes()),
                    selected = settings.detail == detail,
                    onClick = { onDetailChange(detail) },
                )
            }
        }
        SwitchRow(
            label = stringResource(R.string.ui_settings_prompt_tts),
            description = stringResource(R.string.ui_settings_prompt_tts_help),
            checked = settings.promptAnnounceMode == PromptAnnounceMode.PANE_TITLE_AND_TTS,
            onCheckedChange = {
                onPromptModeChange(if (it) PromptAnnounceMode.PANE_TITLE_AND_TTS else PromptAnnounceMode.PANE_TITLE_ONLY)
            },
        )
        if (takeoverFeatureEnabled) {
            SwitchRow(
                label = stringResource(R.string.ui_settings_takeover),
                description = stringResource(R.string.ui_settings_takeover_help),
                checked = settings.talkBackTakeover,
                onCheckedChange = onTalkBackTakeoverChange,
            )
        }
        BodyText(stringResource(R.string.ui_settings_speech_rate_label))
        Column(Modifier.selectableGroup()) {
            SpeechRateChoice.entries.forEach { rate ->
                RadioRow(
                    label = stringResource(rate.labelRes()),
                    description = if (rate == SpeechRateChoice.FOLLOW_SYSTEM) {
                        stringResource(R.string.ui_settings_speech_rate_system_help)
                    } else {
                        null
                    },
                    selected = settings.speechRate == rate,
                    onClick = { onSpeechRateChange(rate) },
                )
            }
        }

        if (voiceFeatureEnabled) {
            HorizontalDivider()
            VoiceSection(settings, microphoneState, microphoneDenied, onVoiceCommandsChange, onAllowMicrophone)
        }

        HorizontalDivider()
        Heading(R.string.ui_settings_section_rules)
        HelpText(stringResource(R.string.ui_settings_rules_help))
        if (rules.isEmpty()) {
            Text(stringResource(R.string.ui_settings_rules_empty))
            Text(stringResource(R.string.ui_settings_rules_empty_help), style = MaterialTheme.typography.bodySmall)
        } else {
            rules.forEach { rule -> RuleRow(rule, appName(rule.key.packageName), nowEpochMillis, zone, onRevoke) }
        }
        // Always shown (D's request): rows D could not read are not listed but still need erasing.
        OutlinedButton(
            onClick = { confirmDeleteAll = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.ui_settings_rules_delete_all)) }
        if (deleteAllFailed) {
            // Polite live region: TalkBack reads it once, focus stays where the person is.
            Text(
                stringResource(R.string.ui_settings_rules_delete_all_failed),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (onRestartSetup != null) {
            HorizontalDivider()
            Heading(R.string.ui_settings_section_setup)
            HelpText(stringResource(R.string.ui_settings_restart_setup_help))
            OutlinedButton(onClick = onRestartSetup, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text(stringResource(R.string.ui_settings_restart_setup), fontSize = UiType.body)
            }
        }
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            title = { Text(stringResource(R.string.ui_settings_rules_delete_all_title)) },
            text = { Text(stringResource(R.string.ui_settings_rules_delete_all_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAll = false
                    onDeleteAll()
                }) { Text(stringResource(R.string.ui_settings_rules_delete_all_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(R.string.ui_settings_cancel)) }
            },
        )
    }
}

/** Switch first, then one microphone line, then [마이크 허용] only when allowing can help. */
@Composable
private fun VoiceSection(
    settings: UiVoiceSettings,
    microphoneState: SpeakButtonState,
    microphoneDenied: Boolean,
    onVoiceCommandsChange: (Boolean) -> Unit,
    onAllowMicrophone: () -> Unit,
) {
    Heading(R.string.ui_settings_section_voice)
    SwitchRow(
        label = stringResource(R.string.ui_settings_voice_enable),
        description = stringResource(R.string.ui_settings_voice_enable_help),
        checked = settings.voiceCommandsEnabled,
        onCheckedChange = onVoiceCommandsChange,
    )
    val line = when (microphoneState) {
        SpeakButtonState.SHOWN, SpeakButtonState.TURNED_OFF -> R.string.ui_settings_mic_granted
        SpeakButtonState.PERMISSION_NEEDED ->
            if (microphoneDenied) R.string.ui_settings_mic_denied else R.string.ui_settings_mic_needed
        SpeakButtonState.NOT_IN_THIS_VERSION -> R.string.ui_settings_mic_not_in_version
        SpeakButtonState.DEVICE_UNSUPPORTED -> R.string.ui_settings_mic_device_unsupported
    }
    Text(stringResource(line))
    if (microphoneState == SpeakButtonState.PERMISSION_NEEDED) {
        OutlinedButton(
            onClick = onAllowMicrophone,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) { Text(stringResource(R.string.ui_settings_mic_allow)) }
    }
}

/** One line "앱 · 선택 · 상태, 날짜" (one TalkBack node); [해제] repeats it in its description. */
@Composable
private fun RuleRow(rule: PopupRule, appName: String, nowEpochMillis: Long, zone: ZoneId, onRevoke: (String) -> Unit) {
    val resources = LocalContext.current.resources
    val locale = LocalConfiguration.current.locales[0]
    val summary = RuleListText.summary(
        rule = rule,
        appName = appName,
        nowEpochMillis = nowEpochMillis,
        formatDate = { millis ->
            RuleDates.format(millis, nowEpochMillis, zone, locale, android.text.format.DateFormat::getBestDateTimePattern)
        },
        text = { res, args -> resources.getString(res, *args) },
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(rule.key.packageName)
            Text(summary, fontSize = UiType.body, modifier = Modifier.padding(start = 12.dp))
        }
        if (RuleStatusPolicy.canRevoke(RuleStatusPolicy.statusOf(rule, nowEpochMillis))) {
            val revokeDescription = stringResource(R.string.ui_settings_rule_revoke_description, summary)
            OutlinedButton(
                onClick = { onRevoke(rule.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = revokeDescription },
            ) { Text(stringResource(R.string.ui_settings_rule_revoke)) }
        }
    }
}

@Composable
private fun Heading(textRes: Int) {
    Text(
        stringResource(textRes),
        fontSize = UiType.subtitle,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
    )
}

/** [description] sits inside the toggleable row, so TalkBack reads it with the label and state. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
    /** Shows that app's icon first (decorative). */
    iconPackage: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (iconPackage != null) AppIcon(iconPackage)
        LabelWithDescription(label, description, Modifier.weight(1f).padding(horizontal = 8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One node: label, description, "선택됨"/"선택 안 됨" (explicit, same words as the first run), 라디오 버튼. */
@Composable
private fun RadioRow(label: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    val state = stringResource(if (selected) R.string.ui_setup_selected else R.string.ui_setup_not_selected)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        LabelWithDescription(label, description, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LabelWithDescription(label: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = UiType.body)
        if (description != null) HelpText(description)
    }
}

private fun AnnouncementDetail.descriptionRes() = when (this) {
    AnnouncementDetail.SHORT -> R.string.ui_settings_detail_short_help
    AnnouncementDetail.DETAILED -> R.string.ui_settings_detail_detailed_help
}

private fun SpeechRateChoice.labelRes() = when (this) {
    SpeechRateChoice.FOLLOW_SYSTEM -> R.string.ui_settings_speech_rate_system
    SpeechRateChoice.SLOW -> R.string.ui_settings_speech_rate_slow
    SpeechRateChoice.NORMAL -> R.string.ui_settings_speech_rate_normal
    SpeechRateChoice.FAST -> R.string.ui_settings_speech_rate_fast
    SpeechRateChoice.VERY_FAST -> R.string.ui_settings_speech_rate_very_fast
}

private fun AnnouncementDetail.labelRes() = when (this) {
    AnnouncementDetail.SHORT -> R.string.ui_settings_detail_short
    AnnouncementDetail.DETAILED -> R.string.ui_settings_detail_detailed
}

internal fun ChoiceKind.ruleLabelRes() = when (this) {
    ChoiceKind.REMEMBER_EXACT_POPUP -> R.string.ui_settings_rule_always_close
    ChoiceKind.STOP_ASKING -> R.string.ui_settings_rule_stop_asking
    else -> R.string.ui_settings_rule_other
}

@Preview(name = "설정", showBackground = true, heightDp = 1200)
@Composable
private fun SettingsPreview() {
    UiVoiceTheme {
        UiVoiceSettingsScreen(
            settings = UiVoiceSettings(autoClosePackages = setOf("kr.example.shop")),
            coreAllowedPackages = setOf("kr.example.shop", "kr.example.news"),
            collectionEnabled = false,
            rules = persistentListOf(
                previewRule("on", createdAt = PREVIEW_NOW - PREVIEW_DAY),
                previewRule("expired", createdAt = PREVIEW_NOW - 31 * PREVIEW_DAY),
                previewRule("failed", createdAt = PREVIEW_NOW - PREVIEW_DAY).copy(failureCount = 1),
                previewRule("revoked", createdAt = PREVIEW_NOW - PREVIEW_DAY).copy(enabled = false),
            ),
            nowEpochMillis = PREVIEW_NOW,
            appName = { if (it == "kr.example.shop") "예시 쇼핑" else "알 수 없는 앱" },
            onAutoCloseChange = { _, _ -> }, onDetailChange = {}, onPromptModeChange = {},
            onRevoke = {}, onDeleteAll = {},
            voiceFeatureEnabled = true,
            microphoneState = SpeakButtonState.PERMISSION_NEEDED,
        )
    }
}

private const val PREVIEW_DAY = 24 * 60 * 60 * 1000L
private const val PREVIEW_NOW = 1_792_000_000_000L // 2026-10

private fun previewRule(content: String, createdAt: Long) = PopupRule(
    id = "preview-$content", key = RuleKey("kr.example.shop", 1, "s", content, "t"),
    choice = ChoiceKind.REMEMBER_EXACT_POPUP, buttonMeaningConfirmed = true,
    repeatAutomationExplicitlyAllowed = true, createdAtEpochMillis = createdAt,
    expiresAtEpochMillis = RememberRetention.expiryFor(createdAt),
)
