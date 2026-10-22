package com.team.popup.experiment

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = ExperimentSettings(this)
        setContent {
            MaterialTheme {
                ExperimentScreen(
                    settings = settings,
                    onOpenAccessibilitySettings = {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    },
                )
            }
        }
    }
}

@Composable
private fun ExperimentScreen(
    settings: ExperimentSettings,
    onOpenAccessibilitySettings: () -> Unit,
) {
    val running by ExperimentAccessibilityService.running.collectAsState()
    val log by ExperimentLog.entries.collectAsState()
    var mode by remember { mutableStateOf(settings.announceMode) }
    var ttsDelayMs by remember { mutableStateOf(settings.ttsDelayMs) }
    var popupDelaySec by remember { mutableStateOf(settings.popupDelaySec) }
    var statusMessage by remember { mutableStateOf("") }

    val scheduledMessage = stringResource(R.string.popup_scheduled, popupDelaySec)
    val needServiceMessage = stringResource(R.string.popup_need_service)

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.main_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                stringResource(if (running) R.string.service_status_on else R.string.service_status_off),
            )
            OutlinedButton(onClick = onOpenAccessibilitySettings, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.open_accessibility_settings))
            }

            HorizontalDivider()
            OptionGroup(
                title = stringResource(R.string.section_announce_mode),
                options = AnnounceMode.entries,
                selected = mode,
                label = { stringResource(it.labelRes) },
                onSelect = {
                    mode = it
                    settings.announceMode = it
                },
            )
            if (mode == AnnounceMode.TTS) {
                OptionGroup(
                    title = stringResource(R.string.section_tts_delay),
                    options = ExperimentSettings.TTS_DELAY_OPTIONS_MS,
                    selected = ttsDelayMs,
                    label = { stringResource(R.string.delay_ms, it) },
                    onSelect = {
                        ttsDelayMs = it
                        settings.ttsDelayMs = it
                    },
                )
            }
            OptionGroup(
                title = stringResource(R.string.section_popup_delay),
                options = ExperimentSettings.POPUP_DELAY_OPTIONS_SEC,
                selected = popupDelaySec,
                label = { stringResource(R.string.delay_sec, it) },
                onSelect = {
                    popupDelaySec = it
                    settings.popupDelaySec = it
                },
            )

            Button(
                onClick = {
                    val service = ExperimentAccessibilityService.instance
                    statusMessage = if (service == null) {
                        needServiceMessage
                    } else {
                        service.schedulePopup(popupDelaySec)
                        scheduledMessage
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.show_popup_later))
            }
            // 버튼을 누른 뒤 결과 문장을 포커스 이동 없이 읽어 주도록 라이브 영역으로 둔다.
            Text(
                text = statusMessage,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )

            HorizontalDivider()
            Text(
                text = stringResource(R.string.section_log),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (log.isEmpty()) {
                Text(stringResource(R.string.log_empty))
            } else {
                log.forEach { entry -> Text(logText(entry)) }
            }
        }
    }
}

@Composable
private fun logText(entry: ExperimentLog.Entry): String = when (entry) {
    is ExperimentLog.Entry.Shown ->
        stringResource(R.string.log_shown, entry.time, stringResource(entry.mode.labelRes))
    is ExperimentLog.Entry.Chosen ->
        stringResource(R.string.log_choice, entry.time, stringResource(entry.choice.labelRes))
}

/** TalkBack이 "라디오 버튼, 선택됨, n개 중 m번째"로 읽도록 묶은 선택지 그룹. */
@Composable
private fun <T> OptionGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.semantics { heading() },
    )
    Column(Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .selectable(
                        selected = option == selected,
                        onClick = { onSelect(option) },
                        role = Role.RadioButton,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 줄 전체가 하나의 선택지라 RadioButton 자체의 클릭은 끈다 (중복 낭독 방지).
                RadioButton(selected = option == selected, onClick = null)
                Text(text = label(option), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
