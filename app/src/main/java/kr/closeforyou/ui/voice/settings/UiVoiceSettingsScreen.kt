package kr.closeforyou.ui.voice.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import kr.closeforyou.ui.voice.overlay.PromptAnnounceMode
import java.text.DateFormat
import java.util.Date

/**
 * Stateful entry: C settings plus A's pause state (read only) and D's remembered rules.
 * The repository is whatever D provides; today the debug screen passes the in-memory mock.
 */
@Composable
fun UiVoiceSettingsRoute(repository: PopupRuleRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preferences = remember { UiVoicePreferences.get(context) }
    val core = remember { CorePreferences.get(context) }
    val settings by preferences.state.collectAsState()
    val coreSettings by core.state.collectAsState()
    val rules by repository.observeRules().collectAsState(initial = persistentListOf())
    val scope = rememberCoroutineScope()

    UiVoiceSettingsScreen(
        settings = settings,
        coreAllowedPackages = coreSettings.allowedPackages,
        collectionEnabled = coreSettings.collectionEnabled,
        rules = rules,
        onAutoCloseChange = preferences::setAutoClose,
        onDetailChange = preferences::setDetail,
        onPromptModeChange = preferences::setPromptAnnounceMode,
        onRevoke = { id -> scope.launch { repository.revoke(id) } },
        onDeleteAll = { scope.launch { repository.deleteAll() } },
        modifier = modifier,
    )
}

/** Reading order = layout order. Every control has a visible label and a 48dp touch target. */
@Composable
fun UiVoiceSettingsScreen(
    settings: UiVoiceSettings,
    coreAllowedPackages: Set<String>,
    collectionEnabled: Boolean,
    rules: ImmutableList<PopupRule>,
    onAutoCloseChange: (String, Boolean) -> Unit,
    onDetailChange: (AnnouncementDetail) -> Unit,
    onPromptModeChange: (PromptAnnounceMode) -> Unit,
    onRevoke: (String) -> Unit,
    onDeleteAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDeleteAll by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Heading(R.string.ui_settings_title, MaterialTheme.typography.titleLarge)

        Heading(R.string.ui_settings_section_status)
        Text(
            stringResource(
                if (collectionEnabled) R.string.ui_settings_status_running else R.string.ui_settings_status_paused,
            ),
        )
        Text(stringResource(R.string.ui_settings_status_where), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Heading(R.string.ui_settings_section_auto_close)
        Text(stringResource(R.string.ui_settings_auto_close_help))
        if (coreAllowedPackages.isEmpty()) {
            Text(stringResource(R.string.ui_settings_auto_close_no_apps))
        } else {
            coreAllowedPackages.sorted().forEach { packageName ->
                SwitchRow(
                    label = stringResource(R.string.ui_settings_auto_close_app, packageName),
                    checked = settings.autoCloseAllowed(packageName, coreAllowedPackages),
                    onCheckedChange = { onAutoCloseChange(packageName, it) },
                )
            }
        }

        HorizontalDivider()
        Heading(R.string.ui_settings_section_announce)
        Text(stringResource(R.string.ui_settings_detail_label))
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

        HorizontalDivider()
        Heading(R.string.ui_settings_section_rules)
        if (rules.isEmpty()) {
            Text(stringResource(R.string.ui_settings_rules_empty))
            Text(stringResource(R.string.ui_settings_rules_empty_help), style = MaterialTheme.typography.bodySmall)
        } else {
            rules.forEach { rule -> RuleRow(rule, onRevoke) }
            OutlinedButton(
                onClick = { confirmDeleteAll = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.ui_settings_rules_delete_all)) }
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

@Composable
private fun RuleRow(rule: PopupRule, onRevoke: (String) -> Unit) {
    val date = remember(rule.createdAtEpochMillis) {
        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(rule.createdAtEpochMillis))
    }
    val summary = stringResource(R.string.ui_settings_rule_summary, rule.key.packageName, stringResource(rule.choice.ruleLabelRes()), date)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(summary)
        if (rule.enabled) {
            val revokeDescription = stringResource(R.string.ui_settings_rule_revoke_description, summary)
            OutlinedButton(
                onClick = { onRevoke(rule.id) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = revokeDescription },
            ) { Text(stringResource(R.string.ui_settings_rule_revoke)) }
        } else {
            Text(stringResource(R.string.ui_settings_rule_revoked), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Heading(textRes: Int, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.titleMedium) {
    Text(stringResource(textRes), style = style, modifier = Modifier.semantics { heading() })
}

/** [description] sits inside the toggleable row, so TalkBack reads it with the label and state. */
@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelWithDescription(label, description, Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun RadioRow(label: String, description: String?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        LabelWithDescription(label, description, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun LabelWithDescription(label: String, description: String?, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp)) {
        Text(label)
        if (description != null) Text(description, style = MaterialTheme.typography.bodySmall)
    }
}

private fun AnnouncementDetail.descriptionRes() = when (this) {
    AnnouncementDetail.SHORT -> R.string.ui_settings_detail_short_help
    AnnouncementDetail.DETAILED -> R.string.ui_settings_detail_detailed_help
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
    MaterialTheme {
        UiVoiceSettingsScreen(
            settings = UiVoiceSettings(autoClosePackages = setOf("kr.example.shop")),
            coreAllowedPackages = setOf("kr.example.shop", "kr.example.news"),
            collectionEnabled = false,
            rules = persistentListOf(
                PopupRule(
                    id = "preview", key = RuleKey("kr.example.shop", 1, "s", "c", "t"),
                    choice = ChoiceKind.REMEMBER_EXACT_POPUP, buttonMeaningConfirmed = true,
                    repeatAutomationExplicitlyAllowed = true, createdAtEpochMillis = 0, expiresAtEpochMillis = null,
                ),
            ),
            onAutoCloseChange = { _, _ -> }, onDetailChange = {}, onPromptModeChange = {},
            onRevoke = {}, onDeleteAll = {},
        )
    }
}
