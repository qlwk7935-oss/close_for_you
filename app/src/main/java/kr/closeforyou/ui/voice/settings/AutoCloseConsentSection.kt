package kr.closeforyou.ui.voice.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kr.closeforyou.R

/**
 * Preset radio group + the separate "자동 닫기" switch (first-run step 1 and settings).
 * - Switch on: a dialog names what the chosen preset closes; only [켜기] calls [onConsentChange] (true).
 *   Off: at once, no dialog.
 * - While on, choosing a preset that closes more kinds asks again ("범위를 넓힐까요?"); [취소] keeps
 *   the old preset. A narrower preset applies at once. [onPresetChange]'s second value says the
 *   wider range was confirmed.
 * Reading order: presets → fixed decision line → switch → (not live) "지금은 닫기 전에 물어봐요".
 * Focus is never moved; the dialogs are system dialogs TalkBack enters by itself.
 */
@Composable
fun HandlingPresetWithConsent(
    selected: HandlingPreset,
    consentOn: Boolean,
    onPresetChange: (HandlingPreset, Boolean) -> Unit,
    onConsentChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmTurnOn by rememberSaveable { mutableStateOf(false) }
    var widenTo by rememberSaveable { mutableStateOf<HandlingPreset?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HandlingPresetGroup(
            selected = selected,
            onSelect = { preset ->
                if (AutoCloseConsentPolicy.needsReconfirm(consentOn, selected, preset)) widenTo = preset
                else onPresetChange(preset, false)
            },
            notLiveLine = false,
        )
        ConsentSwitchRow(
            checked = consentOn,
            onCheckedChange = { on -> if (on) confirmTurnOn = true else onConsentChange(false) },
        )
        if (!PresetAutoCloseFeature.live) Text(stringResource(R.string.ui_preset_not_live))
    }

    if (confirmTurnOn) {
        ConsentDialog(
            titleRes = R.string.ui_preset_consent_title,
            preset = selected,
            confirmRes = R.string.ui_preset_consent_turn_on,
            onConfirm = {
                confirmTurnOn = false
                onConsentChange(true)
            },
            onDismiss = { confirmTurnOn = false },
        )
    }
    widenTo?.let { preset ->
        ConsentDialog(
            titleRes = R.string.ui_preset_consent_widen_title,
            preset = preset,
            confirmRes = R.string.ui_preset_consent_widen,
            onConfirm = {
                widenTo = null
                onPresetChange(preset, true)
            },
            onDismiss = { widenTo = null },
        )
    }
}

/** One toggleable node: TalkBack reads label, help line, "스위치", 켜짐/꺼짐. 48dp. */
@Composable
private fun ConsentSwitchRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp).padding(vertical = 4.dp)) {
            Text(stringResource(R.string.ui_preset_consent_switch))
            Text(
                stringResource(ConsentText.switchHelpRes(checked)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ConsentDialog(titleRes: Int, preset: HandlingPreset, confirmRes: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConsentText.bodyRes(preset, PresetAutoCloseFeature.live).forEach { Text(stringResource(it)) }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirmRes)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_settings_cancel)) } },
    )
}

/** Dialog and switch texts, separate so a JVM test checks them per preset. */
internal object ConsentText {
    fun kindsRes(preset: HandlingPreset) = when (preset) {
        HandlingPreset.CLOSE_ADS_ONLY -> R.string.ui_preset_consent_kinds_ads
        HandlingPreset.CLOSE_ADS_AND_COUPONS -> R.string.ui_preset_consent_kinds_ads_coupons
        HandlingPreset.CLOSE_ALL -> R.string.ui_preset_consent_kinds_all
    }

    /** What the preset closes, the safety line, and (not live) that the app still asks first. */
    fun bodyRes(preset: HandlingPreset, live: Boolean): List<Int> = buildList {
        add(kindsRes(preset))
        add(R.string.ui_preset_consent_safety)
        if (!live) add(R.string.ui_preset_consent_not_live)
    }

    fun switchHelpRes(on: Boolean) =
        if (on) R.string.ui_preset_consent_switch_on_help else R.string.ui_preset_consent_switch_off_help
}
