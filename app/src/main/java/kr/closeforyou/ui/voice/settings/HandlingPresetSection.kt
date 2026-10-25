package kr.closeforyou.ui.voice.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kr.closeforyou.R

/**
 * The three presets as one radio group (first-run screen and settings). Each row is one TalkBack
 * node: name, what it does to ads / notices / coupons, then "선택됨". Selecting never moves focus.
 * The fixed lines follow the group: important popups are always asked, and (while
 * [PresetAutoCloseFeature.live] is false) nothing is closed automatically yet.
 */
@Composable
fun HandlingPresetGroup(
    selected: HandlingPreset,
    onSelect: (HandlingPreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.selectableGroup()) {
            HandlingPreset.entries.forEach { preset ->
                PresetRow(preset, selected = preset == selected, onClick = { onSelect(preset) })
            }
        }
        Text(stringResource(R.string.ui_preset_decision_fixed))
        if (!PresetAutoCloseFeature.live) Text(stringResource(R.string.ui_preset_not_live))
    }
}

@Composable
private fun PresetRow(preset: HandlingPreset, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 8.dp).padding(vertical = 4.dp)) {
            Text(stringResource(PresetText.labelRes(preset)))
            Text(
                stringResource(
                    R.string.ui_preset_line,
                    stringResource(PresetText.preferenceRes(preset.preferenceFor(PresetPopupKind.ADVERTISEMENT))),
                    stringResource(PresetText.preferenceRes(preset.preferenceFor(PresetPopupKind.NOTICE))),
                    stringResource(PresetText.preferenceRes(preset.preferenceFor(PresetPopupKind.COUPON))),
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            PresetText.noteRes(preset)?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/** String resources per preset, separate so a JVM test can check every preset has its text. */
internal object PresetText {
    fun labelRes(preset: HandlingPreset) = when (preset) {
        HandlingPreset.CLOSE_ALL -> R.string.ui_preset_close_all
        HandlingPreset.CLOSE_ADS_AND_COUPONS -> R.string.ui_preset_close_ads_and_coupons
        HandlingPreset.CLOSE_ADS_ONLY -> R.string.ui_preset_close_ads_only
    }

    fun preferenceRes(preference: PopupHandlingPreference) = when (preference) {
        PopupHandlingPreference.CLOSE -> R.string.ui_preset_pref_close
        PopupHandlingPreference.ANNOUNCE -> R.string.ui_preset_pref_announce
        PopupHandlingPreference.ASK -> R.string.ui_preset_pref_ask
    }

    /** "전부 자동 닫기" says it only closes what passes the safety checks. */
    fun noteRes(preset: HandlingPreset): Int? =
        if (preset == HandlingPreset.CLOSE_ALL) R.string.ui_preset_close_all_note else null
}
