package kr.closeforyou.ui.voice.debug

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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kr.closeforyou.core.contract.AnnouncementKind
import kr.closeforyou.core.contract.AnnouncementRequest
import kr.closeforyou.core.contract.VoiceAvailability
import kr.closeforyou.core.contract.VoiceEvent
import kr.closeforyou.core.model.ButtonRole
import kr.closeforyou.core.model.ChoiceKind
import kr.closeforyou.core.model.UserChoice
import kr.closeforyou.testing.mock.MockPopupFixture
import kr.closeforyou.testing.mock.MockPopupFixtures
import kr.closeforyou.ui.voice.choice.ChoicePrompt
import kr.closeforyou.ui.voice.choice.ChoicePromptContent
import kr.closeforyou.ui.voice.choice.ChoicePromptPlanner
import kr.closeforyou.ui.voice.choice.PromptOption
import kr.closeforyou.ui.voice.command.OnDeviceVoiceCommandInput
import kr.closeforyou.ui.voice.command.VoiceAction
import kr.closeforyou.ui.voice.command.VoiceChoiceMapper
import kr.closeforyou.ui.voice.TtsPopupAnnouncer
import kr.closeforyou.ui.voice.settings.UiVoiceSettingsRoute
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
        setContent { MaterialTheme { ChoicePreviewScreen(openSettings) } }
        if (savedInstanceState == null) scheduleOverlayFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        scheduleOverlayFromIntent(intent)
    }

    /**
     * adb test hook (debug only): `am start -n kr.closeforyou/.ui.voice.debug.ChoicePreviewActivity
     * --ei ui_debug_overlay_scenario <0..6>` shows that fixture above the next app after 5 seconds.
     * singleTop (debug manifest) delivers the extra even when this screen is already in front, and a
     * freshly started process waits briefly for the test service to connect.
     * Optional `--el ui_debug_tts_delay_ms <ms>` changes when our own TTS speaks (default 700).
     */
    private fun scheduleOverlayFromIntent(intent: Intent?) {
        val index = intent?.getIntExtra(EXTRA_OVERLAY_SCENARIO, -1) ?: -1
        val fixture = scenarios.getOrNull(index)?.fixture ?: return
        val ttsDelay = intent?.getLongExtra(EXTRA_TTS_DELAY_MS, -1L)?.takeIf { it in 0..MAX_TTS_DELAY_MILLIS }
            ?: PromptAnnouncementListener.DEFAULT_TTS_DELAY_MILLIS
        // Consume the extras so a configuration change does not show the prompt again.
        intent?.removeExtra(EXTRA_OVERLAY_SCENARIO)
        intent?.removeExtra(EXTRA_TTS_DELAY_MS)
        val prompt = ChoicePromptPlanner.plan(fixture.analysis, fixture.expectedDisplayClassification)
        val content = PromptSpeech.content(this, fixture.analysis)
        lifecycleScope.launch {
            val connected = withTimeoutOrNull(SERVICE_WAIT_MILLIS) {
                ChoiceOverlayTestService.connected.first { it }
            } == true
            if (!connected || !ChoiceOverlayTestService.scheduleShow(prompt, content, OVERLAY_DELAY_MILLIS, ttsDelay)) {
                Toast.makeText(this@ChoicePreviewActivity, R.string.ui_debug_overlay_service_off, Toast.LENGTH_LONG).show()
            }
        }
    }

    private companion object {
        const val EXTRA_OVERLAY_SCENARIO = "ui_debug_overlay_scenario"
        const val SERVICE_WAIT_MILLIS = 3_000L
        const val EXTRA_TTS_DELAY_MS = "ui_debug_tts_delay_ms"
        const val MAX_TTS_DELAY_MILLIS = 10_000L
        /** adb: `--ez ui_debug_open_settings true` opens the settings page directly. */
        const val EXTRA_OPEN_SETTINGS = "ui_debug_open_settings"
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
private fun ChoicePreviewScreen(openSettings: Boolean) {
    var showSettings by rememberSaveable { mutableStateOf(openSettings) }
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
    val prompt = remember(selected) {
        ChoicePromptPlanner.plan(selected.fixture.analysis, selected.fixture.expectedDisplayClassification)
    }

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
            val handleOption: (PromptOption) -> Unit = { option ->
                val choice = ChoicePromptPlanner.toUserChoice(prompt, option)
                lastChoice = choice
                if (choice.kind == ChoiceKind.READ_CONTENT) {
                    val content = PromptSpeech.content(context, selected.fixture.analysis)
                    scope.launch {
                        announcer.announce(AnnouncementRequest(choice.token, AnnouncementKind.CONTENT, content))
                    }
                }
            }
            ChoicePromptContent(prompt = prompt, onOption = handleOption)
            VoiceTestSection(prompt, onOption = handleOption)

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
