package kr.closeforyou.ui.voice.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kr.closeforyou.R
import kr.closeforyou.simpleclose.DemoUnknownAutoClose

/**
 * The three presets as one radio group (first-run screen and settings). Each row is one large card
 * and one TalkBack node: name, what it does to ads / notices / coupons, then "선택됨" / "선택 안 됨"
 * (explicit stateDescription). Selecting never moves focus.
 * The fixed lines follow the group: important popups are always asked, and (only while
 * [PresetAutoCloseFeature.live] is false, i.e. the plan-B engine is off) nothing is closed automatically yet. [notLiveLine] false
 * when the caller shows that line itself (after the "자동 닫기" switch, [HandlingPresetWithConsent]).
 * [selected] null = none chosen yet (first-run screen, A #34 10/25): every row reads "선택 안 됨".
 */
@Composable
fun HandlingPresetGroup(
    selected: HandlingPreset?,
    onSelect: (HandlingPreset) -> Unit,
    modifier: Modifier = Modifier,
    notLiveLine: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.selectableGroup()) {
            HandlingPreset.entries.forEach { preset ->
                PresetRow(preset, selected = preset == selected, onClick = { onSelect(preset) })
            }
        }
        HelpText(stringResource(PresetText.decisionLineRes(selected)))
        if (notLiveLine && !PresetAutoCloseFeature.live) HelpText(stringResource(R.string.ui_preset_not_live))
    }
}

/** One large radio card ([ChoiceCard]): name, ads / notices / coupons line, note, "선택됨". */
@Composable
private fun PresetRow(preset: HandlingPreset, selected: Boolean, onClick: () -> Unit) {
    ChoiceCard(
        title = stringResource(PresetText.labelRes(preset)),
        body = stringResource(
            R.string.ui_preset_line,
            stringResource(PresetText.preferenceRes(preset.preferenceFor(PresetPopupKind.ADVERTISEMENT))),
            stringResource(PresetText.preferenceRes(preset.preferenceFor(PresetPopupKind.NOTICE))),
            stringResource(PresetText.preferenceRes(preset.preferenceFor(PresetPopupKind.COUPON))),
        ),
        selected = selected,
        onClick = onClick,
        note = PresetText.noteRes(preset)?.let { stringResource(it) },
    )
}

/** String resources per preset, separate so a JVM test can check every preset has its text. */
internal object PresetText {
    /**
     * The fixed line under the presets. "무엇인지 알 수 없는 팝업도 물어봐요" is not true for "전부 자동 닫기"
     * while [DemoUnknownAutoClose] is on (demo mode 6-0, F7), so that preset gets its own line.
     */
    fun decisionLineRes(selected: HandlingPreset?, unknownAutoClose: Boolean = DemoUnknownAutoClose.ENABLED) =
        if (unknownAutoClose && selected == HandlingPreset.CLOSE_ALL) R.string.ui_preset_decision_fixed_close_all
        else R.string.ui_preset_decision_fixed

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
