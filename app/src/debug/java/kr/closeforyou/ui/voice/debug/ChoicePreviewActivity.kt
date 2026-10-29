package kr.closeforyou.ui.voice.debug

import kr.closeforyou.ui.voice.UiVoiceTheme
import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import kr.closeforyou.R
import kr.closeforyou.app.CorePreferences
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.VoiceAvailability
import kr.closeforyou.core.contract.VoiceEvent
import kr.closeforyou.core.model.ActionResult
import kr.closeforyou.core.model.ActionStatus
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.ClickOutcome
import kr.closeforyou.core.model.SnapshotToken
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.testing.mock.MockPopupFixture
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptContent
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.choice.PromptSelection
import kr.closeforyou.ui.voice.command.OnDeviceVoiceCommandInput
import kr.closeforyou.ui.voice.command.VoiceAction
import kr.closeforyou.ui.voice.command.VoiceChoiceMapper
import kr.closeforyou.ui.voice.BatchSentence
import kr.closeforyou.ui.voice.BatchSummary
import kr.closeforyou.ui.voice.PressResultSpeech
import kr.closeforyou.ui.voice.TtsPopupAnnouncer
import kr.closeforyou.ui.voice.settings.AndroidCoreSetupStatus
import kr.closeforyou.ui.voice.settings.OnboardingGatePolicy
import kr.closeforyou.ui.voice.settings.UiVoiceOnboardingScreen
import kr.closeforyou.ui.voice.settings.UiVoicePreferences
import kr.closeforyou.ui.voice.settings.UiVoiceSettingsRoute
import kr.closeforyou.ui.voice.settings.CoreAllowedApps
import kr.closeforyou.ui.voice.settings.HelpAppsEditScreen
import kr.closeforyou.ui.voice.settings.HelpAppsSync
import kr.closeforyou.ui.voice.overlay.PromptAnnouncementListener
import kr.closeforyou.ui.voice.overlay.PromptSpeech

/**
 * Debug-only screen to try the "닫을까요?" prompt with synthetic fixtures (TalkBack included).
 * Uses each fixture's expected answer in place of B's classifier. No real screen data, no actions:
 * the produced UserChoice is only displayed, never sent to B or A.
 */
class ChoicePreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val openSettings = intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true
        val openOnboarding = intent?.getBooleanExtra(EXTRA_OPEN_ONBOARDING, false) == true
        val openAppPicker = intent?.getBooleanExtra(EXTRA_OPEN_APP_PICKER, false) == true
        setContent { UiVoiceTheme { ChoicePreviewScreen(openSettings, openOnboarding, openAppPicker) } }
        if (savedInstanceState == null) scheduleOverlayFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: the screen may already be in front, so open the first-run page here too.
        if (intent.getBooleanExtra(EXTRA_OPEN_ONBOARDING, false)) {
            intent.removeExtra(EXTRA_OPEN_ONBOARDING)
            setContent { UiVoiceTheme { ChoicePreviewScreen(openSettings = false, openOnboarding = true) } }
        } else if (intent.getBooleanExtra(EXTRA_OPEN_APP_PICKER, false)) {
            intent.removeExtra(EXTRA_OPEN_APP_PICKER)
            setContent { UiVoiceTheme { ChoicePreviewScreen(openSettings = false, openOnboarding = false, openAppPicker = true) } }
        }
        scheduleOverlayFromIntent(intent)
    }

    /**
     * adb test hook (debug only): `am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity
     * --ei ui_debug_overlay_scenario <0..6>` shows that fixture above the next app after 5 seconds.
     * singleTop (debug manifest) delivers the extra even when this screen is already in front, and a
     * freshly started process waits briefly for the test service to connect.
     * Optional `--el ui_debug_tts_delay_ms <ms>` changes when our own TTS speaks (default 700).
     * Optional `--ez ui_debug_c29_diag true` logs window facts for that show under C29Diag (issue #29).
     * `--el ui_debug_c29_watch_ms <ms>` (no scenario) only watches events for that long (issue #29).
     * Optional `--ez ui_debug_revalidate_demo true` (REVALIDATING, #29): 3 s after the prompt shows it
     * starts "다시 확인 중", and 2 s later the same prompt is confirmed into the same window. Adding
     * `--ez ui_debug_revalidate_timeout true` skips the confirmation, so the 3 s timeout closes it.
     */
    private fun scheduleOverlayFromIntent(intent: Intent?) {
        // `--ez ui_debug_c34_dump true` (alone): logs the shown prompt's node tree under C34Nodes.
        if (intent?.getBooleanExtra(EXTRA_C34_DUMP, false) == true) {
            intent.removeExtra(EXTRA_C34_DUMP)
            if (!ChoiceOverlayTestService.dumpOwnNodes()) {
                Toast.makeText(this, R.string.ui_debug_overlay_service_off, Toast.LENGTH_LONG).show()
            }
            return
        }
        // `--el ui_debug_c34_focus_ms <ms>` (alone): logs TalkBack focus moves under C34Nodes for that long, and
        // C's nodes 4 s after each prompt shown meanwhile. Read-only: TalkBack's focus is not moved unless
        // `--ez ui_debug_c34_focus_move true` is added too.
        val focusMillis = intent?.getLongExtra(EXTRA_C34_FOCUS_MS, -1L)?.takeIf { it in 1..MAX_C29_WATCH_MILLIS * 3 }
        val moveFocus = intent?.getBooleanExtra(EXTRA_C34_FOCUS_MOVE, false) == true
        intent?.removeExtra(EXTRA_C34_FOCUS_MS)
        intent?.removeExtra(EXTRA_C34_FOCUS_MOVE)
        if (focusMillis != null) {
            if (!ChoiceOverlayTestService.watchFocus(focusMillis, moveFocus)) {
                Toast.makeText(this, R.string.ui_debug_overlay_service_off, Toast.LENGTH_LONG).show()
            }
            return
        }
        val watchMillis = intent?.getLongExtra(EXTRA_C29_WATCH_MS, -1L)?.takeIf { it in 1..MAX_C29_WATCH_MILLIS }
        intent?.removeExtra(EXTRA_C29_WATCH_MS)
        if (watchMillis != null) {
            lifecycleScope.launch {
                val connected = withTimeoutOrNull(SERVICE_WAIT_MILLIS) {
                    ChoiceOverlayTestService.connected.first { it }
                } == true
                if (!connected || !ChoiceOverlayTestService.watchEvents(watchMillis)) {
                    Toast.makeText(this@ChoicePreviewActivity, R.string.ui_debug_overlay_service_off, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        val index = intent?.getIntExtra(EXTRA_OVERLAY_SCENARIO, -1) ?: -1
        val fixture = scenarios.getOrNull(index)?.fixture ?: return
        val ttsDelay = intent?.getLongExtra(EXTRA_TTS_DELAY_MS, -1L)?.takeIf { it in 0..MAX_TTS_DELAY_MILLIS }
            ?: PromptAnnouncementListener.DEFAULT_TTS_DELAY_MILLIS
        val diagnose = intent?.getBooleanExtra(EXTRA_C29_DIAG, false) == true
        val revalidateDemo = when {
            intent?.getBooleanExtra(EXTRA_REVALIDATE_DEMO, false) != true -> RevalidateDemo.OFF
            intent.getBooleanExtra(EXTRA_REVALIDATE_TIMEOUT, false) -> RevalidateDemo.TIMEOUT
            else -> RevalidateDemo.CONFIRM
        }
        intent?.removeExtra(EXTRA_REVALIDATE_DEMO)
        intent?.removeExtra(EXTRA_REVALIDATE_TIMEOUT)
        // Consume the extras so a configuration change does not show the prompt again.
        intent?.removeExtra(EXTRA_OVERLAY_SCENARIO)
        intent?.removeExtra(EXTRA_TTS_DELAY_MS)
        intent?.removeExtra(EXTRA_C29_DIAG)
        val prompt = ChoicePromptPlanner.plan(fixture.analysis, fixture.expectedDisplayClassification)
        val content = PromptSpeech.content(this, fixture.analysis)
        lifecycleScope.launch {
            val connected = withTimeoutOrNull(SERVICE_WAIT_MILLIS) {
                ChoiceOverlayTestService.connected.first { it }
            } == true
            if (!connected || !ChoiceOverlayTestService.scheduleShow(prompt, content, OVERLAY_DELAY_MILLIS, ttsDelay, diagnose, revalidateDemo)) {
                Toast.makeText(this@ChoicePreviewActivity, R.string.ui_debug_overlay_service_off, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val EXTRA_OVERLAY_SCENARIO = "ui_debug_overlay_scenario"
        const val SERVICE_WAIT_MILLIS = 3_000L
        const val EXTRA_TTS_DELAY_MS = "ui_debug_tts_delay_ms"
        const val MAX_TTS_DELAY_MILLIS = 10_000L
        const val EXTRA_C29_DIAG = "ui_debug_c29_diag"
        const val EXTRA_REVALIDATE_DEMO = "ui_debug_revalidate_demo"
        const val EXTRA_REVALIDATE_TIMEOUT = "ui_debug_revalidate_timeout"
        const val EXTRA_C29_WATCH_MS = "ui_debug_c29_watch_ms"
        const val MAX_C29_WATCH_MILLIS = 60_000L
        const val EXTRA_C34_DUMP = "ui_debug_c34_dump"
        const val EXTRA_C34_FOCUS_MS = "ui_debug_c34_focus_ms"
        const val EXTRA_C34_FOCUS_MOVE = "ui_debug_c34_focus_move"
        /** adb: `--ez ui_debug_open_settings true` opens the settings page directly. */
        const val EXTRA_OPEN_SETTINGS = "ui_debug_open_settings"
        /** adb: `--ez ui_debug_open_onboarding true` opens the first-run setup directly. */
        const val EXTRA_OPEN_ONBOARDING = "ui_debug_open_onboarding"
        /** adb: `--ez ui_debug_open_app_picker true` opens "도와줄 앱" (settings' editor) directly. */
        const val EXTRA_OPEN_APP_PICKER = "ui_debug_open_app_picker"
    }
}

private data class Scenario(val labelRes: Int, val fixture: MockPopupFixture)

private val scenarios = listOf(
    Scenario(R.string.ui_debug_scenario_advertisement, MockPopupFixtures.advertisement()),
    Scenario(R.string.ui_debug_scenario_notice, MockPopupFixtures.notice()),
    Scenario(R.string.ui_debug_scenario_changed_notice, MockPopupFixtures.changedDateNotice()),
    Scenario(R.string.ui_debug_scenario_decision, MockPopupFixtures.decision()),
    Scenario(R.string.ui_debug_scenario_unlabeled, MockPopupFixtures.unlabeledButton()),
    Scenario(R.string.ui_debug_scenario_video, MockPopupFixtures.videoWithoutAdEvidence()),
    Scenario(R.string.ui_debug_scenario_close_and_skip, closeAndSkipFixture()),
)

/** C-local synthetic variant (debug only): the ad fixture with both a close and a skip button. */
private fun closeAndSkipFixture(): MockPopupFixture {
    val base = MockPopupFixtures.advertisement()
    val close = base.analysis.closeCandidates.single()
    val skip = close.copy(id = "fixture-skip-button", label = SKIP_LABEL, role = ButtonRole.SKIP)
    return base.copy(
        scenarioId = "close-and-skip",
        analysis = base.analysis.copy(closeCandidates = persistentListOf(close, skip)),
    )
}

/** Synthetic fixture text, same category as the strings inside MockPopupFixtures. */
private const val SKIP_LABEL = "건너뛰기"

@Composable
private fun ChoicePreviewScreen(openSettings: Boolean, openOnboarding: Boolean, openAppPicker: Boolean = false) {
    var showSettings by rememberSaveable { mutableStateOf(openSettings) }
    var showOnboarding by rememberSaveable { mutableStateOf(openOnboarding) }
    var showAppPicker by rememberSaveable { mutableStateOf(openAppPicker) }
    if (showAppPicker) {
        // Real A preferences: [저장] calls CorePreferences.addAllowedPackage like the settings screen.
        Scaffold { innerPadding ->
            HelpAppsEditScreen(onClose = { showAppPicker = false }, modifier = Modifier.padding(innerPadding))
        }
        return
    }
    if (showOnboarding) {
        OnboardingPage(onBack = { showOnboarding = false })
        return
    }
    if (showSettings) {
        SettingsPage(onBack = { showSettings = false })
        return
    }
    var selected by remember { mutableStateOf(scenarios.first()) }
    var lastChoice by remember { mutableStateOf<UserChoice?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Same announcer as the service uses; released when the screen leaves composition.
    val announcer = remember { TtsPopupAnnouncer.create(context) }
    DisposableEffect(announcer) { onDispose { announcer.close() } }
    val basePrompt = remember(selected) {
        ChoicePromptPlanner.plan(selected.fixture.analysis, selected.fixture.expectedDisplayClassification)
    }
    var simulated by remember { mutableStateOf<ActionStatus?>(null) }
    // Follow-up prompt after a simulated failed press; reset when another fixture is picked.
    var followUp by remember(selected) { mutableStateOf<ChoicePrompt?>(null) }
    val prompt = followUp ?: basePrompt

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle(R.string.ui_debug_preview_title)
            OutlinedButton(
                onClick = { showSettings = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ui_debug_open_settings)) }
            OutlinedButton(
                onClick = { showOnboarding = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ui_debug_open_onboarding)) }
            OutlinedButton(
                onClick = { showAppPicker = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ui_debug_open_app_picker)) }
            SectionTitle(R.string.ui_debug_section_scenario)
            Column(Modifier.selectableGroup()) {
                scenarios.forEach { scenario ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(
                                selected = scenario == selected,
                                onClick = {
                                    selected = scenario
                                    lastChoice = null
                                },
                                role = Role.RadioButton,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = scenario == selected, onClick = null)
                        Text(stringResource(scenario.labelRes), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }

            HorizontalDivider()
            SectionTitle(R.string.ui_debug_section_prompt)
            val handleOption: (PromptOption) -> Unit = handleOption@{ option ->
                // MOVE_TO_BUTTON only appears when the feature flag is on; nothing to move here.
                val choice = (ChoicePromptPlanner.select(prompt, option) as? PromptSelection.Choice)?.choice
                    ?: return@handleOption
                lastChoice = choice
                val status = simulated
                if (status != null && PressResultSpeech.isPressChoice(choice.kind)) {
                    val result = simulatedResult(choice.token, status)
                    val speech = simulatedResultSpeech(context, result, choice.kind)
                    PressResultSpeech.failureOf(result)?.let {
                        followUp = ChoicePromptPlanner.afterFailedPress(basePrompt, it)
                    }
                    scope.launch {
                        announcer.announce(AnnouncementRequest(choice.token, AnnouncementKind.ACTION_RESULT, speech))
                    }
                } else if (choice.kind == ChoiceKind.READ_CONTENT) {
                    val content = PromptSpeech.content(context, selected.fixture.analysis)
                    scope.launch {
                        announcer.announce(AnnouncementRequest(choice.token, AnnouncementKind.CONTENT, content))
                    }
                }
            }
            ChoicePromptContent(prompt = prompt, onOption = handleOption)
            VoiceTestSection(prompt, onOption = handleOption)

            HorizontalDivider()
            SimulateSection(
                selected = simulated,
                onSelect = { simulated = it },
                onReset = {
                    followUp = null
                    lastChoice = null
                },
            )

            HorizontalDivider()
            SectionTitle(R.string.ui_debug_section_result)
            // Polite live region: the result is read without moving TalkBack focus.
            Text(
                text = lastChoice?.let { resultText(it) } ?: stringResource(R.string.ui_debug_result_none),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            HorizontalDivider()
            OverlayTestSection(prompt, PromptSpeech.content(context, selected.fixture.analysis))
        }
    }
}

/**
 * Push-to-talk test: one on-device listening session per press. Only the matched command enum is
 * shown; the recognized sentence itself is never displayed, logged or stored.
 */
@Composable
private fun VoiceTestSection(prompt: ChoicePrompt, onOption: (PromptOption) -> Unit) {
    val context = LocalContext.current
    val input = remember { OnDeviceVoiceCommandInput(context) }
    DisposableEffect(input) { onDispose { input.cancel() } }
    var availability by remember { mutableStateOf(input.availability()) }
    var status by remember { mutableStateOf<String?>(null) }
    val listening = stringResource(R.string.ui_debug_voice_listening)
    val notUnderstood = stringResource(R.string.ui_debug_voice_not_understood)
    val unavailable = stringResource(R.string.ui_debug_voice_unavailable)
    val recognizedFormat = stringResource(R.string.ui_debug_voice_recognized)
    val cannotPressFormat = stringResource(R.string.ui_debug_voice_cannot_press)
    val notHereFormat = stringResource(R.string.ui_debug_voice_not_here)
    val currentPrompt by rememberUpdatedState(prompt)
    val currentOnOption by rememberUpdatedState(onOption)

    LaunchedEffect(input) {
        input.events.collect { event ->
            status = when (event) {
                is VoiceEvent.Recognized -> when (val action = VoiceChoiceMapper.map(event.command, currentPrompt)) {
                    is VoiceAction.Choose -> {
                        currentOnOption(action.option)
                        recognizedFormat.format(event.command.name)
                    }
                    VoiceAction.CannotPressForUser -> cannotPressFormat.format(event.command.name)
                    is VoiceAction.NotHandledHere -> notHereFormat.format(event.command.name)
                }
                VoiceEvent.Cancelled -> notUnderstood
                VoiceEvent.Unavailable -> unavailable
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        availability = input.availability()
    }

    HorizontalDivider()
    SectionTitle(R.string.ui_debug_section_voice)
    Text(
        stringResource(
            when (availability) {
                VoiceAvailability.READY -> R.string.ui_debug_voice_ready
                VoiceAvailability.MICROPHONE_PERMISSION_REQUIRED -> R.string.ui_debug_voice_need_permission
                else -> R.string.ui_debug_voice_unavailable
            },
        ),
    )
    Button(
        onClick = {
            availability = input.availability()
            when (availability) {
                VoiceAvailability.MICROPHONE_PERMISSION_REQUIRED ->
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                else -> {
                    availability = input.startFromSpeakButton(prompt.token)
                    status = if (availability == VoiceAvailability.READY) listening else unavailable
                }
            }
        },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) { Text(stringResource(R.string.ui_debug_voice_speak)) }
    status?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
}

private val simulateOptions = listOf(
    null to R.string.ui_debug_simulate_off,
    ActionStatus.MECHANICAL_SUCCESS to R.string.ui_debug_simulate_success,
    ActionStatus.BLOCKED to R.string.ui_debug_simulate_blocked,
    ActionStatus.STALE to R.string.ui_debug_simulate_stale,
    ActionStatus.ATTEMPT_FAILED to R.string.ui_debug_simulate_failed,
    ActionStatus.OUTCOME_UNKNOWN to R.string.ui_debug_simulate_unknown,
)

/**
 * A fake result shaped like A's: "눌림" is a press A attempted and saw close (CLOSED_CONFIRMED), so
 * it sounds like success ([kr.closeforyou.ui.voice.PressSuccess]); failed / unknown were attempted
 * but not observed; blocked / stale were never attempted.
 */
private fun simulatedResult(token: SnapshotToken, status: ActionStatus): ActionResult = when (status) {
    ActionStatus.MECHANICAL_SUCCESS ->
        ActionResult(token, status, reason = null, attempted = true, outcome = ClickOutcome.CLOSED_CONFIRMED)
    ActionStatus.ATTEMPT_FAILED, ActionStatus.OUTCOME_UNKNOWN -> ActionResult(token, status, reason = null, attempted = true)
    ActionStatus.NOT_EXECUTED, ActionStatus.BLOCKED, ActionStatus.STALE -> ActionResult(token, status, reason = null)
}

/** Picks a fake ActionResult for the next press. Nothing is pressed; only the speech is tried. */
@Composable
private fun SimulateSection(selected: ActionStatus?, onSelect: (ActionStatus?) -> Unit, onReset: () -> Unit) {
    SectionTitle(R.string.ui_debug_section_simulate)
    Column(Modifier.selectableGroup()) {
        simulateOptions.forEach { (status, labelRes) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(selected = status == selected, onClick = { onSelect(status) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = status == selected, onClick = null)
                Text(stringResource(labelRes), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
    OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(stringResource(R.string.ui_debug_simulate_reset))
    }
}

/** Success uses the batch sentence for one popup; anything else the failure sentence. */
private fun simulatedResultSpeech(context: android.content.Context, result: ActionResult, choice: ChoiceKind): String {
    val failure = PressResultSpeech.failureOf(result)
        ?: return BatchSentence.build(
            BatchSummary(
                closed = if (choice == ChoiceKind.SKIP_ONCE) 0 else 1,
                skipped = if (choice == ChoiceKind.SKIP_ONCE) 1 else 0,
                notConfirmed = 0,
            ),
        ) { res, count -> context.getString(res, count) }
    return PressResultSpeech.sentence(failure, moveToButtonAvailable = false) { context.getString(it) }
}

/** C settings with fake remembered rules (in-memory mock, cleared when the process ends). */
@Composable
private fun SettingsPage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) { DebugRules.seedOnce() }
    Scaffold { innerPadding ->
        Column(Modifier.padding(innerPadding)) {
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp),
            ) { Text(stringResource(R.string.ui_debug_back_to_preview)) }
            UiVoiceSettingsRoute(DebugRules.repository)
        }
    }
}

/**
 * First-run setup (five steps), shown even when it was already finished. [사용 시작] saves the
 * preset, the switches and the "done" flag in C's real preferences, then comes back.
 * "도와줄 앱" is saved through A's real CorePreferences (pending until A's collection consent).
 * The service step reads A's real service state; [서비스 켜기] opens system settings.
 */
@Composable
private fun OnboardingPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val core = remember { CorePreferences.get(context) }
    val status = remember { AndroidCoreSetupStatus(context) }
    // The first step's back returns to the preview list (later steps go one step back themselves).
    BackHandler(onBack = onBack)
    Scaffold { innerPadding ->
        UiVoiceOnboardingScreen(
            initialDraft = OnboardingGatePolicy.initialDraft(preferences.current()),
            status = status,
            onFinish = { result ->
                result.helpApps?.let { HelpAppsSync.save(it.draft, it.catalog, preferences, CoreAllowedApps(core)) }
                preferences.completeOnboarding(result.preset, result.autoCloseConsent, result.talkBackTakeover)
                onBack()
            },
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/** Shows the same prompt through the real overlay, above whatever app the tester opens next. */
@Composable
private fun OverlayTestSection(prompt: ChoicePrompt, content: String) {
    val context = LocalContext.current
    val connected by ChoiceOverlayTestService.connected.collectAsState()
    val overlayChoice by ChoiceOverlayTestService.lastChoice.collectAsState()
    var scheduled by remember { mutableStateOf(false) }

    SectionTitle(R.string.ui_debug_section_overlay)
    Text(
        text = stringResource(
            if (connected) R.string.ui_debug_overlay_service_on else R.string.ui_debug_overlay_service_off,
        ),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
    val buttonModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    if (connected) {
        Button(
            onClick = { scheduled = ChoiceOverlayTestService.scheduleShow(prompt, content, OVERLAY_DELAY_MILLIS) },
            modifier = buttonModifier,
        ) { Text(stringResource(R.string.ui_debug_overlay_schedule)) }
    } else {
        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
            modifier = buttonModifier,
        ) { Text(stringResource(R.string.ui_debug_overlay_open_settings)) }
    }
    val status = overlayChoice?.let { stringResource(R.string.ui_debug_overlay_result, resultText(it)) }
        ?: if (scheduled) stringResource(R.string.ui_debug_overlay_scheduled) else null
    if (status != null) {
        Text(text = status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}

private const val OVERLAY_DELAY_MILLIS = 5_000L

@Composable
private fun SectionTitle(textRes: Int) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
}

/** Shows only enum values and synthetic IDs, never popup text. */
@Composable
private fun resultText(choice: UserChoice): String = stringResource(
    R.string.ui_debug_result,
    choice.kind.name,
    choice.candidateId ?: stringResource(R.string.ui_debug_none),
    choice.buttonMeaningConfirmed.toString(),
    choice.repeatAutomationExplicitlyAllowed.toString(),
)
